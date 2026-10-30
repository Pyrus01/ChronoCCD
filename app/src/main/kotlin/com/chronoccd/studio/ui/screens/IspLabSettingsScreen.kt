package com.chronoccd.studio.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.motionScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.chronoccd.studio.data.DigicamPersona
import com.chronoccd.studio.data.NostalgicDigicamCatalog
import com.chronoccd.studio.ui.AppTab
import com.chronoccd.studio.ui.ChronoCCDUiState
import com.chronoccd.studio.ui.components.DigicamPersonaSelectorStrip
import com.chronoccd.studio.ui.components.ExpressiveMetricTile
import com.chronoccd.studio.ui.components.IspTelemetryWavyHeroGauge
import com.chronoccd.studio.ui.theme.CustomColors
import com.chronoccd.studio.ui.theme.ExpressiveSeedPresets
import com.chronoccd.studio.ui.theme.LocalAppFonts
import com.chronoccd.studio.ui.theme.SeedPreset
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun IspLabSettingsScreen(
    uiState: ChronoCCDUiState,
    contentPadding: PaddingValues,
    onSelectPersona: (DigicamPersona) -> Unit,
    onSelectSeedPreset: (SeedPreset) -> Unit,
    onSetSyncThemeWithCamera: (Boolean) -> Unit,
    onSetDarkThemeOverride: (Boolean?) -> Unit,
    onSetAmoledBlack: (Boolean) -> Unit,
    onNavigateTab: (AppTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val fonts = LocalAppFonts.current
    val persona = uiState.selectedPersona
    val spec = persona.ispSpec

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 112.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxSize()
    ) {
        item {
            Column {
                Text(
                    text = "ISP Lab & Expressive Theme",
                    fontFamily = fonts.topBarTitle,
                    style = typography.headlineMedium,
                    color = colorScheme.onSurface
                )
                Text(
                    text = "9-Stage CCD Hardware Architecture • Material 3 Expressive",
                    style = typography.labelMedium,
                    color = colorScheme.primary
                )
            }
        }

        // 1. Camera Selector Strip
        item {
            DigicamPersonaSelectorStrip(
                personas = NostalgicDigicamCatalog,
                selectedPersona = persona,
                onSelectPersona = onSelectPersona
            )
        }

        // 2. Hero Wavy ISP Telemetry Gauge (Google Health Expressive Component)
        item {
            IspTelemetryWavyHeroGauge(
                persona = persona,
                telemetry = uiState.latestTelemetry,
                intensity = uiState.ispTuning.intensity
            )
        }

        // 3. Compact Expressive Metric Tiles Row
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ExpressiveMetricTile(
                    title = "SENSOR PITCH",
                    value = "${(spec.sensorPitchScale * 100).roundToInt()}%",
                    subtitle = persona.sensorBadge,
                    progress = spec.sensorPitchScale.coerceIn(0.1f, 1f),
                    icon = Icons.Rounded.GridView,
                    accentColor = colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateTab(AppTab.DARKROOM) }
                )
                ExpressiveMetricTile(
                    title = "SOOC CRISPNESS",
                    value = "${(spec.usmCrispness * 100).roundToInt()}%",
                    subtitle = "USM ${spec.usmCrispness}x • De-Clarity ${(spec.deClaritySoftness * 100).roundToInt()}%",
                    progress = spec.usmCrispness.coerceIn(0.1f, 1f),
                    icon = Icons.Rounded.BlurOn,
                    accentColor = colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateTab(AppTab.DARKROOM) }
                )
            }
        }

        // 4. Hardware ISP Architecture Breakdown Card
        item {
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = colorScheme.surfaceBright
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = colorScheme.tertiaryContainer,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Memory,
                                    contentDescription = null,
                                    tint = colorScheme.onTertiaryContainer
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "HARDWARE SIGNAL PIPELINE PROFILE",
                                style = typography.labelSmall,
                                color = colorScheme.tertiary
                            )
                            Text(
                                text = "${persona.displayName} • ${persona.ispChipName}",
                                style = typography.titleMedium,
                                color = colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    IspStageWavyRow(
                        stageName = "1. Optical Micro-Contrast De-Clarity",
                        metricLabel = "${(spec.deClaritySoftness * 100).roundToInt()}%",
                        fraction = spec.deClaritySoftness.coerceIn(0.12f, 1f),
                        color = colorScheme.primary
                    )
                    Spacer(Modifier.height(12.dp))
                    IspStageWavyRow(
                        stageName = "2. Single-Exposure De-HDR+ Shadow Roll-Off",
                        metricLabel = "${(spec.shadowRollOff * 100).roundToInt()}%",
                        fraction = (spec.shadowRollOff / 0.40f).coerceIn(0.12f, 1f),
                        color = colorScheme.tertiary
                    )
                    Spacer(Modifier.height(12.dp))
                    IspStageWavyRow(
                        stageName = "3. Manufacturer CCD Color Matrix & SOOC Contrast",
                        metricLabel = "Contrast ${(spec.toneContrast * 100).roundToInt()}%",
                        fraction = (spec.toneContrast / 0.45f).coerceIn(0.12f, 1f),
                        color = colorScheme.secondary
                    )
                    Spacer(Modifier.height(12.dp))
                    IspStageWavyRow(
                        stageName = "4. Fine Sensor Texture & SOOC JPEG Q${spec.jpegQuality}",
                        metricLabel = "Q${spec.jpegQuality}",
                        fraction = (spec.usmCrispness).coerceIn(0.15f, 1f),
                        color = colorScheme.error
                    )
                }
            }
        }

        // 5. Material 3 Expressive & MaterialKolor 2025 Spec Card (From Google Health Project)
        item {
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = colorScheme.surfaceContainerHigh
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Palette,
                            contentDescription = null,
                            tint = colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Material 3 Expressive Theme",
                                style = typography.titleMedium,
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = "MaterialExpressiveTheme + MaterialKolor SPEC_2025",
                                style = typography.labelSmall,
                                color = colorScheme.primary
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    ListItem(
                        headlineContent = { Text("Sync Theme with Active 2000s Camera") },
                        supportingContent = {
                            Text("Dynamically shifts MaterialKolor SPEC_2025 palette to ${persona.shortCode}")
                        },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = colorScheme.primary
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = uiState.syncThemeWithCamera,
                                onCheckedChange = onSetSyncThemeWithCamera,
                                colors = CustomColors.switchColors
                            )
                        },
                        colors = CustomColors.listItemColors,
                        modifier = Modifier.clip(RoundedCornerShape(20.dp))
                    )

                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = "Expressive Color Seed",
                        style = typography.labelLarge,
                        color = colorScheme.onSurface
                    )

                    Spacer(Modifier.height(10.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        ExpressiveSeedPresets.forEach { preset ->
                            val isSelected = !uiState.syncThemeWithCamera &&
                                uiState.selectedSeedPreset.id == preset.id
                            val swatchColor = if (preset.color == Color.White) colorScheme.primary else preset.color
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable { onSelectSeedPreset(preset) }
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(swatchColor)
                                        .border(
                                            width = if (isSelected) 3.dp else 1.dp,
                                            color = if (isSelected) colorScheme.onSurface else colorScheme.outlineVariant,
                                            shape = CircleShape
                                        )
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = "Selected",
                                            tint = Color.White,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = preset.name.substringBefore(" "),
                                    style = typography.labelSmall,
                                    color = colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = "Appearance Mode",
                        style = typography.labelLarge,
                        color = colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val options = listOf(
                            "System" to null,
                            "Light" to false,
                            "Dark" to true
                        )
                        options.forEach { (label, value) ->
                            ToggleButton(
                                checked = uiState.darkThemeOverride == value,
                                onCheckedChange = { onSetDarkThemeOverride(value) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(label)
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    ListItem(
                        headlineContent = { Text("AMOLED Pure Black Mode") },
                        supportingContent = {
                            Text("Uses MaterialKolor SPEC_2021 pitch-black surfaces in dark theme")
                        },
                        trailingContent = {
                            Switch(
                                checked = uiState.blackAmoledTheme,
                                onCheckedChange = onSetAmoledBlack,
                                colors = CustomColors.switchColors
                            )
                        },
                        colors = CustomColors.listItemColors,
                        modifier = Modifier.clip(RoundedCornerShape(20.dp))
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun IspStageWavyRow(
    stageName: String,
    metricLabel: String,
    fraction: Float,
    color: Color
) {
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = motionScheme.defaultSpatialSpec(),
        label = "ispStageFraction"
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stageName,
                style = typography.bodySmall,
                color = colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = metricLabel,
                style = typography.labelMedium,
                color = color
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearWavyProgressIndicator(
            progress = { animatedFraction },
            color = color,
            trackColor = colorScheme.surfaceContainerHighest,
            amplitude = { 0.4f },
            wavelength = 22.dp,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
