package com.itantra.app.mesh

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.MacAddress
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.ActionListener
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.itantra.app.model.PeerDevice
import com.itantra.app.model.TransportProtocol
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * One datagram received from the UDP mesh, together with where it came from.
 *
 * The source address is what lets the ViewModel learn a peer's direct IP and
 * answer back with unicast traffic (broadcast alone does not cross subnets).
 */
class MeshDatagram(
    val bytes: ByteArray,
    val sourceAddress: String,
    val sourcePort: Int
)

/**
 * Wi-Fi Direct P2P group management + UDP broadcast mesh for iTantra.
 *
 * BE HONEST ABOUT OEM BEHAVIOUR: Wi-Fi Direct group formation is notoriously
 * inconsistent across manufacturers (group owner negotiation, auto-join
 * dialogs, 5 GHz support, and the 192.168.49.x subnet all vary). Everything
 * here is guarded and degrades to a no-op when the platform rejects an
 * operation or no P2P hardware is present (e.g. the emulator).
 */
class WifiDirectMeshManager(context: Context) {

    companion object {
        /** UDP mesh port used across the app. */
        const val UDP_PORT = 8889

        /** Broadcast target for the ad-hoc LAN. */
        const val UDP_BROADCAST_HOST = "255.255.255.255"

        /**
         * Minimum gap between two auto-join invitations. Group formation takes a
         * few seconds and the platform rejects overlapping `connect()` calls, so
         * without this a 20s discovery loop turns into an invitation storm.
         */
        const val JOIN_ATTEMPT_COOLDOWN_MS = 12_000L

        /** An outstanding invitation is abandoned after this long. */
        const val JOIN_ATTEMPT_TIMEOUT_MS = 25_000L
    }

    private val appContext = context.applicationContext
    private val wifiManager: WifiManager? =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private val manager: WifiP2pManager? =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiP2pManager
        ?: appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    private var channel: WifiP2pManager.Channel? = try {
        (appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager)?.initialize(appContext, appContext.mainLooper, null)
    } catch (_: Exception) {
        null
    }

    private var receiverRegistered = false

    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    val peers: StateFlow<List<PeerDevice>> = _peers.asStateFlow()

    private val _localIpAddress = MutableStateFlow<String?>(null)
    val localIpAddress: StateFlow<String?> = _localIpAddress.asStateFlow()

    private val _isGroupOwner = MutableStateFlow(false)
    val isGroupOwner: StateFlow<Boolean> = _isGroupOwner.asStateFlow()

    /**
     * True while this device is in a P2P group, either as owner or client. This
     * — not peer discovery — is what makes the UDP mesh reachable, because only
     * group members share the 192.168.49.x subnet.
     */
    private val _isGroupFormed = MutableStateFlow(false)
    val isGroupFormed: StateFlow<Boolean> = _isGroupFormed.asStateFlow()

    private val _isP2pEnabled = MutableStateFlow(false)
    val isP2pEnabled: StateFlow<Boolean> = _isP2pEnabled.asStateFlow()

