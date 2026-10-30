package com.chronoccd.studio.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.motionScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chronoccd.studio.data.DigicamPersona
import com.chronoccd.studio.data.TimestampConfig
import com.chronoccd.studio.isp.IspPipelineTelemetry
import com.chronoccd.studio.isp.QuartzTimestampRenderer
import com.chronoccd.studio.ui.theme.LocalAppFonts
import kotlin.math.roundToInt

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.BorderStroke

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun IspTelemetryWavyHeroGauge(
    persona: DigicamPersona,
    telemetry: IspPipelineTelemetry?,
    intensity: Float,
    modifier: Modifier = Modifier
) {
    val fonts = LocalAppFonts.current
    val motion = motionScheme
    val scorePercent = ((intensity.coerceIn(0.2f, 1.3f) / 1.3f) * 100f).roundToInt().coerceIn(15, 100)
    val animatedProgress by animateFloatAsState(
        targetValue = (scorePercent / 100f).coerceIn(0.08f, 1f),
        animationSpec = motion.slowSpatialSpec(),
        label = "ispWavyProgress"
    )

    val strokeWidthDp = 16.dp
    val density = LocalDensity.current
    val stroke = remember(strokeWidthDp, density) {
        Stroke(
            width = with(density) { strokeWidthDp.toPx() },
            cap = StrokeCap.Round
        )
    }

    Card(
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surfaceContainerHigh
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = colorScheme.secondaryContainer,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Memory,
                        contentDescription = null,
                        tint = colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${persona.ispChipName.uppercase()} • ${persona.releaseYear}",
                        style = typography.labelMedium,
                        color = colorScheme.onSecondaryContainer
                    )
                }
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(210.dp)
            ) {
                CircularWavyProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.size(200.dp),
                    color = colorScheme.primary,
                    trackColor = colorScheme.surfaceVariant,
                    stroke = stroke,
                    trackStroke = stroke,
                    amplitude = { 0.65f },
                    wavelength = 38.dp,
                    waveSpeed = 14.dp
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = persona.shortCode,
                        fontFamily = fonts.heroMetric,
                        fontSize = 30.sp,
                        lineHeight = 34.sp,
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = "${persona.megapixelLabel} • ${persona.isoDefaultLabel}",
                        style = typography.titleSmall,
                        color = colorScheme.primary
                    )
                    Text(
                        text = persona.sensorBadge,
                        style = typography.labelSmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                MiniTelemetryPill(
                    label = "CCD Grid",
                    value = if (telemetry != null) {
                        "${telemetry.effectiveSensorWidth}×${telemetry.effectiveSensorHeight}"
                    } else {
                        "${(persona.ispSpec.sensorPitchScale * 100).roundToInt()}% Pitch"
                    }
                )
                MiniTelemetryPill(
                    label = "JPEG DCT",
                    value = "Q${telemetry?.effectiveJpegQuality ?: persona.ispSpec.jpegQuality} 4:2:0"
                )
                MiniTelemetryPill(
                    label = "ISP Time",
                    value = if (telemetry != null) "${telemetry.processingTimeMs} ms" else "Real-Time"
                )
            }
        }
    }
}

@Composable
private fun MiniTelemetryPill(
    label: String,
    value: String
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = colorScheme.surfaceBright
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = value,
                style = typography.titleSmall,
                color = colorScheme.onSurface
            )
            Text(
                text = label,
                style = typography.labelSmall,
                color = colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveMetricTile(
    title: String,
    value: String,
    subtitle: String,
    progress: Float,
    icon: ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0.05f, 1f),
        animationSpec = motionScheme.defaultSpatialSpec(),
        label = "tileProgress"
    )

    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surfaceBright
        ),
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = CircleShape,
                    color = accentColor.copy(alpha = 0.16f),
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = accentColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Text(
                    text = title,
                    style = typography.labelMedium,
                    color = colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = value,
                fontFamily = LocalAppFonts.current.heroMetric,
                fontSize = 24.sp,
                color = colorScheme.onSurface
            )

            Text(
                text = subtitle,
                style = typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            LinearWavyProgressIndicator(
                progress = { animatedProgress },
                color = accentColor,
                trackColor = colorScheme.surfaceContainerHighest,
                amplitude = { 0.45f },
                wavelength = 24.dp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun DigicamPersonaSelectorStrip(
    personas: List<DigicamPersona>,
    selectedPersona: DigicamPersona,
    onSelectPersona: (DigicamPersona) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        personas.forEach { persona ->
            val selected = persona.id == selectedPersona.id
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = if (selected) colorScheme.primaryContainer else colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) colorScheme.primary else colorScheme.outlineVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(22.dp)
                    )
                    .clickable { onSelectPersona(persona) }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(persona.accentColor.copy(alpha = if (selected) 0.95f else 0.25f))
                    ) {
                        Icon(
                            imageVector = if (selected) Icons.Rounded.AutoAwesome else Icons.Rounded.CameraAlt,
                            contentDescription = null,
                            tint = if (selected) Color.White else persona.accentColor,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = persona.shortCode,
                                style = typography.titleSmall,
                                color = if (selected) colorScheme.onPrimaryContainer else colorScheme.onSurface
                            )
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) colorScheme.primary else colorScheme.surfaceBright
                            ) {
                                Text(
                                    text = "'${(persona.releaseYear % 100).toString().padStart(2, '0')}",
                                    style = typography.labelSmall,
                                    color = if (selected) colorScheme.onPrimary else colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "${persona.megapixelLabel} • ${persona.sensorBadge.substringAfter(" ")}",
                            style = typography.labelSmall,
                            color = if (selected) colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ViewfinderQuartzTimestampOverlay(
    timestampConfig: TimestampConfig,
    modifier: Modifier = Modifier
) {
    if (!timestampConfig.enabled) return
    Canvas(modifier = modifier) {
        val w = size.width.toInt()
        val h = size.height.toInt()
        if (w > 20 && h > 20) {
            QuartzTimestampRenderer.imprintTimestamp(
                canvas = drawContext.canvas.nativeCanvas,
                imageWidth = w,
                imageHeight = h,
                config = timestampConfig
            )
        }
    }
}

/**
 * Compact dual-state resolution mode toggle badge for the Top Bar.
 * Toggles between each camera mode's predetermined retro resolution (VGA, 0.8MP, 1.3MP, 2MP, 4MP)
 * and the camera sensor's full native resolution for crystal clear high-res photos.
 */
@Composable
fun ResolutionModeToggleBadge(
    modeLabel: String,
    isFullSensorRes: Boolean,
    accentColor: Color,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF1E1F22),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(2.5.dp)
        ) {
            // Predetermined Mode Resolution Segment (e.g. "VGA", "0.8 MP", "1.3 MP", "2 MP", "4 MP")
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (!isFullSensorRes) Color.White else Color.Transparent)
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    text = modeLabel,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (!isFullSensorRes) Color.Black else Color.White.copy(alpha = 0.50f),
                    letterSpacing = 0.3.sp
                )
            }

            Spacer(Modifier.width(2.dp))

            // Full Sensor Resolution Segment
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isFullSensorRes) accentColor else Color.Transparent)
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "FULL",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isFullSensorRes) Color.White else Color.White.copy(alpha = 0.50f),
                    letterSpacing = 0.4.sp
                )
            }
        }
    }
}
