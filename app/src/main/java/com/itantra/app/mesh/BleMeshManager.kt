package com.itantra.app.mesh

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * iTantra BLE distress beacon payload.
 *
 * Compact manufacturer-data layout (all fields big-endian):
 *
 *  | OFFSET | FIELD        | SIZE | NOTES                        |
 *  |--------|--------------|------|------------------------------|
 *  | 0      | NODE_ID      | 8    | Sender transceiver id        |
 *  | 8      | BATTERY_PCT  | 1    | 0..100                       |
 *  | 9      | LAT_INT      | 4    | microdegrees (lat * 1e6)     |
 *  | 13     | LON_INT      | 4    | microdegrees (lon * 1e6)     |
 *  | 17     | ALTITUDE_M   | 2    | signed meters                |
 *  | 19     | LANG_CODE    | 2    | ISO 639-1 ASCII              |
 *  | 21     | DISTRESS_FLAG| 1    | non-zero when in distress    |
 *
 * Pure logic — no Android imports — so the encode/parse round-trip is
 * unit-testable on the host JVM.
 */
data class DistressBeaconPayload(
    val nodeId: Long,
    val batteryPercent: Int,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeMeters: Int,
    val languageIso: String,
    val isDistress: Boolean
) {

    companion object {
        /** iTantra BLE manufacturer ID (ASCII 'IT'). */
        const val MANUFACTURER_ID = 0x4954

        /** Payload size in bytes, excluding the 2-byte manufacturer ID. */
        const val PAYLOAD_BYTES = 8 + 1 + 4 + 4 + 2 + 2 + 1 // 22

        /**
         * `altitudeMeters` sentinel for a non-distress node that is only
         * advertising Walkie-Talkie presence. Keeps walkie peers out of the
         * rescuer lists (which treat `0` as an idle rescuer and a positive
         * value as "this rescuer is calling my node").
         */
        const val ALTITUDE_WALKIE_PRESENCE = -32768

        /** `altitudeMeters` value of an idle rescuer that is not targeting anybody. */
        const val ALTITUDE_RESCUER_IDLE = 0

        /** `altitudeMeters` value of a rescuer streaming a 1-way broadcast to all. */
        const val ALTITUDE_RESCUER_BROADCAST_ALL = -1

        /**
         * Parses a beacon payload as delivered by BLE scan callbacks (the
         * 22-byte form) or the full 24-byte form prefixed with the
         * manufacturer ID.
         *
         * @return the payload, or null when the length or the (optional)
         * manufacturer ID does not match.
         */
        fun parseManufacturerData(bytes: ByteArray): DistressBeaconPayload? {
            val buffer: ByteBuffer = try {
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                when (bytes.size) {
                    PAYLOAD_BYTES -> b
                    PAYLOAD_BYTES + 2 -> {
                        if (b.short != MANUFACTURER_ID.toShort()) return null
                        b
                    }
                    else -> return null
                }
            } catch (_: Exception) {
                return null
            }
            return try {
                val nodeId = buffer.long
                val battery = buffer.get().toInt() and 0xFF
                val lat = buffer.int / 1e6
                val lon = buffer.int / 1e6
                val altitude = buffer.short.toInt()
                val langBytes = ByteArray(2)
                buffer.get(langBytes)
                val language = String(langBytes, Charsets.US_ASCII).trim().lowercase()
                    .ifEmpty { "en" }
                val distress = buffer.get().toInt() != 0
                DistressBeaconPayload(
                    nodeId = nodeId,
                    batteryPercent = battery,
                    latitudeDeg = lat,
                    longitudeDeg = lon,
                    altitudeMeters = altitude,
                    languageIso = language,
                    isDistress = distress
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    /** The 22-byte payload handed to `AdvertiseData.Builder.addManufacturerData`. */
    fun toManufacturerData(): ByteArray = encode(includeManufacturerId = false)

    /** The full 24-byte form including the manufacturer ID prefix. */
    fun toManufacturerDataWithId(): ByteArray = encode(includeManufacturerId = true)

    private fun encode(includeManufacturerId: Boolean): ByteArray {
        val buffer = ByteBuffer
            .allocate(PAYLOAD_BYTES + if (includeManufacturerId) 2 else 0)
            .order(ByteOrder.BIG_ENDIAN)
        if (includeManufacturerId) buffer.putShort(MANUFACTURER_ID.toShort())
        buffer.putLong(nodeId)
        buffer.put(batteryPercent.coerceIn(0, 255).toByte())
        buffer.putInt((latitudeDeg * 1e6).roundToInt())
        buffer.putInt((longitudeDeg * 1e6).roundToInt())
        buffer.putShort(altitudeMeters.coerceIn(-32768, 32767).toShort())
        val lang = languageIso.take(2).padEnd(2, ' ').lowercase()
        buffer.put(lang.toByteArray(Charsets.US_ASCII))
        buffer.put(if (isDistress) 1 else 0)
        return buffer.array()
    }
}

/** A beacon observed by the scanner, with Kalman-smoothed distance. */
data class DiscoveredBeacon(
    val nodeId: Long,
    val rssi: Int,
    val estimatedDistanceMeters: Double,
    val batteryPercent: Int,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeMeters: Int = 0,
    val languageIso: String,
    val isDistress: Boolean,
    val lastSeenEpochMs: Long
)

/** Advertise TX power level mapped onto the platform constants. */
enum class BeaconTxPower(val advertiseConstant: Int) {
    LOW(AdvertiseSettings.ADVERTISE_TX_POWER_LOW),
    MEDIUM(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM),
    HIGH(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
}

/** Failure details surfaced via [BleMeshManager.onAdvertisingFailed]. */
data class AdvertiseFailure(val errorCode: Int, val message: String)

/**
 * The role-defining part of a beacon: the sender plus what the beacon *means*
 * on the air (victim vs idle rescuer vs broadcast-all vs targeted rescuer vs
 * walkie presence). Volatile telemetry (coordinates, battery) is deliberately
 * excluded - these change on every GPS tick and must never force the BLE
 * advertiser to stop and restart.
 */
data class BeaconIdentity(
    val nodeId: Long,
    val isDistress: Boolean,
    val altitudeMeters: Int
) {
    companion object {
        fun of(beacon: DistressBeaconPayload): BeaconIdentity =
            BeaconIdentity(beacon.nodeId, beacon.isDistress, beacon.altitudeMeters)
    }
}

/**
 * Pure decision: must the radio advertisement be restarted to serve [requested]?
 *
 *  - Nothing on air yet -> yes.
 *  - Role/identity changed (nodeId / distress flag / altitude sentinel) -> yes, immediately.
 *  - Same identity and identical bytes -> no.
 *  - Same identity, only volatile telemetry changed -> only once the throttle
 *    window [refreshMinIntervalMs] has elapsed since the last restart.
 *
 * Host-JVM testable (no Android imports).
 */
internal fun shouldRestartAdvertising(
    onAirIdentity: BeaconIdentity?,
    onAirBytes: ByteArray?,
    requested: DistressBeaconPayload,
    lastRestartEpochMs: Long,
    nowEpochMs: Long,
    refreshMinIntervalMs: Long
): Boolean {
    if (onAirIdentity == null || onAirBytes == null) return true
    if (onAirIdentity != BeaconIdentity.of(requested)) return true
    if (onAirBytes.contentEquals(requested.toManufacturerData())) return false
    return (nowEpochMs - lastRestartEpochMs) >= refreshMinIntervalMs
}

/**
 * BLE advertiser + scanner for the iTantra mesh.
 *
 * Both sides agree on a single 128-bit service UUID and a fixed
 * manufacturer-data codec ([DistressBeaconPayload]). Every entry point is
 * permission-guarded and wrapped so unsupported hardware (e.g. the emulator)
 * degrades to a no-op instead of crashing.
 */
class BleMeshManager(context: Context) {

    companion object {
        /**
         * The service UUID string as written in the iTantra protocol notes.
         * It is not valid UUID syntax, so it is kept only as documentation;
         * [SERVICE_UUID] below is its real 128-bit form (group bytes 0x4954
         * 0x414E are ASCII 'I','T','A','N'). Advertiser and scanner both use
         * [SERVICE_UUID], which is all that matters on the air.
         */
        const val SERVICE_UUID_SPEC_STRING = "0000ITAN-0000-1000-8000-00805F9B34FB"

        /** The actual UUID advertised/scanned, derived from the spec string. */
        val SERVICE_UUID: UUID = UUID.fromString("00004954-414e-1000-8000-00805f9b34fb")

        /** The characteristic UUID used for bi-directional framed mesh packets. */
        val CHAR_DATA_UUID: UUID = UUID.fromString("00004955-414e-1000-8000-00805f9b34fb")

        /** Standard CCCD UUID for BLE notifications. */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * Largest single GATT write this stack is configured for (the peer MTU
         * requested on connect). Packets above this limit are NOT delivered by
         * a plain characteristic write; callers must fall back to another
         * transport.
         */
        const val MAX_GATT_WRITE_BYTES = 512

        private const val BEACON_STALE_MS = 10_000L
        private const val PRUNE_PERIOD_MS = 1_000L

        /**
         * Minimum gap between two real advertisement restarts that only refresh
         * volatile telemetry (coordinates / battery). Volatile churn must never
         * take the beacon off the air, so restarts are throttled to this cadence.
         */
        private const val ADVERT_REFRESH_MIN_INTERVAL_MS = 15_000L

        /** Bounded advertise-start retry schedule after a controller failure. */
        private const val ADVERTISE_MAX_RETRIES = 5
        private const val ADVERTISE_RETRY_DELAY_MS = 1_500L

        /** How often the beacon is force re-asserted to survive a silent stop. */
        private const val ADVERT_WATCHDOG_PERIOD_MS = 30_000L

        /** Bounded scan-start retry schedule after a controller failure. */
        private const val SCAN_MAX_RETRIES = 5
        private const val SCAN_RETRY_DELAY_MS = 2_000L

        /** How often the scan watchdog verifies scanning is still alive. */
        private const val SCAN_WATCHDOG_PERIOD_MS = 15_000L

        /**
         * A scan is declared silently dead when _isScanning is still true but no
         * raw scan result of any kind has arrived for this long. Set above
         * BEACON_STALE_MS (10s) so a normal quiet RF window is not mistaken for
         * death, yet low enough that — with the 15s watchdog period — recovery
         * lands in ~15-27s instead of the multi-minute blackouts observed in the
         * field. Worst case 1 restart / 15s = 2 / 30s, safely under Android's
         * ~5-registrations-per-30s scan throttle.
         */
        private const val SCAN_LIVENESS_TIMEOUT_MS = 12_000L
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _discoveredBeacons = MutableStateFlow<List<DiscoveredBeacon>>(emptyList())
    val discoveredBeacons: StateFlow<List<DiscoveredBeacon>> = _discoveredBeacons.asStateFlow()

    private val _incomingPackets = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incomingPackets: SharedFlow<ByteArray> = _incomingPackets.asSharedFlow()

    /**
     * Mesh node ids that currently have a live BLE GATT link with us (either
     * direction: we are the GATT client, or the peer is connected to our GATT
     * server). Drives the "link active" HUD instead of a hardcoded label.
     */
    private val _connectedNodeIds = MutableStateFlow<Set<Long>>(emptySet())
    val connectedNodeIds: StateFlow<Set<Long>> = _connectedNodeIds.asStateFlow()

    /** Invoked with the failure code when advertising cannot start. */
    var onAdvertisingFailed: ((AdvertiseFailure) -> Unit)? = null

    private val latest = LinkedHashMap<Long, DiscoveredBeacon>()
    private val trackers = HashMap<Long, RangedNodeDistanceTracker>()
    private var pruneJob: Job? = null

    private var gattServer: BluetoothGattServer? = null
    private val connectedGattClients = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val discoveredDevices = ConcurrentHashMap<Long, BluetoothDevice>()
    private val activeGattClients = ConcurrentHashMap<String, BluetoothGatt>()
    private val negotiatedMtus = ConcurrentHashMap<String, Int>()
    private val chunkAssemblers = ConcurrentHashMap<String, BleChunkAssembler>()
    private var nextBleTransferId = 0

    // --- Radio diagnostics. The liveness fields (lastRawScanResultEpochMs,
    // recentScanStartEpochMs) now also drive the scan watchdog's silent-death
    // detection; the error-code fields remain log-only, letting a debug dump
    // distinguish "flag off", "dead scan", "advertise failed" and "GATT churn". ---
    @Volatile private var lastRawScanResultEpochMs = 0L
    @Volatile private var lastItantraBeaconEpochMs = 0L
    @Volatile private var lastScanErrorCode: Int? = null
    @Volatile private var lastAdvertiseErrorCode: Int? = null
    private val recentScanStartEpochMs = ArrayDeque<Long>()

    private class BleChunkAssembler(val totalChunks: Int) {
        val createdEpochMs = System.currentTimeMillis()
        val chunks = arrayOfNulls<ByteArray>(totalChunks)
        var receivedCount = 0

        @Synchronized
        fun addChunk(index: Int, data: ByteArray): ByteArray? {
            if (index in 0 until totalChunks && chunks[index] == null) {
                chunks[index] = data
                receivedCount++
                if (receivedCount == totalChunks) {
                    val totalBytes = chunks.filterNotNull().sumOf { it.size }
                    val out = ByteArray(totalBytes)
                    var offset = 0
                    for (chunk in chunks) {
                        if (chunk != null) {
                            System.arraycopy(chunk, 0, out, offset, chunk.size)
                            offset += chunk.size
                        }
                    }
                    return out
                }
            }
            return null
        }
    }

    private fun handleReceivedBleBytes(fromAddress: String, bytes: ByteArray) {
        if (bytes.size >= 5 && bytes[0] == 0x49.toByte() && bytes[1] == 0x43.toByte()) {
            val transferId = bytes[2].toInt() and 0xFF
            val chunkIdx = bytes[3].toInt() and 0xFF
            val totalChunks = bytes[4].toInt() and 0xFF
            val chunkData = bytes.copyOfRange(5, bytes.size)

            val now = System.currentTimeMillis()
            chunkAssemblers.entries.removeIf { (now - it.value.createdEpochMs) > 8000L }

            val key = "$fromAddress-$transferId"
            val assembler = chunkAssemblers.getOrPut(key) { BleChunkAssembler(totalChunks) }
            val fullPacket = assembler.addChunk(chunkIdx, chunkData)
            if (fullPacket != null) {
                chunkAssemblers.remove(key)
                Log.i("BleMeshManager", "Reassembled BLE packet: ${fullPacket.size} bytes from $fromAddress")
                _incomingPackets.tryEmit(fullPacket)
            }
        } else {
            _incomingPackets.tryEmit(bytes)
        }
    }

    private fun chunkPacket(bytes: ByteArray, maxChunkSize: Int): List<ByteArray> {
        val transferId = synchronized(this) { (nextBleTransferId++ and 0xFF) }
        val chunkDataSize = maxChunkSize.coerceAtLeast(15)
        val totalChunks = (bytes.size + chunkDataSize - 1) / chunkDataSize
        val chunks = ArrayList<ByteArray>(totalChunks)
        for (i in 0 until totalChunks) {
            val start = i * chunkDataSize
            val end = minOf(start + chunkDataSize, bytes.size)
            val len = end - start
            val chunk = ByteArray(5 + len)
            chunk[0] = 0x49.toByte() // 'I'
            chunk[1] = 0x43.toByte() // 'C'
            chunk[2] = transferId.toByte()
            chunk[3] = i.toByte()
            chunk[4] = totalChunks.toByte()
            System.arraycopy(bytes, start, chunk, 5, len)
            chunks.add(chunk)
        }
        return chunks
    }

    /** Reverse lookup so a GATT link (keyed by MAC) can be mapped back to a mesh node id. */
    private val nodeIdByAddress = ConcurrentHashMap<String, Long>()

    /**
     * Recomputes the set of mesh nodes with a live GATT link. A node is linked
     * while either direction is up: it is connected to our GATT server, or we
     * hold a client connection to it.
     */
    private fun refreshLinkedNodes() {
        val addresses = connectedGattClients.map { it.address } + activeGattClients.keys
        val nodeIds = addresses.mapNotNull { nodeIdByAddress[it] }.toSet()
        if (nodeIds != _connectedNodeIds.value) _connectedNodeIds.value = nodeIds
    }

    private fun markLinked(device: BluetoothDevice) {
        if (nodeIdByAddress.containsKey(device.address)) refreshLinkedNodes()
    }

    private fun markUnlinked(device: BluetoothDevice) {
        if (nodeIdByAddress.containsKey(device.address)) refreshLinkedNodes()
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        try {
            (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        } catch (_: Exception) {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()
        }
    }

    fun isBluetoothEnabled(): Boolean = try {
        bluetoothAdapter?.isEnabled == true
    } catch (_: Exception) {
        false
    }

    fun isLocationEnabled(): Boolean = try {
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    } catch (_: Exception) {
        false
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasBleAdvertise(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            hasPermission(Manifest.permission.BLUETOOTH)
        }

    private fun hasBleScan(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            hasPermission(Manifest.permission.BLUETOOTH_ADMIN) &&
                hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasBleConnect(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            hasPermission(Manifest.permission.BLUETOOTH)
        }

    private val preparedBuffers = ConcurrentHashMap<String, java.io.ByteArrayOutputStream>()
    private val connectingDevices = ConcurrentHashMap.newKeySet<String>()

    @Synchronized
    fun startGattServer() {
        if (gattServer != null || !hasBleConnect()) return
        try {
            val bm = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
            val server = bm.openGattServer(appContext, object : BluetoothGattServerCallback() {
                override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        connectedGattClients.add(device)
                        markLinked(device)
                        Log.i("BleMeshManager", "GATT client connected to our server: ${device.address}")
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        connectedGattClients.remove(device)
                        preparedBuffers.remove(device.address)
                        negotiatedMtus.remove(device.address)
                        Log.i("BleMeshManager", "GATT client disconnected from our server: ${device.address}")
                    }
                }

                override fun onCharacteristicWriteRequest(
                    device: BluetoothDevice,
                    requestId: Int,
                    characteristic: BluetoothGattCharacteristic,
                    preparedWrite: Boolean,
                    responseNeeded: Boolean,
                    offset: Int,
                    value: ByteArray?
                ) {
                    if (responseNeeded) {
                        try {
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                        } catch (e: Exception) {
                            Log.w("BleMeshManager", "Error sending GATT write response", e)
                        }
                    }
                    if (value != null && value.isNotEmpty()) {
                        if (preparedWrite) {
                            val stream = preparedBuffers.getOrPut(device.address) { java.io.ByteArrayOutputStream() }
                            stream.write(value)
                        } else {
                            Log.i("BleMeshManager", "Received GATT write packet: ${value.size} bytes from ${device.address}")
                            handleReceivedBleBytes(device.address, value)
                        }
                    }
                }

                override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
                    try {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    } catch (e: Exception) {
                        Log.w("BleMeshManager", "Error sending execute write response", e)
                    }
                    val stream = preparedBuffers.remove(device.address)
                    if (execute && stream != null) {
                        val fullBytes = stream.toByteArray()
                        if (fullBytes.isNotEmpty()) {
                            Log.i("BleMeshManager", "Received prepared GATT packet: ${fullBytes.size} bytes from ${device.address}")
                            handleReceivedBleBytes(device.address, fullBytes)
                        }
                    }
                }

                override fun onDescriptorWriteRequest(
                    device: BluetoothDevice,
                    requestId: Int,
                    descriptor: BluetoothGattDescriptor,
                    preparedWrite: Boolean,
                    responseNeeded: Boolean,
                    offset: Int,
                    value: ByteArray?
                ) {
                    if (responseNeeded) {
                        try {
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                        } catch (e: Exception) {
                            Log.w("BleMeshManager", "Error sending descriptor response", e)
                        }
                    }
                    Log.i("BleMeshManager", "Descriptor write from ${device.address} on ${descriptor.uuid}")
                }

                override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                    Log.i("BleMeshManager", "GATT client MTU negotiated: $mtu for ${device.address}")
                    negotiatedMtus[device.address] = mtu
                }
            }) ?: return

            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val char = BluetoothGattCharacteristic(
                CHAR_DATA_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
            )
            val cccd = BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
            )
            char.addDescriptor(cccd)
            service.addCharacteristic(char)
            server.addService(service)
            gattServer = server
            Log.i("BleMeshManager", "BLE GATT Server bound successfully on $SERVICE_UUID")
        } catch (e: Exception) {
            Log.w("BleMeshManager", "Failed to start BLE GATT server", e)
        }
    }

    @Synchronized
    fun stopGattServer() {
        try {
            gattServer?.close()
        } catch (_: Exception) {}
        gattServer = null
        connectedGattClients.clear()
        preparedBuffers.clear()
        for ((_, gatt) in activeGattClients) {
            runCatching {
                gatt.disconnect()
                gatt.close()
            }
        }
        activeGattClients.clear()
        connectingDevices.clear()
        negotiatedMtus.clear()
        chunkAssemblers.clear()
    }

    private fun checkStopGattServer() {
        if (!_isAdvertising.value && !_isScanning.value) {
            stopGattServer()
        }
    }

    /** Proactively connects to a discovered peer GATT server so link is established and ready. */
    fun connectPeerGatt(device: BluetoothDevice) {
        if (!hasBleConnect()) return
        if (activeGattClients.containsKey(device.address) || connectingDevices.contains(device.address)) {
            return
        }
        connectingDevices.add(device.address)
        scope.launch(Dispatchers.IO) {
            try {
                Log.i("BleMeshManager", "Initiating proactive GATT connection to ${device.address}")
                device.connectGatt(appContext, false, object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        connectingDevices.remove(device.address)
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            Log.i("BleMeshManager", "Connected to peer GATT: ${device.address}")
                            activeGattClients[device.address] = gatt
                            val requested = gatt.requestMtu(512)
                            if (!requested) {
                                gatt.discoverServices()
                            }
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            Log.i("BleMeshManager", "Disconnected from peer GATT: ${device.address}")
                            activeGattClients.remove(device.address)
                            negotiatedMtus.remove(device.address)
                            runCatching { gatt.close() }
                        }
                    }

                    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                        Log.d("BleMeshManager", "GATT MTU negotiated with ${device.address}: $mtu")
                        negotiatedMtus[device.address] = mtu
                        gatt.discoverServices()
                    }

                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            val service = gatt.getService(SERVICE_UUID)
                            val characteristic = service?.getCharacteristic(CHAR_DATA_UUID)
                            if (characteristic != null) {
                                gatt.setCharacteristicNotification(characteristic, true)
                                val cccd = characteristic.getDescriptor(CCCD_UUID)
                                if (cccd != null) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                    } else {
                                        @Suppress("DEPRECATION")
                                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                        @Suppress("DEPRECATION")
                                        gatt.writeDescriptor(cccd)
                                    }
                                }
                                Log.i("BleMeshManager", "Subscribed to notifications on peer ${device.address}")
                            }
                        }
                    }

                    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                        Log.i("BleMeshManager", "CCCD descriptor write completed for ${device.address}, status=$status")
                    }

                    override fun onCharacteristicChanged(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        value: ByteArray
                    ) {
                        if (value.isNotEmpty()) {
                            Log.i("BleMeshManager", "Received GATT notification packet (Tiramisu): ${value.size} bytes from ${device.address}")
                            handleReceivedBleBytes(device.address, value)
                        }
                    }

                    @Suppress("DEPRECATION")
                    override fun onCharacteristicChanged(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic
                    ) {
                        val value = characteristic.value
                        if (value != null && value.isNotEmpty()) {
                            Log.i("BleMeshManager", "Received GATT notification packet (Legacy): ${value.size} bytes from ${device.address}")
                            handleReceivedBleBytes(device.address, value)
                        }
                    }
                })
            } catch (e: Exception) {
                connectingDevices.remove(device.address)
                Log.w("BleMeshManager", "Error connecting to peer GATT ${device.address}", e)
            }
        }
    }

    /**
     * Sends [bytes] to every reachable iTantra peer over BLE GATT.
     *
     * @param targetNodeId when non-null, restricts direct writes to that node.
     * @param connectIfNeeded when false, only peers that already hold a live
     *   GATT link are written to. Real-time 20 ms voice frames use this so a
     *   missing link degrades to "UDP only" instead of re-running GATT
     *   discovery 50 times per second.
     * @return number of GATT targets the packet was handed to (notify + write).
     */
    fun broadcastPacket(
        bytes: ByteArray,
        targetNodeId: Long? = null,
        connectIfNeeded: Boolean = true
    ): Int {
        if (!hasBleConnect()) return 0
        var targets = 0

        // 1. Notify any clients currently connected to our local GATT server
        val server = gattServer
        val char = server?.getService(SERVICE_UUID)?.getCharacteristic(CHAR_DATA_UUID)
        if (server != null && char != null) {
            for (client in connectedGattClients) {
                try {
                    val mtu = negotiatedMtus[client.address] ?: 23
                    val maxChunkSize = maxOf(15, mtu - 8)
                    val chunks = chunkPacket(bytes, maxChunkSize)
                    for (chunk in chunks) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            server.notifyCharacteristicChanged(client, char, false, chunk)
                        } else {
                            @Suppress("DEPRECATION")
                            char.value = chunk
                            @Suppress("DEPRECATION")
                            server.notifyCharacteristicChanged(client, char, false)
                        }
                        if (chunks.size > 1) Thread.sleep(12)
                    }
                    Log.d("BleMeshManager", "Notified GATT client ${client.address} with ${chunks.size} chunks (${bytes.size} bytes total)")
                } catch (e: Exception) {
                    Log.w("BleMeshManager", "Failed to notify GATT client ${client.address}", e)
                }
            }
        }

        // 2. Connect & write to discovered peer devices
        val candidates = if (targetNodeId != null) {
            listOfNotNull(discoveredDevices[targetNodeId])
        } else {
            (discoveredDevices.values + connectedGattClients + activeGattClients.values.map { it.device })
                .distinctBy { it.address }
        }
        val linkedAddresses = activeGattClients.keys
        for (device in candidates) {
            if (!connectIfNeeded && device.address !in linkedAddresses) continue
            targets++
            sendPacketToDevice(device, bytes)
        }
        return targets
    }

    private fun sendPacketToDevice(device: BluetoothDevice, bytes: ByteArray) {
        scope.launch(Dispatchers.IO) {
            try {
                var gatt = activeGattClients[device.address]
                if (gatt == null) {
                    connectPeerGatt(device)
                    var waitAttempts = 0
                    while (waitAttempts < 10 && activeGattClients[device.address] == null) {
                        delay(60)
                        waitAttempts++
                    }
                    gatt = activeGattClients[device.address]
                }
                if (gatt != null) {
                    val service = gatt.getService(SERVICE_UUID)
                    val characteristic = service?.getCharacteristic(CHAR_DATA_UUID)
                    if (characteristic != null) {
                        val mtu = negotiatedMtus[device.address] ?: 23
                        val maxChunkSize = maxOf(15, mtu - 8)
                        val chunks = chunkPacket(bytes, maxChunkSize)
                        for (chunk in chunks) {
                            writeCharacteristicData(gatt, characteristic, chunk)
                            if (chunks.size > 1) delay(12)
                        }
                        Log.d("BleMeshManager", "Direct GATT write ${chunks.size} chunks to ${device.address}")
                    }
                }
            } catch (e: Exception) {
                Log.w("BleMeshManager", "Error sending packet to ${device.address}", e)
            }
        }
    }

    private fun writeCharacteristicData(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val res = gatt.writeCharacteristic(
                    characteristic,
                    bytes,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                )
                res == 0
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = bytes
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        } catch (e: Exception) {
            Log.w("BleMeshManager", "Failed to write characteristic data", e)
            false
        }
    }

    // =========================================================================
    // ADVERTISER — distress beacon transmission
    // =========================================================================

    /** The newest beacon the app wants on the air (may differ from [onAirBeacon]). */
    private var requestedBeacon: DistressBeaconPayload? = null

    /** TX power to use for the next real advertise (re)start. */
    private var requestedTxPower: BeaconTxPower = BeaconTxPower.HIGH

    /** True while some mode wants the beacon advertised. */
    private var advertiseRequested = false

    /** The payload currently confirmed (or optimistically) on the air. */
    private var onAirBeacon: DistressBeaconPayload? = null

    /** When the radio was last actually (re)started. */
    private var lastAdvertiseRestartEpochMs = 0L

    private var advertiseRetryJob: Job? = null
    private var advertiseRetryAttempts = 0
    private var advertiseWatchdogJob: Job? = null

    private val advertiseCallback: AdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            _isAdvertising.value = true
            advertiseRetryAttempts = 0
            onAirBeacon = requestedBeacon
            Log.i("BleMeshManager", "BLE beacon advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            _isAdvertising.value = false
            onAirBeacon = null
            lastAdvertiseErrorCode = errorCode
            if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED) {
                // A stale advertisement (old role/coordinates) is still on the
                // air. Tear it down and retry with the payload we actually want
                // instead of pretending success.
                runCatching {
                    bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
                }
                Log.w("BleMeshManager", "BLE advertising already active - stopping stale advert and retrying")
                scheduleAdvertiseRetry()
                return
            }
            Log.e("BleMeshManager", "BLE beacon advertising failed, errorCode: $errorCode")
            onAdvertisingFailed?.invoke(
                AdvertiseFailure(errorCode, "BLE advertise start failed, code $errorCode")
            )
            scheduleAdvertiseRetry()
        }
    }

    /**
     * Re-arms the advertiser after a controller failure while a beacon is still
     * wanted. Bounded so a permanently broken controller cannot loop forever.
     */
    private fun scheduleAdvertiseRetry() {
        if (!advertiseRequested) return
        if (advertiseRetryAttempts >= ADVERTISE_MAX_RETRIES) {
            Log.w("BleMeshManager", "BLE advertise retries exhausted; beacon stays off until the mode changes")
            return
        }
        advertiseRetryJob?.cancel()
        advertiseRetryJob = scope.launch {
            delay(ADVERTISE_RETRY_DELAY_MS * (advertiseRetryAttempts + 1))
            if (!advertiseRequested || _isAdvertising.value) return@launch
            advertiseRetryAttempts++
            Log.i("BleMeshManager", "Retrying BLE advertise start (attempt $advertiseRetryAttempts)")
            applyAdvertising(force = true)
        }
    }

    /**
     * Periodically re-asserts the beacon so a controller that silently stopped
     * advertising (no failure callback) heals itself. Bounded cadence, so this
     * is not the per-tick churn the app used to have.
     */
    private fun startAdvertiseWatchdog() {
        if (advertiseWatchdogJob?.isActive == true) return
        advertiseWatchdogJob = scope.launch {
            while (isActive) {
                delay(ADVERT_WATCHDOG_PERIOD_MS)
                if (advertiseRequested) applyAdvertising(force = true)
            }
        }
    }


    /**
     * Requests that [beacon] be advertised.
     *
     * Coalesces volatile churn: if an advertisement with the same semantic
     * role/identity is already on the air, only a role change or the throttled
     * refresh window ([ADVERT_REFRESH_MIN_INTERVAL_MS]) triggers a real
     * stop+start. Coordinates and battery change on every GPS tick and must not
     * repeatedly take the beacon off the air - that churn is what made some
     * devices intermittently invisible to their peers.
     *
     * @return true when advertising was handed to the platform (or already on air).
     */
    fun startAdvertising(
        beacon: DistressBeaconPayload,
        txPower: BeaconTxPower = BeaconTxPower.HIGH
    ): Boolean {
        requestedBeacon = beacon
        requestedTxPower = txPower
        advertiseRequested = true
        startAdvertiseWatchdog()
        return applyAdvertising(force = false)
    }

    /**
     * Applies [requestedBeacon] to the radio.
     *
     * @param force when true the advertisement is restarted even if only
     *   volatile telemetry changed (used after failures and by the watchdog).
     */
    private fun applyAdvertising(force: Boolean): Boolean {
        val beacon = requestedBeacon ?: return false
        if (!hasBleAdvertise()) {
            onAdvertisingFailed?.invoke(
                AdvertiseFailure(
                    AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED,
                    "BLE advertise permission missing"
                )
            )
            return false
        }
        val advertiser: BluetoothLeAdvertiser = try {
            bluetoothAdapter?.bluetoothLeAdvertiser
        } catch (_: SecurityException) {
            null
        } ?: return false

        val now = System.currentTimeMillis()
        val mustRestart = force || shouldRestartAdvertising(
            onAirIdentity = onAirBeacon?.let { BeaconIdentity.of(it) },
            onAirBytes = onAirBeacon?.toManufacturerData(),
            requested = beacon,
            lastRestartEpochMs = lastAdvertiseRestartEpochMs,
            nowEpochMs = now,
            refreshMinIntervalMs = ADVERT_REFRESH_MIN_INTERVAL_MS
        )
        if (!mustRestart) {
            // An equivalent advertisement is already on the air; leave it alone.
            return true
        }

        // A real (re)start: tear down whatever is on air first.
        if (_isAdvertising.value || onAirBeacon != null) {
            runCatching { advertiser.stopAdvertising(advertiseCallback) }
            _isAdvertising.value = false
        }

        return try {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(requestedTxPower.advertiseConstant)
                .setConnectable(true)
                .setTimeout(0)
                .build()
            val advertiseData = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(
                    DistressBeaconPayload.MANUFACTURER_ID,
                    beacon.toManufacturerData()
                )
                .build()
            val scanResponse = AdvertiseData.Builder()
                .setIncludeTxPowerLevel(true)
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
            startGattServer()
            advertiser.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
            // Bookkeeping; onStartSuccess/onStartFailure reconciles this.
            onAirBeacon = beacon
            lastAdvertiseRestartEpochMs = now
            _isAdvertising.value = true
            true
        } catch (t: Throwable) {
            _isAdvertising.value = false
            onAirBeacon = null
            onAdvertisingFailed?.invoke(
                AdvertiseFailure(
                    AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR,
                    t.message ?: "startAdvertising failed"
                )
            )
            scheduleAdvertiseRetry()
            false
        }
    }

    fun stopAdvertising() {
        advertiseRequested = false
        advertiseRetryJob?.cancel()
        advertiseRetryJob = null
        advertiseRetryAttempts = 0
        runCatching { bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        _isAdvertising.value = false
        onAirBeacon = null
        requestedBeacon = null
        checkStopGattServer()
    }

    // =========================================================================
    // SCANNER — discovers iTantra beacons and estimates distances
    // =========================================================================

    /** True while some mode wants scanning active. */
    private var scanRequested = false

    private var scanRetryJob: Job? = null
    private var scanRetryAttempts = 0
    private var scanWatchdogJob: Job? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach(::handleScanResult)
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
            lastScanErrorCode = errorCode
            Log.e("BleMeshManager", "BLE scan failed, errorCode: $errorCode")
            scheduleScanRetry()
        }
    }

    private fun handleScanResult(result: ScanResult) {
        try {
            lastRawScanResultEpochMs = System.currentTimeMillis()
            val record = result.scanRecord ?: return
            val payloadBytes = record.getManufacturerSpecificData(DistressBeaconPayload.MANUFACTURER_ID)
                ?: return
            val payload = DistressBeaconPayload.parseManufacturerData(payloadBytes) ?: return
            lastItantraBeaconEpochMs = System.currentTimeMillis()

            discoveredDevices[payload.nodeId] = result.device
            nodeIdByAddress[result.device.address] = payload.nodeId
            connectPeerGatt(result.device)

            Log.d(
                "BleMeshManager",
                "Discovered iTantra beacon: node=${payload.nodeId}, distress=${payload.isDistress}, rssi=${result.rssi}"
            )

            val tracker = synchronized(trackers) {
                trackers.getOrPut(payload.nodeId) {
                    RangedNodeDistanceTracker(initialRssi = result.rssi.toDouble())
                }
            }
            val meters = tracker.updateRssi(result.rssi)

            val beacon = DiscoveredBeacon(
                nodeId = payload.nodeId,
                rssi = result.rssi,
                estimatedDistanceMeters = meters,
                batteryPercent = payload.batteryPercent,
                latitudeDeg = payload.latitudeDeg,
                longitudeDeg = payload.longitudeDeg,
                altitudeMeters = payload.altitudeMeters,
                languageIso = payload.languageIso,
                isDistress = payload.isDistress,
                lastSeenEpochMs = System.currentTimeMillis()
            )
            synchronized(latest) {
                latest[beacon.nodeId] = beacon
                publishBeaconsLocked()
            }
        } catch (e: Exception) {
            Log.w("BleMeshManager", "Error handling scan result", e)
        }
    }

    private fun publishBeaconsLocked() {
        _discoveredBeacons.value = latest.values.sortedBy { it.estimatedDistanceMeters }
    }


    /**
     * Starts scanning for iTantra beacons (idempotent).
     *
     * @return true when scanning was handed to the platform successfully.
     */
    fun startScanning(): Boolean {
        scanRequested = true
        startScanWatchdog()
        return startScanningInternal()
    }

    private fun startScanningInternal(): Boolean {
        if (_isScanning.value) return true
        if (!hasBleScan()) {
            Log.w("BleMeshManager", "Cannot start scan: missing BLE scan permission")
            return false
        }
        val scanner: BluetoothLeScanner = try {
            bluetoothAdapter?.bluetoothLeScanner
        } catch (_: SecurityException) {
            null
        } ?: run {
            Log.w("BleMeshManager", "BluetoothLeScanner is null (BT enabled=${isBluetoothEnabled()})")
            return false
        }
        return try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            // Broad filter matches all packets so vendor chipset filters don't drop beacons;
            // handleScanResult specifically discards non-iTantra manufacturer packets.
            val anyFilter = ScanFilter.Builder().build()
            startGattServer()
            synchronized(recentScanStartEpochMs) {
                recentScanStartEpochMs.addLast(System.currentTimeMillis())
                while (recentScanStartEpochMs.size > 10) recentScanStartEpochMs.removeFirst()
            }
            scanner.startScan(listOf(anyFilter), settings, scanCallback)
            _isScanning.value = true
            scanRetryAttempts = 0
            Log.i("BleMeshManager", "BLE scanner started")
            if (pruneJob?.isActive != true) startPruning()
            true
        } catch (t: Throwable) {
            Log.e("BleMeshManager", "startScan threw exception", t)
            _isScanning.value = false
            scheduleScanRetry()
            false
        }
    }

    /**
     * Re-arms the scanner after a controller failure while scanning is still
     * wanted. Bounded so a permanently broken controller cannot loop forever.
     */
    private fun scheduleScanRetry() {
        if (!scanRequested) return
        if (scanRetryAttempts >= SCAN_MAX_RETRIES) {
            Log.w("BleMeshManager", "BLE scan retries exhausted; scanning stays off until the mode changes")
            return
        }
        scanRetryJob?.cancel()
        scanRetryJob = scope.launch {
            delay(SCAN_RETRY_DELAY_MS * (scanRetryAttempts + 1))
            if (!scanRequested || _isScanning.value) return@launch
            scanRetryAttempts++
            Log.i("BleMeshManager", "Retrying BLE scan start (attempt $scanRetryAttempts)")
            startScanningInternal()
        }
    }

    /**
     * Self-heals a scan that the platform stopped silently (OEM throttling /
     * controller reset) without ever calling back into the app.
     */
    private fun startScanWatchdog() {
        if (scanWatchdogJob?.isActive == true) return
        scanWatchdogJob = scope.launch {
            while (isActive) {
                delay(SCAN_WATCHDOG_PERIOD_MS)
                if (!scanRequested) break
                if (!_isScanning.value) {
                    Log.w("BleMeshManager", "Scan watchdog: scanner not active, re-arming")
                    startScanningInternal()
                    continue
                }
                // Flag says we are scanning but nothing has arrived: the platform
                // stopped delivering callbacks without ever calling onScanFailed.
                val lastStart = synchronized(recentScanStartEpochMs) {
                    recentScanStartEpochMs.lastOrNull()
                } ?: 0L
                val aliveSince = maxOf(lastRawScanResultEpochMs, lastStart)
                if (aliveSince == 0L) continue
                val silentFor = System.currentTimeMillis() - aliveSince
                if (silentFor > SCAN_LIVENESS_TIMEOUT_MS) {
                    Log.w(
                        "BleMeshManager",
                        "Scan watchdog: no results for ${silentFor}ms despite isScanning=true, forcing stop+restart"
                    )
                    runCatching { bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback) }
                    _isScanning.value = false
                    startScanningInternal()
                }
            }
        }
    }

    fun stopScanning() {
        scanRequested = false
        scanRetryJob?.cancel()
        scanRetryJob = null
        scanWatchdogJob?.cancel()
        scanWatchdogJob = null
        scanRetryAttempts = 0
        runCatching { bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        _isScanning.value = false
        synchronized(latest) {
            latest.clear()
            publishBeaconsLocked()
        }
        synchronized(trackers) { trackers.clear() }
        checkStopGattServer()
    }

    private fun startPruning() {
        pruneJob = scope.launch {
            while (isActive) {
                delay(PRUNE_PERIOD_MS)
                synchronized(latest) {
                    val cutoff = System.currentTimeMillis() - BEACON_STALE_MS
                    val stale = latest.filterValues { it.lastSeenEpochMs < cutoff }.keys
                    if (stale.isNotEmpty()) {
                        stale.forEach { nodeId ->
                            latest.remove(nodeId)
                            synchronized(trackers) { trackers.remove(nodeId) }
                        }
                        publishBeaconsLocked()
                    }
                }
            }
        }
    }

    /**
     * Log-only one-line snapshot of the BLE radio for a debug dump. Nothing in
     * the control flow consumes this; it exists to tell "flag off", "dead scan"
     * and "advertise failed" apart from device logs. Ages are milliseconds since
     * the event, or -1 when it has never happened.
     */
    fun radioDiagnostics(): String {
        val now = System.currentTimeMillis()
        fun age(epochMs: Long) = if (epochMs == 0L) -1L else now - epochMs
        val scanStarts30s = synchronized(recentScanStartEpochMs) {
            recentScanStartEpochMs.count { now - it <= 30_000L }
        }
        return "ble[advReq=$advertiseRequested adv=${_isAdvertising.value} " +
            "scanReq=$scanRequested scan=${_isScanning.value} " +
            "lastRawScanMs=${age(lastRawScanResultEpochMs)} lastBeaconMs=${age(lastItantraBeaconEpochMs)} " +
            "lastScanErr=$lastScanErrorCode lastAdvErr=$lastAdvertiseErrorCode " +
            "scanStarts30s=$scanStarts30s " +
            "gattClients=${activeGattClients.size} gattServerClients=${connectedGattClients.size} " +
            "connecting=${connectingDevices.size} discovered=${discoveredDevices.size}]"
    }

    /** Stops advertising, scanning, GATT server and background pruning. */
    fun shutdown() {
        stopAdvertising()
        stopScanning()
        stopGattServer()
        scope.cancel()
    }
}
