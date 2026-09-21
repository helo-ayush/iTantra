# iTantra 🚨📡
### Sovereign Off-Grid Disaster Transceiver & Tactical Rescue Mesh
> **"Compute locally, transmit text, reconstruct speech locally."**

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20%28API%2026%2B%29-3DDC84.svg?logo=android&logoColor=white)](https://developer.android.com)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-35%20%28Android%2015%29-blue.svg)](https://developer.android.com)
[![Architecture](https://img.shields.io/badge/Architecture-Clean%20%7C%20MVVM%20%7C%20Jetpack%20Compose-7F52FF.svg?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Mesh Network](https://img.shields.io/badge/Mesh-Wi--Fi%20Direct%20P2P%20%2B%20BLE%205.0-0080FF.svg)](#disaster-mesh-networking--radio-layer)
[![On-Device AI](https://img.shields.io/badge/AI-100%25%20Offline%20%28ONNX%20Runtime%20%2B%20INT8%2FFP16%29-FF6F00.svg)](#on-device-neural-speech--language-pipeline)
[![Languages](https://img.shields.io/badge/Languages-10%20Indic%20Dialects%20%2B%20English-E91E63.svg)](#multilingual--cross-lingual-mesh)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

---

## 📖 Executive Summary & Mission Goal

**iTantra** is a sovereign, zero-infrastructure peer-to-peer disaster communication and tactical search-and-rescue (SAR) system. It is engineered specifically for collapsed infrastructure scenarios—such as earthquakes, cyclones, urban building collapses, and catastrophic floods—where cellular base stations, fiber backbones, power grids, and internet access are completely severed.

Built to address **ISRO Problem Statement 26173 ("Offline Multilingual TTS/STT Aided Neural Transceiver for Low-Bitrate Links")**, iTantra replaces high-bandwidth, failure-prone voice streaming with an edge-computed **neural voice-to-text-to-voice paradigm**.

### The Core Paradigm:
$$\text{Acoustic Speech} \xrightarrow[\text{Edge STT}]{\text{Local}} \text{Compact Text Packet} \xrightarrow[\text{BLE / Wi-Fi Mesh}]{\text{Few Bytes}} \text{Receiver} \xrightarrow[\text{Edge TTS}]{\text{Local}} \text{Synthetic Speech}$$

Instead of streaming fragile 64 kbps–128 kbps raw audio over volatile radio links, iTantra transcribes speech on-device into compact text payloads (typically **under 120 bytes**), routes them through an autonomous multi-hop ad-hoc mesh network, and resynthesizes natural, high-fidelity speech locally on the receiving device in the recipient's preferred dialect.

---

## 🎯 Engineering Restrictions & Real-World Constraints

iTantra is designed under strict constraints to guarantee reliability when human lives are at stake:

| Constraint | Design Challenge | iTantra Solution |
|---|---|---|
| **100% Offline (Air-Gapped)** | No cell towers, no DNS, no internet, no cloud APIs. | Self-contained on-device ONNX inference, local SQLite/Room caching, decentralized P2P ad-hoc routing. |
| **Low-End Android Hardware** | Target devices: 2 GB–4 GB RAM, entry-level ARM Cortex-A53/A55 SoCs. | INT8 dynamic quantization for ASR, FP16 acoustic models, zero-garbage-collection buffer pooling, memory-mapped neural weights. |
| **Extreme Bandwidth Scarcity** | BLE advertising packets cap manufacturer payloads at 24–31 bytes. | Packet fragmentation, byte-level telemetry bitpacking, delta coordinate encoding, and text-only voice transmission (99.8% bandwidth reduction vs. raw PCM). |
| **Battery Conservation** | Trapped victims may wait 48–72 hours for extraction on a single charge. | Hardware-accelerated VAD (Voice Activity Detection), duty-cycled BLE scanning (`Low Power` when stationary, `Low Latency` on distress), zero background wake-locks when idle. |
| **Severe Acoustic Noise** | Background roar of rain, wind, diesel generators, heavy machinery, rubble. | Dual-stage noise suppression: spectral subtraction front-end + adaptive VAD energy gating before neural inference. |
| **Linguistic Diversity** | India has 22 official languages; victims and rescuers often speak different tongues. | 10 regional Indic languages pre-compiled for instant <16ms offline Compose UI switching, plus cross-lingual on-device neural translation. |

---

## 🏛️ System Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                       iTantra System Stack                                      │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘
                                                  │
 ┌────────────────────────────────────────────────┴─────────────────────────────────────────────┐
 │                                   Presentation Layer (Jetpack Compose)                       │
 │  ┌───────────────────────┬───────────────────────┬───────────────────────┬─────────────────┐ │
 │  │    🚨 SOS Distress     │    📻 Walkie-Talkie    │   🦺 Rescue Radar     │   ⚙️ Settings   │ │
 │  │   1-Tap Beacon Dome   │  Push-To-Talk / VAD   │ Compass Vector Minimap│ Neural Hub & TX │ │
 │  │   Panic Shake Sensor  │ Live Mesh Transcript  │ Multi-Victim Triage   │ Sensor Health   │ │
 │  └───────────────────────┴───────────────────────┴───────────────────────┴─────────────────┘ │
 │          │ CompositionLocalProvider(LocalAppStrings) [Instant <16ms Dynamic Dialect Switch]  │
 └──────────┬───────────────────────────────────────────────────────────────────────────────────┘
            │
 ┌──────────┴───────────────────────────────────────────────────────────────────────────────────┐
 │                                    State & Control Layer (MVVM)                              │
 │  • MissionControlViewModel (Central Coordinator, Radio Power FSM, Intercom Lifecycle)       │
 │  • SettingsRepository (Encrypted Preferences, Callsign Identity, Zero-Log Mode)              │
 └──────────┬───────────────────────────────────────────────────────────────────────────────────┘
            │
 ┌──────────┴─────────────────────────────────────────┬─────────────────────────────────────────┐
 │                  Neural AI Engine (Offline)        │          Disaster Mesh Networking       │
 │  • OnnxInferenceManager (INT8 Conformer STT)       │  • BleMeshManager (Autonomous Watchdog) │
 │  • AudioPlaybackEngine (FastPitch + HiFi-GAN)      │  • WifiDirectMeshManager (P2P High-BW)  │
 │  • TranslationEngine (Google ML Kit NMT hi <-> en) │  • DistanceEstimator (Log-Distance RSSI)│
 │  • VoiceCaptureGate (WebRTC VAD + Noise Suppress)  │  • TrilateralPositioner (2D Triangulate)│
 └────────────────────────────────────────────────────┴─────────────────────────────────────────┘
```

---

## 🌟 Core Modules in Detail

### 1. 🚨 SOS Distress Beacon
Built for trapped victims under structural debris or during flash flooding:
- **Soft Tactile Emergency Dome**: High-contrast, breathing radial aura button with depression physics. 1-tap activation immediately initiates high-power beacon broadcasting.
- **Hardware Panic Shake Detector**: Uses the device accelerometer (`TYPE_ACCELEROMETER`) with high-pass energy filtering. Shaking the phone vigorously 5 times triggers SOS even if the screen is broken, wet, or locked.
- **Continuous Telemetry Beaconing**: Broadcasts encrypted victim profile (Callsign, Age, Gender, Emergency Contact, Battery %, GPS Coordinates, and Accuracy).
- **Acoustic Rescue Siren**: Emits high-decibel multi-frequency audio chirp patterns designed to cut through structural concrete and guide acoustic search teams.
- **Auto-Voice Distress Transceiver**: Automatically transcribes victim whispers in low oxygen conditions and transmits text over the mesh.

### 2. 📻 Tactical Walkie-Talkie
Team voice communication for tactical response squads and civilian groups without cellular coverage:
- **Direct P2P Comms**: Operates over hybrid Wi-Fi Direct (UDP socket port 8889) and BLE GATT channels.
- **Dual Transmission Modes**:
  - **Push-to-Talk (PTT)**: Tactile central disc with dynamic pulse animations.
  - **Hands-Free VAD (Voice Activity Detection)**: Automatically opens transmission gate when speaking and closes when silent, conserving battery and channel airtime.
- **Real-Time Live Caption HUD**: Speech is transcribed and synced across all radios in real-time, allowing team members in loud environments to read incoming communications.
- **Equalizer Spectrum Visualizer**: 24-band live audio visualizer reflecting mic activity and incoming peer stream intensity.
- **In-Call Audio Controls**: 1-tap earpiece/speakerphone toggling and microphone mute.

### 3. 🦺 Search & Rescue (SAR) Radar Hub
Designed for first responders, NDRF battalions, and volunteer search parties:
- **Compass-Oriented Minimap**: Integrates hardware magnetometer and gyroscope (`Sensor.TYPE_ROTATION_VECTOR`) to render an edge-to-edge tactical vector map oriented to the rescuer's real-world heading.
- **Log-Distance RSSI Trilateration**:
  $$d = 10^{\frac{\text{TxPower} - \text{RSSI}}{10 \cdot n}}$$
  Continuously computes estimated distance (meters) to nearby distress beacons using path-loss exponent calibration ($n = 2.4$ indoor/rubble).
- **Multi-Victim Triage Classification**: Automatically groups detected victims into medical triage tiers:
  - 🔴 **Immediate (Red)**: Severe condition / critical battery (< 15%) / trapped.
  - 🟡 **Delayed (Yellow)**: Stable condition / moderate distance.
  - 🟢 **Minor (Green)**: Out of immediate danger.
- **Dual Rescue Communication Affordances**:
  - **1-to-1 Two-Way Intercom**: Rescuer initiates an exclusive full-duplex voice session with a specific victim.
  - **1-Way Evacuation Announcement**: Megaphone mode streaming voice announcements to every device within radio range (e.g., *"NDRF team is at the North entrance. Stay calm."*).

### 4. 🧠 On-Device Neural Model Hub
Manages offline language intelligence without third-party cloud dependence:
- **Per-Language Modular Packs (`.itantra`)**: Users download only the dialects they need, conserving precious internal storage on 32 GB / 64 GB entry-level phones.
- **Verified Model Pipeline**:
  - **STT (Speech-to-Text)**: IndicConformer INT8 (~187 MB) via ONNX Runtime with dynamic MatMul/Gemm quantization. Real-Time Factor (RTF) = 0.342x on mobile CPU.
  - **TTS (Text-to-Speech)**: FastPitch FP16 + HiFi-GAN FP16 (~130 MB combined) generating 22,050 Hz synthetic speech.
  - **NMT (Neural Translation)**: Google ML Kit on-device translation model enabling offline cross-lingual interoperability between Hindi and English.
- **Safe Storage Quota Guard**: Visual quota bar (2048 MB tactical limit) with confirmation dialogs detailing exact MB savings prior to deletion.

### 5. ⚙️ Tactical Settings & Privacy
- **Radio TX Power Tuning**: Low (`100m`), Balanced (`500m`), Maximum (`1.5km`).
- **Beacon Ping Interval**: Rapid (`15s`), Default (`30s`), Saver (`60s`).
- **Multi-Hop Relay Limit**: Configurable `3`, `5`, or `7` mesh hops to prevent broadcast storms.
- **Zero-Log Tactical Privacy**: When toggled, all voice buffers, transcripts, and telemetry are maintained purely in volatile RAM, wiping instantly on process termination.
- **Emergency Data Wipe**: 1-tap complete erasure of cached map tiles, identity parameters, and mission logs.

---

## 🌐 Multilingual & Cross-Lingual Mesh

India's linguistic fragmentation presents a major challenge in disaster response. A responder from Delhi deployed to Odisha or Tamil Nadu may not speak the local language.

iTantra solves this at two levels:

### 1. Instant (<16ms) UI Localization
Standard Android locale switching calls `setApplicationLocales`, which **recreates the Activity**. In a disaster scenario, an Activity restart terminates active BLE advertising, disconnects Wi-Fi Direct sockets, and interrupts open audio tracks.

iTantra implements a zero-restart Jetpack Compose architecture:
- **Central Contract**: `AppStrings.kt` defines all user-facing strings.
- **Pre-Compiled Bundles**: 10 language dictionaries in `com.itantra.app.localization.translations`.
- **Reactive Recomposition**: `CompositionLocalProvider(LocalAppStrings provides currentStrings)` live-switches the entire UI across all 10 languages instantly upon selection.

| Language | Native Name | Code | Script |
|---|---|---|---|
| **English** | English | `en` | Latin |
| **Hindi** | हिन्दी | `hi` | Devanagari |
| **Tamil** | தமிழ் | `ta` | Tamil |
| **Bengali** | বাংলা | `bn` | Bengali |
| **Marathi** | मराठी | `mr` | Devanagari |
| **Telugu** | తెలుగు | `te` | Telugu |
| **Gujarati** | ગુજરાતી | `gu` | Gujarati |
| **Kannada** | ಕನ್ನಡ | `kn` | Kannada |
| **Malayalam** | മലയാളം | `ml` | Malayalam |
| **Odia** | ଓଡ଼ିଆ | `or` | Odia |

### 2. Cross-Lingual Voice Translation
When a Hindi-speaking rescuer broadcasts to a Bengali- or English-speaking victim, the incoming text packet is passed to the local `TranslationEngine`. The recipient sees the translated transcript and hears the speech resynthesized in their native language by their on-device TTS engine.

---

## 📡 Disaster Mesh Networking & Radio Layer

```
 Victim Phone (BLE Beacon)
       │
       ▼ (250m BLE 5.0 Advert)
 Intermediate Phone A (Relay Node - Hop 1)
       │
       ▼ (250m BLE Relay / Wi-Fi Direct)
 Intermediate Phone B (Relay Node - Hop 2)
       │
       ▼
 Rescuer Phone (Tactical Radar Display)
```

### 1. Bluetooth Low Energy (BLE 5.0) Layer
- **Manufacturer Data Advertising (`0xFF`)**: Telemetry is packed into custom non-connectable advertising PDUs. Devices scan continuously without pairing or GATT connection overhead.
- **Autonomous Watchdog & Scan Self-Healing**: Mobile OS platforms (Samsung, Xiaomi, etc.) frequently kill BLE background scans without notifying the app. iTantra implements an active watchdog (`BleMeshManager`) that monitors callback frequency and automatically re-arms stalled scanning sessions without dropping discovered peers.
- **Adapter Power-Cycle Resilience**: Listens for `BluetoothAdapter.ACTION_STATE_CHANGED` to immediately re-assert advertising and scanning when Bluetooth is toggled.

### 2. Wi-Fi Direct (P2P) Layer
- When high-bandwidth voice transmission is required, devices establish an autonomous Wi-Fi Direct Group.
- Audio packets and message logs stream over raw UDP sockets (port 8889) with minimal transport overhead.

### 3. Loop Prevention & Flood Routing
- Every mesh packet carries a unique `(originNodeId, packetSequenceId)` tuple and a `TTL` (Time-to-Live) counter.
- Nodes maintain an in-memory LRU cache of recently seen packet hashes. Duplicate packets are immediately dropped, preventing broadcast storms and infinite routing loops.

---

## 🛠️ Repository Structure

```
iTantra/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/itantra/app/
│   │   │   │   ├── ai/               # ONNX runtime inference, neural text-to-speech, STT
│   │   │   │   ├── audio/            # AudioRecord, AudioTrack, VAD gate, audio level visualizer
│   │   │   │   ├── localization/     # Dynamic AppStrings contract & 10 Indic translation bundles
│   │   │   │   ├── mesh/             # BleMeshManager, WifiDirectManager, DistanceEstimator, packets
│   │   │   │   ├── model/            # DistressVictim, RadioChannelState, SupportedLanguage
│   │   │   │   ├── modelhub/         # LanguageModelPack, download manager, storage quota tracker
│   │   │   │   ├── repository/       # SettingsRepository, EncryptedSharedPreferences
│   │   │   │   ├── service/          # PanicShakeDetector, EmergencyBeaconService
│   │   │   │   ├── ui/
│   │   │   │   │   ├── components/   # MissionBottomNav, BatteryIndicator, Soft UI widgets
│   │   │   │   │   ├── screens/      # SosDistressScreen, WalkieScreen, RescueScreen, SettingsScreen
│   │   │   │   │   └── theme/        # MinimalColors, Shape, Typography, Theme switcher
│   │   │   │   ├── viewmodel/        # MissionControlViewModel (Central orchestrator)
│   │   │   │   └── MainActivity.kt   # CompositionLocalProvider root, runtime permission guards
│   │   │   ├── res/                  # Vector drawables, mipmaps, XML configurations
│   │   │   └── AndroidManifest.xml   # BLE, Wi-Fi Direct, Foregrounds, Audio permissions
│   │   └── test/                     # Unit test suites (BleDiscoveryLivenessTest, etc.)
│   └── build.gradle.kts              # Dependencies: Compose BOM, ONNX Runtime Android, ML Kit
├── exported_apks/                    # Pre-built release APK artifacts (iTantra.apk)
├── gradle/                           # Gradle wrapper binaries
└── build.gradle.kts                  # Root build configuration
```

---

## 🚀 Building & Running

### Prerequisites
- **Android Studio**: Ladybug (2024.2+) or newer.
- **JDK**: OpenJDK 17 or 21.
- **Android SDK**: `compileSdk 35`, `minSdk 26` (Android 8.0 Oreo), `targetSdk 35`.
- **Physical Devices**: At least two Android devices with Bluetooth 5.0 and Wi-Fi Direct support are recommended to test mesh communication.

### 1. Clone the Repository
```bash
git clone https://github.com/helo-ayush/iTantra.git
cd iTantra
```

### 2. Build Debug APK
```bash
./gradlew assembleDebug
# Generated APK: app/build/outputs/apk/debug/app-debug.apk
```

### 3. Build Signed / Optimized Release APK
```bash
./gradlew assembleRelease
# Generated APK: app/build/outputs/apk/release/app-release.apk
```

### 4. Run Automated Test Suite
```bash
./gradlew test
```

### 5. Install Directly via ADB
```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## 📋 Hardware Permissions Matrix

| Permission | Purpose | Why Required |
|---|---|---|
| `BLUETOOTH_SCAN` | BLE Mesh Discovery | Locates nearby distress beacons and rescuer nodes without pairing. |
| `BLUETOOTH_ADVERTISE` | BLE Beacon Transmission | Broadcasts emergency telemetry and distress presence. |
| `BLUETOOTH_CONNECT` | BLE Intercom Link | Enables direct GATT voice sessions between victims and rescuers. |
| `NEARBY_WIFI_DEVICES` | Wi-Fi Direct Mesh | Discovers and negotiates high-speed P2P groups without internet. |
| `ACCESS_FINE_LOCATION` | Radar Minimap & Trilateration | Acquires coordinates for distress beacons and anchors distance metrics. |
| `RECORD_AUDIO` | Hands-Free VAD & Walkie | Captures microphone audio for on-device neural transcription. |
| `VIBRATE` | Haptic Alarm Alerts | Vibrates device when a new distress beacon is detected in range. |
| `FOREGROUND_SERVICE` | Background Beacon Persistence | Keeps SOS beacon broadcasting even when phone is locked or in pocket. |

---

## 👥 Contributors & Acknowledgements
- **Author & Maintainer**: Ayush Kumar ([@helo-ayush](https://github.com/helo-ayush))
- **Inspiration**: ISRO Problem Statement 26173 (Smart India Hackathon).
- **Neural Speech Assets**: [AI4Bharat IndicConformer](https://github.com/AI4Bharat/IndicConformer) & [Indic-TTS](https://github.com/AI4Bharat/Indic-TTS).

---

## 📄 License
This project is licensed under the **Apache License 2.0**. See the [LICENSE](LICENSE) file for details.
