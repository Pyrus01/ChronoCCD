package com.chronoccd.studio.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chronoccd.studio.data.DigicamPersona
import com.chronoccd.studio.data.NostalgicDigicamCatalog
import com.chronoccd.studio.ui.ChronoCCDUiState
import kotlinx.coroutines.launch
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
    onSetShowOriginal: (Boolean) -> Unit,
    onPickGalleryUri: (android.net.Uri) -> Unit,
    onSaveToGallery: () -> Unit,
    onSharePhoto: (android.content.Context) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onPickGalleryUri(uri)
        }
    }

    val displayBitmap = if (uiState.showOriginalComparison) {
        uiState.sourceBitmap
    } else {
        uiState.processedBitmap ?: uiState.sourceBitmap
    }

    val dragOffsetX = remember { Animatable(0f) }
    var swipeDirection by remember { mutableIntStateOf(1) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(
                top = contentPadding.calculateTopPadding() + 4.dp,
                bottom = contentPadding.calculateBottomPadding() + 68.dp
            )
    ) {
        // 1. Minimal Top Bar matching Camera Screen
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "ISP",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Color.White,
                    letterSpacing = 0.8.sp
                )

                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = Color.White
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        val mpNum = uiState.selectedPersona.megapixelLabel.substringBefore(" ")
                        Text(
                            text = mpNum,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 9.sp,
                            lineHeight = 9.sp,
                            color = Color.Black
                        )
                        Text(
                            text = "MP",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 8.sp,
                            lineHeight = 8.sp,
                            color = Color.Black
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Classic Timestamp toggle + format pill
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (uiState.timestampConfig.enabled) {
                        Color.White.copy(alpha = 0.16f)
                    } else {
                        Color.Transparent
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggleTimestamp()
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AccessTime,
                            contentDescription = "Toggle Classic Timestamp",
                            tint = if (uiState.timestampConfig.enabled) Color(0xFFFF6D2E) else Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.size(18.dp)
                        )
                        if (uiState.timestampConfig.enabled) {
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = uiState.timestampConfig.formattedString(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFFF6D2E),
                                modifier = Modifier.clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onCycleTimestampFormat()
                                }
                            )
                        }
                    }
                }

                // Xenon Flash Effect Toggle
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggleXenonFlash()
                    }
                ) {
                    Icon(
                        imageVector = if (uiState.ispTuning.xenonFlashEnabled) {
                            Icons.Rounded.FlashOn
                        } else {
                            Icons.Rounded.FlashOff
                        },
                        contentDescription = "Toggle Xenon Flash Effect",
                        tint = if (uiState.ispTuning.xenonFlashEnabled) Color(0xFFFFE066) else Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Compare Raw vs Processed Toggle
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSetShowOriginal(!uiState.showOriginalComparison)
                    }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Compare,
                        contentDescription = "Compare Raw vs Processed",
                        tint = if (uiState.showOriginalComparison) Color(0xFF87D7B8) else Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        // 2. Huge Rounded Photo Preview (~78% of screen) + Left/Right Look Peek Strips
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // Left Peeking Strip with '<' Chevron
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(15.dp)
                    .fillMaxHeight(0.83f)
                    .clip(RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp))
                    .background(Color(0xFF2A2624).copy(alpha = 0.85f))
                    .clickable {
                        swipeDirection = -1
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSwipePrevLook()
                    }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    contentDescription = "Previous Look",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(16.dp)
                )
            }

            // Right Peeking Strip with '>' Chevron
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(15.dp)
                    .fillMaxHeight(0.83f)
                    .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                    .background(Color(0xFF2A2624).copy(alpha = 0.85f))
                    .clickable {
                        swipeDirection = 1
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSwipeNextLook()
                    }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = "Next Look",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(16.dp)
                )
            }

            // Main Rounded Photo Preview Card (Swipe Left/Right to Change Look)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 21.dp, vertical = 4.dp)
                    .offset { IntOffset((dragOffsetX.value * 0.28f).roundToInt(), 0) }
                    .clip(RoundedCornerShape(34.dp))
                    .background(Color(0xFF121212))
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
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    )
                                }
                            },
                            onDragCancel = {
                                coroutineScope.launch {
                                    dragOffsetX.animateTo(0f)
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
                }

                // Top-Center Translucent Look Pill (matches "Editorial" pill in screenshot)
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.44f),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 14.dp)
                ) {
                    AnimatedContent(
                        targetState = uiState.selectedPersona,
                        transitionSpec = {
                            (slideInHorizontally { width -> swipeDirection * (width / 2) } + fadeIn())
                                .togetherWith(
                                    slideOutHorizontally { width -> -swipeDirection * (width / 2) } + fadeOut()
                                )
                        },
                        label = "editorLookNamePill"
                    ) { persona ->
                        Text(
                            text = if (uiState.showOriginalComparison) "Original" else persona.displayName,
                            style = typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                        )
                    }
                }

                // Expressive Loading Indicator while running 9-stage ISP pipeline
                if (uiState.isProcessingIsp) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.50f),
                        modifier = Modifier
                            .align(Alignment.Center)
                    ) {
                        LoadingIndicator(
                            modifier = Modifier
                                .padding(10.dp)
                                .size(34.dp),
                            color = colorScheme.primary
                        )
                    }
                }

                // Bottom-Center Translucent 5-Look Quick Selector Dots/Numbers Pill
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.44f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
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
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) Color.White else Color.Transparent)
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSelectPersona(persona)
                                    }
                            ) {
                                Text(
                                    text = shortTag,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) Color.Black else Color.White
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Bottom Action Row (Open Photo on Left, Save Button in Center, Share on Right)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 42.dp, vertical = 14.dp)
        ) {
            // Left: Pick Photo from Device Gallery
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF282A2D))
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
            ) {
                Icon(
                    imageVector = Icons.Rounded.PhotoLibrary,
                    contentDescription = "Pick Photo from Gallery",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Center: Save Processed Photo Button (matches Shutter Button size & feel)
            val saveInteraction = remember { MutableInteractionSource() }
            val isPressed by saveInteraction.collectIsPressedAsState()
            val saveScale by animateFloatAsState(
                targetValue = if (isPressed) 0.90f else 1.0f,
                animationSpec = spring(stiffness = Spring.StiffnessHigh),
                label = "saveScale"
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(84.dp)
                    .graphicsLayer {
                        scaleX = saveScale
                        scaleY = saveScale
                    }
                    .clip(CircleShape)
                    .border(5.dp, Color(0xFF5F6368), CircleShape)
                    .clickable(
                        interactionSource = saveInteraction,
                        indication = null,
                        enabled = !uiState.isProcessingIsp
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSaveToGallery()
                    }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = "Save Photo",
                        tint = Color.Black,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Right: Share Processed Photo
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF282A2D))
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSharePhoto(context)
                    }
            ) {
                Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = "Share Photo",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
