package com.chronoccd.studio.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import com.materialkolor.ktx.harmonize

@Stable
fun Color.harmonizeIf(other: Color, condition: Boolean, matchSaturation: Boolean = true): Color =
    if (!condition) this.harmonize(other, matchSaturation) else other

data class SeedPreset(
    val id: String,
    val name: String,
    val color: Color
)

val ExpressiveSeedPresets = listOf(
    SeedPreset("health_teal", "Google Health Teal", Color(0xFF00897B)),
    SeedPreset("system", "Dynamic System", Color.White),
    SeedPreset("ixus_coral", "IXUS Sunset Coral", Color(0xFFE5534B)),
    SeedPreset("cyber_indigo", "Cyber-shot Indigo", Color(0xFF3D64E6)),
    SeedPreset("finepix_emerald", "FinePix Super Emerald", Color(0xFF1E8E5A)),
    SeedPreset("coolpix_amber", "Coolpix Quartz Amber", Color(0xFFE68A00)),
    SeedPreset("exilim_plum", "Exilim Nightlife Violet", Color(0xFF8E44AD)),
    SeedPreset("tomato_sage", "Tomato Expressive Sage", Color(0xFF4C662B))
)

// Default Google Health / Tomato Expressive fallback schemes
val primaryLight = Color(0xFF146C53)
val onPrimaryLight = Color(0xFFFFFFFF)
val primaryContainerLight = Color(0xFFA3F4D4)
val onPrimaryContainerLight = Color(0xFF00513D)
val secondaryLight = Color(0xFF4C6359)
val onSecondaryLight = Color(0xFFFFFFFF)
val secondaryContainerLight = Color(0xFFCEE9DB)
val onSecondaryContainerLight = Color(0xFF354B42)
val tertiaryLight = Color(0xFF3F6375)
val onTertiaryLight = Color(0xFFFFFFFF)
val tertiaryContainerLight = Color(0xFFC3E8FD)
val onTertiaryContainerLight = Color(0xFF264B5C)
val errorLight = Color(0xFFBA1A1A)
val onErrorLight = Color(0xFFFFFFFF)
val errorContainerLight = Color(0xFFFFDAD6)
val onErrorContainerLight = Color(0xFF93000A)
val backgroundLight = Color(0xFFF5FBF6)
val onBackgroundLight = Color(0xFF171D1A)
val surfaceLight = Color(0xFFF5FBF6)
val onSurfaceLight = Color(0xFF171D1A)
val surfaceVariantLight = Color(0xFFDBE5DF)
val onSurfaceVariantLight = Color(0xFF404944)
val outlineLight = Color(0xFF707974)
val outlineVariantLight = Color(0xFFBFC9C3)
val scrimLight = Color(0xFF000000)
val inverseSurfaceLight = Color(0xFF2B322F)
val inverseOnSurfaceLight = Color(0xFFECF2EE)
val inversePrimaryLight = Color(0xFF87D7B8)
val surfaceDimLight = Color(0xFFD5DBD7)
val surfaceBrightLight = Color(0xFFF5FBF6)
val surfaceContainerLowestLight = Color(0xFFFFFFFF)
val surfaceContainerLowLight = Color(0xFFEFF5F1)
val surfaceContainerLight = Color(0xFFE9EFEB)
val surfaceContainerHighLight = Color(0xFFE3EAE5)
val surfaceContainerHighestLight = Color(0xFFDEE4E0)

val primaryDark = Color(0xFF87D7B8)
val onPrimaryDark = Color(0xFF003829)
val primaryContainerDark = Color(0xFF00513D)
val onPrimaryContainerDark = Color(0xFFA3F4D4)
val secondaryDark = Color(0xFFB3CCC0)
val onSecondaryDark = Color(0xFF1F352C)
val secondaryContainerDark = Color(0xFF354B42)
val onSecondaryContainerDark = Color(0xFFCEE9DB)
val tertiaryDark = Color(0xFFA7CCE0)
val onTertiaryDark = Color(0xFF0B3445)
val tertiaryContainerDark = Color(0xFF264B5C)
val onTertiaryContainerDark = Color(0xFFC3E8FD)
val errorDark = Color(0xFFFFB4AB)
val onErrorDark = Color(0xFF690005)
val errorContainerDark = Color(0xFF93000A)
val onErrorContainerDark = Color(0xFFFFDAD6)
val backgroundDark = Color(0xFF0F1512)
val onBackgroundDark = Color(0xFFDEE4E0)
val surfaceDark = Color(0xFF0F1512)
val onSurfaceDark = Color(0xFFDEE4E0)
val surfaceVariantDark = Color(0xFF404944)
val onSurfaceVariantDark = Color(0xFFBFC9C3)
val outlineDark = Color(0xFF89938E)
val outlineVariantDark = Color(0xFF404944)
val scrimDark = Color(0xFF000000)
val inverseSurfaceDark = Color(0xFFDEE4E0)
val inverseOnSurfaceDark = Color(0xFF2B322F)
val inversePrimaryDark = Color(0xFF146C53)
val surfaceDimDark = Color(0xFF0F1512)
val surfaceBrightDark = Color(0xFF343B37)
val surfaceContainerLowestDark = Color(0xFF0A0F0D)
val surfaceContainerLowDark = Color(0xFF171D1A)
val surfaceContainerDark = Color(0xFF1B211E)
val surfaceContainerHighDark = Color(0xFF252B28)
val surfaceContainerHighestDark = Color(0xFF303633)

object CustomColors {
    var black = false

    val topBarColors: TopAppBarColors
        @Composable get() =
            TopAppBarDefaults.topAppBarColors(
                containerColor = if (!black) colorScheme.surfaceContainer else colorScheme.surface,
                scrolledContainerColor = if (!black) colorScheme.surfaceContainerHigh else colorScheme.surface
            )

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    val listItemColors: ListItemColors
        @Composable get() =
            ListItemDefaults.segmentedColors(
                containerColor = if (!black) colorScheme.surfaceBright else colorScheme.surfaceContainerHigh,
                disabledContainerColor = if (!black) colorScheme.surfaceBright else colorScheme.surfaceContainerHigh,
            )

    val switchColors: SwitchColors
        @Composable get() = SwitchDefaults.colors(
            checkedIconColor = colorScheme.primary,
        )
}
