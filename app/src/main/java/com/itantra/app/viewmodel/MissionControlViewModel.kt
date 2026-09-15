package com.itantra.app.viewmodel

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.itantra.app.ai.OnnxInferenceManager
import com.itantra.app.ai.TranslationEngine
import com.itantra.app.audio.AudioCaptureEngine
import com.itantra.app.audio.AudioPlaybackEngine
import com.itantra.app.audio.LiveAudioWindow
import com.itantra.app.audio.VoiceCaptureGate
import com.itantra.app.audio.VoiceStreamGate
import com.itantra.app.audio.VoiceTurnCoordinator
import com.itantra.app.audio.shortsToPcmLittleEndian
import com.itantra.app.data.SettingsRepository
import com.itantra.app.data.VoiceMessageEntity
import com.itantra.app.mesh.BeaconTxPower
import com.itantra.app.mesh.BleMeshManager
import com.itantra.app.mesh.DirectPeerAddressBook
import com.itantra.app.mesh.DiscoveredBeacon
import com.itantra.app.mesh.DistressBeaconPayload
import com.itantra.app.mesh.ItantraPacket
import com.itantra.app.mesh.PacketFraming
import com.itantra.app.mesh.PairingHandshakePayload
import com.itantra.app.mesh.PairingSyncPayload
import com.itantra.app.mesh.PeerProfile
import com.itantra.app.mesh.PeerProfileCache
import com.itantra.app.mesh.ProfilePayload
import com.itantra.app.mesh.VoiceFrame
import com.itantra.app.mesh.WifiDirectMeshManager
import com.itantra.app.mesh.fallbackNodeLabel
import com.itantra.app.mesh.estimateMeters
import com.itantra.app.mesh.fuseGpsAndBleDistance
import com.itantra.app.mesh.smoothCompassHeading
import com.itantra.app.model.AlertPriority
import com.itantra.app.model.ConnectionStatus
import com.itantra.app.model.DistressVictim
import com.itantra.app.model.LanguagePack
import com.itantra.app.model.MissionTelemetry
import com.itantra.app.model.PeerDevice
import com.itantra.app.model.RadioChannelState
import com.itantra.app.model.RescueConnectionMode
import com.itantra.app.model.RescuerNode
import com.itantra.app.model.SttModelInfo
import com.itantra.app.model.SupportedLanguage
import com.itantra.app.model.TransportProtocol
import com.itantra.app.model.TtsModelInfo
import com.itantra.app.model.VadStatus
import com.itantra.app.model.VerifiedAsset
import com.itantra.app.model.VoiceStatus
import com.itantra.app.modelhub.CatalogueLanguage
import com.itantra.app.modelhub.LanguageModelPack
import com.itantra.app.modelhub.ModelCatalogue
import com.itantra.app.modelhub.ModelDownloadManager
import com.itantra.app.modelhub.ModelDownloadState
import com.itantra.app.modelhub.ModelStorageManager
import com.itantra.app.service.TacticalMeshService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

data class MissionUiState(
    val selectedLanguage: SupportedLanguage = SupportedLanguage.HINDI,
    val isPttActive: Boolean = true,
    val deviceRole: String = "TRANSCEIVER",
    val channelState: RadioChannelState = RadioChannelState.STANDBY,
    val currentTranscript: String = "",
    val voiceStatus: VoiceStatus? = null,
    val activeIncomingCaption: String? = null,
    val activeIncomingIsAlert: Boolean = false,
    val forceMaxVolumeAlerts: Boolean = true,
    val isLowPowerListeningEnabled: Boolean = true,
    val keepScreenAwake: Boolean = true,
    val showArmDistressDialog: Boolean = false,
    val directIpInput: String = "",
    val themeMode: String = "light", // Default to bright theme
    val isOnboardingCompleted: Boolean = false,
    val userName: String = "",
    val userAge: Int? = null,
    val userGender: String = "Male",
    val userLanguages: Set<String> = setOf("hi", "en"),
    val relativeRelation: String = "Parent",
    val relativePhone: String = ""
)

/**
 * Pure Compose UI ViewModel supporting the 4 Core Modes:
 * 1. SOS (Distress & Victim Broadcast)
 * 2. Walkie (Team Group Voice Mesh)
 * 3. Rescue (First Responder Sonar & Instant Intercom)
 * 4. Settings (Identity, Neural Models, Radios)
 *
 * Phase B: the simulated parts of SOS/Walkie/Rescue are replaced with real
 * hardware engines — BLE advertising/scanning, Wi-Fi Direct + UDP mesh,
 * AudioRecord/AudioTrack with VAD, and ONNX Runtime inference. Every engine
 * is nullable and permission-guarded: on unsupported hardware (emulator,
 * missing permissions, no model installed) the mode degrades to a safe no-op
 * instead of crashing.
 */
class MissionControlViewModel(application: Application) : AndroidViewModel(application), SensorEventListener, LocationListener {

    // --- Global UI State ---
    private val _uiState = MutableStateFlow(MissionUiState())
    val uiState: StateFlow<MissionUiState> = _uiState.asStateFlow()

    // =========================================================================
    // PHASE B REAL HARDWARE ENGINES (all nullable — never assume hardware)
    // =========================================================================
    private val audioCaptureEngine: AudioCaptureEngine? =
        runCatching { AudioCaptureEngine(getApplication()) }.getOrNull()

    private val audioPlaybackEngine: AudioPlaybackEngine? =
        runCatching { AudioPlaybackEngine(getApplication()) }.getOrNull()

    private val bleMeshManager: BleMeshManager? =
        runCatching { BleMeshManager(getApplication()) }.getOrNull()

    private val wifiDirectMeshManager: WifiDirectMeshManager? =
        runCatching { WifiDirectMeshManager(getApplication()) }.getOrNull()

    private val onnxInferenceManager: OnnxInferenceManager? =
        runCatching { OnnxInferenceManager(getApplication()) }.getOrNull()

    // =========================================================================
    // VOICE MESH PIPELINE (pure policy + field-testing instrumentation)
    // =========================================================================
    /**
     * Every checkpoint of the mic -> mesh -> speaker path logs under this tag
     * so a field test can localize a break to one hop:
     *   [stt]  transcription output        [send] size + target before transmit
     *   [ble]  BLE GATT send result        [udp]  UDP broadcast/unicast result
     *   [rx]   inbound bytes + source      [decode] decoded packet fields
     *   [ui]   transcript/caption update    [tts]  TTS triggered or suppressed
     */
    private val voicePipelineTag = "ItantraVoice"

    private fun logVoice(stage: String, message: String) {
        Log.i(voicePipelineTag, "[$stage] $message")
    }

    /** Decides which captured frames go on the air and owns the frame sequence. */
    private val voiceStreamGate = VoiceStreamGate()

    /** Coordinates voice turn lifecycle, 8s force-flush ceiling, and 200ms noise threshold. */
    private val voiceTurnCoordinator = VoiceTurnCoordinator()

    /** Tracks recent live VOICE_FRAME arrivals so TTS does not double the audio. */
    private val liveAudioWindow = LiveAudioWindow()

    /** Direct peer IPs learned from inbound UDP datagrams (unicast fallback). */
    private val peerAddressBook = DirectPeerAddressBook()

    /**
     * Outbound live-audio queue: the capture thread must never block on socket
     * or GATT I/O, so frames are handed to a single sender coroutine. Old
     * frames are dropped rather than queued when the link cannot keep up.
     */
    private val voiceFrameChannel = Channel<ByteArray>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val voiceFrameSenderJob: Job by lazy {
        viewModelScope.launch(Dispatchers.IO) {
            for (encoded in voiceFrameChannel) {
                dispatchVoiceFrame(encoded)
            }
        }
    }

    private var droppedVoiceFrames = 0L
    private var sentVoiceFrames = 0L

    private fun MissionUiState.withStatus(status: VoiceStatus): MissionUiState =
        copy(voiceStatus = status, currentTranscript = status.displayText)

    private fun MissionUiState.clearStatus(): MissionUiState =
        if (voiceStatus == null) {
            this
        } else {
            copy(
                voiceStatus = null,
                currentTranscript = if (currentTranscript == voiceStatus.displayText) "" else currentTranscript
            )
        }

    private var systemTts: TextToSpeech? = null
    private var isSystemTtsReady = false

    /**
     * This device's stable mesh node id. Falls back to a per-process random id
     * until the persisted DataStore value is read; every beacon/packet built
     * before then uses the fallback.
     */
    private val fallbackNodeId: Long = (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1L)
    private val _nodeId = MutableStateFlow(fallbackNodeId)
    val meshNodeId: StateFlow<Long> = _nodeId.asStateFlow()

    // =========================================================================
    // OFFLINE MODEL HUB & SETTINGS PERSISTENCE (real backends)
    // =========================================================================
    private val settingsRepository = SettingsRepository(getApplication())

    private val modelStorageManager = ModelStorageManager(getApplication())

    private val modelDownloadManager = ModelDownloadManager(
        context = getApplication(),
        storageManager = modelStorageManager,
        scope = viewModelScope,
        onPackInstalled = { languageTag ->
            settingsRepository.setInstalledLanguageTags(modelStorageManager.installedTags())
            // Natural idle point: warm the freshly installed pack's TTS
            // sessions so incoming messages never pay the session load.
            prewarmTts(SupportedLanguage.fromCode(languageTag))
        }
    )

    val translationEngine = TranslationEngine(getApplication(), modelStorageManager)

    private val peerLanguages = ConcurrentHashMap<Long, String>()
    private val peerTranslatorAvailable = ConcurrentHashMap<Long, Boolean>()

    private val _isCrossLingualBlocked = MutableStateFlow(false)
    val isCrossLingualBlocked: StateFlow<Boolean> = _isCrossLingualBlocked.asStateFlow()

    private val _activePeerLanguage = MutableStateFlow<String?>(null)
    val activePeerLanguage: StateFlow<String?> = _activePeerLanguage.asStateFlow()

    private val _isTranslationModelInstalled = MutableStateFlow(translationEngine.isInstalled())
    val isTranslationModelInstalled: StateFlow<Boolean> = _isTranslationModelInstalled.asStateFlow()

    private val _translationDownloadState = MutableStateFlow<ModelDownloadState>(
        if (translationEngine.isInstalled()) ModelDownloadState.Installed else ModelDownloadState.Idle
    )
    val translationDownloadState: StateFlow<ModelDownloadState> = _translationDownloadState.asStateFlow()

    init {
        // Only mark installed if model actually exists on disk
        if (translationEngine.isInstalled()) {
            _isTranslationModelInstalled.value = true
            _translationDownloadState.value = ModelDownloadState.Installed
            // Deferred: checkCrossLingualStatus() reads StateFlow fields that are
            // declared LATER in this constructor (e.g. _connectedVictimIntercom,
            // _connectedRescuer, _pairedWalkieDevices). Calling it synchronously here
            // NPEs on cold start after the NMT model is installed. Post it to run
            // after construction completes.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                checkCrossLingualStatus()
            }
        }

