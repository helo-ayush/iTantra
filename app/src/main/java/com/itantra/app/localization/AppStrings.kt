package com.itantra.app.localization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.itantra.app.localization.translations.EnglishStrings

/**
 * Contract defining all user-facing strings across iTantra.
 * Implemented once per supported language for instant, zero-cost,
 * offline switching in Jetpack Compose.
 */
interface AppStrings {
    // -------------------------------------------------------------------------
    // Common / Navigation / System
    // -------------------------------------------------------------------------
    val navSos: String
    val navWalkie: String
    val navRescue: String
    val navSettings: String

    val cancel: String
    val confirm: String
    val save: String
    val dismiss: String
    val close: String
    val back: String
    val continueText: String
    val grant: String
    val turnOn: String
    val active: String
    val inactive: String
    val connected: String
    val disconnected: String
    val standby: String
    val searching: String
    val battery: String
    val signal: String
    val selectLanguage: String

    val micPermissionNeeded: String
    val micPermissionDesc: String
    val bluetoothOff: String
    val bluetoothOffDesc: String
    val locationOff: String
    val locationOffDesc: String

    // -------------------------------------------------------------------------
    // Onboarding Screen
    // -------------------------------------------------------------------------
    val onboardingWelcomeTitle: String
    val onboardingWelcomeSubtitle: String
    val onboardingProfileHeader: String
    val onboardingProfileSubtitle: String
    val onboardingNameLabel: String
    val onboardingNamePlaceholder: String
    val onboardingAgeLabel: String
    val onboardingGenderLabel: String
    val genderMale: String
    val genderFemale: String
    val genderOther: String
    val onboardingLangLabel: String
    val onboardingLangSubtitle: String
    val onboardingContactHeader: String
    val onboardingContactSubtitle: String
    val onboardingRelationLabel: String
    val relationParent: String
    val relationSpouse: String
    val relationChild: String
    val relationSibling: String
    val relationFriend: String
    val onboardingPhoneLabel: String
    val onboardingPhonePlaceholder: String
    val onboardingCompleteButton: String

    // -------------------------------------------------------------------------
    // SOS Distress Screen
    // -------------------------------------------------------------------------
    val sosScreenTitle: String
    val sosHoldToBroadcast: String
    val sosReleaseToCancel: String
    val sosTapToActivate: String
    val sosArming: String
    val sosBeaconActive: String
    val sosBroadcastingBle: String
    val sosCancelButton: String
    val sosCancelDialogTitle: String
    val sosCancelDialogMessage: String
    val sosKeepBroadcasting: String
    val sosConfirmCancel: String
    val sosPanicShakeActive: String
    val sosPanicShakeDesc: String
    val sosGpsAcquiring: String
    val sosGpsLocked: String
    val sosAudioBeaconTitle: String
    val sosAudioBeaconDesc: String
    val sosQuickPhrasesTitle: String
    val sosBroadcastLangTitle: String
    val sosEmergencyTipsTitle: String
    val sosTip1: String
    val sosTip2: String
    val sosTip3: String

    // -------------------------------------------------------------------------
    // Walkie Screen
    // -------------------------------------------------------------------------
    val walkieScreenTitle: String
    val walkieHoldToTalk: String
    val walkieTransmitting: String
    val walkieListening: String
    val walkieReleaseToSend: String
    val walkieChannelTitle: String
    val walkieChannelStandby: String
    val walkieChannelBusy: String
    fun walkiePeersInRange(count: Int): String
    val walkieNoPeers: String
    val walkieLiveTranscript: String
    val walkieTranscriptPlaceholder: String
    val walkieCrossLingualActive: String
    val walkieSyncLanguage: String
    val walkieNoiseSuppression: String
    val walkieDirectIpTitle: String
    val walkieDirectIpDesc: String
    val walkieConnectIp: String

    // -------------------------------------------------------------------------
    // Rescue Screen
    // -------------------------------------------------------------------------
    val rescueScreenTitle: String
    val rescueActiveBeacons: String
    val rescueNoBeaconsFound: String
    val rescueScanningNearby: String
    val rescueNearestVictim: String
    val rescueSignalRssi: String
    val rescueEstimatedDistance: String
    val rescueTriageImmediate: String
    val rescueTriageDelayed: String
    val rescueTriageMinor: String
    val rescueOpenIntercom: String
    val rescueBroadcastPresence: String
    val rescuePresenceActive: String
    val rescueQuickShoutsTitle: String

    // -------------------------------------------------------------------------
    // Settings Screen
    // -------------------------------------------------------------------------
    val settingsScreenTitle: String
    val settingsIdentityTitle: String
    val settingsCallsign: String
    val settingsRadioTitle: String
    val settingsTxPower: String
    val settingsBeaconInterval: String
    val settingsMeshHops: String
    val settingsAudioTitle: String
    val settingsVadSensitivity: String
    val settingsNoiseSuppression: String
    val settingsMaxVolumeAlerts: String
    val settingsLowPowerListening: String
    val settingsSecurityTitle: String
    val settingsKeepScreenAwake: String
    val settingsZeroLogPrivacy: String
    val settingsThemeTitle: String
    val settingsThemeLight: String
    val settingsThemeDark: String
    val settingsThemeSystem: String
    val settingsModelHubTitle: String
    val settingsStorageUsed: String
    val settingsDownload: String
    val settingsInstalled: String
    val settingsDownloading: String
    val settingsEmergencyWipeTitle: String
    val settingsEmergencyWipeDesc: String
    val settingsEmergencyWipeButton: String
    val settingsEmergencyWipeConfirmTitle: String
    val settingsEmergencyWipeConfirmMessage: String
}

/**
 * CompositionLocal providing [AppStrings] to all composable children.
 */
val LocalAppStrings = staticCompositionLocalOf<AppStrings> { EnglishStrings }

/**
 * Quick accessor for composables: `val strings = Strings.current`
 */
object Strings {
    val current: AppStrings
        @Composable
        @ReadOnlyComposable
        get() = LocalAppStrings.current
}
