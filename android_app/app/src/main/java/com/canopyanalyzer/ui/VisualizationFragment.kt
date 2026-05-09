package com.canopyanalyzer.ui

import android.graphics.*
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.canopyanalyzer.R
import com.canopyanalyzer.core.CanopyProcessor
import com.canopyanalyzer.databinding.FragmentVisualizationBinding
import com.canopyanalyzer.export.DataExporter
import com.canopyanalyzer.model.AnalysisResult
import com.canopyanalyzer.model.GapFractionData
import com.canopyanalyzer.viewmodel.AnalysisViewModel
import kotlin.math.*

class VisualizationFragment : Fragment() {

    private var _binding: FragmentVisualizationBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AnalysisViewModel by activityViewModels()
    private var cachedResult: AnalysisResult? = null
    private var importTabBitmap: Bitmap? = null
    private var binaryTabBitmap: Bitmap? = null
    private var gapFracTabBitmap: Bitmap? = null
    private var suppressTabCallback = false

    // ─── Mask-adjustment state ────────────────────────────────────────────────
    private var adjXc = 0
    private var adjYc = 0
    private var adjRc = 0
    private var adjDefaultXc = 0
    private var adjDefaultYc = 0
    private var adjDefaultRc = 0
    private var adjOrigW = 0
    private var adjOrigH = 0
    private var adjBaseBitmap: Bitmap? = null   // owned by VM — do NOT recycle
    private var adjOverlayBitmap: Bitmap? = null // we own — must recycle
    private var isBatchSetupMode = false

