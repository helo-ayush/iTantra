package com.itantra.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.app.R
import com.itantra.app.modelhub.LanguageModelPack
import com.itantra.app.modelhub.ModelDownloadState
import com.itantra.app.ui.components.soft.SoftBadge
import com.itantra.app.ui.components.soft.SoftIcon
import com.itantra.app.ui.theme.MinimalColorsInstance
import com.itantra.app.ui.theme.minimalColors
import com.itantra.app.viewmodel.MissionControlViewModel

/**
 * Settings:
 * 1. Device callsign & identity
 * 2. Appearance (light · dark · system) + keep screen awake
 * 3. On-device AI model packs + neural translation
 * 4. Mesh & radio tuning
 * 5. Voice & audio tuning
 * 6. Privacy, map cache & sensor health
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: MissionControlViewModel,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.minimalColors
    val uiState by viewModel.uiState.collectAsState()
    val callsign by viewModel.callsign.collectAsState()
    val modelPacks by viewModel.modelPacks.collectAsState()
    val txPower by viewModel.txPower.collectAsState()
    val beaconInterval by viewModel.beaconInterval.collectAsState()
    val meshHopLimit by viewModel.meshHopLimit.collectAsState()
    val vadSensitivity by viewModel.vadSensitivity.collectAsState()
    val noiseSuppressionEnabled by viewModel.noiseSuppressionEnabled.collectAsState()
    val keepScreenAwake by viewModel.keepScreenAwake.collectAsState()
    val zeroLogPrivacy by viewModel.zeroLogPrivacy.collectAsState()
    val mapCacheSizeMb by viewModel.mapCacheSizeMb.collectAsState()

    val installedPacks = modelPacks.filter { it.isInstalled }
    val installedStorageMb = installedPacks.sumOf { it.sizeMb }

    val isTranslationInstalled by viewModel.isTranslationModelInstalled.collectAsState()
    val translationDownloadState by viewModel.translationDownloadState.collectAsState()
    var testTranslationInput by remember { mutableStateOf("हम मलबे में दबे हैं") }
    var testTranslationOutput by remember { mutableStateOf("") }

    // State for delete confirmation dialog
    var modelToDelete by remember { mutableStateOf<LanguageModelPack?>(null) }
    var showWipeConfirmDialog by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(scrollState)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ==========================================
        // 1. DEVICE IDENTITY & CALLSIGN
        // ==========================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(22.dp))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(colors.badgeBlueContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        SoftIcon(
                            resId = R.drawable.ic_soft_id,
                            contentDescription = null,
                            tint = colors.badgeBlueText,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Radio callsign & node",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.2.sp,
                            color = colors.textSecondary
                        )
                        Text(
                            text = callsign,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "MAC: 00:1B:44:11:3A:B7 · Full-duplex",
                            fontSize = 11.sp,
                            color = colors.textTertiary
                        )
                    }
                }

                SoftBadge(
                    text = "Transceiver",
                    containerColor = colors.badgeMintContainer,
                    contentColor = colors.badgeMintText
                )
            }
        }

        // ==========================================
        // 2. APPEARANCE & THEME SWITCHER
        // ==========================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(22.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SoftIcon(
                        resId = R.drawable.ic_soft_moon,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(17.dp)
                    )
                    Text(
                        text = "Appearance",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary
                    )
                }

                // 3-Way Segmented Theme Switcher
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThemeOptionButton(
                        title = "Light",
                        iconRes = R.drawable.ic_soft_sun,
                        isSelected = uiState.themeMode == "light",
                        onClick = { viewModel.setThemeMode("light") },
                        modifier = Modifier.weight(1f),
                        colors = colors
                    )

                    ThemeOptionButton(
                        title = "Dark",
                        iconRes = R.drawable.ic_soft_moon,
                        isSelected = uiState.themeMode == "dark",
                        onClick = { viewModel.setThemeMode("dark") },
                        modifier = Modifier.weight(1f),
                        colors = colors
                    )

                    ThemeOptionButton(
                        title = "System",
                        iconRes = R.drawable.ic_soft_sparkle,
                        isSelected = uiState.themeMode == "system",
                        onClick = { viewModel.setThemeMode("system") },
                        modifier = Modifier.weight(1f),
                        colors = colors
                    )
                }

                HorizontalDivider(color = colors.outline, thickness = 1.dp)

                // Screen Awake Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Keep screen awake during missions",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "Prevents standby while SOS or Walkie is active",
                            fontSize = 12.sp,
                            color = colors.textSecondary
                        )
                    }
                    Switch(
                        checked = keepScreenAwake,
                        onCheckedChange = { viewModel.setKeepScreenAwake(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = colors.accent
                        )
                    )
                }
            }
        }

        // ==========================================
        // 3. ON-DEVICE AI MODEL PACKS (REAL OFFLINE HUB)
        // ==========================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(22.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(colors.badgePurpleContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        SoftIcon(
                            resId = R.drawable.ic_soft_cpu,
                            contentDescription = null,
                            tint = colors.badgePurpleText,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "On-device AI models",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "100% offline · Download packs on demand",
                            fontSize = 12.sp,
                            color = colors.textSecondary
                        )
                    }
                }

                // Storage usage breakdown bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.cardSecondaryBg)
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SoftIcon(
                                    resId = R.drawable.ic_soft_database,
                                    contentDescription = null,
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Model storage: ",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textSecondary
                                )
                                Text(
                                    text = "${formatSizeMb(installedStorageMb)} MB",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.textPrimary
                                )
                            }
                            Text(
                                text = "${installedPacks.size} installed",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.badgeMintText
                            )
                        }

                        // Progress relative to a 2048 MB tactical storage quota
                        LinearProgressIndicator(
                            progress = { (installedStorageMb / MODEL_STORAGE_QUOTA_MB).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape),
                            color = colors.accent,
                            trackColor = colors.outline,
                        )
                    }
                }

                HorizontalDivider(color = colors.outline, thickness = 1.dp)

                // Model pack list
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    modelPacks.forEach { pack ->
                        LanguagePackRow(
                            pack = pack,
                            colors = colors,
                            onDownload = { viewModel.downloadModel(pack.languageTag) },
                            onPauseDownload = { viewModel.pauseModelDownload(pack.languageTag) },
                            onResumeDownload = { viewModel.downloadModel(pack.languageTag) },
                            onCancelDownload = { viewModel.cancelModelDownload(pack.languageTag) },
                            onDeleteClick = { modelToDelete = pack }
                        )
                    }
                }
            }
        }

        // ==========================================
        // 3B. NEURAL TRANSLATION ENGINES (CROSS-LINGUAL MESH)
        // ==========================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(22.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(colors.accentContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        SoftIcon(
                            resId = R.drawable.ic_soft_globe,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Neural translation",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "Cross-lingual mesh (Hindi ⟷ English)",
                            fontSize = 12.sp,
                            color = colors.textSecondary
                        )
                    }
                    // Status Badge
                    SoftBadge(
                        text = when {
                            isTranslationInstalled -> "Installed"
                            translationDownloadState is ModelDownloadState.Downloading -> "Downloading"
                            else -> "Not downloaded"
                        },
                        containerColor = when {
                            isTranslationInstalled -> colors.badgeMintContainer
                            translationDownloadState is ModelDownloadState.Downloading -> colors.accentContainer
                            else -> colors.cardSecondaryBg
                        },
                        contentColor = when {
                            isTranslationInstalled -> colors.badgeMintText
                            translationDownloadState is ModelDownloadState.Downloading -> colors.accent
                            else -> colors.textSecondary
                        }
                    )
                }

                // Description Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.cardSecondaryBg)
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Model: Google ML Kit Neural NMT (hi ↔ en)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "On-device",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.textSecondary
                            )
                        }
                        Text(
                            text = "Powered by Google ML Kit on-device neural machine translation. Translates full sentences offline between Hindi and English. If neither device has this pack installed, cross-lingual voice turns will pause with a prompt.",
                            fontSize = 12.sp,
                            color = colors.textSecondary,
                            lineHeight = 16.sp
                        )

                        // Download Progress bar if active
                        if (translationDownloadState is ModelDownloadState.Downloading) {
                            val state = translationDownloadState as ModelDownloadState.Downloading
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(CircleShape),
                                color = colors.accent,
                                trackColor = colors.outline
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Downloading offline neural weights…",
                                    fontSize = 10.sp,
                                    color = colors.accent
                                )
                                Text(
                                    text = "${(state.progress * 100).toInt()}%",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.accent
                                )
                            }
                        }
                    }
                }

                // Action Button Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isTranslationInstalled) {
                        OutlinedButton(
                            onClick = { viewModel.deleteTranslationModel() },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = colors.error
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.error.copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            SoftIcon(
                                resId = R.drawable.ic_soft_trash,
                                contentDescription = null,
                                tint = colors.error,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Remove model", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    } else if (translationDownloadState is ModelDownloadState.Downloading) {
                        Button(
                            onClick = { },
                            enabled = false,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("Downloading…", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Button(
                            onClick = { viewModel.downloadTranslationModel() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.accent,
                                contentColor = colors.onAccent
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            SoftIcon(
                                resId = R.drawable.ic_soft_download,
                                contentDescription = null,
                                tint = colors.onAccent,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Download translation model", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                HorizontalDivider(color = colors.outline, thickness = 1.dp)

                // -------------------------------------------------------------
                // TEST SANDBOX: Instant offline verification
                // -------------------------------------------------------------
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SoftIcon(
                            resId = R.drawable.ic_soft_sparkle,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Offline translation test",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary
                        )
                    }

                    Text(
                        text = "Tap any emergency phrase to test fully offline bidirectional translation:",
                        fontSize = 12.sp,
                        color = colors.textSecondary
                    )

                    // Quick test pills
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf(
                            "हम मलबे में दबे हैं",
                            "मदद चाहिए",
                            "Rescue team is on the way",
                            "Are you okay?",
                            "पानी चाहिए",
                            "Severely injured"
                        ).forEach { sample ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(colors.cardSecondaryBg)
                                    .border(1.dp, colors.outline, RoundedCornerShape(10.dp))
                                    .clickable {
                                        testTranslationInput = sample
                                        val isHindi = sample.any { it.code in 0x0900..0x097F }
                                        testTranslationOutput = if (isHindi) {
                                            viewModel.translateEmergencyText(sample, "hi", "en")
                                        } else {
                                            viewModel.translateEmergencyText(sample, "en", "hi")
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = sample,
                                    fontSize = 11.sp,
                                    color = colors.textPrimary
                                )
                            }
                        }
                    }

                    // Live Test Result Card
                    if (testTranslationOutput.isNotBlank() || testTranslationInput.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.cardSecondaryBg)
                                .border(1.dp, colors.accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                                .padding(10.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val isInputHindi = testTranslationInput.any { it.code in 0x0900..0x097F }
                                val fromTag = if (isInputHindi) "Hindi" else "English"
                                val toTag = if (isInputHindi) "English" else "Hindi"

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "$fromTag → $toTag",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.accent
                                    )
                                    Text(
                                        text = "Google ML Kit Neural NMT",
                                        fontSize = 10.sp,
                                        color = colors.badgeMintText
                                    )
                                }
                                Text(
                                    text = "Input: \"$testTranslationInput\"",
                                    fontSize = 12.sp,
                                    color = colors.textSecondary
                                )
                                Text(
                                    text = "Output: \"${if (testTranslationOutput.isNotBlank()) testTranslationOutput else viewModel.translateEmergencyText(testTranslationInput, if (isInputHindi) "hi" else "en", if (isInputHindi) "en" else "hi")}\"",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.badgeMintText
                                )
                            }
                        }
                    }
                }
            }
        }

        // ==========================================
        // 4. DISASTER MESH & RADIO TUNING
        // ==========================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(22.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SoftIcon(
                        resId = R.drawable.ic_soft_radio,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(17.dp)
                    )
                    Text(
                        text = "Mesh & radio tuning",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary
                    )
                }

                // Setting 1: Radio Transmission Range
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Radio TX power & range",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary
                        )
                        Text(
                            text = txPower,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.accent
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Low (100m)", "Balanced (500m)", "Max (1.5km)").forEach { option ->
                            val isSel = txPower.startsWith(option.substringBefore(" "))
                            SegmentedOptionChip(
                                label = option,
                                isSelected = isSel,
                                onClick = { viewModel.setTxPower(option) },
                                modifier = Modifier.weight(1f),
                                colors = colors
                            )
                        }
                    }
                }

                // Setting 2: Beacon Ping Frequency
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Beacon broadcast frequency",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "Every ${beaconInterval}s",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.accent
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(15 to "15s (Rapid)", 30 to "30s (Default)", 60 to "60s (Saver)").forEach { (sec, label) ->
                            val isSel = beaconInterval == sec
                            SegmentedOptionChip(
                                label = label,
                                isSelected = isSel,
                                onClick = { viewModel.setBeaconInterval(sec) },
                                modifier = Modifier.weight(1f),
                                colors = colors
                            )
                        }
                    }
                }

                // Setting 3: Mesh Hop Limit
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Mesh relay multi-hop limit",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "$meshHopLimit hops",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.accent
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(3 to "3 Hops", 5 to "5 Hops (Rec)", 7 to "7 Hops (Deep)").forEach { (hops, label) ->
                            val isSel = meshHopLimit == hops
                            SegmentedOptionChip(
                                label = label,
                                isSelected = isSel,
                                onClick = { viewModel.setMeshHopLimit(hops) },
                                modifier = Modifier.weight(1f),
                                colors = colors
                            )
                        }
                    }
                }
            }
        }

        // ==========================================
        // 5. VOICE & SENSOR AUDIO TUNING
        // ==========================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(22.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SoftIcon(
                        resId = R.drawable.ic_soft_eq,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(17.dp)
                    )
                    Text(
                        text = "Voice & audio",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary
                    )
                }

                // VAD Sensitivity
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Hands-free VAD sensitivity",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary
                        )
                        Text(
                            text = vadSensitivity,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.accent
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Low Noise", "Balanced", "High Sensitivity").forEach { level ->
                            val isSel = vadSensitivity.startsWith(level.substringBefore(" "))
                            SegmentedOptionChip(
                                label = level,
                                isSelected = isSel,
                                onClick = { viewModel.setVadSensitivity(level) },
                                modifier = Modifier.weight(1f),
                                colors = colors
                            )
                        }
                    }
                }

                // Noise Suppression Filter Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AI noise suppression filter",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "Filters heavy wind, rain, and rubble noise",
                            fontSize = 12.sp,
                            color = colors.textSecondary
                        )
                    }
                    Switch(
                        checked = noiseSuppressionEnabled,
                        onCheckedChange = { viewModel.setNoiseSuppressionEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = colors.accent
                        )
                    )
                }

                // Force Max Volume Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Max volume on SOS",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "Overrides silent mode during emergency intercom",
                            fontSize = 12.sp,
                            color = colors.textSecondary
                        )
                    }
                    Switch(
                        checked = uiState.forceMaxVolumeAlerts,
                        onCheckedChange = { viewModel.setForceMaxVolumeAlerts(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = colors.accent
                        )
                    )
                }

                // Battery Saver Duty-Cycle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Battery saver duty-cycling",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "Paces BLE scanning when stationary to save battery",
                            fontSize = 12.sp,
                            color = colors.textSecondary
                        )
                    }
                    Switch(
                        checked = uiState.isLowPowerListeningEnabled,
                        onCheckedChange = { viewModel.setLowPowerListeningEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = colors.accent
                        )
                    )
                }
            }
        }

        // ==========================================
        // 6. TACTICAL PRIVACY, STORAGE & SENSORS
        // ==========================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(22.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SoftIcon(
                        resId = R.drawable.ic_soft_lock,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(17.dp)
                    )
                    Text(
                        text = "Privacy & sensors",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary
                    )
                }

                // Zero-Log Privacy Mode
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Zero-log privacy",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "Keeps voice and message buffers in volatile RAM only",
                            fontSize = 12.sp,
                            color = colors.textSecondary
                        )
                    }
                    Switch(
                        checked = zeroLogPrivacy,
                        onCheckedChange = { viewModel.setZeroLogPrivacy(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = colors.accent
                        )
                    )
                }

                // Offline Map Tile Cache Action
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Offline radar map cache",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary
                        )
                        Text(
                            text = if (mapCacheSizeMb > 0) "$mapCacheSizeMb MB cached tiles" else "Cache cleared",
                            fontSize = 12.sp,
                            color = colors.textSecondary
                        )
                    }
                    OutlinedButton(
                        onClick = { viewModel.clearMapCache() },
                        enabled = mapCacheSizeMb > 0,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        SoftIcon(
                            resId = R.drawable.ic_soft_eraser,
                            contentDescription = null,
                            tint = colors.textPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Clear", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                HorizontalDivider(color = colors.outline, thickness = 1.dp)

                // Hardware Sensor Diagnostics
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Hardware sensor health",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SensorStatusBadge("GPS 3D Fix", "Active", colors.badgeMintContainer, colors.badgeMintText)
                        SensorStatusBadge("Compass / Gyro", "Calibrated", colors.badgeMintContainer, colors.badgeMintText)
                        SensorStatusBadge("BLE Mesh", "Advertising", colors.badgeMintContainer, colors.badgeMintText)
                        SensorStatusBadge("Wi-Fi Direct", "Ready", colors.badgeBlueContainer, colors.badgeBlueText)
                    }
                }

                // Emergency Wipe Action
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.sosContainer)
                        .clickable { showWipeConfirmDialog = true }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SoftIcon(
                            resId = R.drawable.ic_soft_warning,
                            contentDescription = null,
                            tint = colors.sosContainerText,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Emergency data wipe",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.sosContainerText
                        )
                    }
                }
            }
        }

        // App Version Footer
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "iTantra mesh · Version 2.4.0",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textSecondary
                )
                Text(
                    text = "Zero cloud · Everything stays on your device",
                    fontSize = 11.sp,
                    color = colors.textTertiary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    // =========================================================================
    // DIALOG 1: DELETE MODEL CONFIRMATION POPUP
    // =========================================================================
    if (modelToDelete != null) {
        val target = modelToDelete!!
        AlertDialog(
            onDismissRequest = { modelToDelete = null },
            icon = {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(colors.sosContainer),
                    contentAlignment = Alignment.Center
                ) {
                    SoftIcon(
                        resId = R.drawable.ic_soft_trash,
                        contentDescription = null,
                        tint = colors.sosContainerText,
                        modifier = Modifier.size(20.dp)
                    )
                }
            },
            title = {
                Text(
                    text = "Delete language pack?",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Remove \"${target.name}\" (${target.languageTag}) from offline storage?",
                        fontSize = 13.sp,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "This frees up ${formatSizeMb(target.sizeMb)} MB. Offline speech for ${target.name} will be unavailable until re-downloaded.",
                        fontSize = 12.sp,
                        color = colors.textSecondary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteModel(target.languageTag)
                        modelToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Delete", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { modelToDelete = null }
                ) {
                    Text("Cancel", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.textSecondary)
                }
            },
            modifier = Modifier
                .border(1.dp, colors.outline, RoundedCornerShape(22.dp)),
            containerColor = colors.surface,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(22.dp)
        )
    }

    // =========================================================================
    // DIALOG 2: EMERGENCY WIPE CONFIRMATION
    // =========================================================================
    if (showWipeConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showWipeConfirmDialog = false },
            icon = {
                SoftIcon(
                    resId = R.drawable.ic_soft_warning,
                    contentDescription = null,
                    tint = colors.error,
                    modifier = Modifier.size(26.dp)
                )
            },
            title = {
                Text(
                    text = "Wipe local data?",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary
                )
            },
            text = {
                Text(
                    text = "This immediately clears all local message history and cached map tiles, and restores default identity parameters.",
                    fontSize = 13.sp,
                    color = colors.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.emergencyWipe()
                        showWipeConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Wipe data", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showWipeConfirmDialog = false }
                ) {
                    Text("Cancel", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.textSecondary)
                }
            },
            modifier = Modifier
                .border(1.dp, colors.outline, RoundedCornerShape(22.dp)),
            containerColor = colors.surface,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(22.dp)
        )
    }
}

/**
 * 3-Way Theme Switcher Option Pill
 */
