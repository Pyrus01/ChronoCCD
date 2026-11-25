package com.chronoccd.studio.ui

import android.app.Application
import android.content.ContentUris
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
import com.chronoccd.studio.isp.BuiltInDemoScenes
import com.chronoccd.studio.isp.DemoSceneGenerator
import com.chronoccd.studio.isp.DigicamIspPipeline
import com.chronoccd.studio.isp.IspPipelineTelemetry
import com.chronoccd.studio.ui.theme.ExpressiveSeedPresets
import com.chronoccd.studio.ui.theme.SeedPreset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
    val pendingCapturesCount: Int = 0,
    val triggerXenonStrobeOverlay: Boolean = false,
    // Darkroom Editor state
    val sourceBitmap: Bitmap? = null,
    val sourceLinearTonemapActive: Boolean = false,
    val processedBitmap: Bitmap? = null,
    val latestTelemetry: IspPipelineTelemetry? = null,
    val isProcessingIsp: Boolean = false,
    val showOriginalComparison: Boolean = false,
    val currentSourceLabel: String = "No Photo Selected",
    // Saved Memory Stick Roll
    val capturedRoll: List<CapturedPhotoItem> = emptyList(),
    // Expressive Theme Settings
    val selectedSeedPreset: SeedPreset = ExpressiveSeedPresets.first(),
    val syncThemeWithCamera: Boolean = true,
    val darkThemeOverride: Boolean? = null,
    val blackAmoledTheme: Boolean = false,
    val statusBannerMessage: String? = null
)

private data class CaptureJobRequest(
    val rawBitmap: Bitmap,
    val sourceNote: String,
    val linearTonemapActive: Boolean,
    val persona: DigicamPersona,
    val tuning: IspUserTuning,
    val timestampConfig: TimestampConfig
)

class ChronoCCDViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val KEY_USE_FULL_SENSOR_RES = "use_full_sensor_resolution"
        const val MAX_PENDING_CAPTURES = 4
    }

    private val prefs = application.getSharedPreferences("chronoccd_prefs", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        ChronoCCDUiState(
            ispTuning = IspUserTuning(
                useFullSensorResolution = prefs.getBoolean(KEY_USE_FULL_SENSOR_RES, false)
            ),
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
    private var needsReprocessOnEditorOpen: Boolean = false
    private val captureQueue = Channel<CaptureJobRequest>(Channel.UNLIMITED)

    init {
        startCaptureQueueWorker()
        viewModelScope.launch {
            try {
                val recent = withContext(Dispatchers.IO) {
                    loadMostRecentAppPhoto(getApplication())
                }
                if (recent != null && _uiState.value.sourceBitmap == null) {
                    _uiState.update { state ->
                        if (state.sourceBitmap == null) {
                            state.copy(
                                sourceBitmap = recent.first,
                                sourceLinearTonemapActive = false,
                                processedBitmap = recent.first,
                                currentSourceLabel = recent.second
                            )
                        } else {
                            recent.first.recycle()
                            state
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Non-fatal: app opens cleanly with empty editor state
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pipelineJob?.cancel()
        captureQueue.close()
    }

    fun selectTab(tab: AppTab) {
        val prevTab = _uiState.value.currentTab
        _uiState.update { it.copy(currentTab = tab) }
        val state = _uiState.value
        if (tab != AppTab.CAMERA && prevTab == AppTab.CAMERA &&
            state.sourceBitmap != null &&
            (needsReprocessOnEditorOpen || state.processedBitmap == null)
        ) {
            needsReprocessOnEditorOpen = false
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
        var updatedFullRes = _uiState.value.ispTuning.useFullSensorResolution
        _uiState.update { state ->
            val next = transform(state.ispTuning)
            updatedFullRes = next.useFullSensorResolution
            state.copy(ispTuning = next)
        }
        prefs.edit().putBoolean(KEY_USE_FULL_SENSOR_RES, updatedFullRes).apply()
        reprocessCurrentImage()
    }

    fun resetIspTuningToCameraDefault() {
        _uiState.update { state ->
            state.copy(
                ispTuning = IspUserTuning(
                    useFullSensorResolution = state.ispTuning.useFullSensorResolution
                )
            )
        }
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

    fun toggleFullSensorResolution() {
        var nextFull = false
        _uiState.update { state ->
            nextFull = !state.ispTuning.useFullSensorResolution
            state.copy(
                ispTuning = state.ispTuning.copy(useFullSensorResolution = nextFull),
                statusBannerMessage = if (nextFull) {
                    "Resolution: Full Sensor Resolution (High-Res Still)"
                } else {
                    "Resolution: ${state.selectedPersona.displayName} Predetermined (${state.selectedPersona.megapixelLabel})"
                }
            )
        }
        prefs.edit().putBoolean(KEY_USE_FULL_SENSOR_RES, nextFull).apply()
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
            try {
                val bmp = withContext(Dispatchers.Default) {
                    DemoSceneGenerator.generateSceneBitmap(preset.id, 960, 720)
                }
                _uiState.update {
                    it.copy(
                        selectedDemoSceneId = preset.id,
                        sourceBitmap = bmp,
                        sourceLinearTonemapActive = false,
                        currentSourceLabel = "${preset.title} (${preset.badge})"
                    )
                }
                reprocessCurrentImage()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                showBannerMessage("Failed to load test scene.")
            }
        }
    }

    fun loadImageFromUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessingIsp = true) }
            try {
                val loaded = withContext(Dispatchers.IO) {
                    decodeSampledBitmapFromUri(getApplication(), uri, 4096)
                }
                if (loaded != null) {
                    needsReprocessOnEditorOpen = false
                    _uiState.update {
                        it.copy(
                            sourceBitmap = loaded,
                            sourceLinearTonemapActive = false,
                            currentSourceLabel = "Imported Photo • ${loaded.width}×${loaded.height}",
                            currentTab = AppTab.DARKROOM
                        )
                    }
                    reprocessCurrentImage(force = true)
                } else {
                    _uiState.update {
                        it.copy(
                            isProcessingIsp = false,
                            statusBannerMessage = "Could not decode selected image."
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                _uiState.update {
                    it.copy(
                        isProcessingIsp = false,
                        statusBannerMessage = "Could not open selected image."
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
        if (rawBitmap == null) {
            showBannerMessage("Capture failed: camera returned an empty frame.")
            return
        }

        val state = _uiState.value
        if (state.pendingCapturesCount >= MAX_PENDING_CAPTURES) {
            if (!rawBitmap.isRecycled) rawBitmap.recycle()
            showBannerMessage("Processing queue full ($MAX_PENDING_CAPTURES shots) — wait a moment.")
            return
        }

        val request = CaptureJobRequest(
            rawBitmap = rawBitmap,
            sourceNote = sourceNote,
            linearTonemapActive = linearTonemapActive,
            persona = state.selectedPersona,
            tuning = state.ispTuning,
            timestampConfig = state.timestampConfig
        )

        _uiState.update { current ->
            val nextPending = current.pendingCapturesCount + 1
            current.copy(
                pendingCapturesCount = nextPending,
                isCapturingPhoto = true,
                triggerXenonStrobeOverlay = request.tuning.xenonFlashEnabled
            )
        }

        val sent = captureQueue.trySend(request).isSuccess
        if (!sent) {
            if (!rawBitmap.isRecycled) rawBitmap.recycle()
            _uiState.update { current ->
                val nextPending = (current.pendingCapturesCount - 1).coerceAtLeast(0)
                current.copy(
                    pendingCapturesCount = nextPending,
                    isCapturingPhoto = nextPending > 0,
                    triggerXenonStrobeOverlay = false
                )
            }
        }
    }

    private fun startCaptureQueueWorker() {
        viewModelScope.launch {
            for (job in captureQueue) {
                var keepRawAsEditorSource = false
                try {
                    val fullResult = withContext(Dispatchers.Default) {
                        DigicamIspPipeline.processBitmap(
                            source = job.rawBitmap,
                            persona = job.persona,
                            tuning = job.tuning,
                            timestampConfig = job.timestampConfig,
                            isPreviewMode = false,
                            linearTonemapActive = job.linearTonemapActive
                        )
                    }

                    val (savedRollItem, gallerySuccess) = withContext(Dispatchers.IO) {
                        val item = saveProcessedBitmapToAppRoll(
                            context = getApplication(),
                            bitmap = fullResult.bitmap,
                            jpegBytes = fullResult.jpegBytes,
                            persona = job.persona,
                            timestampConfig = job.timestampConfig,
                            telemetry = fullResult.telemetry,
                            flashUsed = job.tuning.xenonFlashEnabled
                        )
                        val exported = exportBitmapToMediaStore(
                            context = getApplication(),
                            bitmap = fullResult.bitmap,
                            jpegBytes = fullResult.jpegBytes,
                            persona = job.persona,
                            jpegQuality = fullResult.telemetry.effectiveJpegQuality
                        )
                        item to exported
                    }

                    val remainingAfterThis = (_uiState.value.pendingCapturesCount - 1).coerceAtLeast(0)
                    val editorSourceBmp: Bitmap? = if (remainingAfterThis == 0) {
                        if (!job.tuning.useFullSensorResolution && maxOf(job.rawBitmap.width, job.rawBitmap.height) > 2048) {
                            val scale = 2048f / maxOf(job.rawBitmap.width, job.rawBitmap.height).toFloat()
                            val sw = (job.rawBitmap.width * scale).toInt().coerceAtLeast(64)
                            val sh = (job.rawBitmap.height * scale).toInt().coerceAtLeast(64)
                            withContext(Dispatchers.Default) {
                                Bitmap.createScaledBitmap(job.rawBitmap, sw, sh, true)
                            }
                        } else {
                            keepRawAsEditorSource = true
                            job.rawBitmap
                        }
                    } else {
                        null
                    }

                    needsReprocessOnEditorOpen = false
                    _uiState.update { current ->
                        val nextPending = (current.pendingCapturesCount - 1).coerceAtLeast(0)
                        current.copy(
                            pendingCapturesCount = nextPending,
                            isCapturingPhoto = nextPending > 0,
                            triggerXenonStrobeOverlay = false,
                            sourceBitmap = editorSourceBmp ?: current.sourceBitmap,
                            sourceLinearTonemapActive = if (editorSourceBmp != null) {
                                job.linearTonemapActive
                            } else {
                                current.sourceLinearTonemapActive
                            },
                            processedBitmap = fullResult.bitmap,
                            latestTelemetry = fullResult.telemetry,
                            currentSourceLabel = "${job.sourceNote} • ${job.persona.shortCode}",
                            capturedRoll = listOfNotNull(savedRollItem) + current.capturedRoll,
                            statusBannerMessage = if (nextPending == 0) {
                                if (gallerySuccess) {
                                    "Saved ${job.persona.displayName} photo to device (Pictures/ChronoCCD)"
                                } else {
                                    "Captured with ${job.persona.shortCode} ISP (${fullResult.telemetry.processingTimeMs} ms)"
                                }
                            } else {
                                current.statusBannerMessage
                            }
                        )
                    }
                } catch (e: CancellationException) {
                    if (!keepRawAsEditorSource && !job.rawBitmap.isRecycled) {
                        job.rawBitmap.recycle()
                    }
                    throw e
                } catch (e: Throwable) {
                    _uiState.update { current ->
                        val nextPending = (current.pendingCapturesCount - 1).coerceAtLeast(0)
                        current.copy(
                            pendingCapturesCount = nextPending,
                            isCapturingPhoto = nextPending > 0,
                            triggerXenonStrobeOverlay = false,
                            statusBannerMessage = "Capture processing failed: ${e.message ?: "Out of memory or I/O error"}"
                        )
                    }
                } finally {
                    if (!keepRawAsEditorSource && !job.rawBitmap.isRecycled) {
                        job.rawBitmap.recycle()
                    }
                }
            }
        }
    }

    fun saveCurrentToRollAndGallery() {
        val state = _uiState.value
        val source = state.sourceBitmap ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isProcessingIsp = true) }
            try {
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _uiState.update {
                    it.copy(
                        isProcessingIsp = false,
                        statusBannerMessage = "Could not save photo: ${e.message ?: "storage error"}"
                    )
                }
            }
        }
    }

    fun shareCurrentPhoto(context: Context) {
        val bmp = _uiState.value.processedBitmap ?: return
        val persona = _uiState.value.selectedPersona
        val exportQuality = persona.ispSpec.finalExportJpegQuality.coerceIn(92, 100)
        viewModelScope.launch {
            try {
                val shareUri = withContext(Dispatchers.IO) {
                    val shareDir = File(context.cacheDir, "shared_images").apply { mkdirs() }
                    cleanupOldShareFiles(shareDir)
                    val file = File(shareDir, "ChronoCCD_${persona.id}_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(file).use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, exportQuality, out)
                    }
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                }
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
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                showBannerMessage("Unable to prepare image for sharing.")
            }
        }
    }

    fun openRollItemInDarkroom(item: CapturedPhotoItem) {
        viewModelScope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) {
                    decodeSampledBitmapFromFile(File(item.filePath), 4096)
                }
                val persona = NostalgicDigicamCatalog.find { it.id == item.personaId } ?: _uiState.value.selectedPersona
                if (bmp != null) {
                    needsReprocessOnEditorOpen = false
                    _uiState.update {
                        it.copy(
                            selectedPersona = persona,
                            sourceBitmap = bmp,
                            sourceLinearTonemapActive = false,
                            processedBitmap = bmp,
                            currentSourceLabel = "Memory Stick • ${item.personaShortCode}",
                            currentTab = AppTab.DARKROOM
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                showBannerMessage("Could not open saved photo.")
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

    fun showBannerMessage(message: String) {
        _uiState.update { it.copy(statusBannerMessage = message) }
    }

    fun clearBannerMessage() {
        _uiState.update { it.copy(statusBannerMessage = null) }
    }

    private fun reprocessCurrentImage(force: Boolean = false) {
        val state = _uiState.value
        if (!force && state.currentTab == AppTab.CAMERA) {
            needsReprocessOnEditorOpen = true
            return
        }
        val source = state.sourceBitmap ?: return

        needsReprocessOnEditorOpen = false
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            _uiState.update { it.copy(isProcessingIsp = true) }
            try {
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _uiState.update {
                    it.copy(
                        isProcessingIsp = false,
                        statusBannerMessage = "Processing failed: ${e.message ?: "unexpected error"}"
                    )
                }
            }
        }
    }

    private fun loadMostRecentAppPhoto(context: Context): Pair<Bitmap, String>? {
        val rollDir = File(context.filesDir, "roll")
        if (rollDir.isDirectory) {
            val newestRollFile = rollDir.listFiles()
                ?.filter { f ->
                    f.isFile && (f.name.endsWith(".jpg", ignoreCase = true) ||
                        f.name.endsWith(".jpeg", ignoreCase = true))
                }
                ?.maxByOrNull { it.lastModified() }
            if (newestRollFile != null) {
                val bmp = decodeSampledBitmapFromFile(newestRollFile, 4096)
                if (bmp != null) {
                    return bmp to "Recent Capture • ${newestRollFile.nameWithoutExtension}"
                }
            }
        }

        return runCatching {
            val resolver = context.contentResolver
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME
            )
            val selection: String
            val selectionArgs: Array<String>
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
                selectionArgs = arrayOf("%${Environment.DIRECTORY_PICTURES}/ChronoCCD%")
            } else {
                @Suppress("DEPRECATION")
                selection = "${MediaStore.Images.Media.DATA} LIKE ?"
                selectionArgs = arrayOf("%ChronoCCD%")
            }
            val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(idCol)
                    val displayName = if (nameCol >= 0) cursor.getString(nameCol) else "ChronoCCD"
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    val bmp = decodeSampledBitmapFromUri(context, contentUri, 4096)
                    if (bmp != null) {
                        bmp to "ChronoCCD • $displayName"
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun cleanupOldShareFiles(shareDir: File) {
        val files = shareDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        val cutoffMs = System.currentTimeMillis() - 10 * 60 * 1000L
        files.forEachIndexed { index, file ->
            if (index >= 4 || file.lastModified() < cutoffMs) {
                runCatching { file.delete() }
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
        var insertedUri: Uri? = null
        return try {
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
            insertedUri = uri
            val stream = resolver.openOutputStream(uri)
                ?: throw IllegalStateException("Failed to open MediaStore stream")
            stream.use { out ->
                if (jpegBytes != null) {
                    out.write(jpegBytes)
                } else {
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)) {
                        throw IllegalStateException("Failed to compress JPEG to MediaStore")
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        } catch (e: CancellationException) {
            insertedUri?.let { orphan ->
                runCatching { context.contentResolver.delete(orphan, null, null) }
            }
            throw e
        } catch (_: Throwable) {
            insertedUri?.let { orphan ->
                runCatching { context.contentResolver.delete(orphan, null, null) }
            }
            false
        }
    }

    private fun decodeSampledBitmapFromFile(
        file: File,
        maxDimension: Int
    ): Bitmap? {
        return runCatching {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
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
            val rawBmp = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null
            val orientation = runCatching {
                ExifInterface(file.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            applyExifOrientation(rawBmp, orientation)
        }.getOrNull()
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

            val orientation = resolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL

            applyExifOrientation(rawBmp, orientation)
        }.getOrNull()
    }

    private fun applyExifOrientation(rawBmp: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return rawBmp
        }
        val transformed = Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, matrix, true)
        if (transformed !== rawBmp) {
            rawBmp.recycle()
        }
        return transformed
    }
}
