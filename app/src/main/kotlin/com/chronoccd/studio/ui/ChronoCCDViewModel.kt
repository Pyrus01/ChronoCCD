package com.chronoccd.studio.ui

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chronoccd.studio.data.CapturedPhotoItem
import com.chronoccd.studio.data.DigicamPersona
import com.chronoccd.studio.data.IspUserTuning
import com.chronoccd.studio.data.NostalgicDigicamCatalog
import com.chronoccd.studio.data.TimestampConfig
import com.chronoccd.studio.data.TimestampFormat
import com.chronoccd.studio.data.TimestampLedColor
import com.chronoccd.studio.data.TimestampPosition
import com.chronoccd.studio.isp.BuiltInDemoScenes
import com.chronoccd.studio.isp.DemoSceneGenerator
import com.chronoccd.studio.isp.DigicamIspPipeline
import com.chronoccd.studio.isp.IspPipelineTelemetry
import com.chronoccd.studio.ui.theme.ExpressiveSeedPresets
import com.chronoccd.studio.ui.theme.SeedPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class AppTab {
    CAMERA,
    DARKROOM,
    ROLL,
    ISP_LAB
}

data class ChronoCCDUiState(
    val currentTab: AppTab = AppTab.CAMERA,
    val selectedPersona: DigicamPersona = NostalgicDigicamCatalog.first(),
    val ispTuning: IspUserTuning = IspUserTuning(),
    val timestampConfig: TimestampConfig = TimestampConfig(
        enabled = true,
        format = NostalgicDigicamCatalog.first().defaultTimestampFormat,
        ledColor = NostalgicDigicamCatalog.first().defaultTimestampColor,
        nostalgicYear = NostalgicDigicamCatalog.first().releaseYear
    ),
    // Viewfinder controls
    val isFrontCamera: Boolean = false,
    val digitalZoom: Float = 1.0f,
    val showRetroLcdHud: Boolean = true,
    val useSimulatedViewfinder: Boolean = false,
    val selectedDemoSceneId: String = BuiltInDemoScenes.first().id,
    val isCapturingPhoto: Boolean = false,
    val triggerXenonStrobeOverlay: Boolean = false,
    // Darkroom Editor state
    val sourceBitmap: Bitmap? = null,
    val sourceLinearTonemapActive: Boolean = false,
    val processedBitmap: Bitmap? = null,
    val latestTelemetry: IspPipelineTelemetry? = null,
    val isProcessingIsp: Boolean = false,
    val showOriginalComparison: Boolean = false,
    val currentSourceLabel: String = "Y2K Nightclub Portrait (Built-in Test Scene)",
    // Saved Memory Stick Roll
    val capturedRoll: List<CapturedPhotoItem> = emptyList(),
    // Expressive Theme Settings
    val selectedSeedPreset: SeedPreset = ExpressiveSeedPresets.first(),
    val syncThemeWithCamera: Boolean = true,
    val darkThemeOverride: Boolean? = null,
    val blackAmoledTheme: Boolean = false,
    val statusBannerMessage: String? = null
)

class ChronoCCDViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("chronoccd_prefs", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        ChronoCCDUiState(
            syncThemeWithCamera = prefs.getBoolean("sync_theme_camera", true),
            blackAmoledTheme = prefs.getBoolean("black_amoled", false),
            darkThemeOverride = when (prefs.getInt("dark_mode_override", -1)) {
                0 -> false
                1 -> true
                else -> null
            }
        )
    )
    val uiState: StateFlow<ChronoCCDUiState> = _uiState.asStateFlow()

    private var pipelineJob: Job? = null

    init {
        // Initialize default built-in demo scene & run initial 9-stage ISP pass
        viewModelScope.launch {
            val initialScene = withContext(Dispatchers.Default) {
                DemoSceneGenerator.generateSceneBitmap(BuiltInDemoScenes.first().id, 960, 720)
            }
            _uiState.update { it.copy(sourceBitmap = initialScene) }
            reprocessCurrentImage(force = true)
        }
    }

    fun selectTab(tab: AppTab) {
        val prevTab = _uiState.value.currentTab
        _uiState.update { it.copy(currentTab = tab) }
        if (tab != AppTab.CAMERA && prevTab == AppTab.CAMERA) {
            reprocessCurrentImage(force = true)
        }
    }

    fun selectPersona(persona: DigicamPersona, syncTimestampDefaults: Boolean = true) {
        _uiState.update { state ->
            val updatedTimestamp = if (syncTimestampDefaults) {
                state.timestampConfig.copy(
                    format = persona.defaultTimestampFormat,
                    ledColor = persona.defaultTimestampColor,
                    nostalgicYear = persona.releaseYear
                )
            } else {
                state.timestampConfig
            }
            state.copy(
                selectedPersona = persona,
                timestampConfig = updatedTimestamp
            )
        }
        reprocessCurrentImage()
    }

    fun updateIspTuning(transform: (IspUserTuning) -> IspUserTuning) {
        _uiState.update { it.copy(ispTuning = transform(it.ispTuning)) }
        reprocessCurrentImage()
    }

    fun resetIspTuningToCameraDefault() {
        _uiState.update { it.copy(ispTuning = IspUserTuning()) }
        reprocessCurrentImage()
    }

    fun updateTimestampConfig(transform: (TimestampConfig) -> TimestampConfig) {
        _uiState.update { it.copy(timestampConfig = transform(it.timestampConfig)) }
        reprocessCurrentImage()
    }

    fun toggleXenonFlash() {
        _uiState.update {
            it.copy(ispTuning = it.ispTuning.copy(xenonFlashEnabled = !it.ispTuning.xenonFlashEnabled))
        }
        reprocessCurrentImage()
    }

    fun toggleExportNativeInternalSize() {
        _uiState.update { state ->
            val nextNative = !state.ispTuning.exportNativeInternalSize
            state.copy(
                ispTuning = state.ispTuning.copy(exportNativeInternalSize = nextNative),
                statusBannerMessage = if (nextNative) {
                    "Export mode: Native internal sensor size (${state.selectedPersona.sensorBadge})"
                } else {
                    "Export mode: 1080×1440 Lanczos-3 upscale (Q95)"
                }
            )
        }
        reprocessCurrentImage()
    }

    fun toggleCameraLens() {
        _uiState.update { it.copy(isFrontCamera = !it.isFrontCamera) }
    }

    fun cycleDigitalZoom() {
        _uiState.update { state ->
            val nextZoom = when {
                state.digitalZoom < 1.3f -> 1.5f
                state.digitalZoom < 2.2f -> 3.0f
                else -> 1.0f
            }
            state.copy(digitalZoom = nextZoom)
        }
    }

    fun setDigitalZoom(zoom: Float) {
        _uiState.update { it.copy(digitalZoom = zoom.coerceIn(0.5f, 10f)) }
    }

    fun cycleTimestampStyle() {
        _uiState.update { state ->
            val formats = TimestampFormat.entries
            val colors = TimestampLedColor.entries
            val nextFmtIdx = (formats.indexOf(state.timestampConfig.format) + 1) % formats.size
            val nextColIdx = if (nextFmtIdx == 0) {
                (colors.indexOf(state.timestampConfig.ledColor) + 1) % colors.size
            } else {
                colors.indexOf(state.timestampConfig.ledColor)
            }
            state.copy(
                timestampConfig = state.timestampConfig.copy(
                    enabled = true,
                    format = formats[nextFmtIdx],
                    ledColor = colors[nextColIdx]
                )
            )
        }
        reprocessCurrentImage()
    }

    fun toggleRetroLcdHud() {
        _uiState.update { it.copy(showRetroLcdHud = !it.showRetroLcdHud) }
    }

    fun setSimulatedViewfinder(enabled: Boolean) {
        _uiState.update { it.copy(useSimulatedViewfinder = enabled) }
    }

    fun setShowOriginalComparison(showOriginal: Boolean) {
        _uiState.update { it.copy(showOriginalComparison = showOriginal) }
    }

    fun loadBuiltInDemoScene(sceneId: String) {
        val preset = BuiltInDemoScenes.find { it.id == sceneId } ?: BuiltInDemoScenes.first()
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.Default) {
                DemoSceneGenerator.generateSceneBitmap(preset.id, 960, 720)
            }
            _uiState.update {
                it.copy(
                    selectedDemoSceneId = preset.id,
                    sourceBitmap = bmp,
                    currentSourceLabel = "${preset.title} (${preset.badge})"
                )
            }
            reprocessCurrentImage()
        }
    }

    fun loadImageFromUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessingIsp = true) }
            val loaded = withContext(Dispatchers.IO) {
                decodeSampledBitmapFromUri(getApplication(), uri, 4096)
            }
            if (loaded != null) {
                _uiState.update {
                    it.copy(
                        sourceBitmap = loaded,
                        sourceLinearTonemapActive = false,
                        currentSourceLabel = "Imported Photo • ${loaded.width}×${loaded.height}",
                        currentTab = AppTab.DARKROOM
                    )
                }
                reprocessCurrentImage()
            } else {
                _uiState.update {
                    it.copy(
                        isProcessingIsp = false,
                        statusBannerMessage = "Could not decode selected image."
                    )
                }
            }
        }
    }

    fun captureFromSimulatedSceneOrBitmap(
        rawBitmap: Bitmap?,
        sourceNote: String = "Viewfinder Capture",
        linearTonemapActive: Boolean = false
    ) {
        viewModelScope.launch {
            val state = _uiState.value
            _uiState.update {
                it.copy(
                    isCapturingPhoto = true,
                    triggerXenonStrobeOverlay = state.ispTuning.xenonFlashEnabled
                )
            }

            val inputBmp = rawBitmap ?: withContext(Dispatchers.Default) {
                DemoSceneGenerator.generateSceneBitmap(state.selectedDemoSceneId, 1280, 960)
            }
            val effectiveLinearTonemap = if (rawBitmap != null) linearTonemapActive else false

            val fullResult = withContext(Dispatchers.Default) {
                DigicamIspPipeline.processBitmap(
                    source = inputBmp,
                    persona = state.selectedPersona,
                    tuning = state.ispTuning,
                    timestampConfig = state.timestampConfig,
                    isPreviewMode = false,
                    linearTonemapActive = effectiveLinearTonemap
                )
            }

            val (savedRollItem, gallerySuccess) = withContext(Dispatchers.IO) {
                val item = saveProcessedBitmapToAppRoll(
                    context = getApplication(),
                    bitmap = fullResult.bitmap,
                    jpegBytes = fullResult.jpegBytes,
                    persona = state.selectedPersona,
                    timestampConfig = state.timestampConfig,
                    telemetry = fullResult.telemetry,
                    flashUsed = state.ispTuning.xenonFlashEnabled
                )
                val exported = exportBitmapToMediaStore(
                    context = getApplication(),
                    bitmap = fullResult.bitmap,
                    jpegBytes = fullResult.jpegBytes,
                    persona = state.selectedPersona,
                    jpegQuality = fullResult.telemetry.effectiveJpegQuality
                )
                item to exported
            }

            val editorSourceBmp = if (maxOf(inputBmp.width, inputBmp.height) > 2048) {
                val scale = 2048f / maxOf(inputBmp.width, inputBmp.height).toFloat()
                val sw = (inputBmp.width * scale).toInt().coerceAtLeast(64)
                val sh = (inputBmp.height * scale).toInt().coerceAtLeast(64)
                Bitmap.createScaledBitmap(inputBmp, sw, sh, true)
            } else {
                inputBmp
            }

            _uiState.update { current ->
                current.copy(
                    isCapturingPhoto = false,
                    triggerXenonStrobeOverlay = false,
                    sourceBitmap = editorSourceBmp,
                    sourceLinearTonemapActive = effectiveLinearTonemap,
                    processedBitmap = fullResult.bitmap,
                    latestTelemetry = fullResult.telemetry,
                    currentSourceLabel = "$sourceNote • ${state.selectedPersona.shortCode}",
                    capturedRoll = listOfNotNull(savedRollItem) + current.capturedRoll,
                    statusBannerMessage = if (gallerySuccess) {
                        "Saved ${state.selectedPersona.displayName} photo to device (Pictures/ChronoCCD)"
                    } else {
                        "Captured with ${state.selectedPersona.shortCode} ISP (${fullResult.telemetry.processingTimeMs} ms)"
                    }
                )
            }
        }
    }

    fun saveCurrentToRollAndGallery() {
        val state = _uiState.value
        val source = state.sourceBitmap ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isProcessingIsp = true) }
            val highResResult = withContext(Dispatchers.Default) {
                DigicamIspPipeline.processBitmap(
                    source = source,
                    persona = state.selectedPersona,
                    tuning = state.ispTuning,
                    timestampConfig = state.timestampConfig,
                    isPreviewMode = false,
                    linearTonemapActive = state.sourceLinearTonemapActive
                )
            }

            val (rollItem, gallerySuccess) = withContext(Dispatchers.IO) {
                val item = saveProcessedBitmapToAppRoll(
                    context = getApplication(),
                    bitmap = highResResult.bitmap,
                    jpegBytes = highResResult.jpegBytes,
                    persona = state.selectedPersona,
                    timestampConfig = state.timestampConfig,
                    telemetry = highResResult.telemetry,
                    flashUsed = state.ispTuning.xenonFlashEnabled
                )
                val exported = exportBitmapToMediaStore(
                    context = getApplication(),
                    bitmap = highResResult.bitmap,
                    jpegBytes = highResResult.jpegBytes,
                    persona = state.selectedPersona,
                    jpegQuality = highResResult.telemetry.effectiveJpegQuality
                )
                item to exported
            }

            _uiState.update { current ->
                current.copy(
                    isProcessingIsp = false,
                    processedBitmap = highResResult.bitmap,
                    latestTelemetry = highResResult.telemetry,
                    capturedRoll = listOfNotNull(rollItem) + current.capturedRoll,
                    statusBannerMessage = if (gallerySuccess) {
                        "Saved ${state.selectedPersona.shortCode} photo to Gallery (Pictures/ChronoCCD)"
                    } else {
                        "Saved ${state.selectedPersona.shortCode} photo to Memory Stick Roll"
                    }
                )
            }
        }
    }

    fun shareCurrentPhoto(context: Context) {
        val bmp = _uiState.value.processedBitmap ?: return
        val persona = _uiState.value.selectedPersona
        viewModelScope.launch {
            val shareUri = withContext(Dispatchers.IO) {
                runCatching {
                    val shareDir = File(context.cacheDir, "shared_images").apply { mkdirs() }
                    val file = File(shareDir, "ChronoCCD_${persona.id}_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(file).use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, persona.ispSpec.jpegQuality, out)
                    }
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                }.getOrNull()
            }
            if (shareUri != null) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, shareUri)
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "Processed with ChronoCCD • ${persona.displayName} (${persona.releaseYear})"
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Share Digicam Photo"))
            } else {
                _uiState.update { it.copy(statusBannerMessage = "Unable to prepare image for sharing.") }
            }
        }
    }

    fun openRollItemInDarkroom(item: CapturedPhotoItem) {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(item.filePath)
            }
            val persona = NostalgicDigicamCatalog.find { it.id == item.personaId } ?: _uiState.value.selectedPersona
            if (bmp != null) {
                _uiState.update {
                    it.copy(
                        selectedPersona = persona,
                        sourceBitmap = bmp,
                        processedBitmap = bmp,
                        currentSourceLabel = "Memory Stick • ${item.personaShortCode}",
                        currentTab = AppTab.DARKROOM
                    )
                }
            }
        }
    }

    fun deleteRollItem(item: CapturedPhotoItem) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { File(item.filePath).delete() }
            _uiState.update { state ->
                state.copy(
                    capturedRoll = state.capturedRoll.filterNot { it.id == item.id },
                    statusBannerMessage = "Deleted photo from Memory Stick Roll"
                )
            }
        }
    }

    // Expressive Theme Settings
    fun selectSeedPreset(preset: SeedPreset) {
        _uiState.update { it.copy(selectedSeedPreset = preset, syncThemeWithCamera = false) }
        prefs.edit().putBoolean("sync_theme_camera", false).apply()
    }

    fun setSyncThemeWithCamera(sync: Boolean) {
        _uiState.update { it.copy(syncThemeWithCamera = sync) }
        prefs.edit().putBoolean("sync_theme_camera", sync).apply()
    }

    fun setDarkThemeOverride(override: Boolean?) {
        _uiState.update { it.copy(darkThemeOverride = override) }
        val code = when (override) {
            false -> 0
            true -> 1
            null -> -1
        }
        prefs.edit().putInt("dark_mode_override", code).apply()
    }

    fun setAmoledBlack(enabled: Boolean) {
        _uiState.update { it.copy(blackAmoledTheme = enabled) }
        prefs.edit().putBoolean("black_amoled", enabled).apply()
    }

    fun clearBannerMessage() {
        _uiState.update { it.copy(statusBannerMessage = null) }
    }

    private fun reprocessCurrentImage(force: Boolean = false) {
        val state = _uiState.value
        if (!force && state.currentTab == AppTab.CAMERA) return
        val source = state.sourceBitmap ?: return

        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            _uiState.update { it.copy(isProcessingIsp = true) }
            val result = withContext(Dispatchers.Default) {
                DigicamIspPipeline.processBitmap(
                    source = source,
                    persona = state.selectedPersona,
                    tuning = state.ispTuning,
                    timestampConfig = state.timestampConfig,
                    isPreviewMode = true,
                    linearTonemapActive = state.sourceLinearTonemapActive
                )
            }
            _uiState.update {
                it.copy(
                    processedBitmap = result.bitmap,
                    latestTelemetry = result.telemetry,
                    isProcessingIsp = false
                )
            }
        }
    }

    private fun saveProcessedBitmapToAppRoll(
        context: Context,
        bitmap: Bitmap,
        jpegBytes: ByteArray? = null,
        persona: DigicamPersona,
        timestampConfig: TimestampConfig,
        telemetry: IspPipelineTelemetry,
        flashUsed: Boolean
    ): CapturedPhotoItem? {
        return runCatching {
            val rollDir = File(context.filesDir, "roll").apply { mkdirs() }
            val id = UUID.randomUUID().toString()
            val file = File(rollDir, "DSC_${id.take(8).uppercase()}.JPG")
            FileOutputStream(file).use { out ->
                if (jpegBytes != null) {
                    out.write(jpegBytes)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, telemetry.effectiveJpegQuality, out)
                }
            }
            val nowStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM dd, HH:mm"))
            CapturedPhotoItem(
                id = id,
                filePath = file.absolutePath,
                personaId = persona.id,
                personaShortCode = persona.shortCode,
                personaDisplayName = persona.displayName,
                timestampImprinted = if (timestampConfig.enabled) timestampConfig.formattedString() else null,
                capturedAtFormatted = nowStr,
                resolutionLabel = "${telemetry.effectiveSensorWidth}×${telemetry.effectiveSensorHeight} CCD → ${telemetry.outputWidth}×${telemetry.outputHeight}",
                jpegQuality = telemetry.effectiveJpegQuality,
                flashUsed = flashUsed,
                processingTimeMs = telemetry.processingTimeMs
            )
        }.getOrNull()
    }

    private fun exportBitmapToMediaStore(
        context: Context,
        bitmap: Bitmap,
        jpegBytes: ByteArray? = null,
        persona: DigicamPersona,
        jpegQuality: Int
    ): Boolean {
        return runCatching {
            val filename = "DSC_${persona.shortCode.replace("-", "_")}_${System.currentTimeMillis()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ChronoCCD")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { out ->
                if (jpegBytes != null) {
                    out.write(jpegBytes)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        }.getOrDefault(false)
    }

    private fun decodeSampledBitmapFromUri(
        context: Context,
        uri: Uri,
        maxDimension: Int
    ): Bitmap? {
        return runCatching {
            val resolver = context.contentResolver
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

            var sampleSize = 1
            while (boundsOpts.outWidth / sampleSize > maxDimension * 2 ||
                boundsOpts.outHeight / sampleSize > maxDimension * 2
            ) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val rawBmp = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return null

            val rotation = resolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f

            if (rotation != 0f) {
                val matrix = Matrix().apply { postRotate(rotation) }
                val rotated = Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, matrix, true)
                if (rotated !== rawBmp) rawBmp.recycle()
                rotated
            } else {
                rawBmp
            }
        }.getOrNull()
    }
}
