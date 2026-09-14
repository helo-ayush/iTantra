package com.itantra.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.app.R
import com.itantra.app.model.SupportedLanguage
import com.itantra.app.ui.components.soft.SoftBadge
import com.itantra.app.ui.components.soft.SoftButton
import com.itantra.app.ui.components.soft.SoftCard
import com.itantra.app.ui.components.soft.SoftIcon
import com.itantra.app.ui.components.soft.SoftSheetShell
import com.itantra.app.ui.theme.MinimalColorsInstance
import com.itantra.app.ui.theme.SoftFieldShape
import com.itantra.app.ui.theme.minimalColors
import com.itantra.app.viewmodel.MissionControlViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    viewModel: MissionControlViewModel,
    onContinue: () -> Unit
) {
    val colors = MaterialTheme.minimalColors
    val scrollState = rememberScrollState()

    // Form States
    var name by remember { mutableStateOf("") }
    var ageText by remember { mutableStateOf("") }
    var selectedGender by remember { mutableStateOf("Male") }
    var selectedLanguageCodes by remember { mutableStateOf(setOf("hi", "en")) }
    var selectedRelation by remember { mutableStateOf("Parent") }
    var relativePhone by remember { mutableStateOf("") }

    // UI state dropdown toggles
    var isGenderDropdownOpen by remember { mutableStateOf(false) }
    var isRelationDropdownOpen by remember { mutableStateOf(false) }
    var isLanguageSheetOpen by remember { mutableStateOf(false) }
    var languageSearchQuery by remember { mutableStateOf("") }
    // Name + emergency contact live behind a collapsed OPTIONAL section.
    var isOptionalExpanded by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Validation
    var showError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    val genderOptions = listOf("Male", "Female", "Other", "Prefer not to say")
    val relationOptions = listOf("Parent", "Father", "Mother", "Spouse", "Sibling", "Child", "Guardian", "Friend", "Other")

    val buttonInteraction = remember { MutableInteractionSource() }
    val isPressed by buttonInteraction.collectIsPressedAsState()
    val buttonScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = tween(120, easing = FastOutSlowInEasing),
        label = "buttonScale"
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // =======================================================
            // 1. HERO BRANDING
            // =======================================================
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(colors.accentContainer)
                    .border(1.dp, colors.accent.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                SoftIcon(
                    resId = R.drawable.ic_soft_shield,
                    contentDescription = "iTantra Shield",
                    tint = colors.accent,
                    modifier = Modifier.size(32.dp)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SoftBadge(
                    text = "Offline emergency mesh",
                    containerColor = colors.accentContainer,
                    contentColor = colors.accentContainerText
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Set up your identity",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.4).sp,
                    color = colors.textPrimary
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Tell the mesh who you are, so nearby teams can recognise and help you in an emergency.",
                    fontSize = 14.sp,
                    color = colors.textSecondary,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(horizontal = 10.dp),
                    textAlign = TextAlign.Center
                )
            }

            // =======================================================
            // 2. REQUIRED SECTION — preferred language, age, gender
            // =======================================================
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Required",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.3.sp,
                        color = colors.textSecondary
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(1.dp)
                            .background(colors.outline)
                    )
                }

                // Preferred language — full-width selector card
                SoftCard(
                    onClick = { isLanguageSheetOpen = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(elevation = 4.dp, shape = RoundedCornerShape(22.dp), spotColor = colors.shadowTint)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SoftIcon(
                                resId = R.drawable.ic_soft_globe,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(17.dp)
                            )
                            Box(Modifier.width(8.dp))
                            Text(
                                text = "Preferred languages",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            SoftIcon(
                                resId = R.drawable.ic_soft_chevron_down,
                                contentDescription = "Open language picker",
                                tint = colors.textTertiary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            selectedLanguageCodes.take(4).forEach { code ->
                                val lang = SupportedLanguage.fromCode(code)
                                SoftBadge(
                                    text = lang.nativeName,
                                    containerColor = colors.badgeMintContainer,
                                    contentColor = colors.badgeMintText
                                )
                            }
                            if (selectedLanguageCodes.size > 4) {
                                SoftBadge(
                                    text = "+${selectedLanguageCodes.size - 4}",
                                    containerColor = colors.cardSecondaryBg,
                                    contentColor = colors.textSecondary
                                )
                            }
                        }

                        Text(
                            text = "Choose the languages you speak",
                            fontSize = 12.sp,
                            color = colors.textSecondary
                        )
                    }
                }

                // Age + Gender — equal-width cards on one row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Age
                    SoftCard(modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "Age",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.3.sp,
                                color = colors.textSecondary
                            )
                            OutlinedTextField(
                                value = ageText,
                                onValueChange = { input ->
                                    if (input.length <= 3 && input.all { it.isDigit() }) {
                                        ageText = input
                                        if (showError) showError = false
                                    }
                                },
                                placeholder = { Text("24", fontSize = 14.sp, color = colors.textTertiary) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = colors.accent,
                                    unfocusedBorderColor = colors.outline,
                                    focusedContainerColor = colors.cardSecondaryBg,
                                    unfocusedContainerColor = colors.cardSecondaryBg,
                                    focusedTextColor = colors.textPrimary,
                                    unfocusedTextColor = colors.textPrimary
                                ),
                                shape = SoftFieldShape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                            )
                        }
                    }

                    // Gender
                    SoftCard(modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "Gender",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.3.sp,
                                color = colors.textSecondary
                            )
                            Box {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(54.dp)
                                        .clip(SoftFieldShape)
                                        .background(colors.cardSecondaryBg)
                                        .border(1.dp, colors.outline, SoftFieldShape)
                                        .clickable { isGenderDropdownOpen = true }
                                        .padding(horizontal = 14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = selectedGender,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    SoftIcon(
                                        resId = R.drawable.ic_soft_chevron_down,
                                        contentDescription = "Open gender options",
                                        tint = colors.textTertiary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                DropdownMenu(
                                    expanded = isGenderDropdownOpen,
                                    onDismissRequest = { isGenderDropdownOpen = false },
                                    modifier = Modifier.background(colors.surface)
                                ) {
                                    genderOptions.forEach { opt ->
                                        DropdownMenuItem(
                                            text = { Text(opt, fontSize = 13.sp, color = colors.textPrimary) },
                                            onClick = {
                                                selectedGender = opt
                                                isGenderDropdownOpen = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // =======================================================
            // 3. OPTIONAL SECTION — collapsed by default
            // =======================================================
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Collapsed header (tap to expand)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.outline, RoundedCornerShape(18.dp))
                        .clickable { isOptionalExpanded = !isOptionalExpanded }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SoftBadge(
                        text = "Optional",
                        containerColor = colors.badgeBlueContainer,
                        contentColor = colors.badgeBlueText
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Name & emergency contact",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary
                        )
                        Text(
                            text = if (isOptionalExpanded) {
                                "Tap to hide these fields"
                            } else {
                                "Add your name and a family contact"
                            },
                            fontSize = 12.sp,
                            color = colors.textSecondary
                        )
                    }

                    SoftIcon(
                        resId = if (isOptionalExpanded) R.drawable.ic_soft_chevron_up else R.drawable.ic_soft_chevron_down,
                        contentDescription = if (isOptionalExpanded) "Collapse optional fields" else "Expand optional fields",
                        tint = colors.textTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                AnimatedVisibility(visible = isOptionalExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        // Full Name Card
                        SoftCard(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SoftIcon(
                                        resId = R.drawable.ic_soft_user,
                                        contentDescription = null,
                                        tint = colors.accent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Full name",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 0.2.sp,
                                        color = colors.textSecondary
                                    )
                                }

                                OutlinedTextField(
                                    value = name,
                                    onValueChange = {
                                        name = it
                                        if (showError) showError = false
                                    },
                                    placeholder = { Text("e.g. Rahul Sharma", color = colors.textTertiary) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = colors.accent,
                                        unfocusedBorderColor = colors.outline,
                                        focusedContainerColor = colors.cardSecondaryBg,
                                        unfocusedContainerColor = colors.cardSecondaryBg,
                                        focusedTextColor = colors.textPrimary,
                                        unfocusedTextColor = colors.textPrimary
                                    ),
                                    shape = SoftFieldShape,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Text(
                                    text = "Shared with nearby nodes, so rescuers see your name instead of an id.",
                                    fontSize = 12.sp,
                                    color = colors.textSecondary
                                )
                            }
                        }

                        // Emergency Contact Card
                        SoftCard(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        SoftIcon(
                                            resId = R.drawable.ic_soft_id,
                                            contentDescription = null,
                                            tint = colors.accent,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "Emergency contact",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            letterSpacing = 0.2.sp,
                                            color = colors.textSecondary
                                        )
                                    }

                                    SoftBadge(
                                        text = "Shared in SOS",
                                        containerColor = colors.badgeBlueContainer,
                                        contentColor = colors.badgeBlueText
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // Relation Dropdown
                                    Box(modifier = Modifier.weight(0.42f)) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(54.dp)
                                                .clip(SoftFieldShape)
                                                .background(colors.cardSecondaryBg)
                                                .border(1.dp, colors.outline, SoftFieldShape)
                                                .clickable { isRelationDropdownOpen = true }
                                                .padding(horizontal = 14.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = selectedRelation,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = colors.textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            SoftIcon(
                                                resId = R.drawable.ic_soft_chevron_down,
                                                contentDescription = "Open relation options",
                                                tint = colors.textTertiary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        DropdownMenu(
                                            expanded = isRelationDropdownOpen,
                                            onDismissRequest = { isRelationDropdownOpen = false },
                                            modifier = Modifier.background(colors.surface)
                                        ) {
                                            relationOptions.forEach { rel ->
                                                DropdownMenuItem(
                                                    text = { Text(rel, fontSize = 13.sp, color = colors.textPrimary) },
                                                    onClick = {
                                                        selectedRelation = rel
                                                        isRelationDropdownOpen = false
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    // Phone Number
                                    OutlinedTextField(
                                        value = relativePhone,
                                        onValueChange = { input ->
                                            if (input.length <= 15 && input.all { it.isDigit() || it == '+' || it == ' ' || it == '-' }) {
                                                relativePhone = input
                                            }
                                        },
                                        placeholder = { Text("+91 98765 43210", fontSize = 13.sp, color = colors.textTertiary) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = colors.accent,
                                            unfocusedBorderColor = colors.outline,
                                            focusedContainerColor = colors.cardSecondaryBg,
                                            unfocusedContainerColor = colors.cardSecondaryBg,
                                            focusedTextColor = colors.textPrimary,
                                            unfocusedTextColor = colors.textPrimary
                                        ),
                                        shape = SoftFieldShape,
                                        modifier = Modifier
                                            .weight(0.58f)
                                            .height(54.dp)
                                    )
                                }

                                Text(
                                    text = "Rescue teams will see this number during an SOS, so your family can be reached quickly.",
                                    fontSize = 12.sp,
                                    color = colors.textSecondary
                                )
                            }
                        }
                    }
                }
            }

            // Inline validation error if any
            AnimatedVisibility(visible = showError) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.sosContainer)
                        .border(1.dp, colors.error.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SoftIcon(
                        resId = R.drawable.ic_soft_warning,
                        contentDescription = null,
                        tint = colors.sosContainerText,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = errorMessage,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.sosContainerText
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // =======================================================
            // 5. CONTINUE ACTION BUTTON
            // =======================================================
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .scale(buttonScale)
                    .shadow(8.dp, SoftFieldShape, spotColor = colors.accent.copy(alpha = 0.30f))
                    .clip(SoftFieldShape)
                    .background(colors.accent)
                    .clickable(
                        interactionSource = buttonInteraction,
                        indication = null
                    ) {
                        if (selectedLanguageCodes.isEmpty()) {
                            errorMessage = "Please select at least one language."
                            showError = true
                            return@clickable
                        }
                        if (ageText.isBlank()) {
                            errorMessage = "Please enter your age to continue."
                            showError = true
                            return@clickable
                        }

                        val parsedAge = ageText.toIntOrNull()
                        viewModel.completeOnboarding(
                            name = name.trim(),
                            age = parsedAge,
                            gender = selectedGender,
                            languages = selectedLanguageCodes,
                            relation = selectedRelation,
                            phone = relativePhone.trim()
                        )
                        onContinue()
                    }
                    .height(56.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Continue",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onAccent
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    SoftIcon(
                        resId = R.drawable.ic_soft_arrow_right,
                        contentDescription = null,
                        tint = colors.onAccent,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(colors.mesh)
                )
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = "100% on-device · Zero cloud · Editable in settings",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }

    // =======================================================
    // 6. MULTI-SELECT LANGUAGE MODAL BOTTOM SHEET
    // =======================================================
    if (isLanguageSheetOpen) {
        SoftSheetShell(
            title = "Your languages",
            subtitle = "Choose the languages you speak or understand",
            onDismiss = { isLanguageSheetOpen = false }
        ) {
            // Search Bar
            OutlinedTextField(
                value = languageSearchQuery,
                onValueChange = { languageSearchQuery = it },
                placeholder = { Text("Search languages…", fontSize = 13.sp, color = colors.textTertiary) },
                leadingIcon = {
                    SoftIcon(
                        resId = R.drawable.ic_soft_search,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.outline,
                    focusedContainerColor = colors.cardSecondaryBg,
                    unfocusedContainerColor = colors.cardSecondaryBg
                ),
                shape = SoftFieldShape,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(14.dp))

            // List of Languages
            val filtered = SupportedLanguage.entries.filter {
                it.englishName.contains(languageSearchQuery, ignoreCase = true) ||
                    it.nativeName.contains(languageSearchQuery, ignoreCase = true)
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered) { lang ->
                    val isSelected = selectedLanguageCodes.contains(lang.code)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSelected) colors.accentContainer else colors.cardSecondaryBg)
                            .clickable {
                                selectedLanguageCodes = if (isSelected) {
                                    if (selectedLanguageCodes.size > 1) {
                                        selectedLanguageCodes - lang.code
                                    } else {
                                        selectedLanguageCodes // Keep at least 1
                                    }
                                } else {
                                    selectedLanguageCodes + lang.code
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) colors.accent else colors.surface)
                                    .border(1.dp, if (isSelected) colors.accent else colors.outline, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = lang.nativeInitial,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isSelected) colors.onAccent else colors.textPrimary
                                )
                            }

                            Column {
                                Text(
                                    text = lang.nativeName,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.textPrimary
                                )
                                Text(
                                    text = lang.englishName,
                                    fontSize = 11.sp,
                                    color = colors.textSecondary
                                )
                            }
                        }

                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { checked ->
                                selectedLanguageCodes = if (checked) {
                                    selectedLanguageCodes + lang.code
                                } else {
                                    if (selectedLanguageCodes.size > 1) {
                                        selectedLanguageCodes - lang.code
                                    } else {
                                        selectedLanguageCodes
                                    }
                                }
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = colors.accent,
                                checkmarkColor = colors.onAccent
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            SoftButton(
                text = "Apply (${selectedLanguageCodes.size} selected)",
                onClick = { isLanguageSheetOpen = false },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
