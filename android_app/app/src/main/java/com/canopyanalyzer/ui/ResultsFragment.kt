package com.canopyanalyzer.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.canopyanalyzer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.canopyanalyzer.databinding.FragmentResultsBinding
import com.canopyanalyzer.export.DataExporter
import com.canopyanalyzer.export.SavedResultsManager
import com.canopyanalyzer.model.AnalysisResult
import com.canopyanalyzer.viewmodel.AnalysisViewModel

class ResultsFragment : Fragment() {

    private var _binding: FragmentResultsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AnalysisViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentResultsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.result.observe(viewLifecycleOwner) { result ->
            if (result != null) showResults(result)
            else {
                binding.layoutNoResults.visibility = View.VISIBLE
                binding.scrollResults.visibility = View.GONE
            }
        }

        binding.btnSaveResults.setOnClickListener { saveResults() }
        binding.btnExportCsv.setOnClickListener { exportCsv() }
        binding.btnExportJson.setOnClickListener { exportJson() }
        binding.btnShareResults.setOnClickListener { shareResults() }
        binding.btnSaveAllImages.setOnClickListener { saveAllImages() }
        binding.btnSaveImportImage.setOnClickListener { saveIndividualImage("import") }
        binding.btnSaveBinaryImage.setOnClickListener { saveIndividualImage("binarize") }
        binding.btnSaveGapFracImage.setOnClickListener { saveIndividualImage("gapfrac") }
    }

    private fun showResults(result: AnalysisResult) {
        binding.layoutNoResults.visibility = View.GONE
        binding.scrollResults.visibility = View.VISIBLE

        val cr = result.canopyResult
        val s  = cr.settings
        val m  = cr.meta
        val canopySummary = when {
            cr.difn >= 60 -> "Open Canopy"
            cr.difn >= 35 -> "Balanced Canopy"
            else -> "Dense Canopy"
        }
        binding.tvResultsHeadline.text = canopySummary
        binding.tvResultsSubhead.text =
            "${m.filename}  •  DIFN ${"%.1f".format(cr.difn)}%  •  LX ${"%.2f".format(cr.lx)}"

        // ── Key Metrics ────────────────────────────────────────────
        binding.tvLe.text   = "%.2f".format(cr.le)
        binding.tvL.text    = "%.2f".format(cr.l)
        binding.tvLx.text   = "%.2f".format(cr.lx)
        binding.tvLxg1.text = "LXG1  •  %.2f".format(cr.lxg1)
        binding.tvLxg2.text = "LXG2  •  %.2f".format(cr.lxg2)
        binding.tvDifn.text = "%.1f".format(cr.difn)
        binding.tvMta.text  = "%.1f".format(cr.mtaEll)
        binding.tvX.text    = "%.2f".format(cr.x)

        // ── Thresholds ─────────────────────────────────────────────
        val thd = result.binaryImage.thresholds.joinToString(" / ")
        binding.tvThresholdValue.text = "Thresholds  •  $thd (${result.binaryImage.method})"

        // ── Image Info ─────────────────────────────────────────────
        binding.tvImageFile.text   = m.filename
        binding.tvImageSize.text   = "Image size  •  ${m.width} × ${m.height} px"
        binding.tvMaskCenter.text  = "Mask  •  xc = ${m.xc.toInt()}, yc = ${m.yc.toInt()}, rc = ${m.rc.toInt()}"

        // ── Settings ───────────────────────────────────────────────
        binding.tvSettingChannel.text  = "Channel  •  ${s.channel.label}"
        binding.tvSettingGamma.text    = "Gamma  •  ${s.gamma}"
        binding.tvSettingStretch.text  = "Stretch  •  ${if (s.stretch) "Yes (1–99 percentile)" else "No"}"
        binding.tvSettingMask.text     = "Mask mode  •  ${s.maskMode.label}"
        binding.tvSettingLens.text     = "Lens  •  ${s.lensType.label}"
        binding.tvSettingVza.text      = "VZA  •  ${s.startVZA}° – ${s.endVZA}° (max ${s.maxVZA}°)"
        binding.tvSettingRings.text    = "Rings / segments  •  ${s.nRings} × ${s.nSegments}"
    }

    // ─── Save to Saved Results list ────────────────────────────────────
    private fun saveResults() {
        val result = viewModel.result.value ?: return toast("No results to save")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { SavedResultsManager.save(requireContext(), result) }
                toast("Results saved — view in Saved tab")
            } catch (e: Exception) {
                toast("Save failed: ${e.message}")
            }
        }
    }

    // ─── Image save actions ────────────────────────────────────────────
    private fun saveAllImages() {
        val result = viewModel.result.value ?: return toast("No results available")
        binding.btnSaveAllImages.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val importBmp = ImageAnnotations.buildImportAnnotated(result)
                    DataExporter.saveBitmap(requireContext(), importBmp, "import")
                    importBmp.recycle()
                    val binaryBmp = ImageAnnotations.buildBinaryAnnotated(result)
                    DataExporter.saveBitmap(requireContext(), binaryBmp, "binarize")
                    binaryBmp.recycle()
                    val gapFracBmp = ImageAnnotations.buildGapFracAnnotated(result)
                    DataExporter.saveBitmap(requireContext(), gapFracBmp, "gapfrac")
                    gapFracBmp.recycle()
                }
                toast("All 3 images saved to Pictures/CanopyAnalyzer in your gallery")
            } catch (e: Exception) {
                toast("Save failed: ${e.message}")
            } finally {
                binding.btnSaveAllImages.isEnabled = true
            }
        }
    }

    private fun saveIndividualImage(label: String) {
        val result = viewModel.result.value ?: return toast("No results available")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val bmp = when (label) {
                        "import"   -> ImageAnnotations.buildImportAnnotated(result)
                        "binarize" -> ImageAnnotations.buildBinaryAnnotated(result)
                        else       -> ImageAnnotations.buildGapFracAnnotated(result)
                    }
                    DataExporter.saveBitmap(requireContext(), bmp, label)
                    bmp.recycle()
                }
                toast("${label.replaceFirstChar { it.uppercase() }} image saved to Pictures/CanopyAnalyzer in your gallery")
            } catch (e: Exception) {
                toast("Save failed: ${e.message}")
            }
        }
    }

    // ─── Export actions ────────────────────────────────────────────────
    private fun exportCsv() {
        val result = viewModel.result.value ?: return toast("No results to export")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { DataExporter.exportCsv(requireContext(), result) }
                toast("CSV saved: ${file.name}")
                shareFile(file, "text/csv")
            } catch (e: Exception) {
                toast("Export failed: ${e.message}")
            }
        }
    }

    private fun exportJson() {
        val result = viewModel.result.value ?: return toast("No results to export")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { DataExporter.exportJson(requireContext(), result) }
                toast("JSON saved: ${file.name}")
                shareFile(file, "application/json")
            } catch (e: Exception) {
                toast("Export failed: ${e.message}")
            }
        }
    }

    private fun shareResults() {
        val result = viewModel.result.value ?: return toast("No results available")
        val cr = result.canopyResult
        val shareText = buildString {
            appendLine("=== Canopy Analyzer Results ===")
            appendLine("Image: ${cr.meta.filename}")
            appendLine("")
            appendLine("Effective LAI (Le): ${cr.le}")
            appendLine("True LAI (L):       ${cr.l}")
            appendLine("Clumping index LX:  ${cr.lx}")
            appendLine("DIFN:               ${cr.difn}%")
            appendLine("MTA (ellipsoidal):  ${cr.mtaEll}°")
            appendLine("x parameter:        ${cr.x}")
            appendLine("LXG1:               ${cr.lxg1}")
            appendLine("LXG2:               ${cr.lxg2}")
            appendLine("")
            appendLine("Lens: ${cr.settings.lensType.label}")
            appendLine("VZA: ${cr.settings.startVZA}°–${cr.settings.endVZA}°")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Canopy Analyzer Results")
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        startActivity(Intent.createChooser(intent, "Share Results"))
    }

    private fun shareFile(file: java.io.File, mimeType: String) {
        val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share File"))
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
