package com.chronoccd.studio.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.chronoccd.studio.data.DigicamPersona
import com.chronoccd.studio.data.NostalgicDigicamCatalog
import com.chronoccd.studio.isp.DigicamAgslShader
import com.chronoccd.studio.ui.ChronoCCDUiState
import com.chronoccd.studio.ui.ChronoCCDViewModel
import com.chronoccd.studio.ui.components.AdjacentModePeekStrip
import com.chronoccd.studio.ui.components.CameraLookPill
import com.chronoccd.studio.ui.components.ExpressiveHeroActionButton
import com.chronoccd.studio.ui.components.ExpressiveSideActionTile
import com.chronoccd.studio.ui.components.ExpressiveTimestampPill
import com.chronoccd.studio.ui.components.ResolutionModeToggleBadge
import com.chronoccd.studio.ui.components.ViewfinderQuartzTimestampOverlay
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
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
    onCaptureError: (String) -> Unit = {},
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
    val motion = motionScheme

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
    var minZoomRatio by remember { mutableFloatStateOf(1.0f) }
    var maxZoomRatio by remember { mutableFloatStateOf(5.0f) }
    var isTakingPicture by remember { mutableStateOf(false) }

    // Dedicated single-thread executor for off-main-thread JPEG decoding
    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(captureExecutor) {
        onDispose {
            captureExecutor.shutdown()
        }
    }

    // Live smoothed accelerometer roll-angle horizon indicator
    var rollAngleDeg by remember { mutableFloatStateOf(0f) }
    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = if (sensorManager != null && accelerometer != null) {
            object : SensorEventListener {
                private var smoothedDeg = 0f
                override fun onSensorChanged(event: SensorEvent) {
                    val ax = event.values[0]
                    val ay = event.values[1]
                    val rawDeg = Math.toDegrees(atan2(ax.toDouble(), ay.toDouble())).toFloat()
                    var delta = rawDeg - smoothedDeg
                    while (delta > 180f) delta -= 360f
                    while (delta < -180f) delta += 360f
                    smoothedDeg += delta * 0.18f
                    val relativeToLevel = ((smoothedDeg + 45f) % 90f + 90f) % 90f - 45f
                    rollAngleDeg = relativeToLevel
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
        } else {
            null
        }
        if (listener != null && accelerometer != null) {
            sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose {
            if (listener != null) {
                sensorManager?.unregisterListener(listener)
            }
        }
    }

    // Xenon flash screen burst state triggered on shutter press
    var flashBurstVisible by remember { mutableStateOf(false) }
    LaunchedEffect(isTakingPicture) {
        if (isTakingPicture && uiState.ispTuning.xenonFlashEnabled) {
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

    // Bind CameraX lifecycle with disposal guard, matching 4:3 preview selector, and zoom range clamp
    DisposableEffect(hasCameraPermission, uiState.isFrontCamera, previewViewRef) {
        var isDisposed = false
        var boundProvider: ProcessCameraProvider? = null
        val pv = previewViewRef
        if (hasCameraPermission && pv != null) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            val executor = ContextCompat.getMainExecutor(context)
            val listener = Runnable {
                if (isDisposed) return@Runnable
                runCatching {
                    val provider = cameraProviderFuture.get()
                    if (isDisposed) return@runCatching
                    boundProvider = provider

                    val previewSelector = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .build()
                    // STEP 1: Never attach NOISE_REDUCTION_MODE / EDGE_MODE to Preview
                    val preview = Preview.Builder()
                        .setResolutionSelector(previewSelector)
                        .build()
                        .also {
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

                    val zoomState = camera.cameraInfo.zoomState.value
                    val minZ = (zoomState?.minZoomRatio ?: 1.0f).coerceAtLeast(0.5f)
                    val maxZ = (zoomState?.maxZoomRatio ?: 5.0f).coerceAtLeast(minZ)
                    minZoomRatio = minZ
                    maxZoomRatio = maxZ

                    val clampedZoom = uiState.digitalZoom.coerceIn(minZ, maxZ)
                    if (abs(clampedZoom - uiState.digitalZoom) > 0.01f) {
                        onSetZoom(clampedZoom)
                    }
                    camera.cameraControl.setZoomRatio(clampedZoom)
                    camera.cameraControl.setExposureCompensationIndex(0)
                }.onFailure { err ->
                    Log.e("ChronoCCD_Step1", "CameraX bind failed: ${err.message}", err)
                    onCaptureError("Camera error: ${err.message ?: "unable to start viewfinder"}")
                }
            }
            cameraProviderFuture.addListener(listener, executor)
        }
        onDispose {
            isDisposed = true
            runCatching { boundProvider?.unbindAll() }
            imageCaptureRef = null
            cameraControlRef = null
        }
    }

    LaunchedEffect(uiState.digitalZoom, minZoomRatio, maxZoomRatio) {
        runCatching {
            cameraControlRef?.setZoomRatio(uiState.digitalZoom.coerceIn(minZoomRatio, maxZoomRatio))
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

    val zoomStops = remember(minZoomRatio, maxZoomRatio) {
        buildList {
            if (minZoomRatio <= 0.75f) {
                val wide = if (minZoomRatio <= 0.55f) 0.5f else minZoomRatio
                add(wide to ".5")
            }
            add(1.0f.coerceIn(minZoomRatio, maxZoomRatio) to "1×")
            if (maxZoomRatio >= 2.0f) add(2.0f to "2")
            if (maxZoomRatio >= 5.0f) add(5.0f to "5")
            if (maxZoomRatio >= 10.0f) add(10.0f to "10")
        }
    }

    val currentIndex = NostalgicDigicamCatalog.indexOfFirst {
        it.id == uiState.selectedPersona.id
    }.coerceAtLeast(0)
    val prevPersona = NostalgicDigicamCatalog[(currentIndex - 1 + NostalgicDigicamCatalog.size) % NostalgicDigicamCatalog.size]
    val nextPersona = NostalgicDigicamCatalog[(currentIndex + 1) % NostalgicDigicamCatalog.size]

    // Horizontal swipe state for switching between the 5 2000s looks
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
        // 1. M3 Expressive Top Bar
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
                    text = "CCD",
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
                        contentDescription = "Toggle Xenon Flash",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // 2. Rounded Camera Preview + Adjacent M3 Expressive Mode Peek Strips
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

            // Main Rounded Camera Preview Card
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 22.dp, vertical = 4.dp)
                    .offset { IntOffset((dragOffsetX.value * 0.28f).roundToInt(), 0) }
                    .clip(RoundedCornerShape(34.dp))
                    .background(colorScheme.surfaceContainerLowest)
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
                if (hasCameraPermission) {
                    // Live CameraX Preview with Real-Time AGSL Shader
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                renderEffect = liveShaderEffect
                                clip = true
                            }
                    ) {
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
                    }
                } else {
                    // Clean tap-to-grant Camera permission prompt
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                            .padding(32.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = colorScheme.secondaryContainer,
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.CameraAlt,
                                    contentDescription = null,
                                    tint = colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Camera access required",
                            style = typography.titleMedium,
                            color = colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Grant camera permission to use the live 2000s CCD viewfinder and shutter.",
                            style = typography.bodyMedium,
                            color = colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(18.dp))
                        FilledTonalButton(
                            onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                            shapes = ButtonDefaults.shapes()
                        ) {
                            Text("Grant Camera Permission")
                        }
                    }
                }

                // Classic 7-Segment Quartz Timestamp Overlay in the Bottom Corner
                if (hasCameraPermission) {
                    ViewfinderQuartzTimestampOverlay(
                        timestampConfig = uiState.timestampConfig,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 58.dp)
                    )
                }

                // Top-Center Camera Look Pill
                CameraLookPill(
                    persona = uiState.selectedPersona,
                    swipeDirection = swipeDirection,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 14.dp)
                )

                // Live Smoothed Accelerometer Roll-Angle Horizon Indicator
                if (hasCameraPermission) {
                    val roundedDeg = rollAngleDeg.roundToInt()
                    val isLevel = abs(roundedDeg) <= 1
                    val levelColor by animateColorAsState(
                        targetValue = if (isLevel) colorScheme.tertiary else Color.White.copy(alpha = 0.72f),
                        animationSpec = motion.fastEffectsSpec(),
                        label = "horizonLevelColor"
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(width = 160.dp, height = 28.dp)
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { rotationZ = -rollAngleDeg }
                        ) {
                            val cy = size.height / 2f
                            drawLine(
                                color = levelColor,
                                start = Offset(0f, cy),
                                end = Offset(size.width * 0.38f, cy),
                                strokeWidth = (if (isLevel) 2.dp else 1.5.dp).toPx()
                            )
                            drawLine(
                                color = levelColor,
                                start = Offset(size.width * 0.62f, cy),
                                end = Offset(size.width, cy),
                                strokeWidth = (if (isLevel) 2.dp else 1.5.dp).toPx()
                            )
                        }
                        Text(
                            text = "${roundedDeg}°",
                            style = typography.labelSmall.copy(
                                fontWeight = if (isLevel) FontWeight.Bold else FontWeight.Medium,
                                fontFeatureSettings = "tnum"
                            ),
                            color = levelColor
                        )
                    }
                }

                // Bottom-Center Dynamic Supported Zoom Pill
                if (hasCameraPermission && zoomStops.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = colorScheme.surfaceContainerHigh.copy(alpha = 0.80f),
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
                            zoomStops.forEach { (zoomVal, label) ->
                                val isSelected = abs(uiState.digitalZoom - zoomVal) < 0.25f
                                val chipBg by animateColorAsState(
                                    targetValue = if (isSelected) colorScheme.primary else Color.Transparent,
                                    animationSpec = motion.fastEffectsSpec(),
                                    label = "zoomChipBg"
                                )
                                val chipFg by animateColorAsState(
                                    targetValue = if (isSelected) colorScheme.onPrimary else colorScheme.onSurface,
                                    animationSpec = motion.fastEffectsSpec(),
                                    label = "zoomChipFg"
                                )
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(chipBg)
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onSetZoom(zoomVal)
                                        }
                                ) {
                                    Text(
                                        text = if (isSelected && !label.endsWith("×")) "${label}×" else label,
                                        style = typography.labelMedium.copy(
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

        // 3. Bottom Shutter Row (Thumbnail + Queue Badge on Left, Hero Shutter in Center, Flip Camera on Right)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 42.dp, vertical = 14.dp)
        ) {
            // Left: Expressive Squircle Thumbnail with Background Processing Indicator
            val thumbBitmap = uiState.processedBitmap ?: uiState.sourceBitmap
            ExpressiveSideActionTile(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (uiState.capturedRoll.isNotEmpty() || uiState.processedBitmap != null || uiState.sourceBitmap != null) {
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
                        tint = colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }

                if (uiState.pendingCapturesCount > 0) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.52f))
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                        Text(
                            text = uiState.pendingCapturesCount.toString(),
                            style = typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontFeatureSettings = "tnum"
                            ),
                            color = Color.White
                        )
                    }
                }
            }

            // Center: M3 Expressive Hero Shutter Button (supports continuous background capture)
            val shutterBusy = isTakingPicture || uiState.pendingCapturesCount >= ChronoCCDViewModel.MAX_PENDING_CAPTURES
            ExpressiveHeroActionButton(
                enabled = hasCameraPermission && !shutterBusy,
                busy = shutterBusy,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val capture = imageCaptureRef
                    val isFront = uiState.isFrontCamera
                    val isLinearTonemap = linearTonemapActive
                    if (capture != null) {
                        isTakingPicture = true
                        val mainExecutor = ContextCompat.getMainExecutor(context)
                        // STEP 1: Strictly use the ImageCapture still, never the preview stream
                        capture.takePicture(
                            captureExecutor,
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val bmp = try {
                                        imageProxyToBitmap(image, isFront)
                                    } finally {
                                        image.close()
                                    }
                                    mainExecutor.execute {
                                        isTakingPicture = false
                                        if (bmp != null) {
                                            onCapturePhoto(bmp, "Camera", isLinearTonemap)
                                        } else {
                                            onCaptureError("Capture decode failed. Please try again.")
                                        }
                                    }
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    Log.e("ChronoCCD_Step1", "ImageCapture failed: ${exception.message}", exception)
                                    mainExecutor.execute {
                                        isTakingPicture = false
                                        onCaptureError("Capture failed: ${exception.message ?: "camera error"}")
                                    }
                                }
                            }
                        )
                    } else {
                        onCaptureError("Camera is still initializing…")
                    }
                }
            )

            // Right: Expressive Squircle Flip Camera Tile
            ExpressiveSideActionTile(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onToggleCameraLens()
                }
            ) {
                Icon(
                    imageVector = Icons.Rounded.Sync,
                    contentDescription = "Switch Camera",
                    tint = colorScheme.onSurface,
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
        characteristics.availableCaptureRequestKeys.toSet()
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
        val raw: Bitmap = if (image.format == ImageFormat.JPEG && image.planes.isNotEmpty()) {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
            var sampleSize = 1
            if (boundsOpts.outWidth > 0 && boundsOpts.outHeight > 0) {
                while (boundsOpts.outWidth / sampleSize > 4096 ||
                    boundsOpts.outHeight / sampleSize > 4096
                ) {
                    sampleSize *= 2
                }
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: image.toBitmap()
        } else {
            image.toBitmap()
        }

        val rotation = image.imageInfo.rotationDegrees.toFloat()
        if (rotation != 0f || isFrontCamera) {
            val matrix = Matrix().apply {
                if (rotation != 0f) postRotate(rotation)
                if (isFrontCamera) postScale(-1f, 1f)
            }
            val transformed = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
            if (transformed !== raw) {
                raw.recycle()
            }
            transformed
        } else {
            raw
        }
    }.getOrNull()
}
