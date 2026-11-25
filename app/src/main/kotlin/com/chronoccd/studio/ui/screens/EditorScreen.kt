package com.chronoccd.studio.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.motionScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chronoccd.studio.data.DigicamPersona
import com.chronoccd.studio.data.NostalgicDigicamCatalog
import com.chronoccd.studio.isp.DigicamIspPipeline
import com.chronoccd.studio.ui.ChronoCCDUiState
import com.chronoccd.studio.ui.components.AdjacentModePeekStrip
import com.chronoccd.studio.ui.components.CameraLookPill
import com.chronoccd.studio.ui.components.ExpressiveHeroActionButton
import com.chronoccd.studio.ui.components.ExpressiveSideActionTile
import com.chronoccd.studio.ui.components.ExpressiveTimestampPill
import com.chronoccd.studio.ui.components.ResolutionModeToggleBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EditorScreen(
    uiState: ChronoCCDUiState,
    contentPadding: PaddingValues,
    onSelectPersona: (DigicamPersona) -> Unit,
    onSwipeNextLook: () -> Unit,
    onSwipePrevLook: () -> Unit,
    onToggleXenonFlash: () -> Unit,
    onToggleTimestamp: () -> Unit,
    onCycleTimestampFormat: () -> Unit,
    onToggleFullSensorResolution: () -> Unit = {},
    onSetShowOriginal: (Boolean) -> Unit,
    onPickGalleryUri: (android.net.Uri) -> Unit,
    onSaveToGallery: () -> Unit,
    onSharePhoto: (android.content.Context) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val motion = motionScheme

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onPickGalleryUri(uri)
        }
    }

    val originalDisplayBitmap by produceState<Bitmap?>(
        initialValue = if (uiState.sourceLinearTonemapActive) null else uiState.sourceBitmap,
        key1 = uiState.sourceBitmap,
        key2 = uiState.showOriginalComparison,
        key3 = uiState.sourceLinearTonemapActive
    ) {
        val src = uiState.sourceBitmap
        value = when {
            src == null -> null
            uiState.showOriginalComparison && uiState.sourceLinearTonemapActive -> {
                withContext(Dispatchers.Default) {
                    DigicamIspPipeline.encodeLinearBitmapToSrgb(src)
                }
            }
            else -> src
        }
    }

    val displayBitmap = if (uiState.showOriginalComparison) {
        originalDisplayBitmap ?: uiState.sourceBitmap
    } else {
        uiState.processedBitmap ?: uiState.sourceBitmap
    }

    val currentIndex = NostalgicDigicamCatalog.indexOfFirst {
        it.id == uiState.selectedPersona.id
    }.coerceAtLeast(0)
    val prevPersona = NostalgicDigicamCatalog[(currentIndex - 1 + NostalgicDigicamCatalog.size) % NostalgicDigicamCatalog.size]
    val nextPersona = NostalgicDigicamCatalog[(currentIndex + 1) % NostalgicDigicamCatalog.size]

    val dragOffsetX = remember { Animatable(0f) }
    var swipeDirection by remember { mutableIntStateOf(1) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colorScheme.surface)
            .padding(
                top = contentPadding.calculateTopPadding() + 4.dp,
                bottom = contentPadding.calculateBottomPadding() + 68.dp
            )
    ) {
        // 1. M3 Expressive Top Bar matching Camera Screen
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "ISP",
                    style = typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onSurface,
                    letterSpacing = 0.8.sp
                )

                ResolutionModeToggleBadge(
                    modeLabel = uiState.selectedPersona.megapixelLabel,
                    isFullSensorRes = uiState.ispTuning.useFullSensorResolution,
                    onToggle = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggleFullSensorResolution()
                    }
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ExpressiveTimestampPill(
                    timestampConfig = uiState.timestampConfig,
                    icon = Icons.Rounded.AccessTime,
                    onToggle = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggleTimestamp()
                    },
                    onCycleFormat = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onCycleTimestampFormat()
                    }
                )

                FilledTonalIconToggleButton(
                    checked = uiState.ispTuning.xenonFlashEnabled,
                    onCheckedChange = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggleXenonFlash()
                    },
                    shapes = IconButtonDefaults.toggleableShapes(),
                    colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                        containerColor = colorScheme.surfaceContainerHigh,
                        contentColor = colorScheme.onSurfaceVariant,
                        checkedContainerColor = colorScheme.primaryContainer,
                        checkedContentColor = colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(
                        imageVector = if (uiState.ispTuning.xenonFlashEnabled) {
                            Icons.Rounded.FlashOn
                        } else {
                            Icons.Rounded.FlashOff
                        },
                        contentDescription = "Toggle Xenon Flash Effect",
                        modifier = Modifier.size(20.dp)
                    )
                }

                FilledTonalIconToggleButton(
                    checked = uiState.showOriginalComparison,
                    onCheckedChange = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSetShowOriginal(!uiState.showOriginalComparison)
                    },
                    shapes = IconButtonDefaults.toggleableShapes(),
                    colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                        containerColor = colorScheme.surfaceContainerHigh,
                        contentColor = colorScheme.onSurfaceVariant,
                        checkedContainerColor = colorScheme.secondaryContainer,
                        checkedContentColor = colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Compare,
                        contentDescription = "Compare Raw vs Processed",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // 2. Huge Rounded Photo Preview + Left/Right Adjacent Mode Peek Strips
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            AdjacentModePeekStrip(
                isLeft = true,
                adjacentPersona = prevPersona,
                icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                contentDescription = "Previous Look (${prevPersona.displayName})",
                onClick = {
                    swipeDirection = -1
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSwipePrevLook()
                },
                modifier = Modifier.align(Alignment.CenterStart)
            )

            AdjacentModePeekStrip(
                isLeft = false,
                adjacentPersona = nextPersona,
                icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = "Next Look (${nextPersona.displayName})",
                onClick = {
                    swipeDirection = 1
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSwipeNextLook()
                },
                modifier = Modifier.align(Alignment.CenterEnd)
            )

            // Main Rounded Photo Preview Card (Swipe Left/Right to Change Look)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 22.dp, vertical = 4.dp)
                    .offset { IntOffset((dragOffsetX.value * 0.28f).roundToInt(), 0) }
                    .clip(RoundedCornerShape(34.dp))
                    .background(colorScheme.surfaceContainerLowest)
                    .pointerInput(uiState.selectedPersona.id) {
                        var totalDrag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { totalDrag = 0f },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                totalDrag += dragAmount
                                coroutineScope.launch {
                                    dragOffsetX.snapTo(totalDrag.coerceIn(-220f, 220f))
                                }
                            },
                            onDragEnd = {
                                when {
                                    totalDrag < -75f -> {
                                        swipeDirection = 1
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSwipeNextLook()
                                    }
                                    totalDrag > 75f -> {
                                        swipeDirection = -1
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSwipePrevLook()
                                    }
                                }
                                coroutineScope.launch {
                                    dragOffsetX.animateTo(
                                        targetValue = 0f,
                                        animationSpec = motion.defaultSpatialSpec()
                                    )
                                }
                            },
                            onDragCancel = {
                                coroutineScope.launch {
                                    dragOffsetX.animateTo(0f, animationSpec = motion.defaultSpatialSpec())
                                }
                            }
                        )
                    }
            ) {
                if (displayBitmap != null) {
                    Image(
                        bitmap = displayBitmap.asImageBitmap(),
                        contentDescription = "Edited Photo Preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Clean empty state when no photo has been captured or selected yet
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = colorScheme.secondaryContainer,
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.PhotoLibrary,
                                    contentDescription = null,
                                    tint = colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "No photo yet",
                            style = typography.titleMedium,
                            color = colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Pick a photo from your device or snap one in Camera to apply 2000s CCD color science.",
                            style = typography.bodyMedium,
                            color = colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(18.dp))
                        FilledTonalButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            shapes = ButtonDefaults.shapes()
                        ) {
                            Text("Choose photo")
                        }
                    }
                }

                // Top-Center Camera Look Pill
                CameraLookPill(
                    persona = uiState.selectedPersona,
                    overrideTitle = if (uiState.showOriginalComparison) "Original" else null,
                    swipeDirection = swipeDirection,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 14.dp)
                )

                // Expressive Contained Loading Indicator while running ISP pipeline
                if (uiState.isProcessingIsp) {
                    ContainedLoadingIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                // Bottom-Center 5-Look Quick Selector Pill
                Surface(
                    shape = RoundedCornerShape(50),
                    color = colorScheme.surfaceContainerHigh.copy(alpha = 0.84f),
                    border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        NostalgicDigicamCatalog.forEachIndexed { idx, persona ->
                            val isSelected = persona.id == uiState.selectedPersona.id
                            val shortTag = when (idx) {
                                0 -> "IXUS"
                                1 -> "T1"
                                2 -> "F30"
                                3 -> "E32"
                                else -> "KDK"
                            }
                            val chipBg by animateColorAsState(
                                targetValue = if (isSelected) colorScheme.primary else Color.Transparent,
                                animationSpec = motion.fastEffectsSpec(),
                                label = "editorLookChipBg"
                            )
                            val chipFg by animateColorAsState(
                                targetValue = if (isSelected) colorScheme.onPrimary else colorScheme.onSurface,
                                animationSpec = motion.fastEffectsSpec(),
                                label = "editorLookChipFg"
                            )
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(chipBg)
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSelectPersona(persona)
                                    }
                            ) {
                                Text(
                                    text = shortTag,
                                    style = typography.labelSmall.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        fontFeatureSettings = "tnum"
                                    ),
                                    color = chipFg
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Bottom Action Row (Pick Photo on Left, Hero Save Button in Center, Share on Right)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 42.dp, vertical = 14.dp)
        ) {
            // Left: Expressive Squircle Gallery Picker Tile
            ExpressiveSideActionTile(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            ) {
                Icon(
                    imageVector = Icons.Rounded.PhotoLibrary,
                    contentDescription = "Pick Photo from Gallery",
                    tint = colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Center: Expressive Hero Save Button
            val hasSource = uiState.sourceBitmap != null
            ExpressiveHeroActionButton(
                enabled = hasSource && !uiState.isProcessingIsp,
                busy = uiState.isProcessingIsp,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSaveToGallery()
                }
            ) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = "Save Photo",
                    tint = if (uiState.isProcessingIsp) {
                        colorScheme.onPrimaryContainer
                    } else if (hasSource) {
                        colorScheme.surface
                    } else {
                        colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(28.dp)
                )
            }

            // Right: Expressive Squircle Share Tile
            ExpressiveSideActionTile(
                onClick = {
                    if (uiState.processedBitmap != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSharePhoto(context)
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = "Share Photo",
                    tint = if (uiState.processedBitmap != null) {
                        colorScheme.onSurface
                    } else {
                        colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    },
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