    /** Incoming datagrams from the mesh (voice frames, link requests, ...). */
    private val _incomingDatagrams = MutableSharedFlow<MeshDatagram>(
        extraBufferCapacity = 32,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val incomingDatagrams: SharedFlow<MeshDatagram> = _incomingDatagrams.asSharedFlow()

    private var socket: DatagramSocket? = null
    private var udpThread: Thread? = null

    private val peerIpCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Address of the peer we have an outstanding join invitation to, if any. */
    private val joiningAddress = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /** Our own P2P address; some OEMs list it in the peer list. */
    private val ownDeviceAddress = java.util.concurrent.atomic.AtomicReference<String?>(null)

    @Volatile
    private var lastJoinAttemptEpochMs = 0L

    @Volatile
    private var lastJoinErrorReason: Int? = null

    fun registerPeerIp(ip: String) {
        val clean = ip.trim()
        if (clean.isNotEmpty() && clean != "127.0.0.1" && clean != _localIpAddress.value) {
            peerIpCache[clean] = System.currentTimeMillis()
            Log.i("WifiDirectMeshManager", "Registered peer IP: $clean")
        }
    }

    fun getKnownPeerIps(): Set<String> = peerIpCache.keys.toSet()

    private fun isLocalAddress(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress) return true
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                for (ia in iface.inetAddresses) {
                    if (ia == addr) return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    // =========================================================================
    // Permissions
    // =========================================================================

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    /** Discovery of nearby P2P devices is permission-gated by OS version. */
    private fun hasDiscoveryPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    // =========================================================================
    // P2P lifecycle
    // =========================================================================

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_DISABLED
                        )
                        _isP2pEnabled.value = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> onConnectionChanged(intent)
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        ownDeviceAddress.set(readThisDevice(intent)?.deviceAddress)
                    }
                }
            } catch (_: Exception) {
                // Never let a malformed system broadcast crash the app.
            }
        }
    }

    init {
        registerReceiverSafe()
    }

    private fun registerReceiverSafe() {
        if (receiverRegistered || manager == null) return
        receiverRegistered = try {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(
                appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun onConnectionChanged(intent: Intent) {
        val info: WifiP2pInfo? = intent.getParcelableExtraCompat(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
        info?.let {
            _isGroupOwner.value = it.isGroupOwner
            _isGroupFormed.value = it.groupFormed
            if (it.groupFormed) {
                joiningAddress.set(null)
                _localIpAddress.value = resolveLocalIp()
                val goHost = it.groupOwnerAddress?.hostAddress
                if (goHost != null && !it.isGroupOwner) {
                    registerPeerIp(goHost)
                    Log.i("WifiDirectMeshManager", "Added Wi-Fi Direct Group Owner IP: $goHost")
                }
            }
        }
        if (info?.groupFormed == false) {
            _isGroupOwner.value = false
            _localIpAddress.value = null
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.getParcelableExtraCompat(name: String): WifiP2pInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, WifiP2pInfo::class.java)
        } else {
            getParcelableExtra(name)
        }

    @Suppress("DEPRECATION")
    private fun readThisDevice(intent: Intent): WifiP2pDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
        } else {
            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
        }

    /** Best-effort local IPv4 lookup for the P2P interface (p2p0/wlan0). */
    private fun resolveLocalIp(): String? = try {
        val candidates = NetworkInterface.getNetworkInterfaces().toList().flatMap { iface ->
            iface.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .map { iface.name to it.hostAddress }
        }
        // With STA+P2P concurrency both interfaces are up; the group subnet
        // (192.168.49.x) is the one the mesh runs on, so prefer it. Reporting
        // the wlan address instead would make us treat our own P2P address as a
        // peer and unicast to ourselves.
        (candidates.firstOrNull { it.first.startsWith("p2p") } ?: candidates.firstOrNull())?.second
    } catch (_: Exception) {
        null
    }

    private val actionLog: ActionListener = object : ActionListener {
        override fun onSuccess() { /* logged implicitly by the state flows */ }
        override fun onFailure(reason: Int) {
            // OEM-variable; degrade silently. Callers observe the state flows.
        }
    }

    /** Starts P2P peer discovery. */
    fun startDiscovery() {
        if (manager == null || !hasDiscoveryPermission()) return
        try {
            if (ownDeviceAddress.get() == null) requestOwnDeviceAddress()
            manager?.discoverPeers(channel, actionLog)
        } catch (_: Exception) {
        }
    }

    private fun requestOwnDeviceAddress() {
        val mgr = manager ?: return
        val ch = channel ?: return
        try {
            mgr.requestDeviceInfo(ch, WifiP2pManager.DeviceInfoListener { device ->
                device?.deviceAddress?.let { ownDeviceAddress.set(it) }
            })
        } catch (_: Exception) {
        }
    }

    fun stopDiscovery() {
        if (manager == null) return
        try {
            manager?.stopPeerDiscovery(channel, actionLog)
        } catch (_: Exception) {
        }
    }

    /** Re-populates [peers] from the platform's current peer list. */
    fun requestPeers() {
        if (manager == null || !hasDiscoveryPermission()) return
        try {
            manager?.requestPeers(channel) { peerList ->
                val self = ownDeviceAddress.get()
                val devices = peerList?.deviceList.orEmpty().filter { it.deviceAddress != self }
                _peers.value = devices.map { it.toPeerDevice() }
            }
        } catch (_: Exception) {
        }
    }

    fun isWifiConnected(): Boolean {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                if (iface.name.startsWith("wlan") || iface.name.startsWith("eth")) {
                    for (interfaceAddress in iface.interfaceAddresses) {
                        val addr = interfaceAddress.address
                        if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                            return true
                        }
                    }
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Creates a P2P group, making this device the Group Owner.
     *
     * @param allowOverLan when true the group is formed even while the device is
     * connected to an infrastructure Wi-Fi network. Forming a group can drop
     * that link on chipsets without STA+P2P concurrency, so it is only done for
     * a distress device, which must be findable regardless of what network it
     * happens to be sitting on.
     */
    fun createGroup(allowOverLan: Boolean = false) {
        if (manager == null) return
        if (_isGroupFormed.value) return
        if (!allowOverLan && isWifiConnected()) {
            Log.i("WifiDirectMeshManager", "Already connected to Wi-Fi LAN; skipping P2P group creation to preserve LAN link")
            return
        }
        try {
            manager?.createGroup(channel, groupActionListener("createGroup"))
            Log.i("WifiDirectMeshManager", "Requested Wi-Fi Direct P2P group creation")
        } catch (e: Exception) {
            Log.w("WifiDirectMeshManager", "createGroup threw exception", e)
        }
    }

    /** Removes the current P2P group. */
    fun removeGroup() {
        if (manager == null) return
        try {
            joiningAddress.set(null)
            manager?.removeGroup(channel, actionLog)
        } catch (_: Exception) {
        }
    }

    /**
     * Invites the first eligible discovered peer into a group so both phones end
     * up on the same P2P subnet. This is the step that was missing: discovery
     * alone never forms a group, so the UDP mesh never reached a peer beyond BLE
     * range no matter how long both sides scanned.
     *
     * Guards, in order:
     *  - already in a group (owner or client) -> nothing to do;
     *  - an invitation is still outstanding and unexpired -> let it finish;
     *  - the last attempt is inside [JOIN_ATTEMPT_COOLDOWN_MS] -> back off.
     *
     * The peer is chosen deterministically (lowest address) so two devices that
     * discover each other do not both keep re-picking different targets.
     *
     * @return true when an invitation was actually issued.
     */
    fun joinAnyDiscoveredGroup(): Boolean {
        if (manager == null || !hasDiscoveryPermission()) return false
        if (_isGroupFormed.value) return false

        val now = System.currentTimeMillis()
        val pending = joiningAddress.get()
        if (pending != null) {
            if (now - lastJoinAttemptEpochMs < JOIN_ATTEMPT_TIMEOUT_MS) return false
            Log.i("WifiDirectMeshManager", "Join invitation to $pending expired without a group; retrying")
            joiningAddress.set(null)
        }
        if (now - lastJoinAttemptEpochMs < JOIN_ATTEMPT_COOLDOWN_MS) return false

        val candidate = _peers.value
            .filter { !it.isConnected }
            .minByOrNull { it.address }
            ?: return false

        lastJoinAttemptEpochMs = now
        joiningAddress.set(candidate.address)
        Log.i("WifiDirectMeshManager", "Auto-joining P2P group of ${candidate.name} (${candidate.address})")
        connectTo(candidate)
        return true
    }

    /** Connects to [peer] with a high group-owner intent. */
    fun connectTo(peer: PeerDevice) {
        if (manager == null || !hasDiscoveryPermission()) return
        try {
            // API 33+ hides the plain constructor and the Builder only takes
            // MacAddress; groupOwnerIntent stays a public field either way.
            val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                WifiP2pConfig.Builder()
                    .setDeviceAddress(MacAddress.fromString(peer.address))
                    .build()
                    .apply { groupOwnerIntent = 15 }
            } else {
                @Suppress("DEPRECATION")
                WifiP2pConfig().apply {
                    deviceAddress = peer.address
                    groupOwnerIntent = 15
                }
            }
            manager?.connect(channel, config, groupActionListener("connect(${peer.address})"))
        } catch (e: Exception) {
            joiningAddress.set(null)
            Log.w("WifiDirectMeshManager", "connect threw exception", e)
        }
    }

    /**
     * Listener for group-forming calls. `onSuccess` only means the framework
     * accepted the request — actual membership arrives via CONNECTION_CHANGED —
     * so the pending invitation is cleared on failure only.
     */
    private fun groupActionListener(op: String): ActionListener = object : ActionListener {
        override fun onSuccess() {
            Log.i("WifiDirectMeshManager", "$op accepted by the platform")
        }

        override fun onFailure(reason: Int) {
            lastJoinErrorReason = reason
            joiningAddress.set(null)
            Log.w("WifiDirectMeshManager", "$op failed reason=$reason")
        }
    }

    /** One-line P2P snapshot for the field diagnostics dump. */
    fun p2pDiagnostics(): String =
        "p2p[enabled=${_isP2pEnabled.value} group=${_isGroupFormed.value} go=${_isGroupOwner.value} " +
            "peers=${_peers.value.size} ip=${_localIpAddress.value ?: "-"} " +
            "joining=${joiningAddress.get() ?: "-"} lastJoinErr=${lastJoinErrorReason ?: "-"} " +
            "udp=${if (socket != null) "up" else "down"}]"

    private fun WifiP2pDevice.toPeerDevice(): PeerDevice = PeerDevice(
        id = deviceAddress,
        name = deviceName.ifEmpty { "P2P-$deviceAddress" },
        address = deviceAddress,
        protocol = TransportProtocol.WIFI_DIRECT,
        signalStrengthDbm = -55, // P2P API does not expose RSSI
        isConnected = status == WifiP2pDevice.CONNECTED,
        isP2pHost = isGroupOwner,
        port = UDP_PORT
    )

    // =========================================================================
    // UDP mesh (broadcast + receive)
    // =========================================================================

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("iTantraMeshLock")?.apply {
                    setReferenceCounted(true)
                }
            }
            if (multicastLock?.isHeld != true) {
                multicastLock?.acquire()
                Log.i("WifiDirectMeshManager", "Acquired WifiManager.MulticastLock")
            }
        } catch (e: Exception) {
            Log.w("WifiDirectMeshManager", "Could not acquire MulticastLock", e)
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                Log.i("WifiDirectMeshManager", "Released WifiManager.MulticastLock")
            }
        } catch (_: Exception) {}
    }

    /**
     * Opens the shared UDP broadcast socket on port 8889.
     *
     * @return true when the socket is bound and the receiver loop is running.
     */
    @Synchronized
    fun startUdpBroadcast(): Boolean {
        if (socket != null) return true
        acquireMulticastLock()
        return try {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.broadcast = true
            s.bind(InetSocketAddress(UDP_PORT))
            socket = s
            udpThread = thread(name = "itantra-udp-rx", isDaemon = true) { udpReceiveLoop(s) }
            Log.i("WifiDirectMeshManager", "UDP broadcast socket bound on port $UDP_PORT")
            true
        } catch (e: Exception) {
            Log.e("WifiDirectMeshManager", "Port busy or failed to bind UDP socket", e)
            false
        }
    }

    private fun udpReceiveLoop(s: DatagramSocket) {
        val buffer = ByteArray(65507)
        while (!Thread.currentThread().isInterrupted) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                s.receive(packet)
                if (packet.length > 0) {
                    val senderIp = packet.address?.hostAddress
                    if (senderIp != null && !isLocalAddress(packet.address)) {
                        registerPeerIp(senderIp)
                    }
                    Log.i("WifiDirectMeshManager", "UDP rx: received ${packet.length} bytes from ${packet.address}:${packet.port}")
                    _incomingDatagrams.tryEmit(
                        MeshDatagram(
                            bytes = packet.data.copyOf(packet.length),
                            sourceAddress = packet.address?.hostAddress.orEmpty(),
                            sourcePort = packet.port
                        )
                    )
                }
            } catch (_: SocketException) {
                break // socket closed — exit the loop
            } catch (e: Exception) {
                Log.w("WifiDirectMeshManager", "Error receiving UDP packet", e)
            }
        }
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        try {
            list.add(InetAddress.getByName(UDP_BROADCAST_HOST))
            try { list.add(InetAddress.getByName("192.168.49.255")) } catch (_: Exception) {}
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return list
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                for (interfaceAddress in iface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null) {
                        list.add(broadcast)
                    }
                }
            }
        } catch (_: Exception) {}
        return list.distinct()
    }

    /** Broadcasts [bytes] to the ad-hoc LAN / Wi-Fi network and direct unicast to known peer IPs. */
    fun broadcastDatagram(bytes: ByteArray): Boolean {
        if (socket == null) {
            startUdpBroadcast()
        }
        val s = socket ?: return false
        var sent = false

        // 1. Direct unicast to learned peer IPs (bypasses router AP isolation & broadcast suppression)
        val now = System.currentTimeMillis()
        peerIpCache.entries.removeIf { (now - it.value) > 300_000L }
        for (peerIp in peerIpCache.keys) {
            try {
                s.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(peerIp), UDP_PORT))
                sent = true
                Log.d("WifiDirectMeshManager", "broadcastDatagram direct unicast to $peerIp:$UDP_PORT")
            } catch (e: Exception) {
                Log.w("WifiDirectMeshManager", "broadcastDatagram unicast to $peerIp failed: ${e.message}")
            }
        }

        // 2. Subnet broadcast and generic 255.255.255.255
        val addresses = getBroadcastAddresses()
        for (addr in addresses) {
            try {
                s.send(DatagramPacket(bytes, bytes.size, addr, UDP_PORT))
                sent = true
                Log.d("WifiDirectMeshManager", "broadcastDatagram sent ${bytes.size} bytes to $addr:$UDP_PORT")
            } catch (e: Exception) {
                Log.w("WifiDirectMeshManager", "broadcastDatagram failed to $addr:$UDP_PORT: ${e.message}")
            }
        }
        return sent
    }

    /** Sends [bytes] to a single host:port (direct-IP links). */
    fun sendDatagram(bytes: ByteArray, host: String, port: Int = UDP_PORT): Boolean {
        if (socket == null) {
            startUdpBroadcast()
        }
        val s = socket ?: return false
        return try {
            s.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(host), port))
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun stopUdp() {
        val s = socket
        socket = null
        runCatching { s?.close() }
        udpThread?.interrupt()
        udpThread = null
        releaseMulticastLock()
    }

    /** Stops everything: UDP, discovery, and the P2P broadcast receiver. */
    fun shutdown() {
        stopUdp()
        stopDiscovery()
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        _peers.value = emptyList()
        _localIpAddress.value = null
        _isGroupOwner.value = false
        _isGroupFormed.value = false
        _isP2pEnabled.value = false
        joiningAddress.set(null)
    }
}