        viewModelScope.launch {
            val savedLangCode = settingsRepository.selectedLanguageCode.value
            if (!savedLangCode.isNullOrBlank()) {
                val lang = SupportedLanguage.fromCode(savedLangCode)
                _uiState.update { it.copy(selectedLanguage = lang) }
                prewarmTts(lang)
            } else {
                // If default is not installed on disk, but another installed pack exists (e.g. "en"), prefer the installed pack
                val currentCode = _uiState.value.selectedLanguage.code
                if (!modelStorageManager.isInstalledOnDisk(currentCode)) {
                    if (modelStorageManager.isInstalledOnDisk("en")) {
                        val en = SupportedLanguage.ENGLISH
                        _uiState.update { it.copy(selectedLanguage = en) }
                        prewarmTts(en)
                    }
                }
            }
        }
    }

    fun downloadTranslationModel() {
        viewModelScope.launch {
            _translationDownloadState.value = ModelDownloadState.Downloading(0L, 50_855_936L)
            for (step in 1..4) {
                delay(250)
                val progress = (50_855_936L * step) / 4
                _translationDownloadState.value = ModelDownloadState.Downloading(progress, 50_855_936L)
            }
            _translationDownloadState.value = ModelDownloadState.Verifying
            delay(250)
            _translationDownloadState.value = ModelDownloadState.Extracting
            delay(250)
            modelStorageManager.installTranslationModelSimulated()
            translationEngine.downloadMlKitModels(
                onSuccess = {
                    Log.i("MissionControl", "Google ML Kit models ready")
                }
            )
            _isTranslationModelInstalled.value = true
            _translationDownloadState.value = ModelDownloadState.Installed
            checkCrossLingualStatus()
            broadcastTranslationCapability()
        }
    }

    fun deleteTranslationModel() {
        viewModelScope.launch {
            translationEngine.deleteMlKitModels()
            modelStorageManager.deleteTranslationModel()
            _isTranslationModelInstalled.value = false
            _translationDownloadState.value = ModelDownloadState.Idle
            checkCrossLingualStatus()
            broadcastTranslationCapability()
        }
    }

    fun translateEmergencyText(text: String, fromIso: String, toIso: String): String {
        return translationEngine.translate(text, fromIso, toIso)
    }

    fun broadcastTranslationCapability() {
        val isInstalled = translationEngine.isInstalled()
        val localLang = _uiState.value.selectedLanguage.code
        val payload = "$localLang|${if (isInstalled) 1 else 0}".toByteArray(Charsets.UTF_8)
        val packet = ItantraPacket(
            nodeId = _nodeId.value,
            ttl = _meshHopLimit.value,
            msgType = PacketFraming.MSG_TYPE_TRANSLATION_CAPABILITY,
            payload = payload
        )
        broadcastMeshPacket(PacketFraming.encode(packet))
    }

    fun checkCrossLingualStatus() {
        val localLang = _uiState.value.selectedLanguage.code
        val connectedVictim = _connectedVictimIntercom.value
        val connectedRescuer = _connectedRescuer.value
        val pairedWalkie = _pairedWalkieDevices.value.firstOrNull { it.isConnected }

        val peerNodeId: Long? = when {
            connectedVictim != null -> connectedVictim.nodeId
            connectedRescuer != null -> connectedRescuer.nodeId
            pairedWalkie != null -> pairedWalkie.id.removePrefix("node-").removePrefix("resc-").removePrefix("beacon-").toLongOrNull()
            else -> null
        }

        val peerLang: String? = when {
            connectedVictim != null -> connectedVictim.language.code
            connectedRescuer != null -> peerLanguages[connectedRescuer.nodeId] ?: connectedRescuer.language.code
            peerNodeId != null -> peerLanguages[peerNodeId]
            else -> null
        }

        _activePeerLanguage.value = peerLang

        if (peerNodeId != null && peerLang != null && !peerLang.equals(localLang, ignoreCase = true)) {
            val localHasTranslator = translationEngine.isInstalled()
            val peerHasTranslator = peerTranslatorAvailable[peerNodeId] == true
            if (!localHasTranslator && !peerHasTranslator) {
                _isCrossLingualBlocked.value = true
                val localName = SupportedLanguage.fromCode(localLang).englishName
                val peerName = SupportedLanguage.fromCode(peerLang).englishName
                _modelWarningMessage.value = "⚠️ Language Mismatch: Local speaks $localName but peer speaks $peerName. Offline Translation model required before voice conversation. Please download in Settings → Models."
                syncVoiceCaptureState()
                return
            }
        }

        _isCrossLingualBlocked.value = false
        if (_modelWarningMessage.value?.startsWith("⚠️ Language Mismatch") == true) {
            _modelWarningMessage.value = null
        }
        syncVoiceCaptureState()
    }

    /** The active catalogue: the built-in fallback, refreshed over the network when available. */
    private val _catalogue = MutableStateFlow<List<CatalogueLanguage>>(ModelCatalogue.fallbackLanguages)

    private fun buildModelPacks(
        catalogue: List<CatalogueLanguage>,
        installed: Map<String, Long>,
        downloadStates: Map<String, ModelDownloadState>
    ): List<LanguageModelPack> = catalogue.map { language ->
        val isInstalled = installed.containsKey(language.languageTag)
        LanguageModelPack(
            languageTag = language.languageTag,
            name = language.name,
            script = language.script,
            iso = language.iso,
            sizeMb = language.sizeMb,
            sha256 = language.sha256,
            isInstalled = isInstalled,
            downloadState = if (isInstalled) {
                ModelDownloadState.Installed
            } else {
                downloadStates[language.languageTag] ?: ModelDownloadState.Idle
            }
        )
    }

    private val _modelPacks: StateFlow<List<LanguageModelPack>> = combine(
        _catalogue,
        modelStorageManager.installedPacks,
        modelDownloadManager.states
    ) { catalogue, installed, states -> buildModelPacks(catalogue, installed, states) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            buildModelPacks(ModelCatalogue.fallbackLanguages, emptyMap(), emptyMap())
        )

    /** Real model-hub state backing the Settings screen model section. */
    val modelPacks: StateFlow<List<LanguageModelPack>> = _modelPacks

    // =========================================================================
    // 1. SOS MODE (Distress Broadcast)
    // =========================================================================
    private val _isSosBroadcasting = MutableStateFlow(false)
    val isSosBroadcasting: StateFlow<Boolean> = _isSosBroadcasting.asStateFlow()

    // Global radio-enable flags: both radios default ON so walkie/rescue usage
    // works out of the box; SOS still forces both ON while broadcasting.
    private val _wifiDirectEnabled = MutableStateFlow(true)
    val wifiDirectEnabled: StateFlow<Boolean> = _wifiDirectEnabled.asStateFlow()

    private val _bluetoothEnabled = MutableStateFlow(true)
    val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()

    /** Periodic Wi-Fi Direct long-range discovery loop while any mesh mode is on. */
    private var wifiDirectScanJob: Job? = null

    // Scan-driven: populated from non-distress iTantra beacons + link requests.
    private val _nearbyRescuers = MutableStateFlow<List<RescuerNode>>(emptyList())
    val nearbyRescuers: StateFlow<List<RescuerNode>> = _nearbyRescuers.asStateFlow()

    private val _connectedRescuer = MutableStateFlow<RescuerNode?>(null)
    val connectedRescuer: StateFlow<RescuerNode?> = _connectedRescuer.asStateFlow()
    private var lastExplicitDisconnectEpochMs = 0L

    /** Real battery percentage via BatteryManager (defaults to 100 when unknown). */
    private fun currentBatteryPercent(): Int {
        return try {
            val bm = getApplication<Application>().getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (pct in 1..100) pct else 100
        } catch (_: Exception) {
            100
        }
    }

    /** Builds the real distress beacon from live identity/location/battery. */
    private fun buildDistressBeaconPayload(): DistressBeaconPayload = DistressBeaconPayload(
        nodeId = _nodeId.value,
        batteryPercent = currentBatteryPercent(),
        latitudeDeg = _rescuerLat.value,
        longitudeDeg = _rescuerLon.value,
        altitudeMeters = 0, // no barometric altitude source wired yet
        languageIso = _uiState.value.selectedLanguage.code,
        isDistress = true
    )

    fun toggleWifiDirect(enabled: Boolean) {
        // Global radio toggle: valid on any page, not only while SOS is live.
        _wifiDirectEnabled.value = enabled
        val mesh = wifiDirectMeshManager
        if (enabled) {
            // Become the group owner so rescuers can join and reach us over UDP.
            mesh?.createGroup()
            mesh?.startUdpBroadcast()
        } else {
            mesh?.removeGroup()
            stopMeshUdpIfIdle()
        }
        // Re-sync the active mode: BLE alone carries everything while this is off.
        syncWifiDirectScan()
    }

    fun toggleBluetooth(enabled: Boolean) {
        // Global radio toggle: valid on any page, not only while SOS is live.
        _bluetoothEnabled.value = enabled
        if (enabled) {
            // Re-assert whatever the active mode needs on the air.
            syncBeaconAdvertising()
            startBleScanIfActive()
        } else {
            stopBeaconAdvertising()
            bleMeshManager?.stopScanning()
        }
        // Re-sync the active mode: Wi-Fi Direct alone carries everything while this is off.
        syncWifiDirectScan()
    }

    private fun currentBeaconTxPower(): BeaconTxPower {
        val p = _txPower.value
        return when {
            p.contains("Low", ignoreCase = true) -> BeaconTxPower.LOW
            p.contains("Max", ignoreCase = true) -> BeaconTxPower.HIGH
            else -> BeaconTxPower.MEDIUM
        }
    }

    /** Starts foreground keeper service and initiates BLE beacon advertising. */
    private fun startBeaconAdvertising(payload: DistressBeaconPayload) {
        val power = currentBeaconTxPower()
        try {
            TacticalMeshService.start(getApplication(), payload, power)
        } catch (_: Exception) {
            // Foreground start restriction fallback
        }
        bleMeshManager?.startAdvertising(payload, power)
    }

    private fun stopBeaconAdvertising() {
        try {
            TacticalMeshService.stop(getApplication())
        } catch (_: Exception) {
        }
        bleMeshManager?.stopAdvertising()
    }

    /** Non-distress presence beacon advertised while Walkie Mesh is on. */
    private fun buildWalkieBeaconPayload(): DistressBeaconPayload = DistressBeaconPayload(
        nodeId = _nodeId.value,
        batteryPercent = currentBatteryPercent(),
        latitudeDeg = _rescuerLat.value,
        longitudeDeg = _rescuerLon.value,
        altitudeMeters = DistressBeaconPayload.ALTITUDE_WALKIE_PRESENCE,
        languageIso = _uiState.value.selectedLanguage.code,
        isDistress = false
    )

    /**
     * Single source of truth for what is on the air. Exactly one beacon can be
     * advertised at a time, so the active modes are ranked:
     * SOS distress > Rescue > Walkie presence > nothing.
     *
     * The identity profile advert follows the same lifecycle (any active mode
     * advertises; no active mode stops it).
     */
    private fun syncBeaconAdvertising() {
        when {
            // Global Bluetooth off: Wi-Fi Direct carries everything, so nothing
            // may be beaconed or scanned over BLE.
            !_bluetoothEnabled.value -> stopBeaconAdvertising()
            _isSosBroadcasting.value -> startBeaconAdvertising(buildDistressBeaconPayload())
            _isRescueActive.value -> startRescuerBeaconAdvertising()
            _isWalkieActive.value -> bleMeshManager?.startAdvertising(
                buildWalkieBeaconPayload(),
                currentBeaconTxPower()
            )
            else -> stopBeaconAdvertising()
        }
        syncProfileBroadcast()
        // Central on/off re-sync: the long-range scan follows the same lifecycle.
        syncWifiDirectScan()
    }

    /**
     * Battery-friendly periodic Wi-Fi Direct long-range discovery. While any
     * mesh mode is active and Wi-Fi Direct is enabled, discovery re-runs on a
     * slow [WIFI_DIRECT_SCAN_INTERVAL_MS] loop and the refreshed peer list is
     * folded into the walkie device list. Idempotent: re-sync calls while the
     * job is already in the desired state are no-ops, so 1 Hz GPS ticks that
     * re-trigger [syncBeaconAdvertising] never reset the scan cadence.
     * BLE remains the always-on near-field scan and is not affected here.
     */
    private fun syncWifiDirectScan() {
        val shouldScan = (_isSosBroadcasting.value || _isRescueActive.value || _isWalkieActive.value) &&
            _wifiDirectEnabled.value
        if (wifiDirectScanJob?.isActive == true) {
            if (!shouldScan) {
                wifiDirectScanJob?.cancel()
                wifiDirectScanJob = null
                wifiDirectMeshManager?.stopDiscovery()
            }
            return
        }
        if (!shouldScan) {
            wifiDirectMeshManager?.stopDiscovery()
            return
        }
        wifiDirectScanJob = viewModelScope.launch {
            while (isActive) {
                wifiDirectMeshManager?.startDiscovery()
                delay(WIFI_DIRECT_SCAN_INTERVAL_MS)
                wifiDirectMeshManager?.requestPeers()
                refreshWalkieDevicesList()
            }
        }
    }

    /**
     * Recomputes the Walkie link indicator from real signals: a live BLE GATT
     * link, or a packet received from any peer in the last few seconds (UDP
     * peers have no GATT link of their own).
     */
    private fun updateWalkieLinkState() {
        val gattLinked = bleMeshManager?.connectedNodeIds?.value?.isNotEmpty() == true
        val recentPeerPacket = System.currentTimeMillis() - lastPeerContactEpochMs < PEER_CONTACT_LIVENESS_MS
        _isWalkieLinkActive.value = _isWalkieActive.value && (gattLinked || recentPeerPacket)
    }

    fun startSos() {
        clearSessionTranscripts()
        _isSosBroadcasting.value = true
        _wifiDirectEnabled.value = true
        _bluetoothEnabled.value = true
        _uiState.update { it.copy(channelState = RadioChannelState.TRANSMITTING) }

        // Real BLE distress beacon (foreground service + in-process fallback).
        syncBeaconAdvertising()

        // Wi-Fi Direct group + UDP mesh so rescuers can send voice-link packets.
        wifiDirectMeshManager?.createGroup()
        wifiDirectMeshManager?.startUdpBroadcast()
        syncWifiDirectScan()

        // Listen for rescuer nodes advertising on the mesh while in distress.
        bleMeshManager?.startScanning()

        // Loud siren beacon (+ localized TTS announcement when installed).
        startAudioBeacon(_uiState.value.selectedLanguage)

        // Voice capture only engages if a rescuer has actively linked to this victim
        syncVoiceCaptureState()
    }

    fun stopSos() {
        clearSessionTranscripts()
        _isSosBroadcasting.value = false
        _connectedRescuer.value = null
        _nearbyRescuers.value = emptyList()
        _wifiDirectEnabled.value = false
        _bluetoothEnabled.value = false
        _isReceivingOneWayBroadcast.value = false
        _uiState.update { it.copy(channelState = RadioChannelState.STANDBY) }

        stopAudioBeacon()
        wifiDirectMeshManager?.removeGroup()
        stopBleScanIfIdle()
        stopMeshUdpIfIdle()
        syncVoiceCaptureState()
        syncBeaconAdvertising()
        syncWifiDirectScan()
    }

    /**
     * Disconnects the victim from the active rescuer intercom.
     * Notifies the rescuer via UDP mesh and returns this victim to silent SOS standby.
     */
    fun disconnectConnectedRescuer() {
        clearSessionTranscripts()
        lastExplicitDisconnectEpochMs = System.currentTimeMillis()
        val rescuer = _connectedRescuer.value
        if (rescuer != null) {
            val rescuerNodeId = rescuer.id.removePrefix("resc-").toLongOrNull()
            if (rescuerNodeId != null) {
                val close = ItantraPacket(
                    nodeId = _nodeId.value,
                    ttl = _meshHopLimit.value,
                    msgType = PacketFraming.MSG_TYPE_VOICE_LINK_CLOSE,
                    payload = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(rescuerNodeId).array()
                )
                broadcastMeshPacket(PacketFraming.encode(close), rescuerNodeId)
            }
        }
        _connectedRescuer.value = null
        _isReceivingOneWayBroadcast.value = false
        syncVoiceCaptureState()
    }

    fun isBluetoothEnabled(): Boolean = bleMeshManager?.isBluetoothEnabled() ?: false
    fun isLocationEnabled(): Boolean = bleMeshManager?.isLocationEnabled() ?: false

    fun onBluetoothStateRestored() {
        // A globally disabled Bluetooth radio stays off even after the adapter
        // comes back — Wi-Fi Direct carries everything until the user re-enables it.
        if (!_bluetoothEnabled.value) return
        if (_isSosBroadcasting.value) {
            startBeaconAdvertising(buildDistressBeaconPayload())
            bleMeshManager?.startScanning()
        }
        if (_isRescueActive.value) {
            bleMeshManager?.startScanning()
            syncBeaconAdvertising()
        }
        if (_isWalkieActive.value) {
            bleMeshManager?.startScanning()
            syncBeaconAdvertising()
        }
    }

    // =========================================================================
    // 2. WALKIE-TALKIE MODE (Group Comms & Remembered Nodes)
    // =========================================================================
    private val _isWalkieActive = MutableStateFlow(false)
    val isWalkieActive: StateFlow<Boolean> = _isWalkieActive.asStateFlow()

    private val _isMicMuted = MutableStateFlow(false)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    private val _isTransmitting = MutableStateFlow(false)
    val isTransmitting: StateFlow<Boolean> = _isTransmitting.asStateFlow()

    private val _isRefreshingNodes = MutableStateFlow(false)
    val isRefreshingNodes: StateFlow<Boolean> = _isRefreshingNodes.asStateFlow()

    private val _activeWalkieChannel = MutableStateFlow(1)
    val activeWalkieChannel: StateFlow<Int> = _activeWalkieChannel.asStateFlow()

    private val _isSpeakerphoneOn = MutableStateFlow(true)
    val isSpeakerphoneOn: StateFlow<Boolean> = _isSpeakerphoneOn.asStateFlow()

    data class WalkiePairRequest(
        val fromNodeId: Long,
        val fromCallsign: String
    )

    private val _incomingPairRequest = MutableStateFlow<WalkiePairRequest?>(null)
    val incomingPairRequest: StateFlow<WalkiePairRequest?> = _incomingPairRequest.asStateFlow()

    private val _pendingPairingTargetNodeId = MutableStateFlow<Long?>(null)
    val pendingPairingTargetNodeId: StateFlow<Long?> = _pendingPairingTargetNodeId.asStateFlow()

    private val latestWalkiePresenceBeacons = ConcurrentHashMap<Long, DiscoveredBeacon>()
    private val lastPacketFromNode = ConcurrentHashMap<Long, Long>()

    private val _pairedWalkieDevices = MutableStateFlow<List<PeerDevice>>(emptyList())
    val pairedWalkieDevices: StateFlow<List<PeerDevice>> = _pairedWalkieDevices.asStateFlow()

    private val _discoveredWalkieDevices = MutableStateFlow<List<PeerDevice>>(emptyList())
    val discoveredWalkieDevices: StateFlow<List<PeerDevice>> = _discoveredWalkieDevices.asStateFlow()

    private val _isWalkieLinkActive = MutableStateFlow(false)
    val isWalkieLinkActive: StateFlow<Boolean> = _isWalkieLinkActive.asStateFlow()

    private val _remoteAudioLevel = MutableStateFlow(0f)
    val remoteAudioLevel: StateFlow<Float> = _remoteAudioLevel.asStateFlow()

    private val _isReceivingAudio = MutableStateFlow(false)
    val isReceivingAudio: StateFlow<Boolean> = _isReceivingAudio.asStateFlow()

    private var lastPeerContactEpochMs = 0L
    private var lastRemoteAudioFrameEpochMs = 0L
    private var remoteAudioDecayJob: Job? = null

    private val _isVadSpeaking = MutableStateFlow(false)
    val isVadSpeaking: StateFlow<Boolean> = _isVadSpeaking.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _speechProbability = MutableStateFlow(0f)
    val speechProbability: StateFlow<Float> = _speechProbability.asStateFlow()

    private val _vadStatus = MutableStateFlow(VadStatus.SILENCE)
    val vadStatus: StateFlow<VadStatus> = _vadStatus.asStateFlow()

    fun toggleWalkieMaster(active: Boolean) {
        clearSessionTranscripts()
        _isWalkieActive.value = active
        if (active) {
            startMeshVoiceCapture()
            syncBeaconAdvertising()
            if (_wifiDirectEnabled.value) {
                wifiDirectMeshManager?.startDiscovery()
                wifiDirectMeshManager?.startUdpBroadcast()
            }
            if (_bluetoothEnabled.value) {
                bleMeshManager?.startScanning() // BLE peers also appear as walkie nodes
            }
            // Walkie has to ADVERTISE as well: discovery alone never forms a
            // GATT link, because peers can only be found by their advert.
            syncBeaconAdvertising()
            syncWifiDirectScan()
            updateWalkieLinkState()
            logVoice(
                "walkie",
                "mesh ON: presence beacon + scan + UDP on ${WifiDirectMeshManager.UDP_PORT} (nodeId=${_nodeId.value})"
            )
        } else {
            wifiDirectMeshManager?.stopDiscovery()
            syncVoiceCaptureState()
            stopMeshUdpIfIdle()
            stopBleScanIfIdle()
            syncBeaconAdvertising()
            syncWifiDirectScan()
            _discoveredWalkieDevices.value = emptyList()
            _isTransmitting.value = false
            _isVadSpeaking.value = false
            _vadStatus.value = VadStatus.SILENCE
            _uiState.update { it.copy(channelState = RadioChannelState.STANDBY).clearStatus() }
            updateWalkieLinkState()
        }
    }

    fun toggleMicMute() {
        _isMicMuted.value = !_isMicMuted.value
        audioCaptureEngine?.setMuted(_isMicMuted.value)
        if (_isMicMuted.value) {
            _isTransmitting.value = false
        }
    }

    private val _isPttActive = MutableStateFlow(false)
    val isPttActive: StateFlow<Boolean> = _isPttActive.asStateFlow()

    fun startPtt() {
        if (_isWalkieActive.value && !_isRescueActive.value && !_isSosBroadcasting.value) {
            if (settingsRepository.pairedWalkieNodeIds.value.isEmpty()) {
                _modelWarningMessage.value = "⚠️ No Paired Radios: Please pair with a nearby team radio before transmitting voice."
                return
            }
        }
        if (_isCrossLingualBlocked.value) {
            val localLangName = _uiState.value.selectedLanguage.englishName
            val peerLangName = _activePeerLanguage.value?.let { SupportedLanguage.fromCode(it).englishName } ?: "Peer"
            _modelWarningMessage.value = "⚠️ Language Mismatch: Local speaks $localLangName but peer speaks $peerLangName. Offline Translation model required before voice conversation. Please download in Settings → Models."
            return
        }
        // Immediately silence any incoming audio playback and clear echo guard so user's PTT voice records instantly
        ttsPlaybackJob?.cancel()
        audioPlaybackEngine?.stop()
        systemTts?.stop()
        echoGuardUntil = 0L

        startMeshVoiceCapture()
        voiceFrameSequence = 0
        synchronized(voiceTurnBuffer ?: this) {
            voiceTurnBuffer?.reset()
        }
        _isMicMuted.value = false
        audioCaptureEngine?.setMuted(false)
        _isPttActive.value = true
        _isVadSpeaking.value = true
        _isTransmitting.value = true
        voiceStreamGate.beginTurn()
        _uiState.update {
            it.copy(channelState = RadioChannelState.TRANSMITTING).withStatus(VoiceStatus.LISTENING_PTT)
        }
        logVoice("send", "PTT down: live voice frames streaming")
    }

    fun stopPtt() {
        if (!_isPttActive.value) return
        _isPttActive.value = false
        _isVadSpeaking.value = false
        _isTransmitting.value = false
        _uiState.update {
            it.copy(channelState = RadioChannelState.STANDBY).withStatus(VoiceStatus.TRANSCRIBING)
        }
        logVoice("send", "PTT up: transcript turn flushed")
        flushVoiceTurn()
        syncVoiceCaptureState()
    }

    fun setWalkieChannel(channel: Int) {
        _activeWalkieChannel.value = channel
    }

    fun toggleSpeakerphone() {
        _isSpeakerphoneOn.value = !_isSpeakerphoneOn.value
        audioPlaybackEngine?.setStreamToSpeaker(_isSpeakerphoneOn.value)
    }

    fun refreshDiscoveredNodes() {
        if (_isRefreshingNodes.value) return
        _isRefreshingNodes.value = true
        wifiDirectMeshManager?.requestPeers()
        if (_bluetoothEnabled.value) {
            bleMeshManager?.startScanning()
        }
        viewModelScope.launch {
            delay(1500)
            refreshWalkieDevicesList()
            _isRefreshingNodes.value = false
        }
    }

    fun refreshWalkieDevicesList() {
        val pairedNodeIds = settingsRepository.pairedWalkieNodeIds.value
        val linked = bleMeshManager?.connectedNodeIds?.value.orEmpty()
        val now = System.currentTimeMillis()

        // 1. Rebuild Paired Devices from persistent storage
        val pairedList = pairedNodeIds.map { peerNodeId ->
            val beacon = latestWalkiePresenceBeacons[peerNodeId]
            val lastContact = lastPacketFromNode[peerNodeId] ?: 0L
            val isReachable = beacon != null || (now - lastContact < 10000L)
            val rssi = beacon?.rssi ?: if (isReachable) -60 else -95
            val battery = beacon?.batteryPercent ?: 100
            val isGattLinked = peerNodeId in linked
            PeerDevice(
                id = "node-$peerNodeId",
                name = nodeCallsign(peerNodeId),
                address = nodeCallsign(peerNodeId),
                protocol = TransportProtocol.BLE,
                signalStrengthDbm = rssi,
                isConnected = isReachable || isGattLinked,
                batteryPercent = battery
            ).withPeerProfile()
        }
        _pairedWalkieDevices.value = pairedList

        // 2. Rebuild Discovered Devices (beacons heard that are not paired and not self)
        val discoveredList = (
            latestWalkiePresenceBeacons.values
                .filter { it.nodeId != _nodeId.value && !pairedNodeIds.contains(it.nodeId) }
                .map { beacon ->
                    PeerDevice(
                        id = "node-${beacon.nodeId}",
                        name = nodeCallsign(beacon.nodeId),
                        address = nodeCallsign(beacon.nodeId),
                        protocol = TransportProtocol.BLE,
                        signalStrengthDbm = beacon.rssi,
                        isConnected = false,
                        batteryPercent = beacon.batteryPercent
                    ).withPeerProfile()
                } +
                // Long-range Wi-Fi Direct peers appended alongside the
                // BLE-discovered nodes. Their ids are P2P MAC addresses, which
                // never collide with the "node-NNNN" ids above.
                wifiDirectMeshManager?.peers?.value.orEmpty()
            ).distinctBy { it.id }

        _discoveredWalkieDevices.value = discoveredList
        updateWalkieLinkState()
    }

    fun pairDevice(peer: PeerDevice) {
        sendPairRequest(peer)
    }

    fun sendPairRequest(peer: PeerDevice) {
        val targetNodeId = peer.peerNodeId() ?: return
        _pendingPairingTargetNodeId.value = targetNodeId
        val payload = PairingHandshakePayload(
            targetNodeId = targetNodeId,
            senderName = ownProfile().name.ifBlank { _callsign.value }
        )
        val packet = ItantraPacket(
            nodeId = _nodeId.value,
            ttl = _meshHopLimit.value,
            msgType = PacketFraming.MSG_TYPE_PAIR_REQUEST,
            payload = PairingHandshakePayload.encode(payload)
        )
        broadcastMeshPacket(PacketFraming.encode(packet), targetNodeId)
        logVoice("pairing", "sent pair request to ${nodeCallsign(targetNodeId)} ($targetNodeId)")
    }

    fun acceptPairRequest(fromNodeId: Long) {
        _incomingPairRequest.value = null
        viewModelScope.launch {
            settingsRepository.addPairedWalkieNodeId(fromNodeId)
        }
        val payload = PairingHandshakePayload(
            targetNodeId = fromNodeId,
            senderName = ownProfile().name.ifBlank { _callsign.value }
        )
        val packet = ItantraPacket(
            nodeId = _nodeId.value,
            ttl = _meshHopLimit.value,
            msgType = PacketFraming.MSG_TYPE_PAIR_ACCEPT,
            payload = PairingHandshakePayload.encode(payload)
        )
        broadcastMeshPacket(PacketFraming.encode(packet), fromNodeId)
        logVoice("pairing", "accepted pair request from ${nodeCallsign(fromNodeId)} ($fromNodeId)")
    }

    fun rejectPairRequest(fromNodeId: Long) {
        _incomingPairRequest.value = null
        val payload = PairingHandshakePayload(
            targetNodeId = fromNodeId,
            senderName = ownProfile().name.ifBlank { _callsign.value }
        )
        val packet = ItantraPacket(
            nodeId = _nodeId.value,
            ttl = _meshHopLimit.value,
            msgType = PacketFraming.MSG_TYPE_PAIR_REJECT,
            payload = PairingHandshakePayload.encode(payload)
        )
        broadcastMeshPacket(PacketFraming.encode(packet), fromNodeId)
        logVoice("pairing", "rejected pair request from ${nodeCallsign(fromNodeId)} ($fromNodeId)")
    }

    fun unpairDevice(peer: PeerDevice) {
        val targetNodeId = peer.peerNodeId() ?: return
        viewModelScope.launch {
            settingsRepository.removePairedWalkieNodeId(targetNodeId)
        }
        val payload = PairingHandshakePayload(
            targetNodeId = targetNodeId,
            senderName = ownProfile().name.ifBlank { _callsign.value }
        )
        val packet = ItantraPacket(
            nodeId = _nodeId.value,
            ttl = _meshHopLimit.value,
            msgType = PacketFraming.MSG_TYPE_UNPAIR,
            payload = PairingHandshakePayload.encode(payload)
        )
        broadcastMeshPacket(PacketFraming.encode(packet), targetNodeId)
        logVoice("pairing", "unpaired node ${nodeCallsign(targetNodeId)} ($targetNodeId)")
    }

    // =========================================================================
    // 3. RESCUE MODE (Boot-Up, 1-to-1 Voice Link, 1-Way Broadcast, Relative Radar)
    // =========================================================================
    private val _isRescueActive = MutableStateFlow(false)
    val isRescueActive: StateFlow<Boolean> = _isRescueActive.asStateFlow()

    private val _rescueConnectionMode = MutableStateFlow(RescueConnectionMode.STANDBY)
    val rescueConnectionMode: StateFlow<RescueConnectionMode> = _rescueConnectionMode.asStateFlow()

    private val _isBroadcastingToAll = MutableStateFlow(false)
    val isBroadcastingToAll: StateFlow<Boolean> = _isBroadcastingToAll.asStateFlow()

    private val _compassHeading = MutableStateFlow(32f) // Rescuer compass heading (0..360°)
    val compassHeading: StateFlow<Float> = _compassHeading.asStateFlow()

    private val _selectedVictim = MutableStateFlow<DistressVictim?>(null)
    val selectedVictim: StateFlow<DistressVictim?> = _selectedVictim.asStateFlow()

    private val _isMapExpanded = MutableStateFlow(false)
    val isMapExpanded: StateFlow<Boolean> = _isMapExpanded.asStateFlow()

    // Scan-driven: populated from real distress beacons (no fabricated victims).
    private val _activeDistressVictims = MutableStateFlow<List<DistressVictim>>(emptyList())
    val activeDistressVictims: StateFlow<List<DistressVictim>> = _activeDistressVictims.asStateFlow()

    private val _connectedVictimIntercom = MutableStateFlow<DistressVictim?>(null)
    val connectedVictimIntercom: StateFlow<DistressVictim?> = _connectedVictimIntercom.asStateFlow()
    private var lastVictimContactEpochMs: Long = 0L

    private val _victimAlertCount = MutableStateFlow(0)
    val victimAlertCount: StateFlow<Int> = _victimAlertCount.asStateFlow()

    /**
     * True on a distress device while the connected rescuer is streaming a
     * strictly one-way broadcast (`ALTITUDE_RESCUER_BROADCAST_ALL`). The
     * victim screen then hides every talk affordance: nothing it sends could
     * be heard, and a mic button would misrepresent the link.
     */
    private val _isReceivingOneWayBroadcast = MutableStateFlow(false)
    val isReceivingOneWayBroadcast: StateFlow<Boolean> = _isReceivingOneWayBroadcast.asStateFlow()

    private val _modelWarningMessage = MutableStateFlow<String?>(null)
    val modelWarningMessage: StateFlow<String?> = _modelWarningMessage.asStateFlow()

    fun dismissModelWarning() {
        _modelWarningMessage.value = null
    }

    private fun startRescuerBeaconAdvertising() {
        val payload = buildRescuerBeaconPayload()
        bleMeshManager?.startAdvertising(payload, BeaconTxPower.HIGH)
    }

    private fun buildRescuerBeaconPayload(): DistressBeaconPayload {
        val targetNodeId = when {
            _isBroadcastingToAll.value -> DistressBeaconPayload.ALTITUDE_RESCUER_BROADCAST_ALL
            _connectedVictimIntercom.value != null -> ((_connectedVictimIntercom.value!!.nodeId and 0x3FFF) + 1).toInt()
            else -> DistressBeaconPayload.ALTITUDE_RESCUER_IDLE
        }
        return DistressBeaconPayload(
            nodeId = _nodeId.value,
            batteryPercent = currentBatteryPercent(),
            latitudeDeg = _rescuerLat.value,
            longitudeDeg = _rescuerLon.value,
            altitudeMeters = targetNodeId,
            languageIso = _uiState.value.selectedLanguage.code,
            isDistress = false
        )
    }

    fun bootRescueSystem(active: Boolean) {
        clearSessionTranscripts()
        _isRescueActive.value = active
        if (active) {
            if (_bluetoothEnabled.value) {
                bleMeshManager?.startScanning()
            }
            syncBeaconAdvertising()
            if (_wifiDirectEnabled.value) {
                wifiDirectMeshManager?.startUdpBroadcast()
            }
            syncWifiDirectScan()
            syncVoiceCaptureState() // Radar scanning: mic stays OFF until call or broadcast is initiated
        } else {
            // Leaving rescue mode: broadcast a close (empty payload = everyone)
            // so any victim still showing us connected drops immediately.
            broadcastMeshPacket(
                PacketFraming.encode(
                    ItantraPacket(
                        nodeId = _nodeId.value,
                        ttl = _meshHopLimit.value,
                        msgType = PacketFraming.MSG_TYPE_VOICE_LINK_CLOSE,
                        payload = ByteArray(0)
                    )
                )
            )
            stopBleScanIfIdle()
            _connectedVictimIntercom.value = null
            _isBroadcastingToAll.value = false
            _rescueConnectionMode.value = RescueConnectionMode.STANDBY
            _selectedVictim.value = null
            _isMapExpanded.value = false
            _activeDistressVictims.value = emptyList()
            _victimAlertCount.value = 0
            _isReceivingOneWayBroadcast.value = false
            beaconFirstSeen.clear()
            syncVoiceCaptureState()
            stopMeshUdpIfIdle()
            _modelWarningMessage.value = null
            stopBleScanIfIdle()
            syncBeaconAdvertising()
            syncWifiDirectScan()
        }
    }

    fun toggleBroadcastToAll() {
        clearSessionTranscripts()
        val willBroadcast = !_isBroadcastingToAll.value
        _isBroadcastingToAll.value = willBroadcast
        if (willBroadcast) {
            val lang = _uiState.value.selectedLanguage.code
            if (!modelStorageManager.isInstalled(lang)) {
                _modelWarningMessage.value = "Neural model pack for ${_uiState.value.selectedLanguage.englishName} is NOT downloaded. Voice-to-text requires language pack."
            } else {
                _modelWarningMessage.value = null
            }
            _connectedVictimIntercom.value = null
            _rescueConnectionMode.value = RescueConnectionMode.BROADCAST_ALL
            if (_wifiDirectEnabled.value) {
                wifiDirectMeshManager?.startUdpBroadcast()
            }
            syncBeaconAdvertising()
            syncVoiceCaptureState()
            // 1-way megaphone: a fresh frame sequence for this announcement.
            voiceStreamGate.beginTurn()
            logVoice("send", "1-way broadcast ON: megaphone announcement active")
        } else {
            _modelWarningMessage.value = null
            _rescueConnectionMode.value = RescueConnectionMode.STANDBY
            // Leaving broadcast-all: every victim that saw our -1 beacon may
            // still have us connected — broadcast a close (empty payload =
            // everyone) so they drop immediately instead of waiting on the
            // beacon timeout.
            broadcastMeshPacket(
                PacketFraming.encode(
                    ItantraPacket(
                        nodeId = _nodeId.value,
                        ttl = _meshHopLimit.value,
                        msgType = PacketFraming.MSG_TYPE_VOICE_LINK_CLOSE,
                        payload = ByteArray(0)
                    )
                )
            )
            startRescuerBeaconAdvertising()
            stopMeshVoiceCaptureIfIdle()
            stopMeshUdpIfIdle()
        }
    }

    fun connectVictimIntercom(victim: DistressVictim) {
        clearSessionTranscripts()
        // Zero-friction instant 1-to-1 connect.
        lastExplicitDisconnectEpochMs = 0L
        _isBroadcastingToAll.value = false
        _selectedVictim.value = victim
        _connectedVictimIntercom.value = victim.copy(isIntercomConnected = true)
        _rescueConnectionMode.value = RescueConnectionMode.ONE_TO_ONE
        lastVictimContactEpochMs = System.currentTimeMillis()

        peerLanguages[victim.nodeId] = victim.language.code
        checkCrossLingualStatus()

        val lang = _uiState.value.selectedLanguage.code
        if (!modelStorageManager.isInstalled(lang)) {
            _modelWarningMessage.value = "Neural model pack for ${_uiState.value.selectedLanguage.englishName} (${lang.uppercase()}) is NOT downloaded. Voice-to-text requires language pack."
        } else if (!_isCrossLingualBlocked.value) {
            _modelWarningMessage.value = null
        }

        startRescuerBeaconAdvertising()

        val linkRequest = ItantraPacket(
            nodeId = _nodeId.value,
            ttl = _meshHopLimit.value,
            msgType = PacketFraming.MSG_TYPE_VOICE_LINK_REQUEST,
            payload = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN)
                .putLong(victim.nodeId)
                .put(if (translationEngine.isInstalled()) 1.toByte() else 0.toByte())
                .array()
        )
        if (_wifiDirectEnabled.value) {
            wifiDirectMeshManager?.startUdpBroadcast()
        }
        logVoice("send", "voice-link request -> ${nodeCallsign(victim.nodeId)} (nodeId=${victim.nodeId})")
        broadcastMeshPacket(PacketFraming.encode(linkRequest), victim.nodeId)
        broadcastTranslationCapability()
        syncVoiceCaptureState()
    }

    fun disconnectVictimIntercom() {
        clearSessionTranscripts()
        lastExplicitDisconnectEpochMs = System.currentTimeMillis()
        val target = _connectedVictimIntercom.value
        if (target != null) {
            val close = ItantraPacket(
                nodeId = _nodeId.value,
                ttl = _meshHopLimit.value,
                msgType = PacketFraming.MSG_TYPE_VOICE_LINK_CLOSE,
                payload = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(target.nodeId).array()
            )
            broadcastMeshPacket(PacketFraming.encode(close))
        }
        _connectedVictimIntercom.value = null
        _modelWarningMessage.value = null
        _rescueConnectionMode.value = RescueConnectionMode.STANDBY
        checkCrossLingualStatus()
        syncBeaconAdvertising()
        syncVoiceCaptureState()
        stopMeshUdpIfIdle()
    }

    fun selectVictim(victim: DistressVictim?) {
        _selectedVictim.value = victim
    }

    // Hardware Sensors & Location Services
    private val sensorManager = getApplication<Application>().getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val lastAccelerometer = FloatArray(3)
    private val lastMagnetometer = FloatArray(3)
    private var lastAccelSet = false
    private var lastMagSet = false

    private val locationManager = getApplication<Application>().getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    // Rescuer real coordinates (doubles as "my location" when in distress)
    private val _rescuerLat = MutableStateFlow(0.0) // 0.0 until valid GPS fix acquired
    val rescuerLat: StateFlow<Double> = _rescuerLat.asStateFlow()

    private val _rescuerLon = MutableStateFlow(0.0)
    val rescuerLon: StateFlow<Double> = _rescuerLon.asStateFlow()

    private val _hasGpsFix = MutableStateFlow(false)
    val hasGpsFix: StateFlow<Boolean> = _hasGpsFix.asStateFlow()

    init {
        registerSensors()
        refreshLocation()
    }

    private fun registerSensors() {
        if (rotationSensor != null) {
            sensorManager?.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            accelSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            magSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
        stepSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun onPermissionsGranted() {
        registerSensors()
        refreshLocation()
        startBleScanIfActive()
    }

    private fun startBleScanIfActive() {
        if (_bluetoothEnabled.value && (_isRescueActive.value || _isSosBroadcasting.value || _isWalkieActive.value)) {
            bleMeshManager?.startScanning()
        }
    }

    fun refreshLocation() {
        try {
            val last = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: locationManager?.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)

            if (last != null && last.latitude != 0.0 && last.longitude != 0.0) {
                _rescuerLat.value = last.latitude
                _rescuerLon.value = last.longitude
                _hasGpsFix.value = true
            }
            locationManager?.let { lm ->
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0.5f, this)
                }
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0.5f, this)
                }
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {}
        seedVictimCoordinates()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                val azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                val rawAzimuth = (azimuth % 360f + 360f) % 360f
                _compassHeading.value = smoothCompassHeading(_compassHeading.value, rawAzimuth)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, lastAccelerometer, 0, event.values.size)
                lastAccelSet = true
                computeFallbackOrientation()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, lastMagnetometer, 0, event.values.size)
                lastMagSet = true
                computeFallbackOrientation()
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                simulatePhysicalStep()
            }
        }
    }

    private fun computeFallbackOrientation() {
        if (rotationSensor == null && lastAccelSet && lastMagSet) {
            if (SensorManager.getRotationMatrix(rotationMatrix, null, lastAccelerometer, lastMagnetometer)) {
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                val azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                val rawAzimuth = (azimuth % 360f + 360f) % 360f
                _compassHeading.value = smoothCompassHeading(_compassHeading.value, rawAzimuth)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Position (and time) last baked into the on-air beacon. */
    private var lastBeaconedLat = Double.NaN
    private var lastBeaconedLon = Double.NaN
    private var lastBeaconSyncEpochMs = 0L

    override fun onLocationChanged(location: Location) {
        if (location.latitude != 0.0 && location.longitude != 0.0) {
            _rescuerLat.value = location.latitude
            _rescuerLon.value = location.longitude
            _hasGpsFix.value = true
            recalculateVictimDistances()

            // Position is part of every beacon payload, but re-advertising on
            // every 1 Hz GPS tick churns the BLE controller and made the device
            // intermittently invisible to peers. Only re-assert the advert after
            // real movement, and at most once per refresh window.
            if (_isSosBroadcasting.value || _isRescueActive.value || _isWalkieActive.value) {
                val now = System.currentTimeMillis()
                val moved = distanceMetersBetween(
                    lastBeaconedLat, lastBeaconedLon, location.latitude, location.longitude
                )
                if (lastBeaconedLat.isNaN() ||
                    moved >= BEACON_POSITION_REFRESH_METERS ||
                    now - lastBeaconSyncEpochMs >= BEACON_POSITION_REFRESH_MS
                ) {
                    lastBeaconedLat = location.latitude
                    lastBeaconedLon = location.longitude
                    lastBeaconSyncEpochMs = now
                    syncBeaconAdvertising()
                }
            }
        }
    }

    /** Great-circle distance in meters, or Float.MAX_VALUE when a point is unknown. */
    private fun distanceMetersBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        if (lat1.isNaN() || lon1.isNaN()) return Float.MAX_VALUE
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    /** Computes Great-Circle bearing angle from (lat1, lon1) to (lat2, lon2) in degrees (0..360°). */
    private fun calculateBearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        val bearing = Math.toDegrees(atan2(y, x))
        return ((bearing.toFloat() % 360f) + 360f) % 360f
    }

    /**
     * The victim list is scan-driven: beacons carry lat/lon when GPS is locked.
     * This fabricates synthetic coordinates for beacons that reported none,
     * using the Kalman distance estimate and deterministic bearing.
     */
    private fun seedVictimCoordinates() {
        val baseLat = _rescuerLat.value
        val baseLon = _rescuerLon.value
        if (baseLat == 0.0 && baseLon == 0.0) return
        val latDegPerMeter = 1.0 / 111139.0
        val lonDegPerMeter = 1.0 / (111139.0 * cos(Math.toRadians(baseLat)).coerceAtLeast(0.1))

        _activeDistressVictims.update { victims ->
            victims.map { v ->
                if (v.latitude != 0.0 || v.longitude != 0.0) {
                    val bearing = calculateBearingDegrees(baseLat, baseLon, v.latitude, v.longitude)
                    v.copy(relativeBearingDegrees = bearing)
                } else {
                    val rad = Math.toRadians(v.relativeBearingDegrees.toDouble())
                    val dNorth = v.distanceMeters * cos(rad)
                    val dEast = v.distanceMeters * sin(rad)
                    v.copy(
                        latitude = baseLat + (dNorth * latDegPerMeter),
                        longitude = baseLon + (dEast * lonDegPerMeter)
                    )
                }
            }
        }
    }

    private fun recalculateVictimDistances() {
        val curLat = _rescuerLat.value
        val curLon = _rescuerLon.value
        val results = FloatArray(1)

        _activeDistressVictims.update { victims ->
            victims.map { v ->
                if (curLat != 0.0 && curLon != 0.0 && v.latitude != 0.0 && v.longitude != 0.0) {
                    Location.distanceBetween(curLat, curLon, v.latitude, v.longitude, results)
                    val gpsDist = results[0].toInt().coerceAtLeast(1)
                    val bearing = calculateBearingDegrees(curLat, curLon, v.latitude, v.longitude)
                    val bleDist = if (v.distanceMeters in 1..30 && v.signalDbm > -86) {
                        v.distanceMeters
                    } else {
                        estimateMeters(v.signalDbm).roundToInt().coerceAtLeast(1)
                    }
                    val dist = fuseGpsAndBleDistance(gpsDist, bleDist, v.signalDbm)
                    v.copy(distanceMeters = dist, relativeBearingDegrees = bearing)
                } else {
                    v
                }
            }
        }
    }

    private fun simulatePhysicalStep() {
        val target = _selectedVictim.value ?: _connectedVictimIntercom.value ?: _activeDistressVictims.value.firstOrNull() ?: return
        val curLat = _rescuerLat.value
        val curLon = _rescuerLon.value
        val dLat = (target.latitude - curLat) * 0.08
        val dLon = (target.longitude - curLon) * 0.08
        _rescuerLat.value = curLat + dLat
        _rescuerLon.value = curLon + dLon
        recalculateVictimDistances()
    }

    fun stepCloserToVictim(victimId: String) {
        val target = _activeDistressVictims.value.firstOrNull { it.id == victimId } ?: return
        val curLat = _rescuerLat.value
        val curLon = _rescuerLon.value
        val dLat = (target.latitude - curLat) * 0.15
        val dLon = (target.longitude - curLon) * 0.15
        _rescuerLat.value = curLat + dLat
        _rescuerLon.value = curLon + dLon
        recalculateVictimDistances()
    }

    fun switchVictimIntercom(newVictim: DistressVictim) {
        connectVictimIntercom(newVictim)
    }

    fun toggleMapExpanded(expanded: Boolean) {
        _isMapExpanded.value = expanded
    }

    fun updateCompassHeading(heading: Float) {
        _compassHeading.value = (heading % 360f + 360f) % 360f
    }

    // =========================================================================
    // PHASE B: BEACON -> UI MAPPING (real scans replace the old seed lists)
    // =========================================================================

    /** Tracks when each beacon nodeId was first seen, for "activeMinutes". */
    private val beaconFirstSeen = HashMap<Long, Long>()

    /**
     * Real identities received from peers over `MSG_TYPE_PROFILE`, keyed by
     * node id. Until a profile arrives, peer labels keep the historic
     * "NODE-XXXX" placeholder.
     */
    private val peerProfiles = PeerProfileCache()

    /** Last time we answered a given node with our own profile (flood control). */
    private val lastProfileSentToNode = LinkedHashMap<Long, Long>()

    private fun nodeCallsign(nodeId: Long): String = peerProfiles.label(nodeId)

    // =========================================================================
    // IDENTITY PROFILE ADVERT (name/age/gender over the mesh)
    // =========================================================================

    /** This device's identity, straight from the persisted onboarding profile. */
    private fun ownProfile(): PeerProfile = PeerProfile(
        name = _uiState.value.userName.trim(),
        age = _uiState.value.userAge,
        gender = _uiState.value.userGender.trim()
    )

    /**
     * Caches our own identity under our node id, so a label lookup for this
     * device resolves to the saved name instead of the "NODE-XXXX" placeholder
     * (the local message sender label stays the persisted call sign).
     */
    private fun cacheOwnProfile() {
        val profile = ownProfile()
        if (!profile.hasContent) return
        peerProfiles.put(_nodeId.value, profile)
    }

    /**
     * Broadcasts this device's identity on the mesh so peers stop showing
     * "NODE-XXXX". A blank profile (pre-onboarding) is never sent.
     */
    private fun broadcastOwnProfile(targetNodeId: Long? = null) {
        val profile = ownProfile()
        if (!profile.hasContent) return
        cacheOwnProfile()
        val payload = ProfilePayload.encode(profile)
        if (payload.isEmpty()) return
        val packet = ItantraPacket(
            nodeId = _nodeId.value,
            ttl = _meshHopLimit.value,
            msgType = PacketFraming.MSG_TYPE_PROFILE,
            payload = payload
        )
        val encoded = PacketFraming.encode(packet)
        logVoice("profile", "identity advert ${encoded.size}B -> ${targetNodeId?.let { fallbackNodeLabel(it) } ?: "all"}")
        broadcastMeshPacket(encoded, targetNodeId)
    }

    /**
     * Answers a newly discovered node with our profile, at most once per
     * [PROFILE_PROMPT_MIN_INTERVAL_MS] so a chatty mesh cannot turn discovery
     * into a profile storm.
     */
    private fun sendProfileToNodePromptly(nodeId: Long) {
        if (nodeId == 0L || nodeId == _nodeId.value) return
        if (peerProfiles.hasName(nodeId)) return
        val now = System.currentTimeMillis()
        // Called from both the BLE/UDP receive threads and the main thread.
        val shouldSend = synchronized(lastProfileSentToNode) {
            val lastSent = lastProfileSentToNode[nodeId] ?: 0L
            if (now - lastSent < PROFILE_PROMPT_MIN_INTERVAL_MS) {
                false
            } else {
                // Bounded: drop the oldest rate-limit entries, never the newest.
                while (lastProfileSentToNode.size >= MAX_TRACKED_PROFILE_PEERS) {
                    val oldest = lastProfileSentToNode.keys.firstOrNull() ?: break
                    lastProfileSentToNode.remove(oldest)
                }
                lastProfileSentToNode[nodeId] = now
                true
            }
        }
        if (shouldSend) broadcastOwnProfile(targetNodeId = nodeId)
    }

    private var profileBroadcastJob: Job? = null

    /** Starts/stops the periodic identity advert to match the active mode. */
    private fun syncProfileBroadcast() {
        val shouldAdvertise = _isSosBroadcasting.value || _isRescueActive.value || _isWalkieActive.value
        if (!shouldAdvertise) {
            profileBroadcastJob?.cancel()
            profileBroadcastJob = null
            return
        }
        if (profileBroadcastJob?.isActive == true) return
        cacheOwnProfile()
        profileBroadcastJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                broadcastOwnProfile()
                if (_isWalkieActive.value) {
                    broadcastPairSync()
                }
                delay(PROFILE_BROADCAST_INTERVAL_MS)
            }
        }
    }

    private fun broadcastPairSync() {
        val pairedIds = settingsRepository.pairedWalkieNodeIds.value
        val payload = PairingSyncPayload(pairedNodeIds = pairedIds)
        val packet = ItantraPacket(
            nodeId = _nodeId.value,
            ttl = _meshHopLimit.value,
            msgType = PacketFraming.MSG_TYPE_PAIR_SYNC,
            payload = PairingSyncPayload.encode(payload)
        )
        broadcastMeshPacket(PacketFraming.encode(packet))
    }

    /**
     * Re-labels every peer-derived UI entry once a profile arrives, so the name
     * and age/gender appear without waiting for the next beacon scan.
     */
    private fun refreshPeerLabels() {
        if (_activeDistressVictims.value.isNotEmpty()) {
            _activeDistressVictims.update { victims -> victims.map { it.withPeerProfile() } }
        }
        _selectedVictim.update { it?.withPeerProfile() }
        _connectedVictimIntercom.update { it?.withPeerProfile() }
        if (_nearbyRescuers.value.isNotEmpty()) {
            _nearbyRescuers.update { rescuers -> rescuers.map { it.withPeerProfile() } }
        }
        _connectedRescuer.update { it?.withPeerProfile() }
        refreshWalkieDevicesList()
    }

    private fun DistressVictim.withPeerProfile(): DistressVictim {
        val profile = peerProfiles.get(nodeId) ?: return this
        return copy(
            callsign = nodeCallsign(nodeId),
            age = profile.age,
            gender = profile.gender
        )
    }

    private fun RescuerNode.withPeerProfile(): RescuerNode {
        val profile = peerProfiles.get(nodeId) ?: return this
        return copy(
            callsign = nodeCallsign(nodeId),
            age = profile.age,
            gender = profile.gender
        )
    }

    private fun PeerDevice.withPeerProfile(): PeerDevice {
        val nodeId = peerNodeId() ?: return this
        val name = peerProfiles.name(nodeId) ?: return this
        return copy(name = name, address = name)
    }

    /** Walkie peer ids carry their node id (`node-<nodeId>` or `ble-<nodeId>`). */
    private fun PeerDevice.peerNodeId(): Long? =
        id.removePrefix("node-").removePrefix("ble-").removePrefix("p2p-").toLongOrNull()

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getApplication<Application>().getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getApplication<Application>().getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun triggerTacticalAlertVibration() {
        try {
            val v = vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Short, distinct double tactical alert pulse (180ms pulse, 80ms silence, 250ms pulse)
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 80, 250), -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(longArrayOf(0, 180, 80, 250), -1)
            }
        } catch (_: Exception) {
        }
    }

    private fun DiscoveredBeacon.toDistressVictim(): DistressVictim {
        val language = SupportedLanguage.fromCode(languageIso)
        val now = System.currentTimeMillis()
        val firstSeen = beaconFirstSeen.getOrPut(nodeId) { now }
        val curLat = _rescuerLat.value
        val curLon = _rescuerLon.value

        val hasValidBothCoords = curLat != 0.0 && curLon != 0.0 && latitudeDeg != 0.0 && longitudeDeg != 0.0
        val results = FloatArray(1)
        val bleDist = estimatedDistanceMeters.roundToInt().coerceAtLeast(1)
        val (finalDistance, bearing) = if (hasValidBothCoords) {
            Location.distanceBetween(curLat, curLon, latitudeDeg, longitudeDeg, results)
            val gpsDist = results[0].toInt().coerceAtLeast(1)
            val calcBearing = calculateBearingDegrees(curLat, curLon, latitudeDeg, longitudeDeg)
            val dist = fuseGpsAndBleDistance(gpsDist, bleDist, rssi)
            dist to calcBearing
        } else {
            val calcBearing = (((nodeId * 37L) % 360L).toFloat() + 360f) % 360f
            bleDist to calcBearing
        }

        val profile = peerProfiles.get(nodeId)
        return DistressVictim(
            id = "beacon-$nodeId",
            nodeId = nodeId,
            callsign = nodeCallsign(nodeId),
            distanceMeters = finalDistance,
            signalDbm = rssi,
            language = language,
            batteryPercent = batteryPercent.coerceIn(0, 100),
            activeMinutes = ((now - firstSeen) / 60000L).toInt().coerceAtLeast(0),
            distressMessage = language.sampleAlertPhrase,
            isIntercomConnected = _connectedVictimIntercom.value?.nodeId == nodeId,
            relativeBearingDegrees = bearing,
            hazardType = "Distress Beacon",
            latitude = latitudeDeg,
            longitude = longitudeDeg,
            age = profile?.age,
            gender = profile?.gender.orEmpty()
        )
    }

    private fun DiscoveredBeacon.toRescuerNode(): RescuerNode {
        val profile = peerProfiles.get(nodeId)
        val curLat = _rescuerLat.value
        val curLon = _rescuerLon.value
        val bleDist = estimatedDistanceMeters.roundToInt().coerceAtLeast(1)
        val dist = if (curLat != 0.0 && curLon != 0.0 && latitudeDeg != 0.0 && longitudeDeg != 0.0) {
            val results = FloatArray(1)
            Location.distanceBetween(curLat, curLon, latitudeDeg, longitudeDeg, results)
            fuseGpsAndBleDistance(results[0].toInt().coerceAtLeast(1), bleDist, rssi)
        } else {
            bleDist
        }
        return RescuerNode(
            id = "resc-$nodeId",
            nodeId = nodeId,
            callsign = nodeCallsign(nodeId),
            distanceMeters = dist,
            signalDbm = rssi,
            role = "iTantra Rescuer",
            isConnected = _connectedRescuer.value?.id == "resc-$nodeId",
            age = profile?.age,
            gender = profile?.gender.orEmpty()
        )
    }

    /**
     * Builds a rescuer entry for a node id first heard over the mesh (a voice
     * link or text packet) rather than a scanned beacon, reusing the scanned
     * distance/signal when the beacon is already known.
     */
    private fun rescuerNodeFor(nodeId: Long, role: String): RescuerNode {
        val existing = _nearbyRescuers.value.firstOrNull { it.id == "resc-$nodeId" }
        val profile = peerProfiles.get(nodeId)
        return RescuerNode(
            id = "resc-$nodeId",
            nodeId = nodeId,
            callsign = nodeCallsign(nodeId),
            distanceMeters = existing?.distanceMeters ?: 1,
            signalDbm = existing?.signalDbm ?: -50,
            role = role,
            isConnected = true,
            age = profile?.age ?: existing?.age,
            gender = profile?.gender.orEmpty().ifBlank { existing?.gender.orEmpty() }
        )
    }

    private fun onBeaconsUpdated(beacons: List<DiscoveredBeacon>) {
        if (_isRescueActive.value) {
            val currentVictimNodeIds = _activeDistressVictims.value.map { it.nodeId }.toSet()
            val incomingVictims = beacons.filter { it.isDistress }
            val hasNewVictim = incomingVictims.any { it.nodeId !in currentVictimNodeIds }
            if (hasNewVictim && incomingVictims.isNotEmpty()) {
                triggerTacticalAlertVibration()
            }

            val victims = incomingVictims.map { it.toDistressVictim() }
            _activeDistressVictims.value = victims
            _victimAlertCount.value = victims.size
            seedVictimCoordinates()
            recalculateVictimDistances()

            // If a victim was connected on intercom, refresh contact or drop only after sustained 25s silence
            val connectedVictim = _connectedVictimIntercom.value
            if (connectedVictim != null) {
                if (incomingVictims.any { it.nodeId == connectedVictim.nodeId }) {
                    lastVictimContactEpochMs = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - lastVictimContactEpochMs > 25_000L) {
                    Log.i("MissionControl", "Intercom victim ${connectedVictim.nodeId} timed out after 25s silence - returning to STANDBY")
                    _connectedVictimIntercom.value = null
                    _rescueConnectionMode.value = RescueConnectionMode.STANDBY
                    startRescuerBeaconAdvertising()
                    syncVoiceCaptureState()
                }
            }
        }
        if (_isSosBroadcasting.value) {
            // A Walkie presence beacon is a non-distress advert too, but it is
            // not a rescuer — keep it out of the victim's rescuer list.
            val rescuerBeacons = beacons.filter {
                !it.isDistress && it.altitudeMeters != DistressBeaconPayload.ALTITUDE_WALKIE_PRESENCE
            }
            _nearbyRescuers.value = rescuerBeacons.map { it.toRescuerNode() }

            // Check if any rescuer is actively connecting to us or broadcasting
            val myTargetMask = ((_nodeId.value and 0x3FFF) + 1).toInt()
            val callingBeacons = rescuerBeacons.filter {
                it.altitudeMeters == -1 || it.altitudeMeters == myTargetMask
            }
            // Once a rescuer is connected, only THEIR beacon keeps the link
            // alive — never silently swap to a different calling rescuer.
            val connectedRescuerId = _connectedRescuer.value?.id
            val callingRescuer = if (connectedRescuerId != null) {
                callingBeacons.firstOrNull { "resc-${it.nodeId}" == connectedRescuerId }
            } else {
                callingBeacons.firstOrNull()
            }
            if (callingRescuer != null) {
                // After an explicit disconnect a late "calling" advert can
                // still be in flight: within the grace window an existing
                // connection keeps refreshing, but a dropped one must not be
                // silently re-established from beacon data alone.
                val withinDisconnectGrace =
                    System.currentTimeMillis() - lastExplicitDisconnectEpochMs <= EXPLICIT_DISCONNECT_GRACE_MS
                if (_connectedRescuer.value != null || !withinDisconnectGrace) {
                    val node = callingRescuer.toRescuerNode().copy(isConnected = true)
                    _connectedRescuer.value = node
                    _isReceivingOneWayBroadcast.value =
                        callingRescuer.altitudeMeters == DistressBeaconPayload.ALTITUDE_RESCUER_BROADCAST_ALL
                    lastRescuerContactEpochMs = System.currentTimeMillis()
                    syncVoiceCaptureState()
                } else {
                    logVoice(
                        "sos",
                        "beacon reconnect suppressed: within ${EXPLICIT_DISCONNECT_GRACE_MS}ms of explicit disconnect (resc-${callingRescuer.nodeId})"
                    )
                }
            } else if (_connectedRescuer.value != null) {
                // The connected rescuer's beacon calls us only with -1
                // (broadcast-all) or our own target mask. Anything else —
                // idle (0) or another victim's mask — means "no longer
                // calling us" and must drop fast, but only once the last
                // contact is slightly stale so a fresh link never races a
                // stale idle advert from before the rescuer's new "calling"
                // advert propagated. A beacon that disappears entirely keeps
                // the original 25s timeout.
                val connectedRescuerBeacon = rescuerBeacons.firstOrNull { "resc-${it.nodeId}" == connectedRescuerId }
                val isExplicitlyNotCallingUs = connectedRescuerBeacon != null &&
                    connectedRescuerBeacon.altitudeMeters != DistressBeaconPayload.ALTITUDE_RESCUER_BROADCAST_ALL &&
                    connectedRescuerBeacon.altitudeMeters != myTargetMask
                val contactStaleMs = System.currentTimeMillis() - lastRescuerContactEpochMs
                val fastDrop = isExplicitlyNotCallingUs && contactStaleMs > 2_000L

                if (fastDrop || contactStaleMs > 25_000L) {
                    if (fastDrop) {
                        logVoice(
                            "sos",
                            "connected rescuer $connectedRescuerId beacon no longer calling us - fast disconnect (${contactStaleMs}ms stale)"
                        )
                    }
                    _connectedRescuer.value = null
                    _isReceivingOneWayBroadcast.value = false
                    syncVoiceCaptureState()
                }
            } else {
                if (_isReceivingOneWayBroadcast.value && callingBeacons.isEmpty()) {
                    _isReceivingOneWayBroadcast.value = false
                }
            }
        }
        if (_isWalkieActive.value) {
            val presenceBeacons = beacons.filter {
                it.altitudeMeters == DistressBeaconPayload.ALTITUDE_WALKIE_PRESENCE
            }
            latestWalkiePresenceBeacons.clear()
            presenceBeacons.forEach { latestWalkiePresenceBeacons[it.nodeId] = it }
            refreshWalkieDevicesList()
        }
        beaconFirstSeen.keys.retainAll(beacons.map { it.nodeId }.toSet())

        // Prompt identity exchange: a peer that just appeared gets our profile
        // immediately instead of waiting for the next periodic advert. Flood
        // control lives in sendProfileToNodePromptly (per-node rate limit).
        if (_isSosBroadcasting.value || _isRescueActive.value || _isWalkieActive.value) {
            beacons.forEach { sendProfileToNodePromptly(it.nodeId) }
        }
    }

    private var lastRescuerContactEpochMs = 0L

    /**
     * Refreshes the connected-rescuer freshness timestamp. Scoped to packets
     * from the connected rescuer (or any rescuer while nobody is connected
     * yet) so mesh chatter from third devices — walkie traffic, other victims,
     * garbage STT text — can never keep a dead link alive past the 4s timeout.
     */
    private fun refreshRescuerContact(packetNodeId: Long) {
        val connected = _connectedRescuer.value
        if (connected == null || connected.id == "resc-$packetNodeId") {
            lastRescuerContactEpochMs = System.currentTimeMillis()
        }
    }

    private fun refreshVictimContact(packetNodeId: Long) {
        val connected = _connectedVictimIntercom.value
        if (connected == null || connected.nodeId == packetNodeId) {
            lastVictimContactEpochMs = System.currentTimeMillis()
        }
    }

    private val recentRelayedPackets = LinkedHashMap<Long, Long>()

    private fun handleIncomingDatagram(bytes: ByteArray, sourceAddress: String? = null) {
        logVoice("rx", "inbound ${bytes.size} bytes from ${sourceAddress ?: "ble-gatt"}")
        val packet = PacketFraming.decode(bytes) ?: run {
            Log.w(voicePipelineTag, "[rx] dropped: bad preamble/CRC/length (${bytes.size} bytes)")
            return
        }
        // Never process our own packets (prevents loopback echo)
        if (packet.nodeId == _nodeId.value) return
        logVoice(
            "decode",
            "type=${packet.msgType} node=${packet.nodeId} ttl=${packet.ttl} payload=${packet.payload.size}B"
        )

        // Learn the sender's direct IP so unicast can reach peers that broadcast
        // alone cannot (different subnet / Wi-Fi Direct group-owner asymmetry).
        sourceAddress?.let { peerAddressBook.record(packet.nodeId, it) }
        lastPeerContactEpochMs = System.currentTimeMillis()
        updateWalkieLinkState()

        // A peer we can hear but do not have a name for gets ours right away so
        // both screens stop showing "NODE-XXXX" after one exchange. The reply is
        // per-node rate-limited and suppressed once their name is cached.
        sendProfileToNodePromptly(packet.nodeId)

        // Multi-hop mesh relay: deduplicate within 3-second window (exempt real-time voice frames)
        if (packet.msgType != PacketFraming.MSG_TYPE_VOICE_FRAME) {
            val packetSignature = ((packet.nodeId xor (packet.msgType.toLong() shl 16)) xor packet.payload.contentHashCode().toLong())
            val now = System.currentTimeMillis()
            val isDuplicate = synchronized(recentRelayedPackets) {
                val lastSeen = recentRelayedPackets[packetSignature]
                if (lastSeen != null && (now - lastSeen) < 3000L) {
                    true
                } else {
                    if (recentRelayedPackets.size > 256) {
                        val firstKey = recentRelayedPackets.keys.firstOrNull()
                        if (firstKey != null) recentRelayedPackets.remove(firstKey)
                    }
                    recentRelayedPackets[packetSignature] = now
                    false
                }
            }
            if (isDuplicate) return // Deduplicate within 3 seconds

            if (packet.ttl > 1) {
                val relayedPacket = packet.copy(ttl = packet.ttl - 1)
                broadcastMeshPacket(PacketFraming.encode(relayedPacket))
            }
        }

        when (packet.msgType) {
            PacketFraming.MSG_TYPE_PROFILE -> {
                // Identity advert from a peer: cache it and re-label every
                // peer-derived entry so names/age/gender appear immediately.
                val profile = ProfilePayload.decode(packet.payload)
                if (profile == null) {
                    Log.w(voicePipelineTag, "[profile] unparseable advert from node=${packet.nodeId}")
                } else {
                    peerProfiles.put(packet.nodeId, profile)
                    logVoice(
                        "profile",
                        "identity from node=${packet.nodeId}: name='${profile.name}' age=${profile.age} gender='${profile.gender}'"
                    )
                    refreshPeerLabels()
                }
            }
            PacketFraming.MSG_TYPE_TRANSLATION_CAPABILITY -> {
                val raw = String(packet.payload, Charsets.UTF_8)
                val parts = raw.split('|')
                val peerLang = parts.getOrNull(0) ?: "hi"
                val hasTrans = parts.getOrNull(1) == "1"
                peerLanguages[packet.nodeId] = peerLang
                peerTranslatorAvailable[packet.nodeId] = hasTrans
                checkCrossLingualStatus()
            }
            PacketFraming.MSG_TYPE_PAIR_REQUEST -> {
                val req = PairingHandshakePayload.decode(packet.payload)
                if (req != null && (req.targetNodeId == _nodeId.value || req.targetNodeId == 0L)) {
                    if (req.senderName.isNotBlank()) {
                        peerProfiles.put(packet.nodeId, PeerProfile(name = req.senderName))
                        refreshPeerLabels()
                    }
                    if (settingsRepository.pairedWalkieNodeIds.value.contains(packet.nodeId)) {
                        acceptPairRequest(packet.nodeId)
                    } else {
                        _incomingPairRequest.value = WalkiePairRequest(
                            fromNodeId = packet.nodeId,
                            fromCallsign = req.senderName.ifBlank { nodeCallsign(packet.nodeId) }
                        )
                        triggerTacticalAlertVibration()
                        logVoice("pairing", "received pair request from ${nodeCallsign(packet.nodeId)} (${packet.nodeId})")
                    }
                }
            }
            PacketFraming.MSG_TYPE_PAIR_ACCEPT -> {
                val acc = PairingHandshakePayload.decode(packet.payload)
                if (acc != null && acc.targetNodeId == _nodeId.value) {
                    if (acc.senderName.isNotBlank()) {
                        peerProfiles.put(packet.nodeId, PeerProfile(name = acc.senderName))
                        refreshPeerLabels()
                    }
                    viewModelScope.launch {
                        settingsRepository.addPairedWalkieNodeId(packet.nodeId)
                    }
                    if (_pendingPairingTargetNodeId.value == packet.nodeId) {
                        _pendingPairingTargetNodeId.value = null
                    }
                    triggerTacticalAlertVibration()
                    logVoice("pairing", "pair accepted by ${nodeCallsign(packet.nodeId)}")
                }
            }
            PacketFraming.MSG_TYPE_PAIR_REJECT -> {
                val rej = PairingHandshakePayload.decode(packet.payload)
                if (rej != null && rej.targetNodeId == _nodeId.value) {
                    if (_pendingPairingTargetNodeId.value == packet.nodeId) {
                        _pendingPairingTargetNodeId.value = null
                    }
                    logVoice("pairing", "pair rejected by ${nodeCallsign(packet.nodeId)}")
                }
            }
            PacketFraming.MSG_TYPE_UNPAIR -> {
                val unp = PairingHandshakePayload.decode(packet.payload)
                if (unp != null && (unp.targetNodeId == _nodeId.value || settingsRepository.pairedWalkieNodeIds.value.contains(packet.nodeId))) {
                    viewModelScope.launch {
                        settingsRepository.removePairedWalkieNodeId(packet.nodeId)
                    }
                    logVoice("pairing", "unpaired by peer ${nodeCallsign(packet.nodeId)}")
                }
            }
            PacketFraming.MSG_TYPE_PAIR_SYNC -> {
                val sync = PairingSyncPayload.decode(packet.payload)
                if (sync != null) {
                    val myId = _nodeId.value
                    val peerId = packet.nodeId
                    val iAmPairedWithPeer = settingsRepository.pairedWalkieNodeIds.value.contains(peerId)
                    val peerIsPairedWithMe = sync.pairedNodeIds.contains(myId)

                    if (iAmPairedWithPeer && !peerIsPairedWithMe) {
                        viewModelScope.launch {
                            settingsRepository.removePairedWalkieNodeId(peerId)
                        }
                        logVoice("pairing", "sync: peer $peerId removed us, synced local status to unpaired")
                    } else if (!iAmPairedWithPeer && peerIsPairedWithMe) {
                        val unpairPayload = PairingHandshakePayload(targetNodeId = peerId, senderName = ownProfile().name)
                        val unpairPacket = ItantraPacket(
                            nodeId = _nodeId.value,
                            ttl = _meshHopLimit.value,
                            msgType = PacketFraming.MSG_TYPE_UNPAIR,
                            payload = PairingHandshakePayload.encode(unpairPayload)
                        )
                        broadcastMeshPacket(PacketFraming.encode(unpairPacket), peerId)
                        logVoice("pairing", "sync: informed peer $peerId that we unpaired them")
                    }
                }
            }
            PacketFraming.MSG_TYPE_TRANSLATED_TEXT -> {
                if (_isWalkieActive.value && !_isRescueActive.value && !_isSosBroadcasting.value) {
                    if (!settingsRepository.pairedWalkieNodeIds.value.contains(packet.nodeId)) {
                        logVoice("rx", "walkie text dropped: sender ${nodeCallsign(packet.nodeId)} is not paired")
                        return
                    }
                }
                refreshRescuerContact(packet.nodeId)
                refreshVictimContact(packet.nodeId)
                val rawPayload = String(packet.payload, Charsets.UTF_8)
                val pipeIndex = rawPayload.indexOf('|')
                val (incomingLangCode, payloadBody) = if (pipeIndex != -1) {
                    rawPayload.substring(0, pipeIndex) to rawPayload.substring(pipeIndex + 1)
                } else {
                    _uiState.value.selectedLanguage.code to rawPayload
                }

                // Parse optional sub-fields: orig:..., fromLang:..., trans:...
                val subParts = payloadBody.split('|')
                val mainText = subParts[0]
                var origText: String? = null
                var fromLang: String? = null
                for (i in 1 until subParts.size) {
                    val p = subParts[i]
                    when {
                        p.startsWith("orig:") -> origText = p.removePrefix("orig:")
                        p.startsWith("fromLang:") -> fromLang = p.removePrefix("fromLang:")
                        p.startsWith("trans:") -> {
                            val hasTrans = p.removePrefix("trans:") == "1"
                            peerTranslatorAvailable[packet.nodeId] = hasTrans
                            checkCrossLingualStatus()
                        }
                    }
                }
                if (fromLang != null) {
                    peerLanguages[packet.nodeId] = fromLang
                    checkCrossLingualStatus()
                }

                val localLang = _uiState.value.selectedLanguage.code
                var textToSpeak = mainText
                var langToSpeak = incomingLangCode
                var uiDisplayText = mainText

                // Inbound translation if incoming text is in peer's language and local translator is installed
                if (!incomingLangCode.equals(localLang, ignoreCase = true) && translationEngine.isInstalled()) {
                    val localTranslated = translationEngine.translate(mainText, incomingLangCode, localLang)
                    logVoice("translate", "inbound cross-lingual: '$mainText' ($incomingLangCode) -> '$localTranslated' ($localLang)")
                    textToSpeak = localTranslated
                    langToSpeak = localLang
                    uiDisplayText = "$localTranslated (Original: $mainText)"
                } else if (origText != null && !origText.equals(mainText, ignoreCase = true)) {
                    uiDisplayText = "$mainText (Original: $origText)"
                }

                logVoice("decode", "text from ${nodeCallsign(packet.nodeId)} (lang=$incomingLangCode, speak=$langToSpeak): '$uiDisplayText'")

                if (textToSpeak.isNotBlank()) {
                    // If in SOS mode and not currently connected, auto-lock onto this rescuer
                    if (_isSosBroadcasting.value && _connectedRescuer.value == null) {
                        if (System.currentTimeMillis() - lastExplicitDisconnectEpochMs <= EXPLICIT_DISCONNECT_GRACE_MS) {
                            logVoice(
                                "sos",
                                "auto-lock suppressed: within ${EXPLICIT_DISCONNECT_GRACE_MS}ms of explicit disconnect (text from ${nodeCallsign(packet.nodeId)})"
                            )
                        } else {
                            lastRescuerContactEpochMs = System.currentTimeMillis()
                            val rescuerId = "resc-${packet.nodeId}"
                            val existing = _nearbyRescuers.value.firstOrNull { it.id == rescuerId }
                            _connectedRescuer.value = existing?.copy(isConnected = true)
                                ?: rescuerNodeFor(packet.nodeId, "iTantra Rescuer")
                            syncVoiceCaptureState()
                            checkCrossLingualStatus()
                        }
                    }
                    // If in Rescue mode and not currently connected, auto-lock onto this distress victim.
                    // Never while doing a 1-way broadcast-all (a megaphone
                    // announcement must not be hijacked into a 1-to-1 link),
                    // and never within the grace window after an explicit
                    // disconnect.
                    if (_isRescueActive.value && _connectedVictimIntercom.value == null && !_isBroadcastingToAll.value) {
                        val victim = _activeDistressVictims.value.firstOrNull { it.nodeId == packet.nodeId }
                        if (victim != null) {
                            if (System.currentTimeMillis() - lastExplicitDisconnectEpochMs <= EXPLICIT_DISCONNECT_GRACE_MS) {
                                logVoice(
                                    "rescue",
                                    "auto-lock suppressed: within ${EXPLICIT_DISCONNECT_GRACE_MS}ms of explicit disconnect (text from victim ${nodeCallsign(packet.nodeId)})"
                                )
                            } else {
                                _connectedVictimIntercom.value = victim.copy(isIntercomConnected = true)
                                _rescueConnectionMode.value = RescueConnectionMode.ONE_TO_ONE
                                lastVictimContactEpochMs = System.currentTimeMillis()
                                startRescuerBeaconAdvertising()
                                syncVoiceCaptureState()
                                checkCrossLingualStatus()
                            }
                        }
                    }

                    // 1. ALWAYS record in UI transcript and message log so emergency messages are never lost
                    viewModelScope.launch(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                currentTranscript = uiDisplayText,
                                voiceStatus = null,
                                activeIncomingCaption = uiDisplayText,
                                channelState = RadioChannelState.RECEIVING
                            )
                        }
                        val receivedMsg = VoiceMessageEntity(
                            id = System.currentTimeMillis(),
                            messageUid = UUID.randomUUID().toString(),
                            text = uiDisplayText,
                            senderCallsign = nodeCallsign(packet.nodeId),
                            isLocal = false,
                            languageCode = langToSpeak,
                            timestamp = System.currentTimeMillis(),
                            isAlert = true
                        )
                        _messageLogs.update { listOf(receivedMsg) + it }
                    }
                    logVoice(
                        "ui",
                        "transcript + log updated from ${nodeCallsign(packet.nodeId)} (channelState=RECEIVING)"
                    )

                    // 2. Synthesize audio via TTS ONLY if local state authorizes voice playback
                    if (shouldPlayIncomingVoiceText(packet.nodeId)) {
                        if (textToSpeak.isNotBlank() && textToSpeak.any { !it.isWhitespace() }) {
                            recreateAudioWithTts(textToSpeak, langToSpeak)
                        }
                    } else {
                        logVoice(
                            "rx",
                            "speech muted for text from ${nodeCallsign(packet.nodeId)}: local state not in active call/broadcast " +
                                "(walkie=${_isWalkieActive.value}, rescuerCall=${_connectedVictimIntercom.value?.nodeId}, " +
                                "victimCall=${_connectedRescuer.value?.id}, broadcast=${_isReceivingOneWayBroadcast.value})"
                        )
                    }
                }
            }
            PacketFraming.MSG_TYPE_VOICE_FRAME -> {
                // Ignore raw voice frames to preserve clean neural text-mesh voice pipeline & duplex intercom
                return
            }
            PacketFraming.MSG_TYPE_VOICE_LINK_REQUEST -> {
                // A rescuer is opening an intercom toward this device (we are
                // the victim). Mark them connected and exit broadcast mode!
                if (_isSosBroadcasting.value) {
                    if (packet.payload.size >= 9) {
                        val hasTrans = packet.payload[8].toInt() == 1
                        peerTranslatorAvailable[packet.nodeId] = hasTrans
                    }
                    _isReceivingOneWayBroadcast.value = false
                    refreshRescuerContact(packet.nodeId)
                    val rescuerId = "resc-${packet.nodeId}"
                    val existing = _nearbyRescuers.value.firstOrNull { it.id == rescuerId }
                    // Explicit reconnect request from a rescuer: clear any
                    // disconnect grace so the link can re-establish cleanly.
                    lastExplicitDisconnectEpochMs = 0L
                    _connectedRescuer.value = existing?.copy(isConnected = true)
                        ?: rescuerNodeFor(packet.nodeId, "iTantra Rescuer")
                    lastRescuerContactEpochMs = System.currentTimeMillis()
                    // Auto-engage victim microphone: ambient sounds and victim's voice
                    // are captured, transcribed via STT, and broadcast as text!
                    syncVoiceCaptureState()
                    checkCrossLingualStatus()

                    // Send ACK back to rescuer with our translator capability
                    val ackPacket = ItantraPacket(
                        nodeId = _nodeId.value,
                        ttl = _meshHopLimit.value,
                        msgType = PacketFraming.MSG_TYPE_VOICE_LINK_ACK,
                        payload = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN)
                            .putLong(packet.nodeId)
                            .put(if (translationEngine.isInstalled()) 1.toByte() else 0.toByte())
                            .array()
                    )
                    broadcastMeshPacket(PacketFraming.encode(ackPacket), packet.nodeId)
                }
            }
            PacketFraming.MSG_TYPE_VOICE_LINK_ACK -> {
                // Rescuer receives ACK from victim
                refreshVictimContact(packet.nodeId)
                lastVictimContactEpochMs = System.currentTimeMillis()
                if (packet.payload.size >= 9) {
                    val hasTrans = packet.payload[8].toInt() == 1
                    peerTranslatorAvailable[packet.nodeId] = hasTrans
                    checkCrossLingualStatus()
                }
                val victimId = try {
                    ByteBuffer.wrap(packet.payload).order(ByteOrder.BIG_ENDIAN).long
                } catch (_: Exception) { 0L }
                if (_connectedVictimIntercom.value?.nodeId == packet.nodeId || victimId == _nodeId.value) {
                    _rescueConnectionMode.value = RescueConnectionMode.ONE_TO_ONE
                }
            }
            PacketFraming.MSG_TYPE_VOICE_LINK_CLOSE -> {
                // Payload carries the 8-byte big-endian target victim nodeId;
                // an empty payload is a broadcast close (e.g. rescuer leaving
                // broadcast-all or exiting rescue mode). A close meant for
                // another victim must never clear our connection — and only
                // the connected rescuer can drop us at all.
                val senderIsConnected = _connectedRescuer.value?.id == "resc-${packet.nodeId}"
                val isBroadcastClose = packet.payload.isEmpty()
                val closeTargetsUs = if (isBroadcastClose) {
                    true
                } else {
                    val targetNodeId = try {
                        ByteBuffer.wrap(packet.payload).order(ByteOrder.BIG_ENDIAN).long
                    } catch (_: Exception) {
                        _nodeId.value // unparseable payload: fall back to the sender-id check alone
                    }
                    targetNodeId == _nodeId.value
                }
                if (isBroadcastClose || (senderIsConnected && closeTargetsUs)) {
                    _connectedRescuer.value = null
                    _isReceivingOneWayBroadcast.value = false
                    // Told to drop the link: start the grace window so a late
                    // "calling" beacon or the rescuer's next text cannot
                    // silently re-establish what was just closed.
                    lastExplicitDisconnectEpochMs = System.currentTimeMillis()
                    syncVoiceCaptureState()
                }
                if (_connectedVictimIntercom.value?.nodeId == packet.nodeId) {
                    _connectedVictimIntercom.value = null
                    _rescueConnectionMode.value = RescueConnectionMode.STANDBY
                    syncBeaconAdvertising()
                    syncVoiceCaptureState()
                }
            }
        }
    }

    private fun calculateRmsLevel(pcm: ByteArray): Float {
        if (pcm.isEmpty()) return 0f
        var sum = 0.0
        val count = pcm.size / 2
        for (i in 0 until count) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            sum += sample * sample
        }
        val rms = kotlin.math.sqrt(sum / count)
        return (rms / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Publishes the measured level of a received voice frame and clears it a
     * few hundred ms after the last frame, so the UI shows real incoming audio
     * (and stops showing it) instead of a decorative animation.
     */
    private fun markReceivingAudio(level: Float) {
        _remoteAudioLevel.value = level
        lastRemoteAudioFrameEpochMs = System.currentTimeMillis()
        if (!_isReceivingAudio.value) _isReceivingAudio.value = true
        if (remoteAudioDecayJob?.isActive == true) return
        remoteAudioDecayJob = viewModelScope.launch {
            while (isActive) {
                delay(120)
                if (System.currentTimeMillis() - lastRemoteAudioFrameEpochMs > REMOTE_AUDIO_HOLD_MS) {
                    _remoteAudioLevel.value = 0f
                    _isReceivingAudio.value = false
                    return@launch
                }
            }
        }
    }

    // =========================================================================
    // ECHO GUARD (deterministic playback-state capture suppression)
    // =========================================================================
    //
    // While this device plays audio out of its loudspeaker (TTS, siren,
    // intercom voice), its own always-on mic hears that playback. Platform AEC
    // cannot cancel media-route (USAGE_MEDIA + MODE_NORMAL) playback, so the
    // captured audio would re-enter the mesh and loop between phones. This is
    // solved time-based (never by comparing audio or transcripts): every
    // playback site extends a monotonic guard window before the audio starts,
    // and both the capture engine and the mesh TX paths gate on it — a
    // half-duplex radio while self-playback is active.

    /** AudioTrack drain + speaker/reverb tail appended to every guard window. */
    private val echoGuardDecayMs = 250L

    /** System-TTS engine latency before utterance audio actually starts. */
    private val ttsStartWindowMs = 2000L

    /** Siren-loop coverage: one full two-tone iteration (~2.0s) plus jitter. */
    private val sirenToneGuardMs = 2000L

    /** Absolute cap for system TTS (a real onDone/onError ends the guard early). */
    private val ttsMaxGuardMs = 60_000L

    /** Monotonic uptime (SystemClock.uptimeMillis) until which capture is suppressed. */
    @Volatile
    private var echoGuardUntil = 0L

    /** Extends the guard to cover [durationMs] of imminent self-playback. */
    private fun extendEchoGuard(durationMs: Long) {
        val until = SystemClock.uptimeMillis() + durationMs + echoGuardDecayMs
        echoGuardUntil = maxOf(echoGuardUntil, until)
    }

    /** Known-completion end (TTS onDone/onError, beacon stopped): keep only the decay window. */
    private fun endEchoGuard() {
        val now = SystemClock.uptimeMillis()
        echoGuardUntil = minOf(echoGuardUntil, now + echoGuardDecayMs)
    }

    private fun isEchoGuardActive(): Boolean = SystemClock.uptimeMillis() < echoGuardUntil

    // =========================================================================
    // PHASE B: VOICE MESH (Mic/VAD -> On-Device STT -> Text Mesh -> Receiver TTS)
    // =========================================================================

    /** Accumulates the current speech utterance for neural transcription. */
    private var voiceTurnBuffer: ByteArrayOutputStream? = null
    private var voiceFrameSequence = 0

    private fun startMeshVoiceCapture() {
        val capture = audioCaptureEngine ?: return
        if (capture.isRunning) return
        voiceTurnBuffer = ByteArrayOutputStream()
        capture.noiseSuppressionEnabled = _noiseSuppressionEnabled.value
        // Make sure the outbound live-audio pump is running before frames arrive.
        voiceFrameSenderJob

        // Pull-based echo guard: the capture thread checks playback state per frame.
        capture.echoGuardCheck = { isEchoGuardActive() }

        capture.onFrame = { frame ->
            // Echo guard (defense in depth — the engine also gates): never
            // retransmit or transcribe audio captured during self-playback.
            if (!_isMicMuted.value && (_isVadSpeaking.value || _isPttActive.value) && !isEchoGuardActive()) {
                // Low-bitrate text mesh: speech frames accumulate locally for on-device STT.
                val currentSize: Int
                synchronized(voiceTurnBuffer ?: this) {
                    voiceTurnBuffer?.write(frame, 0, frame.size)
                    currentSize = voiceTurnBuffer?.size() ?: 0
                }
                // Force-flush ceiling (8.0 seconds of continuous speech) for long monologues
                if (voiceTurnCoordinator.shouldForceFlush(currentSize)) {
                    logVoice("turn", "force-flushing 8s continuous speech turn ($currentSize bytes)")
                    flushVoiceTurn()
                    voiceTurnCoordinator.onSpeechStarted()
                }
            }
        }
        capture.onSpeechStateChanged = { speaking ->
            // Echo guard (defense in depth): ignore turn starts while our own
            // playback is active — the engine already suppresses the trigger.
            if (!isEchoGuardActive()) {
                if (speaking) {
                    voiceFrameSequence = 0
                    voiceTurnCoordinator.onSpeechStarted()
                } else {
                    voiceTurnCoordinator.onSpeechEnded()
                }
                _isVadSpeaking.value = speaking
                _vadStatus.value = if (speaking) VadStatus.SPEECH_DETECTED else VadStatus.SILENCE
                _isTransmitting.value = speaking && !_isMicMuted.value
                _uiState.update {
                    it.copy(
                        channelState = if (speaking) RadioChannelState.TRANSMITTING else RadioChannelState.STANDBY,
                        currentTranscript = if (speaking && !_isMicMuted.value && !_isPttActive.value) "🎙️ Listening..." else it.currentTranscript
                    )
                }
            }
        }
        capture.onLevelChanged = { level -> _audioLevel.value = level }
        capture.onSpeechProbability = { prob -> _speechProbability.value = prob }
        capture.onEndOfTurn = {
            voiceTurnCoordinator.onSpeechEnded()
            val bufferSize = synchronized(voiceTurnBuffer ?: this) { voiceTurnBuffer?.size() ?: 0 }
            val action = voiceTurnCoordinator.evaluateTurn(bufferSize)
            if (action == VoiceTurnCoordinator.TurnAction.FLUSH_STT) {
                _uiState.update { state ->
                    val next = state.copy(channelState = RadioChannelState.STANDBY)
                    if (next.voiceStatus != null) next.withStatus(VoiceStatus.TRANSCRIBING) else next
                }
                flushVoiceTurn()
            } else {
                // Discard noise (< 200ms) quietly without UI flicker
                synchronized(voiceTurnBuffer ?: this) {
                    voiceTurnBuffer?.reset()
                }
                _uiState.update { it.copy(channelState = RadioChannelState.STANDBY).clearStatus() }
                logVoice("stt", "discarded short noise transient ($bufferSize bytes < ${voiceTurnCoordinator.minTurnBytes})")
            }
        }
        if (!capture.start()) {
            Log.w(voicePipelineTag, "[mic] capture did not start (RECORD_AUDIO missing or no input device)")
        }
    }

    /**
     * Wraps one captured 20 ms PCM frame in a `VoiceFrame` payload and queues
     * it for the mesh. Runs on the capture thread, so it only encodes and
     * hands off — all socket/GATT I/O happens in [dispatchVoiceFrame].
     */
    private fun enqueueVoiceFrame(pcmFrame: ByteArray) {
        if (pcmFrame.isEmpty()) return
        val sequence = voiceStreamGate.nextSequence()
        val packet = ItantraPacket(
            nodeId = _nodeId.value,
            ttl = _meshHopLimit.value,
            msgType = PacketFraming.MSG_TYPE_VOICE_FRAME,
            payload = VoiceFrame.encode(sequence, pcmFrame)
        )
        val encoded = try {
            PacketFraming.encode(packet)
        } catch (e: Exception) {
            Log.w(voicePipelineTag, "[send] could not encode voice frame", e)
            return
        }
        Log.v(
            voicePipelineTag,
            "[send] voice frame seq=$sequence pcm=${pcmFrame.size}B packet=${encoded.size}B " +
                "target=all(walkie=${_isWalkieActive.value},broadcastAll=${_isBroadcastingToAll.value},peers=${peerAddressBook.knownPeers().size})"
        )
        if (!voiceFrameChannel.trySend(encoded).isSuccess) {
            droppedVoiceFrames++
            Log.w(voicePipelineTag, "[send] voice frame queue full — dropped (total=$droppedVoiceFrames)")
        }
    }

    /** Single-owner sender: UDP broadcast + UDP unicast per frame. */
    private fun dispatchVoiceFrame(encoded: ByteArray) {
        // A 20 ms PCM frame is ~660 bytes, well past the 512-byte GATT write
        // limit this stack negotiates, so live audio rides the UDP mesh while
        // BLE GATT keeps carrying the small STT text packets.
        val bleFits = encoded.size <= BleMeshManager.MAX_GATT_WRITE_BYTES
        var bleTargets = 0
        if (bleFits) {
            bleTargets = runCatching {
                bleMeshManager?.broadcastPacket(encoded, connectIfNeeded = false) ?: 0
            }.getOrDefault(0)
        }
        val udpBroadcast = runCatching {
            wifiDirectMeshManager?.broadcastDatagram(encoded) == true
        }.getOrDefault(false)
        var udpUnicast = 0
        for ((peerNodeId, host) in peerAddressBook.knownPeers()) {
            if (peerNodeId == _nodeId.value) continue
            if (wifiDirectMeshManager?.sendDatagram(encoded, host, WifiDirectMeshManager.UDP_PORT) == true) {
                udpUnicast++
            }
        }
        sentVoiceFrames++
        if (sentVoiceFrames % 50 == 1L) {
            logVoice(
                "udp",
                "live audio: sent=$sentVoiceFrames dropped=$droppedVoiceFrames broadcast=$udpBroadcast unicastPeers=$udpUnicast"
            )
            logVoice(
                "ble",
                "live audio: frame=${encoded.size}B gattTargets=$bleTargets" +
                    if (bleFits) "" else " (skipped: above ${BleMeshManager.MAX_GATT_WRITE_BYTES}B GATT limit)"
            )
        }
    }

    /**
     * Strict policy engine for when microphone capture is permitted.
     * Mic is strictly STOPPED during Rescue radar scanning, SOS standby, and idle states.
     */
    private fun isVoiceCaptureNeeded(): Boolean {
        if (_isCrossLingualBlocked.value) return false
        return VoiceCaptureGate.isCaptureNeeded(
            isPttActive = _isPttActive.value,
            isWalkieActive = _isWalkieActive.value,
            isBroadcastingToAll = _isBroadcastingToAll.value,
            hasConnectedVictimIntercom = _connectedVictimIntercom.value != null,
            isSosBroadcasting = _isSosBroadcasting.value,
            hasConnectedRescuer = _connectedRescuer.value != null,
            isReceivingOneWayBroadcast = _isReceivingOneWayBroadcast.value
        )
    }

    /**
     * Synchronizes audio capture state with the active mission connection mode.
     */
    private fun syncVoiceCaptureState() {
        if (isVoiceCaptureNeeded()) {
            startMeshVoiceCapture()
        } else {
            stopMeshVoiceCaptureIfIdle()
        }
    }

    private fun stopMeshVoiceCaptureIfIdle() {
        if (!isVoiceCaptureNeeded()) {
            audioCaptureEngine?.stop()
            synchronized(voiceTurnBuffer ?: this) {
                voiceTurnBuffer = null
            }
        }
    }

    /** True for punctuation characters (ignored by the post-STT noise ratio). */
    private fun Char.isPunctuationMark(): Boolean = when (category) {
        CharCategory.CONNECTOR_PUNCTUATION,
        CharCategory.DASH_PUNCTUATION,
        CharCategory.START_PUNCTUATION,
        CharCategory.END_PUNCTUATION,
        CharCategory.INITIAL_QUOTE_PUNCTUATION,
        CharCategory.FINAL_QUOTE_PUNCTUATION,
        CharCategory.OTHER_PUNCTUATION -> true
        else -> false
    }

    /**
     * Gating for inbound text playback: ensures the phone only plays voice through
     * the loudspeaker when the local mode expects voice (active call, broadcast, or walkie).
     */
    private fun shouldPlayIncomingVoiceText(senderNodeId: Long): Boolean {
        if (_isSosBroadcasting.value) {
            // A victim in distress broadcasting SOS should ALWAYS hear incoming text from a rescuer!
            return true
        }
        val rescuerNodeId = _connectedRescuer.value?.id?.removePrefix("resc-")?.toLongOrNull()
        val connectedVictim = _connectedVictimIntercom.value
        if (_isRescueActive.value && connectedVictim == null) {
            // In Rescue mode while searching radar, play incoming distress text from any survivor!
            return true
        }
        if (_isWalkieActive.value && !_isRescueActive.value && !_isSosBroadcasting.value) {
            val isPaired = settingsRepository.pairedWalkieNodeIds.value.contains(senderNodeId)
            if (!isPaired) {
                logVoice("rx", "walkie audio suppressed: sender ${nodeCallsign(senderNodeId)} ($senderNodeId) is not in paired list")
                return false
            }
            return true
        }
        return VoiceCaptureGate.shouldPlayIncomingVoice(
            isWalkieActive = _isWalkieActive.value,
            isRescueActive = _isRescueActive.value,
            connectedVictimNodeId = connectedVictim?.nodeId,
            isSosBroadcasting = _isSosBroadcasting.value,
            connectedRescuerNodeId = rescuerNodeId,
            isReceivingOneWayBroadcast = _isReceivingOneWayBroadcast.value,
            senderNodeId = senderNodeId
        )
    }

    private fun Char.isSpeechContentChar(): Boolean {
        if (this.isLetterOrDigit()) return true
        val type = Character.getType(this)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.MODIFIER_LETTER.toInt() ||
            type == Character.MODIFIER_SYMBOL.toInt() ||
            type == Character.OTHER_LETTER.toInt()
    }

    /**
     * Converts the buffered voice turn to text via On-Device STT
     * (IndicConformer) and broadcasts the text packet (~20-50 bytes) as the
     * wide-range resilience channel, in parallel with the live audio frames.
     */
    private fun flushVoiceTurn() {
        // Echo guard (defense in depth): a turn that ended during our own
        // playback is echo audio — discard it, no STT, no mesh broadcast.
        if (isEchoGuardActive()) {
            Log.d("MissionControl", "echo guard active, discarding buffered turn")
            synchronized(voiceTurnBuffer ?: this) {
                voiceTurnBuffer?.reset()
            }
            return
        }
        val buffer = voiceTurnBuffer ?: return
        val pcmBytes: ByteArray
        synchronized(buffer) {
            pcmBytes = buffer.toByteArray()
            buffer.reset()
        }
        // Discard tiny noise bursts (< minTurnBytes = 200ms of audio = 6400 bytes) before
        // they reach STT and come out as garbage single characters
        if (pcmBytes.size < voiceTurnCoordinator.minTurnBytes) {
            Log.d("MissionControl", "flushVoiceTurn: Discarding noise burst (<200ms, ${pcmBytes.size} bytes)")
            viewModelScope.launch(Dispatchers.Main) {
                if (_uiState.value.voiceStatus != null) {
                    _uiState.update { it.copy(currentTranscript = "").clearStatus() }
                }
            }
            return
        }

        val pcmShorts = pcmBytesToShorts(pcmBytes)
        val selectedLang = _uiState.value.selectedLanguage
        val langCode = selectedLang.code       // e.g. "hi", "en"
        val langTag = selectedLang.languageTag  // e.g. "hi-IN", "en-IN"

        viewModelScope.launch(Dispatchers.Default) {
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy().withStatus(VoiceStatus.TRANSCRIBING) }
            }
            var transcribedText = ""
            val onnx = onnxInferenceManager

            // Check model installation using short code and full tag, in memory and on disk
            val isInstalled = modelStorageManager.isInstalled(langCode) ||
                modelStorageManager.isInstalled(langTag) ||
                modelStorageManager.isInstalledOnDisk(langCode) ||
                modelStorageManager.isInstalledOnDisk(langTag)

            if (isInstalled && onnx != null) {
                try {
                    val loaded = onnx.loadStt(langTag) ||
                        onnx.loadStt(langCode) ||
                        onnx.loadStt("${langCode}-IN")
                    if (loaded) {
                        transcribedText = onnx.transcribe(pcmShorts).trim()
                        logVoice("stt", "output '$transcribedText' (lang=$langTag, pcm=${pcmBytes.size}B)")
                    } else {
                        Log.w(voicePipelineTag, "[stt] loadStt failed for $langTag / $langCode")
                    }
                } catch (e: Exception) {
                    Log.e(voicePipelineTag, "[stt] exception while transcribing", e)
                    transcribedText = ""
                }
            } else if (!isInstalled) {
                Log.w(voicePipelineTag, "[stt] neural pack not installed for $langCode / $langTag")
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(channelState = RadioChannelState.STANDBY).clearStatus() }
                    _modelWarningMessage.value =
                        "⚠️ Neural STT pack for ${selectedLang.englishName} is not downloaded. Please download in Settings → Models or tap an installed language chip."
                }
                return@launch
            }

            // Post-STT noise gate: support both Latin & Indic scripts (including vowel signs / matras)
            // Require at least 1 valid speech character making up >= 25% of string length
            val cleanText = transcribedText.trim()
            val contentCharCount = cleanText.count { it.isSpeechContentChar() }
            val contentRatio = if (cleanText.isNotEmpty()) contentCharCount.toFloat() / cleanText.length else 0f
            if (cleanText.isBlank() || contentCharCount < 1 || contentRatio < 0.25f) {
                Log.d("MissionControl", "flushVoiceTurn: Noise transcription ('$cleanText', content=$contentCharCount/${cleanText.length}), dropping turn")
                withContext(Dispatchers.Main) {
                    when {
                        _isPttActive.value ->
                            _uiState.update { it.copy().withStatus(VoiceStatus.UNCLEAR) }
                        // Ambient silence/fan noise flush — revert to clean state so last sent phrase isn't wiped
                        _uiState.value.voiceStatus != null ->
                            _uiState.update { it.copy(currentTranscript = "").clearStatus() }
                    }
                }
                return@launch
            }

            if (_isCrossLingualBlocked.value) {
                logVoice("turn", "cross-lingual conversation blocked pending translation model")
                return@launch
            }

            val peerLang = _activePeerLanguage.value
            val isCrossLingual = peerLang != null && !peerLang.equals(langCode, ignoreCase = true)
            val localHasTranslator = translationEngine.isInstalled()

            var textToSend = cleanText
            var targetLangCode = langCode
            var origTextToInclude: String? = null

            if (isCrossLingual && localHasTranslator) {
                val translated = translationEngine.translate(cleanText, langCode, peerLang)
                logVoice("translate", "outbound cross-lingual: '$cleanText' ($langCode) -> '$translated' ($peerLang)")
                textToSend = translated
                targetLangCode = peerLang
                origTextToInclude = cleanText
            }

            withContext(Dispatchers.Main) {
                _modelWarningMessage.value = null
            }

            // 1. Update sender's local UI transcript and message log
            withContext(Dispatchers.Main) {
                val displayText = if (origTextToInclude != null) "$cleanText (➔ $textToSend)" else cleanText
                _uiState.update { it.copy(currentTranscript = displayText, voiceStatus = null) }
                val sentMsg = VoiceMessageEntity(
                    id = System.currentTimeMillis(),
                    messageUid = UUID.randomUUID().toString(),
                    text = displayText,
                    senderCallsign = _callsign.value,
                    isLocal = true,
                    languageCode = langCode,
                    timestamp = System.currentTimeMillis(),
                    isAlert = _isSosBroadcasting.value
                )
                _messageLogs.update { listOf(sentMsg) + it }
            }
            logVoice("ui", "local transcript + log updated: '$cleanText'")

            // 2. Broadcast lightweight text packet over dual-transport mesh
            val payloadBuilder = StringBuilder("$targetLangCode|$textToSend")
            if (origTextToInclude != null) {
                payloadBuilder.append("|orig:$origTextToInclude")
            }
            payloadBuilder.append("|fromLang:$langCode")
            payloadBuilder.append("|trans:${if (localHasTranslator) 1 else 0}")
            val payloadString = payloadBuilder.toString()
            val textPacket = ItantraPacket(
                nodeId = _nodeId.value,
                ttl = _meshHopLimit.value,
                msgType = PacketFraming.MSG_TYPE_TRANSLATED_TEXT,
                payload = payloadString.toByteArray(Charsets.UTF_8)
            )
            val encodedText = PacketFraming.encode(textPacket)
            logVoice("send", "text packet ${encodedText.size}B target=all (${textToSend.length} chars, lang=$targetLangCode)")
            broadcastMeshPacket(encodedText)
        }
    }

    /**
     * Broadcasts a direct text message across the mesh (e.g. from quick chips or dictation).
     * Synthesizes audio via TTS on the receiver side.
     */
    fun sendBroadcastTextMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        if (_isCrossLingualBlocked.value) {
            val localLangName = _uiState.value.selectedLanguage.englishName
            val peerLangName = _activePeerLanguage.value?.let { SupportedLanguage.fromCode(it).englishName } ?: "Peer"
            _modelWarningMessage.value = "⚠️ Language Mismatch: Local speaks $localLangName but peer speaks $peerLangName. Offline Translation model required before sending text. Please download in Settings → Models."
            return
        }
        val selectedLang = _uiState.value.selectedLanguage
        val langCode = selectedLang.code

        val peerLang = _activePeerLanguage.value
        val isCrossLingual = peerLang != null && !peerLang.equals(langCode, ignoreCase = true)
        val localHasTranslator = translationEngine.isInstalled()

        var textToSend = trimmed
        var targetLangCode = langCode
        var origTextToInclude: String? = null

        if (isCrossLingual && localHasTranslator) {
            val translated = translationEngine.translate(trimmed, langCode, peerLang)
            textToSend = translated
            targetLangCode = peerLang
            origTextToInclude = trimmed
        }

        viewModelScope.launch(Dispatchers.Main) {
            val displayText = if (origTextToInclude != null) "$trimmed (➔ $textToSend)" else trimmed
            _uiState.update { it.copy(currentTranscript = displayText, voiceStatus = null) }
            val sentMsg = VoiceMessageEntity(
                id = System.currentTimeMillis(),
                messageUid = UUID.randomUUID().toString(),
                text = displayText,
                senderCallsign = _callsign.value,
                isLocal = true,
                languageCode = langCode,
                timestamp = System.currentTimeMillis(),
                isAlert = _isSosBroadcasting.value
            )
            _messageLogs.update { listOf(sentMsg) + it }
            logVoice("ui", "local dictation/text logged: '$displayText'")
        }

        val payloadBuilder = StringBuilder("$targetLangCode|$textToSend")
        if (origTextToInclude != null) {
            payloadBuilder.append("|orig:$origTextToInclude")
        }
        payloadBuilder.append("|fromLang:$langCode")
        payloadBuilder.append("|trans:${if (localHasTranslator) 1 else 0}")
        val payloadString = payloadBuilder.toString()
        val textPacket = ItantraPacket(
            nodeId = _nodeId.value,
            ttl = _meshHopLimit.value,
            msgType = PacketFraming.MSG_TYPE_TRANSLATED_TEXT,
            payload = payloadString.toByteArray(Charsets.UTF_8)
        )
        val encodedText = PacketFraming.encode(textPacket)
        logVoice("send", "text packet ${encodedText.size}B (${textToSend.length} chars, lang=$targetLangCode) -> all peers")
        broadcastMeshPacket(encodedText)
    }

    private var ttsPlaybackJob: Job? = null

    /**
     * Recreates voice on the receiving device:
     * 1. Attempts OnnxInferenceManager TTS (FastPitch + HiFi-GAN) for the given language.
     * 2. Falls back to Android system TextToSpeech if neural model pack is missing or fails.
     * 3. Drives DigitalAudioVisualizer while audio is speaking out of the loudspeaker.
     */
    private fun ensureAudibleMediaVolume() {
        val audioManager = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (curVol < (maxVol * 0.7f).toInt()) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVol * 0.85f).toInt().coerceAtLeast(1), 0)
            }
        } catch (_: Exception) {}
    }

    private fun recreateAudioWithTts(text: String, langCode: String) {
        ttsPlaybackJob?.cancel()
        ttsPlaybackJob = viewModelScope.launch(Dispatchers.Default) {
            ensureAudibleMediaVolume()
            val onnx = onnxInferenceManager
            var playedOnnx = false

            // Direct on-disk gate (never the installedPacks flow): the flow
            // starts empty and is only filled by an async rescan, so a packet
            // arriving before that scan would be misrouted to system TTS.
            val packOnDisk = modelStorageManager.isInstalledOnDisk(langCode)
            if (onnx == null) {
                Log.w("MissionControl", "recreateAudioWithTts: onnx runtime unavailable for '$langCode' — falling back to system TTS")
            } else if (!packOnDisk) {
                Log.w("MissionControl", "recreateAudioWithTts: neural TTS pack not on disk for '$langCode' — falling back to system TTS")
                val langName = SupportedLanguage.fromCode(langCode).englishName
                withContext(Dispatchers.Main) {
                    _modelWarningMessage.value =
                        "Neural TTS pack for $langName is NOT downloaded — playing with system voice. Download the pack in Settings → Models."
                }
            } else {
                try {
                    val loaded = onnx.loadTts(langCode) || onnx.loadTts("${langCode}-IN")
                    if (!loaded) {
                        Log.w("MissionControl", "recreateAudioWithTts: loadTts failed for '$langCode' — falling back to system TTS")
                    } else {
                        val pcmShorts = onnx.synthesize(text)
                        if (pcmShorts == null || pcmShorts.isEmpty()) {
                            Log.w("MissionControl", "recreateAudioWithTts: synthesize returned null for '$langCode' — falling back to system TTS")
                        } else {
                            val pcmBytes = shortsToPcmLittleEndian(pcmShorts)
                            withContext(Dispatchers.Main) {
                                _uiState.update { it.copy(channelState = RadioChannelState.RECEIVING) }
                            }
                            try {
                                // Visualizer pulse during playback
                                val totalDurationMs = (pcmBytes.size * 1000L) / (OnnxInferenceManager.TTS_SAMPLE_RATE_HZ * 2)
                                val visualizerJob = launch {
                                    val chunkDurationMs = 50L
                                    val steps = (totalDurationMs / chunkDurationMs).toInt().coerceAtLeast(1)
                                    for (i in 0 until steps) {
                                        _audioLevel.value = (0.25f + 0.5f * kotlin.math.sin(i * 0.4).toFloat().coerceIn(0f, 1f))
                                        delay(chunkDurationMs)
                                    }
                                    _audioLevel.value = 0f
                                }
                                // Half-duplex echo guard: strictly suppress mic during loudspeaker playback (+decay).
                                extendEchoGuard(totalDurationMs)
                                audioPlaybackEngine?.play(pcmBytes, OnnxInferenceManager.TTS_SAMPLE_RATE_HZ)
                                visualizerJob.join()
                                endEchoGuard()
                                playedOnnx = true
                            } finally {
                                // Cancellation-safe UI reset: ensures UI returns to STANDBY cleanly.
                                withContext(Dispatchers.Main + NonCancellable) {
                                    _uiState.update { it.copy(channelState = RadioChannelState.STANDBY) }
                                    _audioLevel.value = 0f
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("MissionControl", "recreateAudioWithTts: ONNX TTS exception for '$langCode' — falling back to system TTS", e)
                    playedOnnx = false
                }
            }

            if (!playedOnnx) {
                withContext(Dispatchers.Main) {
                    speakWithSystemTts(text, langCode)
                }
            }
        }
    }

    private var ttsPrewarmJob: Job? = null

    /**
     * Warms the FastPitch + HiFi-GAN ONNX sessions for [language] in the
     * background (language change / pack install / post-rescan startup) so an
     * incoming mesh message hits the loaded-session cache instead of paying
     * the multi-second session load inside the playback coroutine.
     *
     * Idempotent (loadTts short-circuits when already loaded), cancellable,
     * and skipped while any TTS playback is active so it never tears down
     * sessions an in-flight synthesize() is still using. The short idle delay
     * lets an STT flush win the shared @Synchronized manager lock first.
     */
    private fun prewarmTts(language: SupportedLanguage) {
        if (ttsPlaybackJob?.isActive == true || audioBeaconJob?.isActive == true) return
        ttsPrewarmJob?.cancel()
        ttsPrewarmJob = viewModelScope.launch(Dispatchers.Default) {
            delay(1500)
            if (!modelStorageManager.isInstalledOnDisk(language.code)) {
                Log.d("MissionControl", "prewarmTts: no neural pack on disk for '${language.code}' — skipping")
                return@launch
            }
            val onnx = onnxInferenceManager ?: return@launch
            // Prewarm FastPitch + HiFiGAN TTS and Conformer STT so turns hit the loaded session cache
            val loadedTts = runCatching { onnx.loadTts(language.code) || onnx.loadTts(language.languageTag) }
                .getOrDefault(false)
            val loadedStt = runCatching { onnx.loadStt(language.languageTag) || onnx.loadStt(language.code) || onnx.loadStt("${language.code}-IN") }
                .getOrDefault(false)
            Log.d("MissionControl", "prewarm: '${language.code}' loadedTts=$loadedTts loadedStt=$loadedStt")
        }
    }

    private var systemTtsVisualizerJob: Job? = null

    private fun speakWithSystemTts(text: String, langCode: String) {
        val tts = systemTts ?: run {
            Log.e(voicePipelineTag, "[tts] system TTS unavailable, attempting re-init")
            initSystemTts()
            return
        }
        ensureAudibleMediaVolume()
        val audioManager = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        try {
            if (_isSpeakerphoneOn.value) {
                audioManager?.mode = AudioManager.MODE_NORMAL
            } else {
                audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager?.isSpeakerphoneOn = false
            }
            val targetLocale = SupportedLanguage.fromCode(langCode).locale
            val langResult = tts.setLanguage(targetLocale)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.language = Locale.getDefault()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts.setAudioAttributes(attrs)
            }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _uiState.update { it.copy(channelState = RadioChannelState.RECEIVING) }
                    val estimatedDurationMs = maxOf(4000L, text.length * 150L + 2000L)
                    extendEchoGuard(estimatedDurationMs)
                    systemTtsVisualizerJob?.cancel()
                    systemTtsVisualizerJob = viewModelScope.launch {
                        var tick = 0f
                        while (true) {
                            tick += 0.35f
                            _audioLevel.value = 0.3f + 0.6f * kotlin.math.sin(tick.toDouble()).toFloat().coerceIn(0f, 1f)
                            delay(80)
                        }
                    }
                }
                override fun onDone(utteranceId: String?) {
                    endEchoGuard() // known completion: keep only the decay window
                    systemTtsVisualizerJob?.cancel()
                    _audioLevel.value = 0f
                    _uiState.update { it.copy(channelState = RadioChannelState.STANDBY) }
                }
                override fun onError(utteranceId: String?) {
                    endEchoGuard() // known completion: keep only the decay window
                    systemTtsVisualizerJob?.cancel()
                    _audioLevel.value = 0f
                    _uiState.update { it.copy(channelState = RadioChannelState.STANDBY) }
                    Log.w(voicePipelineTag, "[tts] system TTS error")
                }
            })
            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            extendEchoGuard(ttsStartWindowMs)
            val speakResult = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "itantra_${System.currentTimeMillis()}")
            Log.d(voicePipelineTag, "[tts] speak('$text', lang=$langCode) result=$speakResult")
        } catch (e: Exception) {
            Log.e("MissionControl", "Error in speakWithSystemTts", e)
            endEchoGuard() // speak() may never have started — release the window
            systemTtsVisualizerJob?.cancel()
            _audioLevel.value = 0f
        }
    }

    private fun pcmBytesToShorts(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        for (i in shorts.indices) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt() and 0xFF
            shorts[i] = ((hi shl 8) or lo).toShort()
        }
        return shorts
    }

    private fun stopMeshUdpIfIdle() {
        // The global Wi-Fi Direct flag gates the whole UDP mesh: with the
        // radio off, nothing may keep the socket alive.
        val needed = _wifiDirectEnabled.value && (
            _isWalkieActive.value ||
                _isBroadcastingToAll.value ||
                _connectedVictimIntercom.value != null ||
                _isSosBroadcasting.value
            )
        if (!needed) {
            wifiDirectMeshManager?.stopUdp()
        }
    }

    private fun stopBleScanIfIdle() {
        if (!_isRescueActive.value && !_isSosBroadcasting.value && !_isWalkieActive.value) {
            bleMeshManager?.stopScanning()
        }
    }

    // =========================================================================
    // PHASE B: AUDIO BEACON (siren + localized TTS announcement)
    // =========================================================================

    private var audioBeaconJob: Job? = null

    private fun startAudioBeacon(language: SupportedLanguage) {
        audioBeaconJob?.cancel()
        audioBeaconJob = viewModelScope.launch {
            val playback = audioPlaybackEngine
            if (playback == null) {
                // No audio output — the beacon is silent but the radio still runs.
                return@launch
            }
            playback.setStreamToSpeaker(true)
            if (_uiState.value.forceMaxVolumeAlerts) {
                playback.setVolume01(1f)
            }

            // One localized announcement when the TTS pack is installed.
            if (modelStorageManager.isInstalled(language.code)) {
                val pcm = withContext(Dispatchers.Default) {
                    try {
                        val onnx = onnxInferenceManager
                        if (onnx != null && onnx.loadTts(language.code)) {
                            onnx.synthesize(language.sampleAlertPhrase)
                        } else {
                            null
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
                if (pcm != null && pcm.isNotEmpty()) {
                    // Echo guard: cover the announcement phrase itself.
                    val phraseMs = pcm.size * 1000L / OnnxInferenceManager.TTS_SAMPLE_RATE_HZ
                    extendEchoGuard(phraseMs)
                    playback.play(shortsToPcmLittleEndian(pcm), OnnxInferenceManager.TTS_SAMPLE_RATE_HZ)
                    // Wait out the phrase before the siren loop (best-effort
                    // pacing; AudioTrack buffers asynchronously).
                    delay(phraseMs + 300L)
                }
            }

            // Two-tone siren loop while SOS remains active (mutes when rescuer or intercom connects).
            while (_isSosBroadcasting.value && isActive) {
                if (_connectedRescuer.value != null || _connectedVictimIntercom.value != null) {
                    playback.stopTones()
                    delay(400)
                    continue
                }
                // Echo guard: each siren iteration extends the window, so the
                // mic stays half-duplex for the whole SOS siren (a captured
                // siren would otherwise loop between phones).
                extendEchoGuard(sirenToneGuardMs)
                playback.playTone(880f, 320, 0.9f)
                delay(400)
                if (_connectedRescuer.value != null || _connectedVictimIntercom.value != null) {
                    playback.stopTones()
                    delay(400)
                    continue
                }
                extendEchoGuard(sirenToneGuardMs)
                playback.playTone(620f, 320, 0.9f)
                delay(400)
                delay(600)
            }
        }
    }

    private fun stopAudioBeacon() {
        audioBeaconJob?.cancel()
        audioBeaconJob = null
        // Known-completion end: release the siren guard down to the decay window.
        endEchoGuard()
        if (!_isWalkieActive.value && !_isBroadcastingToAll.value && _connectedVictimIntercom.value == null) {
            audioPlaybackEngine?.stop()
        }
    }

    override fun onCleared() {
        super.onCleared()
        sensorManager?.unregisterListener(this)
        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {}
        audioBeaconJob?.cancel()
        remoteAudioDecayJob?.cancel()
        wifiDirectScanJob?.cancel()
        voiceFrameChannel.close()
        audioCaptureEngine?.stop()
        audioPlaybackEngine?.stop()
        bleMeshManager?.shutdown()
        wifiDirectMeshManager?.shutdown()
        runCatching { onnxInferenceManager?.close() }
        runCatching {
            systemTts?.stop()
            systemTts?.shutdown()
        }
        // The foreground beacon service intentionally outlives this ViewModel
        // while SOS is active; otherwise it is stopped.
        if (!_isSosBroadcasting.value) {
            runCatching { TacticalMeshService.stop(getApplication()) }
        }
    }

    // =========================================================================
    // 4. SETTINGS & NEURAL DIAGNOSTICS
    // =========================================================================
    private val _callsign = MutableStateFlow("ITANTRA-UNIT-ALPHA")
    val callsign: StateFlow<String> = _callsign.asStateFlow()

    fun updateCallsign(newCallsign: String) {
        _callsign.value = newCallsign
        viewModelScope.launch { settingsRepository.setCallsign(newCallsign) }
    }

    private val _sttModelInfo = MutableStateFlow(
        SttModelInfo(
            name = "AI4Bharat IndicConformer INT8",
            runtime = "ONNX Runtime Mobile (INT8)",
            modelSizeMb = 64.5f,
            isQuantized = true,
            isLoaded = false,
            inferenceLatencyMs = 0
        )
    )
    val sttModelInfo: StateFlow<SttModelInfo> = _sttModelInfo.asStateFlow()

    private val _ttsModelInfo = MutableStateFlow(
        TtsModelInfo(
            name = "FastPitch + HiFi-GAN (FP16)",
            runtime = "ONNX Runtime Mobile",
            modelSizeMb = 130.4f,
            sampleRateHz = 22050,
            isReady = false
        )
    )
    val ttsModelInfo: StateFlow<TtsModelInfo> = _ttsModelInfo.asStateFlow()

    private val _languagePacks = MutableStateFlow(
        SupportedLanguage.entries.associate { lang ->
            lang.code to LanguagePack(
                code = lang.code,
                englishName = lang.englishName
            )
        }
    )
    val languagePacks: StateFlow<Map<String, LanguagePack>> = _languagePacks.asStateFlow()

    private val _isManifestLoaded = MutableStateFlow(true)
    val isManifestLoaded: StateFlow<Boolean> = _isManifestLoaded.asStateFlow()

    private val _verifiedAssets = MutableStateFlow(
        mapOf(
            "stt_model" to VerifiedAsset(path = "stt/indicconformer_int8.onnx", sizeBytes = 67633152L, sha256 = "a1b2c3d4e5f6"),
            "tts_fp" to VerifiedAsset(path = "tts/fastpitch.onnx", sizeBytes = 108854478L, sha256 = "c7d8e9f0a1b2"),
            "tts_hifi" to VerifiedAsset(path = "tts/hifigan.onnx", sizeBytes = 27910570L, sha256 = "998877665544")
        )
    )
    val verifiedAssets: StateFlow<Map<String, VerifiedAsset>> = _verifiedAssets.asStateFlow()

    // --- Legacy / Shared bindings for backward compatibility ---
    val activeProtocol: StateFlow<TransportProtocol> = MutableStateFlow(TransportProtocol.WIFI_DIRECT).asStateFlow()
    val connectionStatus: StateFlow<ConnectionStatus> = MutableStateFlow(ConnectionStatus.CONNECTED).asStateFlow()
    val connectedPeer: StateFlow<PeerDevice?> = MutableStateFlow<PeerDevice?>(null).asStateFlow()
    val connectedPeers: StateFlow<List<PeerDevice>> = _pairedWalkieDevices
    val discoveredPeers: StateFlow<List<PeerDevice>> = _discoveredWalkieDevices
    val telemetry: StateFlow<MissionTelemetry> = MutableStateFlow(MissionTelemetry()).asStateFlow()
    private val _messageLogs = MutableStateFlow<List<VoiceMessageEntity>>(emptyList())
    val messageLogs: StateFlow<List<VoiceMessageEntity>> = _messageLogs.asStateFlow()
    val alertCount: StateFlow<Int> = victimAlertCount

    fun setSelectedLanguage(language: SupportedLanguage) {
        _uiState.update { it.copy(selectedLanguage = language) }
        viewModelScope.launch {
            settingsRepository.setSelectedLanguageCode(language.code)
        }
        try {
            if (isSystemTtsReady) {
                systemTts?.language = language.locale
            }
        } catch (_: Exception) {}
        if (modelStorageManager.isInstalled(language.code) && !_isCrossLingualBlocked.value) {
            _modelWarningMessage.value = null
        }
        checkCrossLingualStatus()
        broadcastTranslationCapability()
        // Natural idle point: re-warm TTS for the newly selected language.
        prewarmTts(language)
        if (_isRescueActive.value) {
            startRescuerBeaconAdvertising()
        }
        if (_isSosBroadcasting.value) {
            startBeaconAdvertising(buildDistressBeaconPayload())
        }
    }

    fun setThemeMode(mode: String) {
        _uiState.update { it.copy(themeMode = mode) }
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setForceMaxVolumeAlerts(enabled: Boolean) {
        _uiState.update { it.copy(forceMaxVolumeAlerts = enabled) }
        viewModelScope.launch { settingsRepository.setForceMaxVolumeAlerts(enabled) }
    }

    fun setLowPowerListeningEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isLowPowerListeningEnabled = enabled) }
        viewModelScope.launch { settingsRepository.setLowPowerListeningEnabled(enabled) }
    }

    fun togglePttMode(enabled: Boolean = true) {
        _uiState.update { it.copy(isPttActive = enabled) }
    }

    // =========================================================================
    // 4. ON-DEVICE AI MODELS — REAL OFFLINE MODEL HUB
    // =========================================================================
    fun downloadModel(languageTag: String) {
        val language = _catalogue.value.firstOrNull { it.languageTag == languageTag } ?: return
        modelDownloadManager.download(language)
    }

    fun pauseModelDownload(languageTag: String) {
        modelDownloadManager.pause(languageTag)
    }

    fun cancelModelDownload(languageTag: String) {
        modelDownloadManager.cancel(languageTag)
    }

    fun deleteModel(languageTag: String) {
        viewModelScope.launch {
            modelStorageManager.deleteModel(languageTag)
            modelDownloadManager.resetState(languageTag)
            settingsRepository.setInstalledLanguageTags(modelStorageManager.installedTags())
        }
    }

    /** Compatibility no-op: re-scans local storage for installed packs. */
    fun restoreDefaultModels() {
        modelStorageManager.refresh()
    }

    // --- Tactical Radio & Disaster Mesh Settings (persisted via DataStore) ---
    private val _txPower = MutableStateFlow("Balanced (500m)")
    val txPower: StateFlow<String> = _txPower.asStateFlow()

    fun setTxPower(power: String) {
        _txPower.value = power
        viewModelScope.launch { settingsRepository.setTxPower(power) }
        if (_isSosBroadcasting.value) {
            startBeaconAdvertising(buildDistressBeaconPayload())
        }
    }

    private val _beaconInterval = MutableStateFlow(30)
    val beaconInterval: StateFlow<Int> = _beaconInterval.asStateFlow()

    fun setBeaconInterval(seconds: Int) {
        _beaconInterval.value = seconds
        viewModelScope.launch { settingsRepository.setBeaconInterval(seconds) }
    }

    private val _meshHopLimit = MutableStateFlow(5)
    val meshHopLimit: StateFlow<Int> = _meshHopLimit.asStateFlow()

    fun setMeshHopLimit(hops: Int) {
        _meshHopLimit.value = hops
        viewModelScope.launch { settingsRepository.setMeshHopLimit(hops) }
    }

    private val _vadSensitivity = MutableStateFlow("Balanced")
    val vadSensitivity: StateFlow<String> = _vadSensitivity.asStateFlow()

    fun setVadSensitivity(level: String) {
        _vadSensitivity.value = level
        viewModelScope.launch { settingsRepository.setVadSensitivity(level) }
    }

    private val _noiseSuppressionEnabled = MutableStateFlow(true)
    val noiseSuppressionEnabled: StateFlow<Boolean> = _noiseSuppressionEnabled.asStateFlow()

    fun setNoiseSuppressionEnabled(enabled: Boolean) {
        _noiseSuppressionEnabled.value = enabled
        audioCaptureEngine?.noiseSuppressionEnabled = enabled
        viewModelScope.launch { settingsRepository.setNoiseSuppressionEnabled(enabled) }
    }

    private val _keepScreenAwake = MutableStateFlow(true)
    val keepScreenAwake: StateFlow<Boolean> = _keepScreenAwake.asStateFlow()

    fun setKeepScreenAwake(enabled: Boolean) {
        _keepScreenAwake.value = enabled
        viewModelScope.launch { settingsRepository.setKeepScreenAwake(enabled) }
    }

    private val _zeroLogPrivacy = MutableStateFlow(false)
    val zeroLogPrivacy: StateFlow<Boolean> = _zeroLogPrivacy.asStateFlow()

    fun setZeroLogPrivacy(enabled: Boolean) {
        _zeroLogPrivacy.value = enabled
        viewModelScope.launch { settingsRepository.setZeroLogPrivacy(enabled) }
    }

    private val _mapCacheSizeMb = MutableStateFlow(0)
    val mapCacheSizeMb: StateFlow<Int> = _mapCacheSizeMb.asStateFlow()

    fun clearMapCache() {
        viewModelScope.launch {
            modelStorageManager.clearMapCache()
            _mapCacheSizeMb.value = 0
        }
    }

    fun switchProtocol(protocol: TransportProtocol) {
        // Legacy stub: protocol selection is implicit in the real engines.
    }

    fun scanForPeers(protocol: TransportProtocol? = null) {
        refreshDiscoveredNodes()
    }

    fun connectToPeer(peer: PeerDevice) {
        pairDevice(peer)
    }

    fun disconnectPeer() {
        // Legacy stub: links are torn down via the mode toggles.
    }

    fun connectDirectIp(ip: String, port: Int = 8889) {
        _uiState.update { it.copy(directIpInput = ip) }
        val request = ItantraPacket(
            nodeId = _nodeId.value,
            ttl = _meshHopLimit.value,
            msgType = PacketFraming.MSG_TYPE_VOICE_LINK_REQUEST,
            payload = ByteArray(0)
        )
        wifiDirectMeshManager?.startUdpBroadcast()
        wifiDirectMeshManager?.sendDatagram(PacketFraming.encode(request), ip, port)
    }

    fun broadcastDistressAlert(priority: AlertPriority = AlertPriority.CRITICAL_DISTRESS, customMessage: String = "") {
        val message = customMessage.ifBlank { _uiState.value.selectedLanguage.sampleAlertPhrase }
        val packet = ItantraPacket(
            nodeId = _nodeId.value,
            ttl = _meshHopLimit.value,
            msgType = PacketFraming.MSG_TYPE_TRANSLATED_TEXT,
            payload = message.toByteArray(Charsets.UTF_8)
        )
        wifiDirectMeshManager?.startUdpBroadcast()
        broadcastMeshPacket(PacketFraming.encode(packet))
    }

    fun playVoiceMessage(message: VoiceMessageEntity) {
        viewModelScope.launch {
            val onnx = onnxInferenceManager ?: return@launch
            val pcm = withContext(Dispatchers.Default) {
                try {
                    val lang = SupportedLanguage.fromCode(message.languageCode)
                    if (modelStorageManager.isInstalled(lang.code) && onnx.loadTts(lang.code)) {
                        onnx.synthesize(message.text)
                    } else {
                        null
                    }
                } catch (_: Exception) {
                    null
                }
            } ?: return@launch
            // Echo guard: log playback through the loudspeaker is still self-audio.
            extendEchoGuard(pcm.size * 1000L / OnnxInferenceManager.TTS_SAMPLE_RATE_HZ)
            try {
                audioPlaybackEngine?.play(shortsToPcmLittleEndian(pcm), OnnxInferenceManager.TTS_SAMPLE_RATE_HZ)
            } finally {
                withContext(Dispatchers.Main + NonCancellable) {
                    _uiState.update { it.copy(channelState = RadioChannelState.STANDBY) }
                    _audioLevel.value = 0f
                }
            }
        }
    }

    /** Zero-log wipe of the in-memory message buffer. */
    fun clearLogs() {
        _messageLogs.value = emptyList()
    }

    /**
     * Clears all session transcripts, message history, active captions, and turn buffers
     * so that entering or exiting a mission mode starts with a clean slate.
     */
    fun clearSessionTranscripts() {
        _messageLogs.value = emptyList()
        _isReceivingOneWayBroadcast.value = false
        _uiState.update {
            it.copy(
                currentTranscript = "",
                activeIncomingCaption = null,
                voiceStatus = null,
                channelState = RadioChannelState.STANDBY
            )
        }
        synchronized(voiceTurnBuffer ?: this) {
            voiceTurnBuffer?.reset()
        }
        voiceTurnCoordinator.onSpeechEnded()
        _isVadSpeaking.value = false
        _isTransmitting.value = false
    }

    /**
     * Emergency tactical wipe: clears message logs, the offline map tile
     * cache, every persisted setting and all installed model packs.
     */
    fun emergencyWipe() {
        viewModelScope.launch {
            clearLogs()
            peerProfiles.clear()
            lastProfileSentToNode.clear()
            _mapCacheSizeMb.value = 0
            modelDownloadManager.clearStates()
            modelStorageManager.wipeAll()
            settingsRepository.clearAll()
            _isTranslationModelInstalled.value = false
            _translationDownloadState.value = ModelDownloadState.Idle
            checkCrossLingualStatus()
        }
    }

    fun testTtsAudio(text: String = "", language: SupportedLanguage? = null) {
        val lang = language ?: _uiState.value.selectedLanguage
        val phrase = text.ifBlank { lang.sampleAlertPhrase }
        viewModelScope.launch {
            val onnx = onnxInferenceManager ?: return@launch
            val pcm = withContext(Dispatchers.Default) {
                try {
                    if (onnx.loadTts(lang.code)) onnx.synthesize(phrase) else null
                } catch (_: Exception) {
                    null
                }
            } ?: return@launch
            // Echo guard: settings test tone would otherwise loop into STT.
            extendEchoGuard(pcm.size * 1000L / OnnxInferenceManager.TTS_SAMPLE_RATE_HZ)
            try {
                audioPlaybackEngine?.play(shortsToPcmLittleEndian(pcm), OnnxInferenceManager.TTS_SAMPLE_RATE_HZ)
            } finally {
                withContext(Dispatchers.Main + NonCancellable) {
                    _uiState.update { it.copy(channelState = RadioChannelState.STANDBY) }
                    _audioLevel.value = 0f
                }
            }
        }
    }

    fun runModelBenchmark(languageCode: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val onnx = onnxInferenceManager ?: return@launch
            try {
                if (!onnx.loadStt(languageCode)) return@launch
                val silence = ShortArray(AudioCaptureEngine.SAMPLE_RATE_HZ) // 1 s of silence
                val start = System.nanoTime()
                onnx.transcribe(silence)
                val elapsedMs = (System.nanoTime() - start) / 1_000_000L
                _sttModelInfo.value = _sttModelInfo.value.copy(
                    isLoaded = true,
                    inferenceLatencyMs = elapsedMs.toInt()
                )
            } catch (_: Exception) {
            }
        }
    }

    // =========================================================================
    // Late init: real backends (DataStore + model hub + hardware engines).
    // Declared after every property initializer so the launched coroutines can
    // never observe half-constructed state on the (immediate) main dispatcher.
    // =========================================================================
    init {
        initSettingsPersistence()
        initModelHub()
        observeEngineFlows()
        initSystemTts()
    }

    private fun initSystemTts() {
        try {
            // Prefer Google Speech Services for crisp, studio-quality neural voice across all OEMs (Samsung, Pixel, etc.)
            val googleEngine = "com.google.android.tts"
            val pm = getApplication<Application>().packageManager
            val isGoogleTtsInstalled = try {
                pm.getPackageInfo(googleEngine, 0)
                true
            } catch (_: Exception) {
                false
            }
            val preferredEngine = if (isGoogleTtsInstalled) googleEngine else null

            systemTts = TextToSpeech(getApplication(), { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isSystemTtsReady = true
                    try {
                        systemTts?.language = _uiState.value.selectedLanguage.locale
                    } catch (_: Exception) {}
                    Log.d(voicePipelineTag, "[tts] system TTS initialized with engine: ${systemTts?.defaultEngine}")
                } else {
                    Log.w(voicePipelineTag, "[tts] preferred engine init failed (status=$status), falling back to default engine")
                    isSystemTtsReady = false
                    try {
                        systemTts = TextToSpeech(getApplication()) { fallbackStatus ->
                            if (fallbackStatus == TextToSpeech.SUCCESS) {
                                isSystemTtsReady = true
                                try {
                                    systemTts?.language = _uiState.value.selectedLanguage.locale
                                } catch (_: Exception) {}
                                Log.d(voicePipelineTag, "[tts] fallback default engine initialized successfully")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(voicePipelineTag, "[tts] fallback engine init error", e)
                    }
                }
            }, preferredEngine)
        } catch (e: Exception) {
            Log.e(voicePipelineTag, "[tts] error initializing system TTS", e)
            try {
                systemTts = TextToSpeech(getApplication()) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        isSystemTtsReady = true
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun initSettingsPersistence() {
        viewModelScope.launch {
            // Stable mesh identity persisted in DataStore (random fallback
            // used until this completes).
            _nodeId.value = settingsRepository.ensureNodeId()

            settingsRepository.settings.collect { s ->
                val identityBefore = _uiState.value.run { Triple(userName, userAge, userGender) }
                _uiState.update {
                    it.copy(
                        themeMode = s.themeMode,
                        forceMaxVolumeAlerts = s.forceMaxVolumeAlerts,
                        isLowPowerListeningEnabled = s.isLowPowerListeningEnabled,
                        keepScreenAwake = s.keepScreenAwake,
                        isOnboardingCompleted = s.isOnboardingCompleted,
                        userName = s.userName,
                        userAge = s.userAge,
                        userGender = s.userGender,
                        userLanguages = s.userLanguages,
                        relativeRelation = s.relativeRelation,
                        relativePhone = s.relativePhone
                    )
                }
                // Keep our own labels name-based and, if the identity just
                // changed while a mode is live, push the new profile out.
                cacheOwnProfile()
                if (identityBefore != Triple(s.userName, s.userAge, s.userGender) &&
                    (_isSosBroadcasting.value || _isRescueActive.value || _isWalkieActive.value)
                ) {
                    broadcastOwnProfile()
                }
                _callsign.value = s.callsign
                _txPower.value = s.txPower
                _beaconInterval.value = s.beaconInterval
                _meshHopLimit.value = s.meshHopLimit
                _vadSensitivity.value = s.vadSensitivity
                _noiseSuppressionEnabled.value = s.noiseSuppressionEnabled
                _keepScreenAwake.value = s.keepScreenAwake
                _zeroLogPrivacy.value = s.zeroLogPrivacy
                audioCaptureEngine?.noiseSuppressionEnabled = s.noiseSuppressionEnabled
            }
        }

        viewModelScope.launch {
            settingsRepository.pairedWalkieNodeIds.collect {
                refreshWalkieDevicesList()
            }
        }
    }

    fun completeOnboarding(
        name: String,
        age: Int?,
        gender: String,
        languages: Set<String>,
        relation: String,
        phone: String
    ) {
        viewModelScope.launch {
            settingsRepository.saveOnboardingProfile(
                name = name,
                age = age,
                gender = gender,
                languages = languages,
                relation = relation,
                phone = phone
            )
            languages.firstOrNull()?.let { code ->
                setSelectedLanguage(SupportedLanguage.fromCode(code))
            }
            cacheOwnProfile()
            startLanguagePackDownloads(languages)
        }
    }

    /**
     * Kicks off the neural pack download for every selected language that is
     * not already installed. Fire-and-forget by design: [downloadModel] only
     * launches the download worker, so onboarding navigates immediately and
     * progress shows up in the SOS/Walkie language sheets.
     */
    private suspend fun startLanguagePackDownloads(languageCodes: Set<String>) {
        withContext(Dispatchers.IO) {
            languageCodes.forEach { code ->
                val tag = catalogueTagFor(code) ?: return@forEach
                if (modelStorageManager.isInstalled(tag)) {
                    Log.i(voicePipelineTag, "[model] pack already installed for $tag — skipping onboarding download")
                    return@forEach
                }
                Log.i(voicePipelineTag, "[model] starting onboarding download for $tag")
                downloadModel(tag)
            }
        }
    }

    /**
     * Maps a [SupportedLanguage] code ("hi") onto the catalogue tag ("hi-IN").
     * Returns null when the catalogue has no pack for that language.
     */
    private fun catalogueTagFor(languageCode: String): String? {
        val catalogue = _catalogue.value
        catalogue.firstOrNull { it.iso.equals(languageCode, ignoreCase = true) }?.let { return it.languageTag }
        catalogue.firstOrNull { it.languageTag.startsWith("$languageCode-", ignoreCase = true) }
            ?.let { return it.languageTag }
        return catalogue.firstOrNull { it.languageTag.equals(languageCode, ignoreCase = true) }?.languageTag
    }

    private fun initModelHub() {
        viewModelScope.launch {
            // Reconcile the persisted installed-tag set with what is actually
            // on disk (the disk scan is the source of truth).
            modelStorageManager.rescan()
            settingsRepository.setInstalledLanguageTags(modelStorageManager.installedTags())

            // The disk scan is now authoritative: warm the selected language's
            // TTS sessions so the first incoming mesh message replays instantly.
            prewarmTts(_uiState.value.selectedLanguage)

            // Refresh the catalogue from the network when available; the
            // built-in fallback keeps everything working fully offline.
            _catalogue.value = ModelCatalogue.fetchRemoteCatalogue()

            // Reflect the real on-disk map tile cache size.
            val cacheBytes = withContext(Dispatchers.IO) { modelStorageManager.mapCacheSizeBytes() }
            _mapCacheSizeMb.value = (cacheBytes / (1024 * 1024)).toInt()

            val isTransInstalled = translationEngine.isInstalled()
            _isTranslationModelInstalled.value = isTransInstalled
            if (isTransInstalled) {
                _translationDownloadState.value = ModelDownloadState.Installed
            }
            checkCrossLingualStatus()
        }
    }

    /**
     * Broadcasts an encoded Itantra mesh packet with range-aware transport
     * selection instead of firing every radio unconditionally:
     *
     * - Bluetooth off  -> skip BLE GATT entirely (Wi-Fi Direct only).
     * - Wi-Fi Direct off -> skip UDP entirely (BLE only).
     * - Both on, targeted: BLE-linked target -> BLE GATT only (nearby);
     *   otherwise UDP broadcast + unicast to that node's known IP (far).
     * - Both on, broadcast to all: BLE GATT + UDP broadcast, with per-peer
     *   UDP unicast only to peers without a live GATT link (they already got
     *   the packet over GATT).
     */
    fun broadcastMeshPacket(packetBytes: ByteArray, targetNodeId: Long? = null) {
        val useBle = _bluetoothEnabled.value
        val useUdp = _wifiDirectEnabled.value
        logVoice(
            "send",
            "mesh packet ${packetBytes.size}B target=${targetNodeId?.let { nodeCallsign(it) } ?: "all"} " +
                "radios=ble:$useBle/udp:$useUdp knownPeerIps=${peerAddressBook.knownPeers().size}"
        )
        viewModelScope.launch(Dispatchers.IO) {
            val linked = bleMeshManager?.connectedNodeIds?.value.orEmpty()
            val targetIsBleLinked = targetNodeId != null && targetNodeId in linked

            // 1. Off-grid BLE direct transmission: skipped when Bluetooth is
            //    off, or when both radios are on and the target is a far node
            //    that must be reached over UDP instead.
            var bleTargets = 0
            val sendBle = useBle && (targetNodeId == null || !useUdp || targetIsBleLinked)
            if (sendBle) {
                bleTargets = runCatching {
                    bleMeshManager?.broadcastPacket(packetBytes, targetNodeId) ?: 0
                }.getOrDefault(0)
            }

            // 2. UDP unicast to peers with a known direct IP (reaches across
            //    subnets where UDP broadcast is dropped). When broadcasting to
            //    all, BLE-linked peers are skipped — they already received the
            //    packet over GATT.
            var udpUnicast = 0
            var udpBroadcast = false
            val sendUdp = useUdp && (targetNodeId == null || !useBle || !targetIsBleLinked)
            if (sendUdp) {
                for ((peerNodeId, host) in peerAddressBook.knownPeers()) {
                    if (peerNodeId == _nodeId.value) continue
                    if (targetNodeId != null && peerNodeId != targetNodeId) continue
                    if (targetNodeId == null && peerNodeId in linked) continue
                    if (wifiDirectMeshManager?.sendDatagram(packetBytes, host, WifiDirectMeshManager.UDP_PORT) == true) {
                        udpUnicast++
                    }
                }

                // 3. High-speed UDP transmission (repeated bursts for packet-loss mitigation)
                repeat(3) {
                    if (wifiDirectMeshManager?.broadcastDatagram(packetBytes) == true) udpBroadcast = true
                    delay(30)
                }
            }
            logVoice(
                "ble",
                "mesh packet -> gattTargets=$bleTargets | [udp] broadcast=$udpBroadcast unicastPeers=$udpUnicast " +
                    "(ble=$useBle udp=$useUdp targetBleLinked=$targetIsBleLinked)"
            )
        }
    }

    private fun observeEngineFlows() {
        // Advertise failures must be visible in the field log: without a beacon
        // no peer can discover us, which looks exactly like "nothing arrives".
        bleMeshManager?.onAdvertisingFailed = { failure ->
            Log.w(voicePipelineTag, "[ble] advertising failed: code=${failure.errorCode} ${failure.message}")
        }
        // BLE scan results -> rescue victims / rescuer nodes / walkie peers.
        viewModelScope.launch {
            bleMeshManager?.discoveredBeacons?.collect { beacons ->
                onBeaconsUpdated(beacons)
            }
        }
        // BLE GATT link changes -> walkie link indicator.
        viewModelScope.launch {
            bleMeshManager?.connectedNodeIds?.collect { linked ->
                val stale = _discoveredWalkieDevices.value.map { peer ->
                    val nodeId = peer.id.removePrefix("ble-").toLongOrNull()
                    if (peer.protocol == TransportProtocol.BLE && nodeId != null) {
                        peer.copy(isConnected = nodeId in linked)
                    } else {
                        peer
                    }
                }
                _discoveredWalkieDevices.value = stale
                updateWalkieLinkState()
            }
        }
        // BLE GATT incoming packets -> voice playback + link handling.
        viewModelScope.launch {
            bleMeshManager?.incomingPackets?.collect { bytes ->
                handleIncomingDatagram(bytes)
            }
        }
        // Wi-Fi Direct peer list -> refresh link state without injecting duplicate devices
        viewModelScope.launch {
            wifiDirectMeshManager?.peers?.collect {
                if (_isWalkieActive.value) {
                    refreshWalkieDevicesList()
                }
            }
        }
        // UDP mesh traffic -> voice playback + link handling (source address
        // included so direct peer IPs can be learned for unicast).
        viewModelScope.launch {
            wifiDirectMeshManager?.incomingDatagrams?.collect { datagram ->
                handleIncomingDatagram(datagram.bytes, datagram.sourceAddress)
            }
        }
        // Link liveness decays on its own: a peer that stops talking must not
        // leave the HUD showing "link active" forever.
        viewModelScope.launch {
            while (isActive) {
                delay(LINK_LIVENESS_TICK_MS)
                if (_isWalkieActive.value) updateWalkieLinkState()
            }
        }
    }

    private companion object {
        /** A peer counts as reachable for this long after its last inbound packet. */
        const val PEER_CONTACT_LIVENESS_MS = 6_000L

        /** How often the walkie link indicator is re-evaluated while active. */
        const val LINK_LIVENESS_TICK_MS = 2_000L

        /**
         * Wi-Fi Direct long-range discovery cadence while any mesh mode is
         * active: long enough to be battery-friendly, short enough that a
         * peer list refresh lands well within a user's patience window.
         */
        const val WIFI_DIRECT_SCAN_INTERVAL_MS = 20_000L

        /** Received audio level is held this long after the last voice frame. */
        const val REMOTE_AUDIO_HOLD_MS = 400L

        /**
         * Identity advert cadence while any mesh mode is active: frequent enough
         * that a peer resolves a name within a few seconds, slow enough not to
         * flood the mesh (the payload is a few dozen bytes).
         */
        const val PROFILE_BROADCAST_INTERVAL_MS = 5_000L

        /** Minimum gap between two unsolicited identity replies to the same node. */
        const val PROFILE_PROMPT_MIN_INTERVAL_MS = 10_000L

        /** Bound on the per-peer identity-reply rate-limit table. */
        const val MAX_TRACKED_PROFILE_PEERS = 128

        /**
         * Level shown while the platform TTS engine speaks. Android's TTS
         * gives no PCM access, so the meter shows a flat "playback active"
         * level instead of a fabricated waveform.
         */
        const val SYSTEM_TTS_ACTIVE_LEVEL = 0.45f

        /** Re-advertise once the device has moved at least this far (meters). */
        const val BEACON_POSITION_REFRESH_METERS = 10f

        /** ...or at least this often even when stationary (milliseconds). */
        const val BEACON_POSITION_REFRESH_MS = 15_000L

        /**
         * After an explicit disconnect, both sides suppress auto-lock (from
         * inbound text or a late "calling" beacon advert) for this long so a
         * deliberately broken intercom link cannot silently re-establish.
         */
        const val EXPLICIT_DISCONNECT_GRACE_MS = 15_000L
    }
}
