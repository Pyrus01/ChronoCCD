package com.chronoccd.studio.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.chronoccd.studio.R

val BASE_TYPOGRAPHY = Typography()

data class AppFonts(
    val topBarTitle: FontFamily,
    val heroMetric: FontFamily,
    val sectionTitle: FontFamily
)

@OptIn(ExperimentalTextApi::class)
@Composable
fun expressiveTypography(): Typography {
    val googleFlex400 = remember {
        FontFamily(
            Font(
                resId = R.font.google_sans_flex,
                weight = FontWeight.Normal,
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(400)
                )
            )
        )
    }

    val googleFlex600 = remember {
        FontFamily(
            Font(
                resId = R.font.google_sans_flex,
                weight = FontWeight.Bold,
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(600),
                    FontVariation.Setting("ROND", 100f)
                )
            )
        )
    }

    return remember(googleFlex400, googleFlex600) {
        Typography(
            displayLarge = BASE_TYPOGRAPHY.displayLarge.copy(
                fontFamily = googleFlex600,
                fontFeatureSettings = "ss02, dlig"
            ),
            displayMedium = BASE_TYPOGRAPHY.displayMedium.copy(
                fontFamily = googleFlex600,
                fontFeatureSettings = "ss02, dlig"
            ),
            displaySmall = BASE_TYPOGRAPHY.displaySmall.copy(
                fontFamily = googleFlex600,
                fontFeatureSettings = "ss02, dlig"
            ),
            headlineLarge = BASE_TYPOGRAPHY.headlineLarge.copy(
                fontFamily = googleFlex600,
                fontFeatureSettings = "ss02, dlig"
            ),
            headlineMedium = BASE_TYPOGRAPHY.headlineMedium.copy(
                fontFamily = googleFlex600,
                fontFeatureSettings = "ss02, dlig"
            ),
            headlineSmall = BASE_TYPOGRAPHY.headlineSmall.copy(
                fontFamily = googleFlex600,
                fontFeatureSettings = "ss02, dlig"
            ),
            titleLarge = BASE_TYPOGRAPHY.titleLarge.copy(
                fontFamily = googleFlex600,
                fontFeatureSettings = "ss02, dlig"
            ),
            titleMedium = BASE_TYPOGRAPHY.titleMedium.copy(
                fontFamily = googleFlex600,
                fontFeatureSettings = "ss02, dlig"
            ),
            titleSmall = BASE_TYPOGRAPHY.titleSmall.copy(
                fontFamily = googleFlex600,
                fontFeatureSettings = "ss02, dlig"
            ),
            bodyLarge = BASE_TYPOGRAPHY.bodyLarge.copy(
                fontFamily = googleFlex400,
                fontFeatureSettings = "ss02, dlig"
            ),
            bodyMedium = BASE_TYPOGRAPHY.bodyMedium.copy(
                fontFamily = googleFlex400,
                fontFeatureSettings = "ss02, dlig"
            ),
            bodySmall = BASE_TYPOGRAPHY.bodySmall.copy(
                fontFamily = googleFlex400,
                fontFeatureSettings = "ss02, dlig"
            ),
            labelLarge = BASE_TYPOGRAPHY.labelLarge.copy(
                fontFamily = googleFlex600,
                fontFeatureSettings = "ss02, dlig"
            ),
            labelMedium = BASE_TYPOGRAPHY.labelMedium.copy(
                fontFamily = googleFlex600,
                fontFeatureSettings = "ss02, dlig"
            ),
            labelSmall = BASE_TYPOGRAPHY.labelSmall.copy(
                fontFamily = googleFlex600,
                fontFeatureSettings = "ss02, dlig"
            )
        )
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
fun rememberAppFonts(): AppFonts {
    val topBarFont = remember {
        FontFamily(
            Font(
                resId = R.font.google_sans_flex,
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(850),
                    FontVariation.width(110f),
                    FontVariation.Setting("ROND", 65f)
                )
            )
        )
    }
    val heroMetricFont = remember {
        FontFamily(
            Font(
                resId = R.font.google_sans_flex,
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(900),
                    FontVariation.width(112.5f),
                    FontVariation.Setting("ROND", 100f)
                )
            )
        )
    }
    val sectionTitleFont = remember {
        FontFamily(
            Font(
                resId = R.font.google_sans_flex,
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(700),
                    FontVariation.width(105f),
                    FontVariation.Setting("ROND", 50f)
                )
            )
        )
    }
    return remember(topBarFont, heroMetricFont, sectionTitleFont) {
        AppFonts(
            topBarTitle = topBarFont,
            heroMetric = heroMetricFont,
            sectionTitle = sectionTitleFont
        )
    }
}

val LocalAppFonts = staticCompositionLocalOf<AppFonts> { error("AppFonts not provided") }