    // Adaptive step: ~1% of the larger image dimension (min 5px, max 50px).
    // 10px was invisible on high-res images (e.g. 3840px wide → only 0.26%).
    private val adjStep: Int get() = (maxOf(adjOrigW, adjOrigH) * 0.01f)
        .toInt().coerceIn(5, 50)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVisualizationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Single observer on uiState drives all three panels
        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is AnalysisViewModel.UiState.MaskAdjust -> {
                    isBatchSetupMode = state.isBatchSetup
                    binding.layoutVizLoading.visibility = View.GONE
                    binding.layoutNoResults.visibility  = View.GONE
                    binding.layoutContent.visibility    = View.GONE
                    binding.layoutMaskAdjust.visibility = View.VISIBLE
                    setupMaskAdjust(state)
                }
                is AnalysisViewModel.UiState.Success -> {
                    binding.layoutVizLoading.visibility = View.GONE
                    binding.layoutNoResults.visibility  = View.GONE
                    binding.layoutMaskAdjust.visibility = View.GONE
                    binding.layoutContent.visibility    = View.VISIBLE
                    clearAdjustState()
                    renderResult(state.result)
                }
                is AnalysisViewModel.UiState.Processing -> {
                    if (binding.layoutMaskAdjust.visibility == View.VISIBLE) {
                        // Mask adjust "Proceed" was clicked — keep panel, disable button
                        binding.btnAdjProceed.isEnabled = false
                        binding.btnAdjReset.isEnabled = false
                        binding.btnAdjProceed.text = "Analyzing…"
                    } else {
                        // Loading from batch "View Analysis" — show spinner
                        binding.layoutNoResults.visibility  = View.GONE
                        binding.layoutVizLoading.visibility = View.VISIBLE
                        binding.layoutContent.visibility    = View.GONE
                        binding.tvVizLoadingStep.text       = state.step
                    }
                }
                else -> {
                    binding.layoutVizLoading.visibility = View.GONE
                    // If batch mask setup just completed, navigate to batch results
                    // instead of showing the "No Results Available" placeholder.
                    if (isBatchSetupMode && !viewModel.batchResults.value.isNullOrEmpty() &&
                        findNavController().currentDestination?.id == R.id.visualizationFragment) {
                        isBatchSetupMode = false
                        clearAdjustState()
                        findNavController().navigate(R.id.action_visualizationFragment_to_batchResultsFragment)
                        return@observe
                    }
                    // Idle / ImageLoaded / Error — clear adjust state
                    clearAdjustState()
                    binding.layoutMaskAdjust.visibility = View.GONE
                    binding.layoutVizLoading.visibility = View.GONE
                    val existingResult = viewModel.result.value
                    if (existingResult == null) {
                        clearRenderedCache()
                        binding.imageView.setImageDrawable(null)
                        binding.layoutNoResults.visibility = View.VISIBLE
                        binding.layoutContent.visibility   = View.GONE
                    } else {
                        // Has a result (e.g. navigated back from batch or home)
                        // — show content and re-render it
                        binding.layoutNoResults.visibility = View.GONE
                        binding.layoutContent.visibility   = View.VISIBLE
                        renderResult(existingResult)
                    }
                }
            }
        }

        // Observe intermediate processing bitmaps and show them in the loading panel
        viewModel.processingBitmap.observe(viewLifecycleOwner) { bmp ->
            binding.imageProcessing.setImageBitmap(bmp)
        }

        binding.btnShowResults.setOnClickListener {
            findNavController().navigate(R.id.action_visualizationFragment_to_resultsFragment)
        }

        // Show "Back to Batch" button whenever batch results exist
        viewModel.batchResults.observe(viewLifecycleOwner) { results ->
            binding.btnBackToBatch.visibility =
                if (!results.isNullOrEmpty()) View.VISIBLE else View.GONE
        }

        binding.btnBackToBatch.setOnClickListener {
            val popped = findNavController().popBackStack(R.id.batchResultsFragment, false)
            if (!popped) {
                // batchResultsFragment not in back stack — navigate fresh
                findNavController().navigate(R.id.action_visualizationFragment_to_batchResultsFragment)
            }
        }

        // Proceed with the user-adjusted mask
        binding.btnAdjProceed.setOnClickListener {
            viewModel.proceedWithMask(adjXc, adjYc, adjRc)
        }
        binding.btnAdjReset.setOnClickListener {
            adjXc = adjDefaultXc
            adjYc = adjDefaultYc
            adjRc = adjDefaultRc
            updateMaskCircle()
            toast("Mask reset to the default position")
        }

        // ─── xc ± buttons ────────────────────────────────────────────────
        binding.btnAdjXcMinus.setOnClickListener { adjXc -= adjStep; updateMaskCircle() }
        binding.btnAdjXcPlus .setOnClickListener { adjXc += adjStep; updateMaskCircle() }
        // ─── yc ± buttons ────────────────────────────────────────────────
        binding.btnAdjYcMinus.setOnClickListener { adjYc -= adjStep; updateMaskCircle() }
        binding.btnAdjYcPlus .setOnClickListener { adjYc += adjStep; updateMaskCircle() }
        // ─── rc ± buttons ────────────────────────────────────────────────
        binding.btnAdjRcMinus.setOnClickListener { adjRc = maxOf(adjStep, adjRc - adjStep); updateMaskCircle() }
        binding.btnAdjRcPlus .setOnClickListener { adjRc += adjStep; updateMaskCircle() }

        binding.btnSaveImage.setOnClickListener {
            val result = viewModel.result.value ?: return@setOnClickListener toast("No results yet")
            val tabId = binding.tabGroup.checkedButtonId
            val label = when (tabId) {
                R.id.btnTabImport  -> "import"
                R.id.btnTabBinary  -> "binarize"
                else               -> "gapfrac"
            }
            binding.btnSaveImage.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val annotated = buildAnnotatedSaveBitmap(result, tabId)
                        DataExporter.saveBitmap(requireContext(), annotated, label)
                        annotated.recycle()
                    }
                    toast("Saved to Pictures/CanopyAnalyzer in your gallery")
                } catch (e: Exception) {
                    toast("Save failed: ${e.message}")
                } finally {
                    binding.btnSaveImage.isEnabled = true
                }
            }
        }

        binding.tabGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressTabCallback) return@addOnButtonCheckedListener
            viewModel.result.value?.let { result ->
                showSelectedTab(result, checkedId)
            }
        }
    }

    // ─── Mask adjustment helpers ──────────────────────────────────────────────

    private fun setupMaskAdjust(state: AnalysisViewModel.UiState.MaskAdjust) {
        adjXc = state.xc
        adjYc = state.yc
        adjRc = state.rc
        adjDefaultXc = state.xc
        adjDefaultYc = state.yc
        adjDefaultRc = state.rc
        adjOrigW = state.origW
        adjOrigH = state.origH
        adjBaseBitmap = state.previewBitmap
        binding.btnAdjProceed.isEnabled = true
        binding.btnAdjReset.isEnabled = true
        binding.btnAdjProceed.text = if (state.isBatchSetup)
            "Apply Mask to All ${state.batchCount} Images →"
        else
            "Mask OK → Proceed"
        binding.tvAdjHelper.text = if (state.isBatchSetup) {
            "Align the mask once here, then the same circle will be applied to all selected images."
        } else {
            "Align the red circle to the canopy edge. The step size adapts to the current image dimensions."
        }
        binding.tvAdjStepLabel.text = if (state.isBatchSetup)
            "Set Circular Mask for Batch  (±${adjStep} px per tap)"
        else
            "Fine-Tune Circular Mask  (±${adjStep} px per tap)"
        updateMaskCircle()
    }

    /**
     * Copies the base preview bitmap, draws the current circle on it, and
     * updates the ImageView.  Called on every ± tap — runs on the main thread
     * so it must be fast (no coroutines needed, just canvas drawing).
     */
    private fun updateMaskCircle() {
        binding.tvAdjXc.text = adjXc.toString()
        binding.tvAdjYc.text = adjYc.toString()
        binding.tvAdjRc.text = adjRc.toString()

        val base = adjBaseBitmap ?: return
        val overlay = if (
            adjOverlayBitmap != null &&
            adjOverlayBitmap?.width == base.width &&
            adjOverlayBitmap?.height == base.height &&
            adjOverlayBitmap?.isRecycled == false
        ) {
            adjOverlayBitmap!!
        } else {
            adjOverlayBitmap?.recycle()
            base.copy(Bitmap.Config.ARGB_8888, true).also { adjOverlayBitmap = it }
        }
        val canvas = Canvas(overlay)
        canvas.drawBitmap(base, 0f, 0f, null)
        val scale = base.width.toFloat() / adjOrigW.coerceAtLeast(1)
        drawMaskOverlay(canvas,
            adjXc * scale, adjYc * scale, adjRc * scale,
            base.width, base.height)
        binding.imageAdjust.setImageBitmap(overlay)
    }

    private fun clearAdjustState() {
        adjOverlayBitmap?.recycle()
        adjOverlayBitmap = null
        adjBaseBitmap = null
    }

    private fun clearRenderedCache() {
        importTabBitmap?.recycle()
        binaryTabBitmap?.recycle()
        gapFracTabBitmap?.recycle()
        importTabBitmap = null
        binaryTabBitmap = null
        gapFracTabBitmap = null
        cachedResult = null
    }

    private fun renderResult(result: AnalysisResult) {
        if (cachedResult !== result) {
            clearRenderedCache()
            cachedResult = result
        }

        val selectedTab = if (binding.tabGroup.checkedButtonId == View.NO_ID) {
            suppressTabCallback = true
            binding.tabGroup.check(R.id.btnTabImport)
            suppressTabCallback = false
            R.id.btnTabImport
        } else {
            binding.tabGroup.checkedButtonId
        }
        showSelectedTab(result, selectedTab)

        val (gapPct, canopyPct) = CanopyProcessor.computeGapStats(result.binaryImage)
        binding.tvGapStats.text = "Gap: ${"%.1f".format(gapPct)}%   Canopy: ${"%.1f".format(canopyPct)}%"
    }

    private fun showSelectedTab(result: AnalysisResult, checkedId: Int) {
        when (checkedId) {
            R.id.btnTabBinary -> {
                binding.tvVizHint.text =
                    "Binary view separates white sky gaps from black canopy pixels using the active threshold method."
                showBinaryView(result)
            }
            R.id.btnTabGapFrac -> {
                binding.tvVizHint.text =
                    "GapFrac view overlays the zenith rings and azimuth segments used to derive the canopy metrics."
                showGapFracView(result)
            }
            else -> {
                binding.tvVizHint.text =
                    "Import view shows the working grayscale channel plus the circular mask used for analysis."
                showImportView(result)
            }
        }
    }

    private fun showImportView(result: AnalysisResult) {
        val bitmap = importTabBitmap ?: run {
            val base = result.processedBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(base)
            val meta = result.processedImage.meta
            val scale = base.width.toFloat() / meta.width.coerceAtLeast(1)
            drawMaskOverlay(canvas, meta.xc * scale, meta.yc * scale, meta.rc * scale, base.width, base.height)
            wrapWithAxes(base, meta.width, meta.height).also {
                base.recycle()
                importTabBitmap = it
            }
        }
        binding.imageView.setImageBitmap(bitmap)
        val meta = result.processedImage.meta
        binding.tvViewLabel.text =
            "${meta.filename}  ·  circular mask  ·  xc=${meta.xc.toInt()}, yc=${meta.yc.toInt()}, rc=${meta.rc.toInt()}" +
            "  ·  channel=${meta.channel}  ·  γ=${meta.gamma}"
    }

    private fun showBinaryView(result: AnalysisResult) {
        val bitmap = binaryTabBitmap ?: run {
            val base = result.binaryBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(base)
            val meta = result.binaryImage.meta
            val scale = base.width.toFloat() / meta.width.coerceAtLeast(1)
            drawMaskOverlay(canvas, meta.xc * scale, meta.yc * scale, meta.rc * scale, base.width, base.height)
            wrapWithAxes(base, meta.width, meta.height).also {
                base.recycle()
                binaryTabBitmap = it
            }
        }
        binding.imageView.setImageBitmap(bitmap)
        val thresholds = result.binaryImage.thresholds
        val threshStr = if (thresholds.size > 1)
            "Binarized (zonal): " + thresholds.joinToString(", ") { it.toInt().toString() }
        else
            "Binarized (threshold=${thresholds.firstOrNull()?.toInt() ?: "—"})"
        binding.tvViewLabel.text = "${result.processedImage.meta.filename}  ·  $threshStr"
    }

    private fun showGapFracView(result: AnalysisResult) {
        val bitmap = gapFracTabBitmap ?: run {
            val base = result.binaryBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(base)
            val gf = result.gapFraction
            val meta = result.binaryImage.meta
            val scale = base.width.toFloat() / meta.width.coerceAtLeast(1)
            drawRingsAndSegments(canvas, gf, base.width, base.height, scale)
            wrapWithAxes(base, meta.width, meta.height).also {
                base.recycle()
                gapFracTabBitmap = it
            }
        }
        binding.imageView.setImageBitmap(bitmap)
        val gf = result.gapFraction
        binding.tvViewLabel.text = "${result.processedImage.meta.filename}  ·  Rings and Segments (${gf.lens})  ·  rings=${gf.nRings}  ·  segments=${gf.nSegments}"
    }

    // ─── Axes + colorbar wrapper ──────────────────────────────────────────────

    /**
     * Wraps [src] in a new bitmap with axes on the left and bottom sides.
     * Tick labels show original-pixel coordinates (y=0 at visual bottom,
     * matching Python matplotlib convention).
     */
    private fun wrapWithAxes(src: Bitmap, origW: Int, origH: Int): Bitmap {
        val safeWidth = origW.coerceAtLeast(1)
        val safeHeight = origH.coerceAtLeast(1)
        val leftPad   = 72
        val bottomPad = 54
        val rightPad  = 12   // small margin, no colorbar
        val topPad    = 14

        val outW = src.width  + leftPad + rightPad
        val outH = src.height + topPad  + bottomPad

        // Use theme-aware colors so the axes area is readable in both light and dark mode.
        // The image content itself keeps its natural colors (binary = black+white, etc.).
        val nightMode = resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isDark = nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bgColor   = if (isDark) 0xFF1E211E.toInt() else Color.WHITE
        val axisColor = if (isDark) 0xFFD0D4D0.toInt() else Color.BLACK

        val out    = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(bgColor)
        canvas.drawBitmap(src, leftPad.toFloat(), topPad.toFloat(), null)

        val textSz = 26f
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color    = axisColor
            textSize = textSz
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color       = axisColor
            strokeWidth = 2f
            style       = Paint.Style.STROKE
        }

        val imgL = leftPad.toFloat()
        val imgT = topPad.toFloat()
        val imgR = (leftPad + src.width).toFloat()
        val imgB = (topPad + src.height).toFloat()

        // Axis border lines
        canvas.drawLine(imgL, imgB, imgR, imgB, linePaint)  // x-axis bottom
        canvas.drawLine(imgL, imgT, imgL, imgB, linePaint)  // y-axis left

        // X-axis ticks + labels (0 → origW, left to right)
        labelPaint.textAlign = Paint.Align.CENTER
        for (v in axisTicks(safeWidth)) {
            val sx = imgL + v.toFloat() / safeWidth * src.width
            canvas.drawLine(sx, imgB, sx, imgB + 8, linePaint)
            canvas.drawText(v.toString(), sx, imgB + 8 + textSz, labelPaint)
        }

        // Y-axis ticks + labels (0 at visual bottom, origH at visual top)
        labelPaint.textAlign = Paint.Align.RIGHT
        for (v in axisTicks(safeHeight)) {
            val sy = imgB - v.toFloat() / safeHeight * src.height
            canvas.drawLine(imgL - 8, sy, imgL, sy, linePaint)
            canvas.drawText(v.toString(), imgL - 11, sy + textSz * 0.35f, labelPaint)
        }

        return out
    }

    /**
     * Generates "nice" axis tick values from 0 to [maxVal].
     * Steps are chosen as 1, 2, or 5 × a power of 10 targeting ~7 ticks.
     */
    private fun axisTicks(maxVal: Int): List<Int> {
        if (maxVal <= 0) return listOf(0)
        val rawStep = maxVal / 7.0
        val mag     = 10.0.pow(floor(log10(rawStep)))
        val norm    = rawStep / mag
        val step = when {
            norm < 1.5 -> mag.toInt()
            norm < 3.5 -> (2 * mag).toInt()
            norm < 7.5 -> (5 * mag).toInt()
            else       -> (10 * mag).toInt()
        }.coerceAtLeast(1)
        return (0..maxVal step step).toList()
    }

    // ─── Image overlay helpers ────────────────────────────────────────────────

    // Draws red circle + radius line — matches Python's circular mask display
    private fun drawMaskOverlay(canvas: Canvas, xc: Float, yc: Float, rc: Float, w: Int, h: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            color       = Color.RED
            strokeWidth = (maxOf(w, h) * 0.005f).coerceAtLeast(3f)
        }
        canvas.drawCircle(xc, yc, rc, paint)
        canvas.drawLine(xc, yc, xc + rc, yc, paint)
    }

    // Draws yellow rings + yellow segment lines — matches Python's gapfrac display
    private fun drawRingsAndSegments(canvas: Canvas, gf: GapFractionData, w: Int, h: Int, scale: Float) {
        val xc      = gf.xc * scale
        val yc      = gf.yc * scale
        val strokeW = (maxOf(w, h) * 0.003f).coerceAtLeast(2f)

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            color       = Color.YELLOW
            strokeWidth = strokeW
        }
        val segPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            color       = Color.YELLOW
            strokeWidth = strokeW * 0.7f
        }

        val maxR_scaled = (gf.rBounds.maxOrNull()?.toFloat() ?: return) * scale

        for (r in gf.rBounds) {
            if (r > 0) canvas.drawCircle(xc, yc, r.toFloat() * scale, ringPaint)
        }
        for (segAngle in gf.segBins) {
            val rad = Math.toRadians(segAngle.toDouble())
            val ex  = (xc + maxR_scaled * sin(rad)).toFloat()
            val ey  = (yc + maxR_scaled * cos(rad)).toFloat()
            canvas.drawLine(xc, yc, ex, ey, segPaint)
        }
    }

    // ─── Annotated-save helper ────────────────────────────────────────────────

    /** Delegates to [ImageAnnotations] — app display uses [wrapWithAxes] instead. */
    private fun buildAnnotatedSaveBitmap(result: AnalysisResult, tabId: Int): Bitmap =
        when (tabId) {
            R.id.btnTabImport  -> ImageAnnotations.buildImportAnnotated(result)
            R.id.btnTabBinary  -> ImageAnnotations.buildBinaryAnnotated(result)
            else               -> ImageAnnotations.buildGapFracAnnotated(result)
        }

    private fun navigateHome() {
        val popped = findNavController().popBackStack(R.id.homeFragment, false)
        if (!popped) {
            findNavController().navigate(
                R.id.homeFragment, null,
                androidx.navigation.NavOptions.Builder()
                    .setPopUpTo(R.id.nav_graph, true)
                    .build()
            )
        }
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        binding.imageView.setImageDrawable(null)
        binding.imageProcessing.setImageDrawable(null)
        clearRenderedCache()
        adjOverlayBitmap?.recycle()
        adjOverlayBitmap = null
        adjBaseBitmap = null
        _binding = null
    }
}
