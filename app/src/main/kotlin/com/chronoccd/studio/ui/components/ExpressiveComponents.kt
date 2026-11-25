package com.chronoccd.studio.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chronoccd.studio.data.DigicamPersona
import com.chronoccd.studio.data.TimestampConfig
import com.chronoccd.studio.isp.IspPipelineTelemetry
import com.chronoccd.studio.isp.QuartzTimestampRenderer
import com.chronoccd.studio.ui.theme.LocalAppFonts
import kotlin.math.roundToInt

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
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ResolutionModeToggleBadge(
    modeLabel: String,
    isFullSensorRes: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val motion = motionScheme
    val retroBg by animateColorAsState(
        targetValue = if (!isFullSensorRes) colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = motion.defaultEffectsSpec(),
        label = "retroResBg"
    )
    val retroFg by animateColorAsState(
        targetValue = if (!isFullSensorRes) colorScheme.onSecondaryContainer else colorScheme.onSurfaceVariant,
        animationSpec = motion.defaultEffectsSpec(),
        label = "retroResFg"
    )
    val retroCorner by animateDpAsState(
        targetValue = if (!isFullSensorRes) 10.dp else 6.dp,
        animationSpec = motion.fastSpatialSpec(),
        label = "retroResCorner"
    )
    val fullBg by animateColorAsState(
        targetValue = if (isFullSensorRes) colorScheme.primary else Color.Transparent,
        animationSpec = motion.defaultEffectsSpec(),
        label = "fullResBg"
    )
    val fullFg by animateColorAsState(
        targetValue = if (isFullSensorRes) colorScheme.onPrimary else colorScheme.onSurfaceVariant,
        animationSpec = motion.defaultEffectsSpec(),
        label = "fullResFg"
    )
    val fullCorner by animateDpAsState(
        targetValue = if (isFullSensorRes) 10.dp else 6.dp,
        animationSpec = motion.fastSpatialSpec(),
        label = "fullResCorner"
    )

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onToggle)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(3.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(retroCorner))
                    .background(retroBg)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = modeLabel,
                    style = typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum"
                    ),
                    color = retroFg,
                    letterSpacing = 0.3.sp
                )
            }

            Spacer(Modifier.width(2.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(fullCorner))
                    .background(fullBg)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "FULL",
                    style = typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum"
                    ),
                    color = fullFg,
                    letterSpacing = 0.4.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AdjacentModePeekStrip(
    isLeft: Boolean,
    adjacentPersona: DigicamPersona,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val stripWidth by animateDpAsState(
        targetValue = if (isPressed) 20.dp else 16.dp,
        animationSpec = motionScheme.fastSpatialSpec(),
        label = "peekStripWidth"
    )
    val stripShape = if (isLeft) {
        RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .width(stripWidth)
            .fillMaxHeight(0.83f)
            .clip(stripShape)
            .background(colorScheme.surfaceContainerHigh.copy(alpha = 0.92f))
            .border(
                width = 1.dp,
                color = colorScheme.outlineVariant.copy(alpha = 0.65f),
                shape = stripShape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .align(if (isLeft) Alignment.CenterStart else Alignment.CenterEnd)
                .width(2.5.dp)
                .fillMaxHeight(0.30f)
                .clip(CircleShape)
                .background(adjacentPersona.accentColor.copy(alpha = 0.90f))
        )
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CameraLookPill(
    persona: DigicamPersona,
    overrideTitle: String? = null,
    swipeDirection: Int = 1,
    modifier: Modifier = Modifier
) {
    val motion = motionScheme
    Surface(
        shape = RoundedCornerShape(50),
        color = colorScheme.surfaceContainerHigh.copy(alpha = 0.84f),
        border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = modifier
    ) {
        AnimatedContent(
            targetState = overrideTitle to persona,
            transitionSpec = {
                (slideInHorizontally(motion.fastSpatialSpec()) { width -> swipeDirection * (width / 2) } +
                    fadeIn(motion.fastEffectsSpec()))
                    .togetherWith(
                        slideOutHorizontally(motion.fastSpatialSpec()) { width -> -swipeDirection * (width / 2) } +
                            fadeOut(motion.fastEffectsSpec())
                    )
            },
            label = "cameraLookPill"
        ) { (titleOverride, currentPersona) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (titleOverride != null) {
                    Text(
                        text = titleOverride,
                        style = typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(currentPersona.accentColor)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = currentPersona.displayName,
                        style = typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onSurface
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "'${(currentPersona.releaseYear % 100).toString().padStart(2, '0')}",
                        style = typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum"
                        ),
                        color = colorScheme.primary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveTimestampPill(
    timestampConfig: TimestampConfig,
    icon: ImageVector,
    onToggle: () -> Unit,
    onCycleFormat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val motion = motionScheme
    val enabled = timestampConfig.enabled
    val containerColor by animateColorAsState(
        targetValue = if (enabled) colorScheme.tertiaryContainer else colorScheme.surfaceContainerHigh,
        animationSpec = motion.defaultEffectsSpec(),
        label = "timestampContainer"
    )
    val contentColor by animateColorAsState(
        targetValue = if (enabled) colorScheme.onTertiaryContainer else colorScheme.onSurfaceVariant,
        animationSpec = motion.defaultEffectsSpec(),
        label = "timestampContent"
    )
    val cornerRadius by animateDpAsState(
        targetValue = if (enabled) 12.dp else 20.dp,
        animationSpec = motion.fastSpatialSpec(),
        label = "timestampCorner"
    )

    Surface(
        shape = RoundedCornerShape(cornerRadius),
        color = containerColor,
        border = if (!enabled) BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.55f)) else null,
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .clickable(onClick = onToggle)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = "Toggle Classic Timestamp",
                tint = contentColor,
                modifier = Modifier.size(17.dp)
            )
            AnimatedVisibility(visible = enabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = timestampConfig.formattedString(),
                        style = typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum"
                        ),
                        color = contentColor,
                        modifier = Modifier.clickable(onClick = onCycleFormat)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveHeroActionButton(
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    innerContent: @Composable BoxScope.() -> Unit = {}
) {
    val motion = motionScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.91f else 1.0f,
        animationSpec = motion.fastSpatialSpec(),
        label = "heroButtonScale"
    )
    val coreCornerPercent by animateIntAsState(
        targetValue = when {
            busy -> 32
            isPressed && enabled -> 36
            else -> 50
        },
        animationSpec = motion.fastSpatialSpec(),
        label = "heroCoreCorner"
    )
    val coreColor by animateColorAsState(
        targetValue = when {
            busy -> colorScheme.primaryContainer
            enabled -> colorScheme.onSurface
            else -> colorScheme.surfaceContainerHighest
        },
        animationSpec = motion.defaultEffectsSpec(),
        label = "heroCoreColor"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(84.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .border(
                width = 4.dp,
                color = if (enabled) colorScheme.outline else colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = CircleShape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(68.dp)
                .clip(RoundedCornerShape(percent = coreCornerPercent))
                .background(coreColor),
            content = innerContent
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveSideActionTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val motion = motionScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val cornerRadius by animateDpAsState(
        targetValue = if (isPressed) 12.dp else 18.dp,
        animationSpec = motion.fastSpatialSpec(),
        label = "sideTileCorner"
    )
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = motion.fastSpatialSpec(),
        label = "sideTileScale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(56.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(cornerRadius))
            .background(colorScheme.surfaceContainerHigh)
            .border(
                width = 1.dp,
                color = colorScheme.outlineVariant.copy(alpha = 0.7f),
                shape = RoundedCornerShape(cornerRadius)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        content = content
    )
}