@Composable
private fun ThemeOptionButton(
    title: String,
    iconRes: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: com.itantra.app.ui.theme.MinimalColors
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) colors.accentContainer else colors.cardSecondaryBg)
            .border(
                width = 1.dp,
                color = if (isSelected) colors.accent.copy(alpha = 0.55f) else Color.Transparent,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            SoftIcon(
                resId = iconRes,
                contentDescription = title,
                tint = if (isSelected) colors.accent else colors.textSecondary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (isSelected) colors.accent else colors.textSecondary
            )
        }
    }
}

/** On-device model storage quota (MB) used for the storage bar. */
private const val MODEL_STORAGE_QUOTA_MB = 2048.0

private fun formatSizeMb(sizeMb: Double): String =
    String.format(java.util.Locale.US, "%.1f", sizeMb)

/**
 * Single language pack row in the ON-DEVICE AI MODELS list. The trailing
 * action adapts to the pack's live download state.
 */
@Composable
private fun LanguagePackRow(
    pack: LanguageModelPack,
    colors: com.itantra.app.ui.theme.MinimalColors,
    onDownload: () -> Unit,
    onPauseDownload: () -> Unit,
    onResumeDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val state = pack.downloadState

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.cardSecondaryBg)
            .border(0.5.dp, colors.outline, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Script badge
                        SoftBadge(
                            text = pack.script,
                            containerColor = colors.badgeBlueContainer,
                            contentColor = colors.badgeBlueText
                        )

                        // Language tag
                        Text(
                            text = pack.languageTag.uppercase(),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textTertiary
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = pack.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary
                    )

                    Text(
                        text = "STT + TTS engine · ${formatSizeMb(pack.sizeMb)} MB",
                        fontSize = 11.sp,
                        color = colors.textSecondary
                    )
                }

                when {
                    pack.isInstalled -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Installed",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.badgeMintText
                        )
                        IconButton(
                            onClick = onDeleteClick,
                            modifier = Modifier.size(36.dp)
                        ) {
                            SoftIcon(
                                resId = R.drawable.ic_soft_trash,
                                contentDescription = "Delete ${pack.name}",
                                tint = colors.error,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }

                    state is ModelDownloadState.Downloading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${(state.progress * 100).toInt()}%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.accent
                        )
                        IconButton(onClick = onPauseDownload, modifier = Modifier.size(32.dp)) {
                            SoftIcon(
                                resId = R.drawable.ic_soft_pause,
                                contentDescription = "Pause ${pack.name}",
                                tint = colors.textSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        IconButton(onClick = onCancelDownload, modifier = Modifier.size(32.dp)) {
                            SoftIcon(
                                resId = R.drawable.ic_soft_close,
                                contentDescription = "Cancel ${pack.name}",
                                tint = colors.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    state is ModelDownloadState.Paused -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Paused · ${(state.progress * 100).toInt()}%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.badgePurpleText
                        )
                        IconButton(onClick = onResumeDownload, modifier = Modifier.size(32.dp)) {
                            SoftIcon(
                                resId = R.drawable.ic_soft_play,
                                contentDescription = "Resume ${pack.name}",
                                tint = colors.accent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        IconButton(onClick = onCancelDownload, modifier = Modifier.size(32.dp)) {
                            SoftIcon(
                                resId = R.drawable.ic_soft_close,
                                contentDescription = "Cancel ${pack.name}",
                                tint = colors.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    state is ModelDownloadState.Verifying -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Verifying",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.accent
                        )
                        IconButton(onClick = onCancelDownload, modifier = Modifier.size(32.dp)) {
                            SoftIcon(
                                resId = R.drawable.ic_soft_close,
                                contentDescription = "Cancel ${pack.name}",
                                tint = colors.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    state is ModelDownloadState.Extracting -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Extracting",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.accent
                        )
                        IconButton(onClick = onCancelDownload, modifier = Modifier.size(32.dp)) {
                            SoftIcon(
                                resId = R.drawable.ic_soft_close,
                                contentDescription = "Cancel ${pack.name}",
                                tint = colors.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    state is ModelDownloadState.Error -> Button(
                        onClick = onDownload,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Retry", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }

                    else -> Button(
                        onClick = onDownload,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Download", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            when (state) {
                is ModelDownloadState.Downloading -> {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape),
                        color = colors.accent,
                        trackColor = colors.outline
                    )
                    Text(
                        text = "Downloading · ${formatSizeMb(state.progressBytes / (1024.0 * 1024.0))} / ${formatSizeMb(state.totalBytes / (1024.0 * 1024.0))} MB",
                        fontSize = 10.sp,
                        color = colors.textSecondary
                    )
                }
                is ModelDownloadState.Paused -> {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape),
                        color = colors.badgePurpleText,
                        trackColor = colors.outline
                    )
                    Text(
                        text = "Paused · ${(state.progress * 100).toInt()}%",
                        fontSize = 10.sp,
                        color = colors.textSecondary
                    )
                }
                is ModelDownloadState.Error -> {
                    Text(
                        text = state.message,
                        fontSize = 10.sp,
                        color = colors.error
                    )
                }
                else -> Unit
            }
        }
    }
}

/**
 * Segmented Option Chip
 */
@Composable
private fun SegmentedOptionChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: com.itantra.app.ui.theme.MinimalColors
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) colors.accentContainer else colors.cardSecondaryBg)
            .border(
                width = 1.dp,
                color = if (isSelected) colors.accent.copy(alpha = 0.55f) else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) colors.accent else colors.textSecondary,
            maxLines = 1
        )
    }
}

/**
 * Diagnostic Sensor Status Pill
 */
@Composable
private fun SensorStatusBadge(
    sensorName: String,
    status: String,
    bgColor: Color,
    textColor: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(textColor)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = "$sensorName: $status",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = textColor
            )
        }
    }
}
