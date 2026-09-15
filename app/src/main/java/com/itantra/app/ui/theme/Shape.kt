package com.itantra.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// =========================================================================
// SOFT MINIMALISM SHAPE SYSTEM
// Generous rounded geometry: 14/18/22/28dp scale, pill badges & buttons.
// =========================================================================

val SoftShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** Standard card container. */
val SoftCardShape = RoundedCornerShape(22.dp)

/** Bottom sheets & hero surfaces. */
val SoftSheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/** Text fields & inputs. */
val SoftFieldShape = RoundedCornerShape(16.dp)

/** Badges & chips — full pill. */
val SoftPillShape = RoundedCornerShape(percent = 50)

/** Small inner tiles / icon containers. */
val SoftTileShape = RoundedCornerShape(14.dp)
