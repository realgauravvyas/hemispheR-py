package com.canopyanalyzer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.canopyanalyzer.R
import com.canopyanalyzer.databinding.FragmentHomeBinding
import com.canopyanalyzer.model.MaskMode
import com.canopyanalyzer.viewmodel.AnalysisViewModel
import java.io.File

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AnalysisViewModel by activityViewModels()

    private var cameraImageFile: File? = null

    // ─── Permission launcher ───────────────────────────────────────────
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        when {
            perms[Manifest.permission.CAMERA] == true -> launchCamera()
            // Permanently denied (denied twice on Android 11+, or "Don't ask again")
            // shouldShowRequestPermissionRationale returns false after permanent denial
            !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                AlertDialog.Builder(requireContext())
                    .setTitle("Camera Permission Required")
                    .setMessage(
                        "Camera access was permanently denied.\n\n" +
                        "To capture images, open App Settings and enable the Camera permission."
                    )
                    .setPositiveButton("Open Settings") { _, _ ->
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", requireContext().packageName, null)))
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            else -> toast("Camera permission denied")
        }
    }

    // ─── Camera launcher ───────────────────────────────────────────────
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            // cameraImageFile may be null if the fragment was recreated (e.g. rotation)
            // while the camera was open. Fall back to the known fixed filename.
            val file = cameraImageFile ?: File(requireContext().cacheDir, "canopy_capture.jpg")
            // Some Samsung devices return success=true but write an empty file.
            // Verify the file is non-empty before attempting to decode it.
            if (!file.exists() || file.length() == 0L) {
                toast("Camera capture failed — no image data written")
                return@registerForActivityResult
            }
            viewModel.loadImageFromFile(file)
        }
    }

    // ─── Gallery launcher (single image) ──────────────────────────────
    // GetContent opens the device photo gallery instead of the full document picker,
    // avoiding automatic display of cloud storage providers (OneDrive, Drive, etc.).
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.loadImage(it) }
    }

    // ─── Batch gallery launcher (multiple images) ─────────────────────
    private val batchGalleryLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        // Persist permissions for all selected files
        for (uri in uris) {
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) { /* Not always possible */ }
        }
        // Load first image for mask setup; VisualizationFragment handles the
        // "Proceed" step which runs batch with the confirmed mask on all images.
        viewModel.setupBatchMask(uris)
        // HomeFragment's existing MaskAdjust observer navigates to VisualizationFragment.
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Observe UI state
        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is AnalysisViewModel.UiState.Idle -> showIdle()
                is AnalysisViewModel.UiState.ImageLoaded -> showImageLoaded(state)
                is AnalysisViewModel.UiState.Processing -> showProcessing(state.step, state.progress)
                is AnalysisViewModel.UiState.MaskAdjust -> showMaskAdjust()
                is AnalysisViewModel.UiState.Success -> showSuccess(state)
                is AnalysisViewModel.UiState.Error -> showError(state.message)
            }
        }

        // Button listeners
        binding.btnCamera.setOnClickListener { checkCameraPermission() }
        binding.btnGallery.setOnClickListener { launchGalleryWithInitialFolder() }
        binding.btnBatchGallery.setOnClickListener { batchGalleryLauncher.launch(arrayOf("image/*")) }
        binding.btnCameraReload.setOnClickListener { checkCameraPermission() }
        binding.btnGalleryReload.setOnClickListener { launchGalleryWithInitialFolder() }
        binding.btnAnalyze.setOnClickListener {
            if (viewModel.settings.value?.maskMode == MaskMode.MANUAL) {
                val xc = binding.etHomeMaskXc.text.toString().toIntOrNull() ?: 0
                val yc = binding.etHomeMaskYc.text.toString().toIntOrNull() ?: 0
                val rc = binding.etHomeMaskRc.text.toString().toIntOrNull() ?: 0
                viewModel.updateSettings(
                    viewModel.settings.value!!.copy(maskXc = xc, maskYc = yc, maskRc = rc)
                )
            }
            viewModel.runAnalysis()
        }
        binding.btnSettings.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_settingsFragment)
        }
        binding.btnReset.setOnClickListener {
            viewModel.reset()
        }
        binding.btnQuickVisualize.setOnClickListener {
            val state = viewModel.uiState.value
            if (state is AnalysisViewModel.UiState.MaskAdjust || viewModel.result.value != null) {
                findNavController().navigate(R.id.visualizationFragment)
            } else {
                toast("Run analysis first to open visualization")
            }
        }
        binding.btnQuickResults.setOnClickListener {
            if (viewModel.result.value != null) {
                findNavController().navigate(R.id.resultsFragment)
            } else {
                toast("No analysis results available yet")
            }
        }
        binding.btnQuickSaved.setOnClickListener {
            findNavController().navigate(R.id.savedResultsFragment)
        }

        // Show/hide mask fields and populate them based on current settings mode
        viewModel.settings.observe(viewLifecycleOwner) { s ->
            binding.layoutHomeMask.visibility =
                if (s.maskMode == MaskMode.MANUAL) View.VISIBLE else View.GONE
            if (s.maskMode == MaskMode.MANUAL) {
                binding.etHomeMaskXc.setText(s.maskXc.toString())
                binding.etHomeMaskYc.setText(s.maskYc.toString())
                binding.etHomeMaskRc.setText(s.maskRc.toString())
            }
            if (viewModel.uiState.value is AnalysisViewModel.UiState.ImageLoaded) {
                binding.tvHomeHelper.text =
                    if (s.maskMode == MaskMode.MANUAL) {
                        "Manual mask mode is active. Review the coordinates below, then run the analysis."
                    } else {
                        "Run analysis to preview the detected circular mask before the canopy metrics are calculated."
                    }
            }
        }
    }

    private fun launchGalleryWithInitialFolder() {
        galleryLauncher.launch("image/*")
    }

    private fun showIdle() {
        binding.layoutEmpty.visibility = View.VISIBLE
        binding.layoutImage.visibility = View.GONE
        binding.layoutProgress.visibility = View.GONE
        binding.btnAnalyze.isEnabled = false
        binding.btnReset.visibility = View.GONE
        binding.tvResultSummary.visibility = View.GONE
        binding.tvHomeHelper.text =
            "Choose a single image for one-off analysis, or batch mode to reuse one confirmed mask on several shots."
        updateQuickActions(canVisualize = false, hasResults = false,
            hint = "Load an image to enable visualization and metrics.")
    }

    private fun showImageLoaded(state: AnalysisViewModel.UiState.ImageLoaded) {
        binding.layoutEmpty.visibility = View.GONE
        binding.layoutImage.visibility = View.VISIBLE
        binding.layoutProgress.visibility = View.GONE
        binding.imagePreview.setImageBitmap(state.bitmap)
        binding.tvFilename.text = state.filename
        binding.tvImageInfo.text = if (state.wasDownscaled) {
            "${state.sourceWidth} × ${state.sourceHeight} px source  •  " +
                "${state.analysisWidth} × ${state.analysisHeight} px analysis"
        } else {
            "${state.analysisWidth} × ${state.analysisHeight} px"
        }
        binding.tvResultSummary.visibility = View.GONE
        binding.btnAnalyze.isEnabled = true
        binding.btnReset.visibility = View.VISIBLE
        binding.tvHomeHelper.text =
            if (viewModel.settings.value?.maskMode == MaskMode.MANUAL) {
                "Manual mask mode is active. Review the coordinates below, then run the analysis."
            } else {
                "Run analysis to preview the detected circular mask before the canopy metrics are calculated."
            }
        updateQuickActions(canVisualize = false, hasResults = false,
            hint = "Run analysis next. Visualization and canopy metrics will unlock after processing starts.")
    }

    private fun showProcessing(step: String, progress: Int) {
        binding.layoutEmpty.visibility = View.GONE
        binding.layoutImage.visibility = View.GONE
        binding.layoutProgress.visibility = View.VISIBLE
        binding.tvProgressStep.text = step
        binding.progressBar.progress = progress
        binding.btnAnalyze.isEnabled = false
        binding.tvResultSummary.visibility = View.GONE
        binding.tvHomeHelper.text =
            "Working through import, binarization, and canopy metrics. The Visualize tab will update as previews become available."
        updateQuickActions(canVisualize = false, hasResults = false,
            hint = "Analysis is in progress. Visualization and metrics will become available automatically.")
    }

    private fun showMaskAdjust() {
        // Mask has been detected — send the user to the Visualize tab to adjust it.
        binding.layoutEmpty.visibility    = View.GONE
        binding.layoutImage.visibility    = View.VISIBLE
        binding.layoutProgress.visibility = View.GONE
        binding.btnAnalyze.isEnabled = true
        binding.btnReset.visibility = View.VISIBLE
        binding.tvHomeHelper.text =
            "Fine-tune the circular mask in the Visualize tab, then continue to compute the final canopy metrics."
        updateQuickActions(canVisualize = true, hasResults = false,
            hint = "Visualization is ready for mask adjustment. Metrics will unlock after you confirm the mask.")
        // LiveData re-delivers its last value on every resume, so guard against
        // re-navigating when the user is already on (or returns from) VisualizationFragment.
        if (findNavController().currentDestination?.id != R.id.visualizationFragment) {
            findNavController().navigate(R.id.action_homeFragment_to_visualizationFragment)
        }
    }

    private fun showSuccess(state: AnalysisViewModel.UiState.Success) {
        binding.layoutEmpty.visibility = View.GONE
        binding.layoutImage.visibility = View.VISIBLE
        binding.layoutProgress.visibility = View.GONE
        binding.btnAnalyze.isEnabled = true
        binding.btnReset.visibility = View.VISIBLE
        val cr = state.result.canopyResult
        binding.tvResultSummary.visibility = View.VISIBLE
        binding.tvResultSummary.text =
            "Le = ${cr.le}  |  L = ${cr.l}  |  DIFN = ${cr.difn}%"
        binding.tvHomeHelper.text =
            "Analysis complete. Open Visualize to inspect overlays or Results to export metrics and save the working images."
        updateQuickActions(canVisualize = true, hasResults = true,
            hint = "Open Visualize to inspect overlays, or Metrics to export files and save this result.")

        // Guard: LiveData re-delivers on resume — don't push VisualizationFragment again
        // if the user navigated back to HomeFragment after analysis completed.
        if (findNavController().currentDestination?.id != R.id.visualizationFragment) {
            findNavController().navigate(R.id.action_homeFragment_to_visualizationFragment)
        }
    }

    private fun showError(message: String) {
        if (binding.imagePreview.drawable == null && binding.tvFilename.text.isNullOrBlank()) {
            showIdle()
            toast(message)
            return
        }
        binding.layoutEmpty.visibility = View.GONE
        binding.layoutImage.visibility = View.VISIBLE
        binding.layoutProgress.visibility = View.GONE
        binding.btnAnalyze.isEnabled = true
        binding.tvResultSummary.visibility = View.GONE
        binding.tvHomeHelper.text =
            "Something went wrong. Try another image or adjust the mask and threshold settings before rerunning."
        updateQuickActions(
            canVisualize = viewModel.uiState.value is AnalysisViewModel.UiState.MaskAdjust || viewModel.result.value != null,
            hasResults = viewModel.result.value != null,
            hint = "Saved results remain available. Load another image or rerun the analysis to continue."
        )
        toast(message)
    }

    private fun updateQuickActions(canVisualize: Boolean, hasResults: Boolean, hint: String) {
        binding.btnQuickVisualize.isEnabled = canVisualize
        binding.btnQuickResults.isEnabled = hasResults
        binding.tvQuickActionHint.text = hint
    }

    // ─── Camera permission & launch ────────────────────────────────────
    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED -> launchCamera()
            // Show rationale when the user has previously denied once
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                AlertDialog.Builder(requireContext())
                    .setTitle("Camera Permission Needed")
                    .setMessage(
                        "CanopyAnalyzer needs camera access to capture hemispherical canopy images.\n\n" +
                        "Your choice will be remembered — you won't be asked again after granting."
                    )
                    .setPositiveButton("Grant") { _, _ ->
                        requestPermissions.launch(arrayOf(Manifest.permission.CAMERA))
                    }
                    .setNegativeButton("Not Now", null)
                    .show()
            }
            // First-time request — ask directly
            else -> requestPermissions.launch(arrayOf(Manifest.permission.CAMERA))
        }
    }

    private fun launchCamera() {
        val ctx = requireContext()
        // Delete the previous temp file to avoid accumulating camera captures in cache
        cameraImageFile?.delete()
        val file = File(ctx.cacheDir, "canopy_capture.jpg")
        cameraImageFile = file
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", file)
        cameraLauncher.launch(uri)
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()

    override fun onDestroyView() {
        super.onDestroyView()
        binding.imagePreview.setImageDrawable(null)
        _binding = null
    }
}
