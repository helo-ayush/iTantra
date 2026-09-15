package com.itantra.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.app.R
import com.itantra.app.model.RadioChannelState
import com.itantra.app.model.SupportedLanguage
import com.itantra.app.model.TransportProtocol
import com.itantra.app.ui.components.BatteryIndicator
import com.itantra.app.ui.components.soft.SoftBadge
import com.itantra.app.ui.components.soft.SoftHeroDome
import com.itantra.app.ui.components.soft.SoftIcon
import com.itantra.app.ui.components.soft.SoftMissionHeader
import com.itantra.app.ui.components.soft.SoftSheetShell
import com.itantra.app.ui.components.soft.SoftStatusDot
import com.itantra.app.ui.theme.SoftFieldShape
import com.itantra.app.ui.theme.minimalColors
import com.itantra.app.viewmodel.MissionControlViewModel

/**
 * Walkie-Talkie screen, Soft Minimalism edition:
 * - Telemetry header (brand pill · status capsule)
 * - Language selector card with 1-tap dialect chips and full sheet
 * - Pairing request banner + language mismatch banner with 1-tap peer sync
 * - Standby: soft hero dome ("Join walkie")
 * - Active: team voice room with PTT disc (hold-to-talk + VAD), live equalizer,
 *   mute / speaker / disconnect controls, transcription log, paired & nearby nodes
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalkieScreen(
    viewModel: MissionControlViewModel,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.minimalColors
    val isWalkieActive by viewModel.isWalkieActive.collectAsState()
    val isMicMuted by viewModel.isMicMuted.collectAsState()
    val isTransmitting by viewModel.isTransmitting.collectAsState()
    val isPttActive by viewModel.isPttActive.collectAsState()
    val isRefreshingNodes by viewModel.isRefreshingNodes.collectAsState()
    val isSpeakerphoneOn by viewModel.isSpeakerphoneOn.collectAsState()
    val pairedDevices by viewModel.pairedWalkieDevices.collectAsState()
    val discoveredDevices by viewModel.discoveredWalkieDevices.collectAsState()
    val incomingPairRequest by viewModel.incomingPairRequest.collectAsState()
    val pendingPairingTargetNodeId by viewModel.pendingPairingTargetNodeId.collectAsState()
    val isVadSpeaking by viewModel.isVadSpeaking.collectAsState()
    val audioLevel by viewModel.audioLevel.collectAsState()
    val isWalkieLinkActive by viewModel.isWalkieLinkActive.collectAsState()
    val isReceivingAudio by viewModel.isReceivingAudio.collectAsState()
    val remoteAudioLevel by viewModel.remoteAudioLevel.collectAsState()
    val liveAudioLevel = if (isReceivingAudio) remoteAudioLevel else audioLevel
    val connectedPairedCount = pairedDevices.count { it.isConnected }
    val uiState by viewModel.uiState.collectAsState()
    val messageLogs by viewModel.messageLogs.collectAsState()
    val modelPacks by viewModel.modelPacks.collectAsState()
    val modelWarningMessage by viewModel.modelWarningMessage.collectAsState()
    val activePeerLanguage by viewModel.activePeerLanguage.collectAsState()

    var showLanguageSheet by remember { mutableStateOf(false) }
    var languageSearchQuery by remember { mutableStateOf("") }
    val selectedLanguage = uiState.selectedLanguage

    val scrollState = rememberScrollState()

    // Breathing radar aura transition
    val infiniteTransition = rememberInfiniteTransition(label = "walkiePulse")
    val ringScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isWalkieActive) 1.25f else 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isWalkieActive) 800 else 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringScale"
    )
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = if (isWalkieActive) 0.35f else 0.12f,
        targetValue = if (isWalkieActive) 0.85f else 0.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isWalkieActive) 800 else 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringAlpha"
    )

    // Animated rotation for refresh button
    val refreshRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "refreshSpin"
    )

    // Tactile button depression
    val buttonInteractionSource = remember { MutableInteractionSource() }
    val isPressed by buttonInteractionSource.collectIsPressedAsState()
    val buttonPressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = tween(120, easing = FastOutSlowInEasing),
        label = "buttonPressScale"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // =======================================================
        // 1. MISSION TELEMETRY HEADER
        // =======================================================
        SoftMissionHeader(
            mode = "Walkie mesh",
            statusText = if (isWalkieActive) "Team live" else "Standby",
            statusActive = isWalkieActive,
            activeColor = colors.mesh,
            logoRes = R.drawable.img_logo_itrantra
        )

        // =======================================================
        // INCOMING PAIRING REQUEST APPROVAL BANNER
        // =======================================================
        AnimatedVisibility(
            visible = incomingPairRequest != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            val req = incomingPairRequest
            if (req != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(elevation = 8.dp, shape = RoundedCornerShape(22.dp), spotColor = colors.accent.copy(alpha = 0.28f))
                        .clip(RoundedCornerShape(22.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.accent.copy(alpha = 0.45f), RoundedCornerShape(22.dp))
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                SoftIcon(
                                    resId = R.drawable.ic_soft_link,
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier.size(17.dp)
                                )
                                Text(
                                    text = "Pairing request",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.textPrimary
                                )
                            }

                            SoftBadge(
                                text = "Approval needed",
                                containerColor = colors.accentContainer,
                                contentColor = colors.accentContainerText
                            )
                        }

                        Text(
                            text = "${req.fromCallsign} wants to pair with your radio to start sharing voice.",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textSecondary
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { viewModel.rejectPairRequest(req.fromNodeId) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.cardSecondaryBg,
                                    contentColor = colors.textSecondary
                                ),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("Decline", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }

                            Button(
                                onClick = { viewModel.acceptPairRequest(req.fromNodeId) },
                                modifier = Modifier.weight(1.3f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.mesh,
                                    contentColor = colors.onAccent
                                ),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("Accept & pair", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        // =======================================================
        // WARNING / STATUS BANNER (Language Mismatch, Model Packs)
        // =======================================================
        AnimatedVisibility(
            visible = modelWarningMessage != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            val warning = modelWarningMessage
            if (warning != null) {
                val isMismatch = warning.contains("Language Mismatch", ignoreCase = true)
                val peerLangCode = activePeerLanguage
                val peerLangName = peerLangCode?.let { SupportedLanguage.fromCode(it).englishName }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (isMismatch) colors.rescueContainer else colors.accentContainer)
                        .border(
                            width = 1.dp,
                            color = if (isMismatch) colors.rescue.copy(alpha = 0.4f) else colors.accent.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(18.dp)
                        )
                        .padding(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SoftIcon(
                                resId = R.drawable.ic_soft_warning,
                                contentDescription = null,
                                tint = if (isMismatch) colors.rescueContainerText else colors.accentContainerText,
                                modifier = Modifier.size(17.dp)
                            )
                            Text(
                                text = if (isMismatch) "Language mismatch" else "Radio notice",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isMismatch) colors.rescueContainerText else colors.accentContainerText
                            )
                        }

                        Text(
                            text = warning,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary
                        )

                        // If it's a language mismatch, provide a 1-tap button to sync language with the peer!
                        if (isMismatch && peerLangCode != null && peerLangName != null) {
                            Button(
                                onClick = {
                                    viewModel.setSelectedLanguage(SupportedLanguage.fromCode(peerLangCode))
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.rescue,
                                    contentColor = colors.onAccent
                                ),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text(
                                    text = "Switch to $peerLangName",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }

        // =======================================================
        // 2. LANGUAGE SELECTOR CARD
        // =======================================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(elevation = 3.dp, shape = RoundedCornerShape(22.dp), spotColor = colors.shadowTint)
                .clip(RoundedCornerShape(22.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(22.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(colors.accentContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = selectedLanguage.nativeInitial,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.accent
                            )
                        }
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Radio dialect: ",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textSecondary
                                )
                                Text(
                                    text = "${selectedLanguage.englishName} (${selectedLanguage.code.uppercase()})",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.textPrimary
                                )
                            }
                            val isInstalled = modelPacks.firstOrNull {
                                it.iso == selectedLanguage.code || it.languageTag.startsWith(selectedLanguage.code)
                            }?.isInstalled == true
                            Text(
                                text = if (isInstalled) "Neural pack ready" else "Pack not installed · ${selectedLanguage.downloadSizeMb} MB",
                                fontSize = 11.sp,
                                fontWeight = if (isInstalled) FontWeight.Medium else FontWeight.Normal,
                                color = if (isInstalled) colors.meshContainerText else colors.rescueContainerText
                            )
                        }
                    }

                    Surface(
                        onClick = { showLanguageSheet = true },
                        shape = RoundedCornerShape(10.dp),
                        color = colors.cardSecondaryBg,
                        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Switch",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.accent
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            SoftIcon(
                                resId = R.drawable.ic_soft_chevron_down,
                                contentDescription = "Switch Language",
                                tint = colors.accent,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(color = colors.outline.copy(alpha = 0.6f), thickness = 1.dp)

                // 1-Tap Dialect Chips: Hindi, English, Bengali, Marathi
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val quickLangs = listOf(
                        SupportedLanguage.HINDI,
                        SupportedLanguage.ENGLISH,
                        SupportedLanguage.BENGALI,
                        SupportedLanguage.MARATHI
                    )

                    quickLangs.forEach { lang ->
                        val isLangActive = selectedLanguage == lang
                        val isInstalled = modelPacks.firstOrNull {
                            it.iso == lang.code || it.languageTag.startsWith(lang.code)
                        }?.isInstalled == true

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isLangActive) colors.accent else colors.cardSecondaryBg)
                                .border(
                                    width = 1.dp,
                                    color = if (isLangActive) colors.accent else colors.outline,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { viewModel.setSelectedLanguage(lang) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = lang.englishName,
                                    fontSize = 11.sp,
                                    fontWeight = if (isLangActive) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (isLangActive) colors.onAccent else colors.textPrimary
                                )
                                if (isInstalled) {
                                    Text(
                                        text = "Ready",
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isLangActive) colors.onAccent.copy(alpha = 0.8f) else colors.meshContainerText
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // =======================================================
        // 3. STANDBY HERO (When Walkie is Inactive)
        // =======================================================
        if (!isWalkieActive) {
            Box(
                modifier = Modifier
                    .size(250.dp)
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                // Outermost soft breathing pulse halo
                Box(
                    modifier = Modifier
                        .size(238.dp)
                        .scale(ringScale)
                        .alpha(ringAlpha)
                        .clip(CircleShape)
                        .background(Brush.radialGradient(listOf(colors.accent.copy(alpha = 0.16f), Color.Transparent)))
                )

                // Middle radar reference ring
                Box(
                    modifier = Modifier
                        .size(218.dp)
                        .clip(CircleShape)
                        .border(1.dp, colors.outline, CircleShape)
                )

                // SoftHeroDome as the major action
                SoftHeroDome(
                    iconRes = R.drawable.ic_soft_radio,
                    title = "Join walkie",
                    subLabel = "Off-grid team voice",
                    onClick = { viewModel.toggleWalkieMaster(true) },
                    domeSize = 184.dp,
                    containerColor = colors.accent,
                    deepColor = colors.accentDeep,
                    glowColor = colors.accent,
                    contentColor = colors.onAccent,
                    pulsing = false,
                    modifier = Modifier.scale(buttonPressScale)
                )
            }

            // Trust Badges underneath the standby hero
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("100% offline", "Direct mesh voice", "Zero mobile data").forEach { feature ->
                    SoftBadge(
                        text = feature,
                        containerColor = colors.cardSecondaryBg,
                        contentColor = colors.textSecondary
                    )
                }
            }
        }

        // =======================================================
        // 4. ACTIVE MODE: CALL-STYLE CONSOLE & CONTROLS
        // =======================================================
        if (isWalkieActive) {
            // Hero Voice Comms Stage Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(elevation = 4.dp, shape = RoundedCornerShape(24.dp), spotColor = colors.shadowTint)
                    .clip(RoundedCornerShape(24.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.outline, RoundedCornerShape(24.dp))
                    .padding(18.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Top Room Info Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SoftBadge(
                            text = "Team voice room",
                            containerColor = colors.accentContainer,
                            contentColor = colors.accentContainerText
                        )

                        SoftBadge(
                            text = if (isWalkieLinkActive) "Direct link active" else "Searching for team",
                            containerColor = if (isWalkieLinkActive) colors.badgeMintContainer else colors.cardSecondaryBg,
                            contentColor = if (isWalkieLinkActive) colors.badgeMintText else colors.textSecondary
                        )
                    }

                    // Voice Equalizer / PTT Central Disc (Supports HOLD TO TALK via pointerInput)
                    val isLiveTx = (isTransmitting || isVadSpeaking || isPttActive) && !isMicMuted
                    Box(
                        modifier = Modifier
                            .size(136.dp)
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Concentric expanding soundwave rings when speaking/transmitting
                        if (isLiveTx || isReceivingAudio) {
                            Box(
                                modifier = Modifier
                                    .size(136.dp)
                                    .scale(ringScale)
                                    .alpha(ringAlpha)
                                    .clip(CircleShape)
                                    .background(
                                        if (isReceivingAudio) colors.accent.copy(alpha = 0.20f)
                                        else colors.mesh.copy(alpha = 0.22f)
                                    )
                            )
                        }

                        // Central Disc: Push-to-Talk touch gesture handler
                        Box(
                            modifier = Modifier
                                .size(108.dp)
                                .shadow(elevation = 8.dp, shape = CircleShape, spotColor = colors.accent.copy(alpha = 0.28f))
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isMicMuted -> Brush.radialGradient(listOf(colors.sosContainer, colors.sosContainer.copy(alpha = 0.7f)))
                                        isLiveTx -> Brush.radialGradient(listOf(colors.mesh, colors.meshDeep))
                                        isReceivingAudio -> Brush.radialGradient(listOf(colors.accent, colors.accentDeep))
                                        else -> Brush.radialGradient(listOf(colors.accent, colors.accentDeep))
                                    }
                                )
                                .border(
                                    width = 2.dp,
                                    color = when {
                                        isMicMuted -> colors.error
                                        isLiveTx -> colors.onAccent.copy(alpha = 0.7f)
                                        isReceivingAudio -> colors.onAccent.copy(alpha = 0.7f)
                                        else -> colors.onAccent.copy(alpha = 0.55f)
                                    },
                                    shape = CircleShape
                                )
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onPress = {
                                            viewModel.startPtt()
                                            try {
                                                tryAwaitRelease()
                                            } finally {
                                                viewModel.stopPtt()
                                            }
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            SoftIcon(
                                resId = when {
                                    isMicMuted -> R.drawable.ic_soft_mic_off
                                    isLiveTx -> R.drawable.ic_soft_eq
                                    isReceivingAudio -> R.drawable.ic_soft_volume
                                    else -> R.drawable.ic_soft_mic
                                },
                                contentDescription = "Push to Talk",
                                tint = if (isMicMuted) colors.sosContainerText else colors.onAccent,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }

                    // Dynamic Transmission Status Badge
                    SoftBadge(
                        text = when {
                            isMicMuted -> "Mic muted — unmute to speak"
                            isReceivingAudio -> "Receiving live voice…"
                            isLiveTx -> if (isPttActive) "Transmitting (PTT held)" else "Transmitting…"
                            else -> "Hold the disc to talk, or speak freely"
                        },
                        containerColor = when {
                            isMicMuted -> colors.sosContainer
                            isReceivingAudio -> colors.accentContainer
                            isLiveTx -> colors.meshContainer
                            else -> colors.accentContainer
                        },
                        contentColor = when {
                            isMicMuted -> colors.sosContainerText
                            isReceivingAudio -> colors.accentContainerText
                            isLiveTx -> colors.meshContainerText
                            else -> colors.accentContainerText
                        }
                    )

                    // Live 24-Bar Equalizer Audio Spectrum Visualizer
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .height(28.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(24) { index ->
                            val centreDistance = kotlin.math.abs(index - 11.5f) / 11.5f
                            val weight = 1f - centreDistance * 0.7f
                            val heightFraction = when {
                                isMicMuted -> 0.08f
                                isReceivingAudio || isLiveTx -> (0.06f + 0.94f * liveAudioLevel * weight).coerceIn(0.06f, 1f)
                                else -> 0.08f
                            }

                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height((26.dp * heightFraction).coerceAtLeast(3.dp))
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        when {
                                            isMicMuted -> colors.outlineStrong
                                            isReceivingAudio -> colors.accent
                                            isLiveTx -> colors.mesh
                                            else -> colors.accent.copy(alpha = 0.30f)
                                        }
                                    )
                            )
                        }
                    }

                    HorizontalDivider(color = colors.outline, thickness = 1.dp)

                    // =======================================================
                    // CLEAN 3-BUTTON HANDS-FREE CONTROLS
                    // (1. Mute/Unmute Mic, 2. Speaker/Earpiece, 3. Disconnect)
                    // Note: Dictate button removed as requested.
                    // =======================================================
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Mic Mute / Unmute Button
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Surface(
                                onClick = { viewModel.toggleMicMute() },
                                shape = CircleShape,
                                color = if (isMicMuted) colors.sosContainer else colors.cardSecondaryBg,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isMicMuted) colors.error.copy(alpha = 0.35f) else colors.outline
                                ),
                                shadowElevation = 0.dp,
                                modifier = Modifier.size(58.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    SoftIcon(
                                        resId = if (isMicMuted) R.drawable.ic_soft_mic_off else R.drawable.ic_soft_mic,
                                        contentDescription = "Mute Mic",
                                        tint = if (isMicMuted) colors.sosContainerText else colors.textPrimary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            Text(
                                text = if (isMicMuted) "Unmute" else "Mute mic",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isMicMuted) colors.sosContainerText else colors.textSecondary
                            )
                        }

                        // 2. Speakerphone Toggle Button
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Surface(
                                onClick = { viewModel.toggleSpeakerphone() },
                                shape = CircleShape,
                                color = if (isSpeakerphoneOn) colors.accentContainer else colors.cardSecondaryBg,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSpeakerphoneOn) colors.accent.copy(alpha = 0.35f) else colors.outline
                                ),
                                shadowElevation = 0.dp,
                                modifier = Modifier.size(58.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    SoftIcon(
                                        resId = if (isSpeakerphoneOn) R.drawable.ic_soft_volume else R.drawable.ic_soft_hearing,
                                        contentDescription = "Speaker",
                                        tint = if (isSpeakerphoneOn) colors.accent else colors.textPrimary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            Text(
                                text = if (isSpeakerphoneOn) "Speaker" else "Earpiece",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.textSecondary
                            )
                        }

                        // 3. Call-Like Red "End / Disconnect" Button
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Surface(
                                onClick = { viewModel.toggleWalkieMaster(false) },
                                shape = CircleShape,
                                color = Color.Transparent,
                                shadowElevation = 0.dp,
                                modifier = Modifier.size(58.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Brush.radialGradient(listOf(colors.error, colors.sosDeep))),
                                    contentAlignment = Alignment.Center
                                ) {
                                    SoftIcon(
                                        resId = R.drawable.ic_soft_call_end,
                                        contentDescription = "Disconnect Walkie",
                                        tint = colors.onAccent,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }
                            Text(
                                text = "Disconnect",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.error
                            )
                        }
                    }
                }
            }

            // =======================================================
            // LIVE TRANSCRIPTION & VOICE COMMS CARD
            // =======================================================
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(elevation = 3.dp, shape = RoundedCornerShape(22.dp), spotColor = colors.shadowTint)
                    .clip(RoundedCornerShape(22.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.outline, RoundedCornerShape(22.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Live transcription",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary
                            )
                            SoftBadge(
                                text = if (uiState.channelState == RadioChannelState.RECEIVING) "Receiving"
                                else if (isTransmitting || isVadSpeaking || isPttActive) "Transmitting"
                                else "Standby",
                                containerColor = when {
                                    uiState.channelState == RadioChannelState.RECEIVING -> colors.meshContainer
                                    isTransmitting || isVadSpeaking || isPttActive -> colors.accentContainer
                                    else -> colors.cardSecondaryBg
                                },
                                contentColor = when {
                                    uiState.channelState == RadioChannelState.RECEIVING -> colors.meshContainerText
                                    isTransmitting || isVadSpeaking || isPttActive -> colors.accentContainerText
                                    else -> colors.textSecondary
                                }
                            )
                        }

                        if (messageLogs.isNotEmpty()) {
                            Text(
                                text = "${messageLogs.size} logs",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.textTertiary
                            )
                        }
                    }

                    // Current Live Transcript Bar
                    val transcript = uiState.currentTranscript
                    if (transcript.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (uiState.channelState == RadioChannelState.RECEIVING) colors.meshContainer.copy(alpha = 0.5f)
                                    else colors.cardSecondaryBg
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (uiState.channelState == RadioChannelState.RECEIVING) colors.mesh.copy(alpha = 0.4f)
                                    else colors.outline,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .padding(12.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = if (uiState.channelState == RadioChannelState.RECEIVING) "Incoming speech" else "Live caption",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (uiState.channelState == RadioChannelState.RECEIVING) colors.meshContainerText else colors.textSecondary
                                )
                                Text(
                                    text = transcript,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.textPrimary
                                )
                            }
                        }
                    } else if (messageLogs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.cardSecondaryBg)
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Hold the central disc or speak to transmit. Transcriptions sync across all radios automatically.",
                                fontSize = 12.sp,
                                color = colors.textSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    // Recent Message Log (Last 3 messages)
                    if (messageLogs.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            messageLogs.take(3).forEach { msg ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (msg.isLocal) colors.accentContainer.copy(alpha = 0.4f) else colors.meshContainer.copy(alpha = 0.4f))
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (msg.isLocal) "You (${msg.senderCallsign})" else msg.senderCallsign,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (msg.isLocal) colors.accentContainerText else colors.meshContainerText
                                        )
                                        Text(
                                            text = msg.text,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Normal,
                                            color = colors.textPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // =======================================================
            // 5. PAIRED TEAM RADIOS (Revealed on Activation)
            // =======================================================
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(elevation = 3.dp, shape = RoundedCornerShape(22.dp), spotColor = colors.shadowTint)
                    .clip(RoundedCornerShape(22.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.outline, RoundedCornerShape(22.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Paired radios (${connectedPairedCount} of ${pairedDevices.size} connected)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary
                        )
                        SoftBadge(
                            text = "Auto-mesh",
                            containerColor = colors.badgeMintContainer,
                            contentColor = colors.badgeMintText
                        )
                    }

                    if (pairedDevices.isEmpty()) {
                        Text(
                            text = "No paired radios yet. Pair a discovered node below — it reconnects automatically.",
                            fontSize = 12.sp,
                            color = colors.textSecondary
                        )
                    }

                    pairedDevices.forEach { peer ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(colors.cardSecondaryBg)
                                .border(0.5.dp, colors.outline, RoundedCornerShape(16.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(colors.badgeBlueContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    SoftIcon(
                                        resId = when (peer.protocol) {
                                            TransportProtocol.WIFI_DIRECT -> R.drawable.ic_soft_wifi
                                            else -> R.drawable.ic_soft_bluetooth
                                        },
                                        contentDescription = null,
                                        tint = colors.badgeBlueText,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column {
                                    Text(
                                        text = peer.name,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.textPrimary
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val signalDesc = when {
                                            peer.signalStrengthDbm > -65 -> "Strong signal"
                                            peer.signalStrengthDbm > -80 -> "Good signal"
                                            else -> "Fair signal"
                                        }
                                        Text(
                                            text = if (peer.isConnected) {
                                                "Connected · $signalDesc"
                                            } else {
                                                "Paired · Out of range"
                                            },
                                            fontSize = 11.sp,
                                            color = if (peer.isConnected) colors.badgeMintText else colors.textSecondary,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        BatteryIndicator(
                                            batteryPercent = peer.batteryPercent,
                                            heightDp = 10.dp
                                        )
                                    }
                                }
                            }

                            IconButton(
                                onClick = { viewModel.unpairDevice(peer) }
                            ) {
                                SoftIcon(
                                    resId = R.drawable.ic_soft_trash,
                                    contentDescription = "Unpair",
                                    tint = colors.error.copy(alpha = 0.8f),
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }
                }
            }

            // =======================================================
            // 6. AVAILABLE NODES NEARBY (With Refresh Button)
            // =======================================================
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(elevation = 3.dp, shape = RoundedCornerShape(22.dp), spotColor = colors.shadowTint)
                    .clip(RoundedCornerShape(22.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.outline, RoundedCornerShape(22.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Nearby nodes (${discoveredDevices.size})",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary
                        )

                        // Interactive Refresh Button with animated spin during scanning
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.cardSecondaryBg)
                                .clickable { viewModel.refreshDiscoveredNodes() }
                                .padding(horizontal = 9.dp, vertical = 5.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SoftIcon(
                                    resId = R.drawable.ic_soft_refresh,
                                    contentDescription = "Refresh",
                                    tint = colors.accent,
                                    modifier = Modifier
                                        .size(14.dp)
                                        .rotate(if (isRefreshingNodes) refreshRotation else 0f)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (isRefreshingNodes) "Scanning…" else "Rescan",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.accent
                                )
                            }
                        }
                    }

                    if (discoveredDevices.isEmpty()) {
                        Text(
                            text = if (isRefreshingNodes) {
                                "Scanning for BLE + Wi-Fi Direct nodes in range…"
                            } else {
                                "No nodes in range yet. Both phones must have Walkie mesh active."
                            },
                            fontSize = 12.sp,
                            color = colors.textSecondary
                        )
                    }

                    discoveredDevices.forEach { peer ->
                        val peerNodeId = peer.id.removePrefix("node-").removePrefix("ble-").removePrefix("p2p-").toLongOrNull()
                        val isPending = pendingPairingTargetNodeId != null && pendingPairingTargetNodeId == peerNodeId

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(colors.cardSecondaryBg)
                                .border(0.5.dp, colors.outline, RoundedCornerShape(16.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = peer.name,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.textPrimary
                                )
                                val signalDesc = when {
                                    peer.signalStrengthDbm > -65 -> "Strong signal"
                                    peer.signalStrengthDbm > -80 -> "Good signal"
                                    else -> "Fair signal"
                                }
                                Text(
                                    text = "Radio mesh · $signalDesc",
                                    fontSize = 11.sp,
                                    color = colors.textSecondary
                                )
                            }

                            Button(
                                onClick = { viewModel.sendPairRequest(peer) },
                                enabled = !isPending,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isPending) colors.outline else colors.accent,
                                    contentColor = colors.onAccent,
                                    disabledContainerColor = colors.cardSecondaryBg,
                                    disabledContentColor = colors.textSecondary
                                ),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                if (isPending) {
                                    Text("Requesting…", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                } else {
                                    SoftIcon(
                                        resId = R.drawable.ic_soft_add,
                                        contentDescription = null,
                                        tint = colors.onAccent,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Pair", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
    }

    // =======================================================
    // FULL DIALECT SELECTION BOTTOM SHEET
    // =======================================================
    if (showLanguageSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        SoftSheetShell(
            title = "Radio language",
            subtitle = "Pick the language for voice and transcription",
            onDismiss = { showLanguageSheet = false }
        ) {
            OutlinedTextField(
                value = languageSearchQuery,
                onValueChange = { languageSearchQuery = it },
                placeholder = { Text("Search language or dialect…", fontSize = 13.sp, color = colors.textTertiary) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = SoftFieldShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.outline,
                    focusedContainerColor = colors.cardSecondaryBg,
                    unfocusedContainerColor = colors.cardSecondaryBg
                )
            )

            Spacer(modifier = Modifier.height(4.dp))

            val filtered = remember(languageSearchQuery) {
                if (languageSearchQuery.isBlank()) SupportedLanguage.entries
                else {
                    val q = languageSearchQuery.trim().lowercase()
                    SupportedLanguage.entries.filter {
                        it.englishName.lowercase().contains(q) ||
                            it.nativeName.lowercase().contains(q) ||
                            it.code.lowercase().contains(q)
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered) { lang ->
                    val isSelected = selectedLanguage == lang
                    val isInstalled = modelPacks.firstOrNull {
                        it.iso == lang.code || it.languageTag.startsWith(lang.code)
                    }?.isInstalled == true

                    Surface(
                        onClick = {
                            viewModel.setSelectedLanguage(lang)
                            showLanguageSheet = false
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) colors.accentContainer else colors.cardSecondaryBg,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) colors.accent.copy(alpha = 0.5f) else colors.outline
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "${lang.englishName} (${lang.nativeName})",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = if (isSelected) colors.accentContainerText else colors.textPrimary
                                )
                                Text(
                                    text = if (isInstalled) "Installed & ready" else "Neural pack · ${lang.downloadSizeMb} MB",
                                    fontSize = 11.sp,
                                    color = if (isInstalled) colors.meshContainerText else colors.textSecondary
                                )
                            }
                            if (isSelected) {
                                SoftStatusDot(color = colors.accent, dotSize = 8.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}
