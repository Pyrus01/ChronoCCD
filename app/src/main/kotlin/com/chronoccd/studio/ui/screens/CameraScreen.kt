package com.chronoccd.studio.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.TonemapCurve
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.chronoccd.studio.data.DigicamPersona
import com.chronoccd.studio.isp.DemoSceneGenerator
import com.chronoccd.studio.isp.DigicamAgslShader
import com.chronoccd.studio.ui.ChronoCCDUiState
import com.chronoccd.studio.ui.components.ResolutionModeToggleBadge
import com.chronoccd.studio.ui.components.ViewfinderQuartzTimestampOverlay
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CameraScreen(
    uiState: ChronoCCDUiState,
    contentPadding: PaddingValues,
    onSelectPersona: (DigicamPersona) -> Unit,
    onSwipeNextLook: () -> Unit,
    onSwipePrevLook: () -> Unit,
    onToggleXenonFlash: () -> Unit,
    onToggleTimestamp: () -> Unit,
    onCycleTimestampFormat: () -> Unit,
    onToggleCameraLens: () -> Unit,
    onSetZoom: (Float) -> Unit,
    onCapturePhoto: (Bitmap?, String, Boolean) -> Unit,
    onPickGalleryUri: (android.net.Uri) -> Unit,
    onOpenEditor: () -> Unit,
    onToggleNativeSizeExport: () -> Unit = {},
    onToggleFullSensorResolution: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onPickGalleryUri(uri)
        }
    }

    var viewfinderSize by remember { mutableStateOf(IntSize(1080, 1440)) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var imageCaptureRef by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraControlRef by remember { mutableStateOf<androidx.camera.core.CameraControl?>(null) }
    var linearTonemapActive by remember { mutableStateOf(false) }

    // Xenon flash screen burst state
    var flashBurstVisible by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.isCapturingPhoto) {
        if (uiState.isCapturingPhoto && uiState.ispTuning.xenonFlashEnabled) {
            flashBurstVisible = true
            delay(160)
            flashBurstVisible = false
        }
    }

    val flashAlpha by animateFloatAsState(
        targetValue = if (flashBurstVisible) 0.90f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "xenonBurstAlpha"
    )

    val fallbackSceneBitmap = remember(uiState.selectedDemoSceneId) {
        DemoSceneGenerator.generateSceneBitmap(uiState.selectedDemoSceneId, 960, 1280)
    }

    // Bind CameraX lifecycle
    DisposableEffect(hasCameraPermission, uiState.isFrontCamera, previewViewRef) {
        val pv = previewViewRef
        if (hasCameraPermission && pv != null) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            val executor = ContextCompat.getMainExecutor(context)
            val listener = Runnable {
                runCatching {
                    val provider = cameraProviderFuture.get()
                    // STEP 1: Never attach NOISE_REDUCTION_MODE / EDGE_MODE to Preview
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = pv.surfaceProvider
                    }
                    val highResSelector = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        .build()
                    val captureBuilder = ImageCapture.Builder()
                        .setResolutionSelector(highResSelector)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setFlashMode(
                            if (uiState.ispTuning.xenonFlashEnabled) {
                                ImageCapture.FLASH_MODE_ON
                            } else {
                                ImageCapture.FLASH_MODE_OFF
                            }
                        )
                    // STEP 1: Read CameraCharacteristics and attach available keys ONLY to ImageCapture
                    linearTonemapActive = configureImageCaptureWithCharacteristics(
                        context = context,
                        isFrontCamera = uiState.isFrontCamera,
                        captureBuilder = captureBuilder
                    )
                    val capture = captureBuilder.build()
                    imageCaptureRef = capture

                    val selector = if (uiState.isFrontCamera) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }

                    provider.unbindAll()
                    val camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                    cameraControlRef = camera.cameraControl
                    camera.cameraControl.setZoomRatio(uiState.digitalZoom.coerceAtLeast(1.0f))
                    camera.cameraControl.setExposureCompensationIndex(0)
                }
            }
            cameraProviderFuture.addListener(listener, executor)
        }
        onDispose { }
    }

    LaunchedEffect(uiState.digitalZoom) {
        runCatching {
            cameraControlRef?.setZoomRatio(uiState.digitalZoom.coerceAtLeast(0.6f))
        }
    }

    LaunchedEffect(uiState.ispTuning.xenonFlashEnabled) {
        runCatching {
            imageCaptureRef?.flashMode = if (uiState.ispTuning.xenonFlashEnabled) {
                ImageCapture.FLASH_MODE_ON
            } else {
                ImageCapture.FLASH_MODE_OFF
            }
        }
    }

    val liveShaderEffect = remember(
        uiState.selectedPersona,
        uiState.ispTuning,
        viewfinderSize
    ) {
        DigicamAgslShader.createComposeRenderEffect(
            persona = uiState.selectedPersona,
            tuning = uiState.ispTuning,
            widthPx = viewfinderSize.width.toFloat(),
            heightPx = viewfinderSize.height.toFloat()
        )
    }

    // Horizontal swipe state for switching between the 5 2000s looks
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
        // 1. Minimal Top Bar (matches RAW | 12MP ... icons from screenshot)
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
                    text = "CCD",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Color.White,
                    letterSpacing = 0.8.sp
                )

                // Dual-state resolution toggle: Mode Predetermined Res vs Full Sensor Resolution
                ResolutionModeToggleBadge(
                    modeLabel = uiState.selectedPersona.megapixelLabel,
                    isFullSensorRes = uiState.ispTuning.useFullSensorResolution,
                    accentColor = uiState.selectedPersona.accentColor,
                    onToggle = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggleFullSensorResolution()
                    }
                )
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

                // Xenon Flash Toggle Icon
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
                        contentDescription = "Toggle Xenon Flash",
                        tint = if (uiState.ispTuning.xenonFlashEnabled) Color(0xFFFFE066) else Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        // 2. Huge Rounded Camera Preview (~78% of screen) + Left/Right Look Peek Strips
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // Left Peeking Look Strip with '<' Chevron (matches screenshot)
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

            // Right Peeking Look Strip with '>' Chevron (matches screenshot)
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

            // Main Rounded Camera Preview Card
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 21.dp, vertical = 4.dp)
                    .offset { IntOffset((dragOffsetX.value * 0.28f).roundToInt(), 0) }
                    .clip(RoundedCornerShape(34.dp))
                    .background(Color(0xFF121212))
                    .onSizeChanged { viewfinderSize = it }
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
                // Live CameraX Preview or Fallback Scene with Real-Time 9-Stage AGSL Shader
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            renderEffect = liveShaderEffect
                            clip = true
                        }
                ) {
                    if (hasCameraPermission) {
                        AndroidView(
                            factory = { ctx ->
                                PreviewView(ctx).apply {
                                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                    previewViewRef = this
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Image(
                            bitmap = fallbackSceneBitmap.asImageBitmap(),
                            contentDescription = "Camera Preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Classic 7-Segment Quartz Timestamp Overlay in the Bottom Corner
                ViewfinderQuartzTimestampOverlay(
                    timestampConfig = uiState.timestampConfig,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 58.dp)
                )

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
                        label = "lookNamePill"
                    ) { persona ->
                        Text(
                            text = persona.displayName,
                            style = typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                        )
                    }
                }

                // Subtle Center Level Horizon Line ("0°" from screenshot)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(width = 160.dp, height = 28.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cy = size.height / 2f
                        drawLine(
                            color = Color.White.copy(alpha = 0.65f),
                            start = Offset(0f, cy),
                            end = Offset(size.width * 0.40f, cy),
                            strokeWidth = 1.5.dp.toPx()
                        )
                        drawLine(
                            color = Color.White.copy(alpha = 0.65f),
                            start = Offset(size.width * 0.60f, cy),
                            end = Offset(size.width, cy),
                            strokeWidth = 1.5.dp.toPx()
                        )
                    }
                    Text(
                        text = "0°",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }

                // Bottom-Center Translucent Zoom Pill (.5 | 1× | 2 | 5 | 10 from screenshot)
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.44f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                ) {
                    val zoomStops = listOf(
                        0.6f to ".5",
                        1.0f to "1×",
                        2.0f to "2",
                        5.0f to "5",
                        10.0f to "10"
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        zoomStops.forEach { (zoomVal, label) ->
                            val isSelected = kotlin.math.abs(uiState.digitalZoom - zoomVal) < 0.25f
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) Color.White else Color.Transparent)
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSetZoom(zoomVal)
                                    }
                            ) {
                                Text(
                                    text = if (isSelected && !label.endsWith("×")) "${label}×" else label,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) Color.Black else Color.White
                                )
                            }
                        }
                    }
                }

                // Xenon Strobe Discharge Flash Overlay
                if (flashAlpha > 0.01f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFFF2FAFF).copy(alpha = flashAlpha))
                    )
                }
            }
        }

        // 3. Bottom Shutter Row (Thumbnail on Left, Big White Shutter in Center, Flip Camera on Right)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 42.dp, vertical = 14.dp)
        ) {
            // Left: Rounded-Square Thumbnail (opens Editor or Photo Picker)
            val thumbBitmap = uiState.processedBitmap ?: uiState.sourceBitmap
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF232528))
                    .border(
                        width = 1.5.dp,
                        color = Color.White.copy(alpha = 0.22f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clickable {
                        if (uiState.capturedRoll.isNotEmpty() || uiState.processedBitmap != null) {
                            onOpenEditor()
                        } else {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    }
            ) {
                if (thumbBitmap != null) {
                    Image(
                        bitmap = thumbBitmap.asImageBitmap(),
                        contentDescription = "Open in Editor",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.PhotoLibrary,
                        contentDescription = "Open Gallery",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Center: Iconic Pixel Camera Circular Shutter Button
            val shutterInteraction = remember { MutableInteractionSource() }
            val isPressed by shutterInteraction.collectIsPressedAsState()
            val shutterScale by animateFloatAsState(
                targetValue = if (isPressed) 0.90f else 1.0f,
                animationSpec = spring(stiffness = Spring.StiffnessHigh),
                label = "shutterScale"
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(84.dp)
                    .graphicsLayer {
                        scaleX = shutterScale
                        scaleY = shutterScale
                    }
                    .clip(CircleShape)
                    .border(5.dp, Color(0xFF5F6368), CircleShape)
                    .clickable(
                        interactionSource = shutterInteraction,
                        indication = null,
                        enabled = !uiState.isCapturingPhoto
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val isLinearTonemap = linearTonemapActive
                        if (hasCameraPermission) {
                            val capture = imageCaptureRef
                            val isFront = uiState.isFrontCamera
                            if (capture != null) {
                                // STEP 1: Strictly use the ImageCapture still, never the preview stream
                                capture.takePicture(
                                    ContextCompat.getMainExecutor(context),
                                    object : ImageCapture.OnImageCapturedCallback() {
                                        override fun onCaptureSuccess(image: ImageProxy) {
                                            val bmp = imageProxyToBitmap(image, isFront)
                                            image.close()
                                            if (bmp != null) {
                                                onCapturePhoto(bmp, "Camera", isLinearTonemap)
                                            }
                                        }

                                        override fun onError(exception: ImageCaptureException) {
                                            Log.e("ChronoCCD_Step1", "ImageCapture failed: ${exception.message}", exception)
                                        }
                                    }
                                )
                            }
                        } else {
                            onCapturePhoto(fallbackSceneBitmap, "Camera", false)
                        }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }

            // Right: Rounded-Square Flip Camera Button (matches screenshot)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF282A2D))
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggleCameraLens()
                    }
            ) {
                Icon(
                    imageVector = Icons.Rounded.Sync,
                    contentDescription = "Switch Camera",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
private fun configureImageCaptureWithCharacteristics(
    context: Context,
    isFrontCamera: Boolean,
    captureBuilder: ImageCapture.Builder
): Boolean {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
    val targetLensFacing = if (isFrontCamera) {
        CameraCharacteristics.LENS_FACING_FRONT
    } else {
        CameraCharacteristics.LENS_FACING_BACK
    }

    val characteristics = runCatching {
        val camId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == targetLensFacing
        } ?: cameraManager.cameraIdList.firstOrNull()
        camId?.let { cameraManager.getCameraCharacteristics(it) }
    }.getOrNull() ?: return false

    val availableKeys = runCatching {
        characteristics.availableCaptureRequestKeys?.toSet()
    }.getOrNull() ?: emptySet()

    val extender = Camera2Interop.Extender(captureBuilder)
    val appliedSummary = mutableListOf<String>()
    var linearTonemapActive = false

    // 1. NOISE_REDUCTION_MODE (OFF or MINIMAL, fallback to FAST) — attached ONLY to ImageCapture, never to Preview
    if (CaptureRequest.NOISE_REDUCTION_MODE in availableKeys) {
        val nrModes = characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: intArrayOf()
        val chosenNr = when {
            CaptureRequest.NOISE_REDUCTION_MODE_OFF in nrModes -> CaptureRequest.NOISE_REDUCTION_MODE_OFF
            CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL in nrModes -> CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL
            CaptureRequest.NOISE_REDUCTION_MODE_FAST in nrModes -> CaptureRequest.NOISE_REDUCTION_MODE_FAST
            else -> null
        }
        if (chosenNr != null) {
            extender.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, chosenNr)
            appliedSummary.add("NR=$chosenNr")
        }
    }

    // 2. EDGE_MODE (OFF, fallback to FAST) — attached ONLY to ImageCapture, never to Preview
    if (CaptureRequest.EDGE_MODE in availableKeys) {
        val edgeModes = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: intArrayOf()
        val chosenEdge = when {
            CaptureRequest.EDGE_MODE_OFF in edgeModes -> CaptureRequest.EDGE_MODE_OFF
            CaptureRequest.EDGE_MODE_FAST in edgeModes -> CaptureRequest.EDGE_MODE_FAST
            else -> null
        }
        if (chosenEdge != null) {
            extender.setCaptureRequestOption(CaptureRequest.EDGE_MODE, chosenEdge)
            appliedSummary.add("EDGE=$chosenEdge")
        }
    }

    // 3. Linear Tonemap: only use TONEMAP_MODE_GAMMA_VALUE if explicitly listed;
    // avoid forcing a raw (0,0)->(1,1) TONEMAP_MODE_CONTRAST_CURVE on 8-bit JPEG ImageCapture
    // because Pixel Lyric HAL drops AE post-gain by ~3.5 EV when contrast curve is overridden.
    if (CaptureRequest.TONEMAP_MODE in availableKeys) {
        val tonemapModes = characteristics.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: intArrayOf()
        if (CaptureRequest.TONEMAP_MODE_GAMMA_VALUE in tonemapModes && CaptureRequest.TONEMAP_GAMMA in availableKeys) {
            extender.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_GAMMA_VALUE)
            extender.setCaptureRequestOption(CaptureRequest.TONEMAP_GAMMA, 1.0f)
            linearTonemapActive = true
            appliedSummary.add("TONEMAP=GAMMA_1.0")
        }
    }

    // 4. COLOR_CORRECTION_ABERRATION_MODE_OFF if supported
    if (CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE in availableKeys) {
        val abModes = characteristics.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES) ?: intArrayOf()
        if (CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF in abModes) {
            extender.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF
            )
            appliedSummary.add("ABERRATION=OFF")
        }
    }

    // 5. DISTORTION_CORRECTION_MODE_OFF if supported
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        CaptureRequest.DISTORTION_CORRECTION_MODE in availableKeys
    ) {
        val distModes = characteristics.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES) ?: intArrayOf()
        if (CaptureRequest.DISTORTION_CORRECTION_MODE_OFF in distModes) {
            extender.setCaptureRequestOption(
                CaptureRequest.DISTORTION_CORRECTION_MODE,
                CaptureRequest.DISTORTION_CORRECTION_MODE_OFF
            )
            appliedSummary.add("DISTORTION=OFF")
        }
    }

    Log.i(
        "ChronoCCD_Step1",
        "Configured ImageCapture from CameraCharacteristics: ${appliedSummary.joinToString(", ")}, linearTonemapActive=$linearTonemapActive"
    )
    return linearTonemapActive
}

private fun imageProxyToBitmap(image: ImageProxy, isFrontCamera: Boolean = false): Bitmap? {
    return runCatching {
        val raw = image.toBitmap()
        val rotation = image.imageInfo.rotationDegrees.toFloat()
        if (rotation != 0f || isFrontCamera) {
            val matrix = Matrix().apply {
                if (rotation != 0f) postRotate(rotation)
                if (isFrontCamera) postScale(-1f, 1f)
            }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        } else {
            raw
        }
    }.getOrNull()
}
