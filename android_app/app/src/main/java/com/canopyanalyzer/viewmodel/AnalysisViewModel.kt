package com.canopyanalyzer.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.canopyanalyzer.BuildConfig
import com.canopyanalyzer.core.CanopyProcessor
import com.canopyanalyzer.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.sqrt

class AnalysisViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        // Images larger than this (in total pixels) will trigger a warning.
        // 8000x8000 = 64MP — well beyond typical fisheye cameras.
        private const val LARGE_IMAGE_PIXEL_THRESHOLD = 64_000_000L
        // Max dimension to auto-downscale very large images for processing
        private const val MAX_PROCESSING_DIM = 6000
    }

    sealed class UiState {
        object Idle : UiState()
        data class ImageLoaded(
            val bitmap: Bitmap,
            val filename: String,
            val sourceWidth: Int,
            val sourceHeight: Int,
            val analysisWidth: Int,
            val analysisHeight: Int,
            val wasDownscaled: Boolean = false
        ) : UiState()
        data class Processing(val step: String, val progress: Int) : UiState()
        data class MaskAdjust(
            val previewBitmap: Bitmap,
            val origW: Int,
            val origH: Int,
            val xc: Int,
            val yc: Int,
            val rc: Int,
            val isBatchSetup: Boolean = false,
            val batchCount: Int = 0
        ) : UiState()
        data class Success(val result: AnalysisResult) : UiState()
        data class Error(val message: String) : UiState()
    }

    private data class LoadedImage(
        val analysisBitmap: Bitmap,
        val previewBitmap: Bitmap,
        val filename: String,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val wasDownscaled: Boolean
    )

    private val _uiState = MutableLiveData<UiState>(UiState.Idle)
    val uiState: LiveData<UiState> get() = _uiState

    private val _settings = MutableLiveData(AnalysisSettings())
    val settings: LiveData<AnalysisSettings> get() = _settings

    private val _result = MutableLiveData<AnalysisResult?>()
    val result: LiveData<AnalysisResult?> get() = _result

    /** Emits intermediate bitmaps during analysis so the Visualize tab can show them live. */
    private val _processingBitmap = MutableLiveData<Bitmap?>(null)
    val processingBitmap: LiveData<Bitmap?> get() = _processingBitmap

    private var sourceBitmap: Bitmap? = null
    private var previewBitmap: Bitmap? = null
    private var sourceFilename: String = "image"
    private var analysisJob: Job? = null

    private fun recycleBitmap(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) {
            bitmap.recycle()
        }
    }

    private fun clearCurrentResult() {
        _result.value?.let { result ->
            recycleBitmap(result.processedBitmap)
            recycleBitmap(result.binaryBitmap)
        }
        _result.value = null
        _processingBitmap.value = null
    }

    fun updateSettings(settings: AnalysisSettings) {
        _settings.value = settings
    }

    fun loadImage(uri: Uri) {
        analysisJob?.cancel()
        clearCurrentResult()
        analysisJob = viewModelScope.launch {
            try {
                _uiState.value = UiState.Processing("Loading image…", 5)
                val ctx = getApplication<Application>()
                val loadedImage = withContext(Dispatchers.IO) {
                    val contentResolver = ctx.contentResolver

                    // Load full resolution for analysis — NO EXIF rotation applied.
                    // Python PIL.Image.open() also returns the image in its stored
                    // (on-disk) orientation without honouring the EXIF tag, so the
                    // mask coordinates are calibrated for the stored pixel layout.
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inScaled = false
                    }
                    val raw = contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, opts)
                    } ?: throw IllegalArgumentException("Cannot decode image")
                    if (BuildConfig.DEBUG) Log.d("CanopyDebug", "Loaded via URI: ${raw.width}x${raw.height}  config=${raw.config}  colorSpace=${raw.colorSpace?.name}")
                    val sourceWidth = raw.width
                    val sourceHeight = raw.height

                    // Create preview BEFORE stripping color space (raw still valid here).
                    // Preview is for display only — color-managed sRGB is fine.
                    val previewBmp = correctOrientation(createPreview(raw), ctx, uri)

                    // Python PIL returns raw JPEG byte values WITHOUT applying the ICC profile.
                    // Android getPixels() converts non-sRGB bitmaps (e.g. Display P3) to sRGB,
                    // changing every pixel value. Strip the ICC tag so getPixels() returns the
                    // same raw bytes that Python PIL reads.
                    val stripped = stripColorSpace(raw)
                    if (stripped !== raw) raw.recycle()

                    // Auto-downscale very large images to prevent OOM
                    val (fullBmp, wasDownscaled) = downscaleIfNeeded(stripped)
                    if (fullBmp !== stripped) stripped.recycle()

                    val imgName = runCatching {
                        contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
                            null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else "image.jpg"
                        } ?: "image.jpg"
                    }.getOrDefault("image.jpg")

                    LoadedImage(
                        analysisBitmap = fullBmp,
                        previewBitmap = previewBmp,
                        filename = imgName,
                        sourceWidth = sourceWidth,
                        sourceHeight = sourceHeight,
                        wasDownscaled = wasDownscaled
                    )
                }

                recycleBitmap(sourceBitmap)
                recycleBitmap(previewBitmap)

                val fullBmp = loadedImage.analysisBitmap
                val previewBmp = loadedImage.previewBitmap
                sourceBitmap = fullBmp
                previewBitmap = previewBmp
                sourceFilename = loadedImage.filename
                _uiState.value = UiState.ImageLoaded(
                    bitmap = previewBmp,
                    filename = loadedImage.filename,
                    sourceWidth = loadedImage.sourceWidth,
                    sourceHeight = loadedImage.sourceHeight,
                    analysisWidth = fullBmp.width,
                    analysisHeight = fullBmp.height,
                    wasDownscaled = loadedImage.wasDownscaled
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to load image: ${e.message}")
            }
        }
    }

    fun loadImageFromFile(file: File) {
        analysisJob?.cancel()
        clearCurrentResult()
        analysisJob = viewModelScope.launch {
            try {
                _uiState.value = UiState.Processing("Loading image…", 5)
                val loadedImage = withContext(Dispatchers.IO) {
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inScaled = false
                    }
                    val raw = BitmapFactory.decodeFile(file.absolutePath, opts)
                        ?: throw IllegalArgumentException("Cannot decode image file")
                    if (BuildConfig.DEBUG) Log.d("CanopyDebug", "Loaded via File: ${raw.width}x${raw.height}  config=${raw.config}  colorSpace=${raw.colorSpace?.name}")
                    val sourceWidth = raw.width
                    val sourceHeight = raw.height

                    // Create preview BEFORE stripping color space (raw still valid here).
                    val exif = ExifInterface(file.absolutePath)
                    val previewBmp = applyExifRotation(createPreview(raw), exif)

                    // Strip ICC profile tag so getPixels() returns raw bytes matching Python PIL.
                    val stripped = stripColorSpace(raw)
                    if (stripped !== raw) raw.recycle()

                    val (fullBmp, wasDownscaled) = downscaleIfNeeded(stripped)
                    if (fullBmp !== stripped) stripped.recycle()

                    LoadedImage(
                        analysisBitmap = fullBmp,
                        previewBitmap = previewBmp,
                        filename = file.name,
                        sourceWidth = sourceWidth,
                        sourceHeight = sourceHeight,
                        wasDownscaled = wasDownscaled
                    )
                }

                recycleBitmap(sourceBitmap)
                recycleBitmap(previewBitmap)

                val fullBmp = loadedImage.analysisBitmap
                val previewBmp = loadedImage.previewBitmap
                sourceBitmap = fullBmp
                previewBitmap = previewBmp
                sourceFilename = loadedImage.filename
                _uiState.value = UiState.ImageLoaded(
                    bitmap = previewBmp,
                    filename = loadedImage.filename,
                    sourceWidth = loadedImage.sourceWidth,
                    sourceHeight = loadedImage.sourceHeight,
                    analysisWidth = fullBmp.width,
                    analysisHeight = fullBmp.height,
                    wasDownscaled = loadedImage.wasDownscaled
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to load image: ${e.message}")
            }
        }
    }

    private fun createPreview(source: Bitmap): Bitmap {
        val maxDim = 1024  // preview dimensions — display only, not used for processing
        if (source.width <= maxDim && source.height <= maxDim) return source.copy(source.config, false)
        val scale = maxDim.toFloat() / maxOf(source.width, source.height)
        return Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true)
    }

    fun runAnalysis() {
        val bmp = sourceBitmap ?: run {
            _uiState.value = UiState.Error("No image loaded.")
            return
        }
        val currentSettings = _settings.value ?: AnalysisSettings()

        // For AUTO mask mode: compute the default circle from image dimensions and
        // show the adjustment UI — no heavy processing yet.
        if (currentSettings.maskMode == MaskMode.AUTO) {
            val preview = previewBitmap ?: run {
                _uiState.value = UiState.Error("No image loaded.")
                return
            }
            val xc = bmp.width / 2
            val yc = bmp.height / 2
            val rc = if (currentSettings.circularFisheye) minOf(xc, yc) - 2
                     else (sqrt((xc.toDouble() * xc + yc.toDouble() * yc)) - 2).toInt()
            _uiState.value = UiState.MaskAdjust(preview, bmp.width, bmp.height, xc, yc, rc)
            return
        }

        runFullPipeline(currentSettings)
    }

    /**
     * Called from the mask-adjustment UI when the user is satisfied with the circle.
     *
     * If [pendingBatchUris] is set, the adjusted mask is applied to ALL batch images
     * and [runBatchAnalysis] is invoked with a MANUAL mask override.
     * Otherwise, runs the normal single-image full pipeline.
     */
    fun proceedWithMask(xc: Int, yc: Int, rc: Int) {
        if (sourceBitmap == null) {
            _uiState.value = UiState.Error("No image loaded.")
            return
        }
        val baseSettings = _settings.value ?: AnalysisSettings()
        val effectiveSettings = baseSettings.copy(
            maskMode = MaskMode.MANUAL,
            maskXc = xc, maskYc = yc, maskRc = rc
        )

        val batchUris = pendingBatchUris
        if (batchUris != null) {
            pendingBatchUris = null
            // Save mask into persisted settings so batch uses it
            _settings.value = effectiveSettings
            runBatchAnalysis(batchUris)
        } else {
            runFullPipeline(effectiveSettings)
        }
    }

    private fun runFullPipeline(settings: AnalysisSettings) {
        val bmp = sourceBitmap ?: return
        analysisJob?.cancel()
        clearCurrentResult()
        analysisJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    emit("Step 1/4 · Extracting channel & applying mask…", 15)
                    val processedData = CanopyProcessor.processImage(bmp, settings, sourceFilename)
                    // Emit processed (channel/gamma) image so Visualize tab shows it live
                    val procBmp = CanopyProcessor.processedToDisplayBitmap(processedData)
                    withContext(Dispatchers.Main) { _processingBitmap.value = procBmp }

                    emit("Step 2/4 · Binarizing image…", 40)
                    val binaryData = CanopyProcessor.binarize(processedData, settings)
                    // Emit binary image
                    val binBmp = CanopyProcessor.binaryToDisplayBitmap(binaryData)
                    withContext(Dispatchers.Main) { _processingBitmap.value = binBmp }

                    emit("Step 3/4 · Computing gap fractions…", 60)
                    val gfData = CanopyProcessor.computeGapFraction(binaryData, settings)

                    emit("Step 4/4 · Deriving canopy attributes…", 85)
                    val canopyResult = CanopyProcessor.computeCanopyAttributes(
                        gfData, settings, processedData.meta)

                    emit("Finalising…", 95)
                    // procBmp and binBmp already created above — reuse for AnalysisResult
                    AnalysisResult(processedData, binaryData, gfData, canopyResult, procBmp, binBmp)
                }
                _processingBitmap.value = null
                _result.value = result
                _uiState.value = UiState.Success(result)
            } catch (e: Exception) {
                _processingBitmap.value = null
                Log.e("CanopyDebug", "Analysis failed", e)
                _uiState.value = UiState.Error(
                    "Analysis failed: ${e.javaClass.simpleName} — ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    private suspend fun emit(step: String, progress: Int) {
        withContext(Dispatchers.Main) { _uiState.value = UiState.Processing(step, progress) }
    }

    private fun correctOrientation(bitmap: Bitmap, ctx: android.content.Context, uri: Uri): Bitmap {
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { stream ->
                applyExifRotation(bitmap, ExifInterface(stream))
            } ?: bitmap
        } catch (e: Exception) { bitmap }
    }

    private fun applyExifRotation(bitmap: Bitmap, exif: ExifInterface): Bitmap {
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val matrix = android.graphics.Matrix()
        var rotated = false
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90  -> { matrix.postRotate(90f); rotated = true }
            ExifInterface.ORIENTATION_ROTATE_180 -> { matrix.postRotate(180f); rotated = true }
            ExifInterface.ORIENTATION_ROTATE_270 -> { matrix.postRotate(270f); rotated = true }
        }
        if (!rotated) return bitmap
        
        val result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (result != bitmap) bitmap.recycle()
        return result
    }

    /**
     * Downscales a bitmap if it exceeds [MAX_PROCESSING_DIM] on either axis.
     * Returns the (possibly new) bitmap and whether downscaling was applied.
     */
    private fun downscaleIfNeeded(bitmap: Bitmap): Pair<Bitmap, Boolean> {
        val maxDim = max(bitmap.width, bitmap.height)
        if (maxDim <= MAX_PROCESSING_DIM) return bitmap to false

        val scale = MAX_PROCESSING_DIM.toFloat() / maxDim
        val newW = (bitmap.width * scale).toInt()
        val newH = (bitmap.height * scale).toInt()
        if (BuildConfig.DEBUG) Log.d("CanopyDebug",
            "downscaleIfNeeded: ${bitmap.width}x${bitmap.height} → ${newW}x${newH}")
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true) to true
    }

    /**
     * Python PIL returns raw JPEG-encoded byte values WITHOUT applying the embedded
     * ICC color profile.  Android's BitmapFactory decodes to the image's native color
     * space (e.g. Display P3) and Bitmap.getPixels() converts those values to sRGB,
     * producing different numbers than Python.
     *
     * Fix: copy the raw pixel bytes into a new ARGB_8888 bitmap that is tagged as sRGB.
     * getPixels() on an sRGB bitmap returns values as-is (no conversion), so we get
     * exactly the same raw encoded bytes that Python PIL reads.
     */
    private fun stripColorSpace(bitmap: Bitmap): Bitmap {
        val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
        if (bitmap.colorSpace == srgb) return bitmap  // already sRGB — nothing to do

        // Copy raw pixel bytes (no color-space conversion)
        val buffer = ByteBuffer.allocate(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(buffer)
        buffer.rewind()

        // Create a new sRGB-tagged bitmap and fill it with the same raw bytes.
        // getPixels() on this bitmap will return the original encoded values unchanged.
        // Caller is responsible for recycling the original bitmap after this returns.
        val retagged = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        retagged.copyPixelsFromBuffer(buffer)
        if (BuildConfig.DEBUG) Log.d("CanopyDebug", "stripColorSpace: retagged ${bitmap.colorSpace?.name} → sRGB (raw bytes preserved)")
        return retagged
    }

    // ─── Batch analysis ────────────────────────────────────────────────
    data class BatchProgress(val current: Int, val total: Int, val filename: String)
    data class BatchResult(
        val filename: String,
        val uri: Uri,
        val result: CanopyResult?,
        val error: String? = null
    )

    private val _batchProgress = MutableLiveData<BatchProgress?>()
    val batchProgress: LiveData<BatchProgress?> get() = _batchProgress

    private val _batchResults = MutableLiveData<List<BatchResult>?>()
    val batchResults: LiveData<List<BatchResult>?> get() = _batchResults


    // URIs waiting for mask confirmation before batch starts
    private var pendingBatchUris: List<Uri>? = null

    /**
     * Loads the first URI from [uris], emits [UiState.MaskAdjust] so the user
     * can position the circle, then stores [uris] in [pendingBatchUris].
     * When the user confirms the mask in VisualizationFragment,
     * [proceedWithMask] detects [pendingBatchUris] and runs batch instead of single.
     */
    fun setupBatchMask(uris: List<Uri>) {
        if (uris.isEmpty()) return
        pendingBatchUris = uris
        analysisJob?.cancel()
        clearCurrentResult()
        analysisJob = viewModelScope.launch {
            try {
                _uiState.value = UiState.Processing("Loading preview…", 5)
                val ctx = getApplication<Application>()
                val firstUri = uris.first()

                val (fullBmp, previewBmp) = withContext(Dispatchers.IO) {
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inScaled = false
                    }
                    val raw = ctx.contentResolver.openInputStream(firstUri)?.use {
                        BitmapFactory.decodeStream(it, null, opts)
                    } ?: throw IllegalArgumentException("Cannot decode first image")
                    val preview = correctOrientation(createPreview(raw), ctx, firstUri)
                    val stripped = stripColorSpace(raw)
                    if (stripped !== raw) raw.recycle()
                    val (full, _) = downscaleIfNeeded(stripped)
                    if (full !== stripped) stripped.recycle()
                    full to preview
                }

                recycleBitmap(sourceBitmap)
                recycleBitmap(previewBitmap)
                sourceBitmap = fullBmp
                previewBitmap = previewBmp

                val currentSettings = _settings.value ?: AnalysisSettings()
                val xc = fullBmp.width / 2
                val yc = fullBmp.height / 2
                val rc = if (currentSettings.circularFisheye) minOf(xc, yc) - 2
                         else (kotlin.math.sqrt((xc.toDouble() * xc + yc.toDouble() * yc)) - 2).toInt()
                _uiState.value = UiState.MaskAdjust(
                    previewBitmap = previewBmp,
                    origW = fullBmp.width, origH = fullBmp.height,
                    xc = xc, yc = yc, rc = rc,
                    isBatchSetup = true, batchCount = uris.size
                )
            } catch (e: Exception) {
                pendingBatchUris = null
                _uiState.value = UiState.Error("Failed to load preview: ${e.message}")
            }
        }
    }

    fun runBatchAnalysis(uris: List<Uri>) {
        val currentSettings = _settings.value ?: AnalysisSettings()
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            val results = mutableListOf<BatchResult>()
            _uiState.value = UiState.Processing("Batch: preparing…", 0)

            for ((index, uri) in uris.withIndex()) {
                val filename = withContext(Dispatchers.IO) {
                    runCatching {
                        val ctx = getApplication<Application>()
                        ctx.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
                            null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getString(0) else "image_${index + 1}.jpg"
                        } ?: "image_${index + 1}.jpg"
                    }.getOrDefault("image_${index + 1}.jpg")
                }

                _batchProgress.value = BatchProgress(index + 1, uris.size, filename)
                val pct = ((index.toFloat() / uris.size) * 100).toInt()
                _uiState.value = UiState.Processing(
                    "Batch ${index + 1}/${uris.size}: $filename", pct
                )

                try {
                    val bmp = withContext(Dispatchers.IO) {
                        val ctx = getApplication<Application>()
                        val opts = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                            inScaled = false
                        }
                        val raw = ctx.contentResolver.openInputStream(uri)?.use {
                            BitmapFactory.decodeStream(it, null, opts)
                        } ?: throw IllegalArgumentException("Cannot decode image")

                        val stripped = stripColorSpace(raw)
                        if (stripped !== raw) raw.recycle()

                        val (bmp, _) = downscaleIfNeeded(stripped)
                        if (bmp !== stripped) stripped.recycle()
                        bmp
                    }

                    val canopyResult = withContext(Dispatchers.Default) {
                        try {
                            val processed = CanopyProcessor.processImage(bmp, currentSettings, filename)
                            val binary = CanopyProcessor.binarize(processed, currentSettings)
                            val gf = CanopyProcessor.computeGapFraction(binary, currentSettings)
                            CanopyProcessor.computeCanopyAttributes(gf, currentSettings, processed.meta)
                        } finally {
                            bmp.recycle()
                        }
                    }
                    results.add(BatchResult(filename, uri, canopyResult))
                } catch (e: Exception) {
                    results.add(BatchResult(filename, uri, null, e.message ?: "Unknown error"))
                }
            }

            _batchResults.value = results
            _batchProgress.value = null
            _uiState.value = UiState.Idle
        }
    }

    /**
     * Loads a single batch item's URI and prepares it for full analysis, then
     * delegates to [runAnalysis] — which respects the current mask mode:
     *  - AUTO  → emits [UiState.MaskAdjust] so the user can adjust the circle
     *            in VisualizationFragment before proceeding
     *  - MANUAL → runs the full pipeline immediately
     */
    fun loadBatchItem(item: BatchResult) {
        analysisJob?.cancel()
        clearCurrentResult()
        analysisJob = viewModelScope.launch {
            try {
                _uiState.value = UiState.Processing("Loading ${item.filename}…", 5)
                val ctx = getApplication<Application>()

                val loadResult = withContext(Dispatchers.IO) {
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inScaled = false
                    }
                    val raw = ctx.contentResolver.openInputStream(item.uri)?.use {
                        BitmapFactory.decodeStream(it, null, opts)
                    } ?: throw IllegalArgumentException("Cannot decode image")

                    val previewBmp = correctOrientation(createPreview(raw), ctx, item.uri)
                    val stripped = stripColorSpace(raw)
                    if (stripped !== raw) raw.recycle()
                    val (fullBmp, _) = downscaleIfNeeded(stripped)
                    if (fullBmp !== stripped) stripped.recycle()
                    Triple(fullBmp, previewBmp, item.filename)
                }

                recycleBitmap(sourceBitmap)
                recycleBitmap(previewBitmap)
                sourceBitmap = loadResult.first
                previewBitmap = loadResult.second
                sourceFilename = loadResult.third

                // Delegate to runAnalysis so AUTO mask mode shows the adjustment UI
                // in VisualizationFragment before running the full pipeline.
                runAnalysis()
            } catch (e: Exception) {
                Log.e("CanopyDebug", "loadBatchItem failed", e)
                _uiState.value = UiState.Error(
                    "Failed to load ${item.filename}: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    fun deleteBatchItem(index: Int) {
        val current = _batchResults.value?.toMutableList() ?: return
        if (index < 0 || index >= current.size) return
        current.removeAt(index)
        _batchResults.value = if (current.isEmpty()) null else current
    }

    fun clearBatchResults() {
        _batchResults.value = null
        _batchProgress.value = null
        _uiState.value = UiState.Idle
    }

    fun reset() {
        // Cancel any in-progress analysis before recycling the bitmaps it holds a ref to.
        analysisJob?.cancel()
        analysisJob = null
        recycleBitmap(sourceBitmap)
        recycleBitmap(previewBitmap)
        sourceBitmap = null
        previewBitmap = null
        clearCurrentResult()
        _uiState.value = UiState.Idle
        // Settings are intentionally NOT touched here — the user's chosen
        // mask mode, xc/yc/rc, channel, etc. should persist across analyses.
    }

    override fun onCleared() {
        super.onCleared()
        analysisJob?.cancel()
        recycleBitmap(sourceBitmap)
        recycleBitmap(previewBitmap)
        clearCurrentResult()
    }
}
