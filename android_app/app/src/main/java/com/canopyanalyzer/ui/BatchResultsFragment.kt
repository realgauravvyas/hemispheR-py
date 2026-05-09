package com.canopyanalyzer.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.canopyanalyzer.R
import com.canopyanalyzer.databinding.FragmentBatchResultsBinding
import com.canopyanalyzer.databinding.ItemBatchResultBinding
import com.canopyanalyzer.export.DataExporter
import com.canopyanalyzer.export.SavedResultsManager
import com.canopyanalyzer.viewmodel.AnalysisViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BatchResultsFragment : Fragment() {

    private var _binding: FragmentBatchResultsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AnalysisViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBatchResultsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.batchProgress.observe(viewLifecycleOwner) { progress ->
            if (progress != null) {
                binding.layoutBatchProgress.visibility = View.VISIBLE
                binding.tvBatchProgressLabel.text =
                    "Processing ${progress.current}/${progress.total}: ${progress.filename}"
                binding.batchProgressBar.progress =
                    (progress.current.toFloat() / progress.total * 100).toInt()
            } else {
                binding.layoutBatchProgress.visibility = View.GONE
            }
        }

        viewModel.batchResults.observe(viewLifecycleOwner) { results ->
            if (results == null) showEmpty() else showResults(results)
        }

        // If the user somehow arrives here while a MaskAdjust is pending
        // (e.g. navigated back from Viz before confirming), navigate to Viz.
        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            if (state is AnalysisViewModel.UiState.MaskAdjust && !state.isBatchSetup &&
                findNavController().currentDestination?.id == R.id.batchResultsFragment) {
                findNavController().navigate(R.id.action_batchResultsFragment_to_visualizationFragment)
            }
        }

        binding.btnBatchBack.setOnClickListener { navigateHome() }

        binding.btnClearBatch.setOnClickListener {
            viewModel.clearBatchResults()
            navigateHome()
        }

        binding.btnExportBatchCsv.setOnClickListener { exportBatchCsv() }

        // Show current state on first load
        val current = viewModel.batchResults.value
        if (current == null) showEmpty() else showResults(current)
    }

    private fun showEmpty() {
        binding.tvBatchEmpty.visibility = View.VISIBLE
        binding.scrollBatch.visibility = View.GONE
        binding.tvBatchSummary.text = ""
        binding.btnExportBatchCsv.isEnabled = false
    }

    private fun showResults(results: List<AnalysisViewModel.BatchResult>) {
        binding.tvBatchEmpty.visibility = View.GONE
        binding.scrollBatch.visibility = View.VISIBLE

        val successCount = results.count { it.result != null }
        binding.tvBatchSummary.text = "$successCount/${results.size} images processed successfully"
        binding.btnExportBatchCsv.isEnabled = successCount > 0

        val inflater = LayoutInflater.from(requireContext())
        binding.batchListContainer.removeAllViews()

        for ((index, item) in results.withIndex()) {
            val itemBinding = ItemBatchResultBinding.inflate(inflater, binding.batchListContainer, false)
            itemBinding.tvBatchItemName.text = item.filename

            // Delete button always visible
            itemBinding.btnBatchDeleteItem.setOnClickListener {
                viewModel.deleteBatchItem(index)
            }

            if (item.result != null) {
                itemBinding.tvBatchItemStatus.text = "✓"
                itemBinding.tvBatchItemStatus.setTextColor(
                    resources.getColor(android.R.color.holo_green_dark, null))
                val r = item.result
                itemBinding.tvBatchItemMetrics.text =
                    "Le=%.2f  L=%.2f  LX=%.2f  DIFN=%.1f%%  MTA=%.1f°".format(
                        r.le, r.l, r.lx, r.difn, r.mtaEll)

                // Full metrics for expandable details
                itemBinding.tvBatchItemDetails.text =
                    "Le  (Eff. LAI) = %.4f\n".format(r.le) +
                    "L   (True LAI) = %.4f\n".format(r.l) +
                    "LX  (Clumping) = %.4f\n".format(r.lx) +
                    "LXG1           = %.4f\n".format(r.lxg1) +
                    "LXG2           = %.4f\n".format(r.lxg2) +
                    "DIFN           = %.2f %%\n".format(r.difn) +
                    "MTA.ell        = %.2f °\n".format(r.mtaEll) +
                    "x (ellips.)    = %.4f".format(r.x)

                itemBinding.layoutBatchItemActions.visibility = View.VISIBLE

                itemBinding.btnBatchToggleDetails.setOnClickListener {
                    val details = itemBinding.layoutBatchItemDetails
                    if (details.visibility == View.VISIBLE) {
                        details.visibility = View.GONE
                        itemBinding.btnBatchToggleDetails.text = "Details"
                    } else {
                        details.visibility = View.VISIBLE
                        itemBinding.btnBatchToggleDetails.text = "Hide"
                    }
                }

                itemBinding.btnBatchViewAnalysis.setOnClickListener {
                    viewModel.loadBatchItem(item)
                    // Navigate immediately so the user sees the Visualization screen
                    // while the pipeline loads in the background.
                    if (findNavController().currentDestination?.id == R.id.batchResultsFragment) {
                        findNavController().navigate(R.id.action_batchResultsFragment_to_visualizationFragment)
                    }
                }

                itemBinding.btnBatchSaveResult.setOnClickListener {
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                SavedResultsManager.saveCanopyResult(requireContext(), item.result)
                            }
                            toast("${item.filename} saved to Saved tab")
                        } catch (e: Exception) {
                            toast("Save failed: ${e.message}")
                        }
                    }
                }
            } else {
                itemBinding.tvBatchItemStatus.text = "✗"
                itemBinding.tvBatchItemStatus.setTextColor(
                    resources.getColor(android.R.color.holo_red_dark, null))
                itemBinding.tvBatchItemMetrics.text = "Error: ${item.error}"
                itemBinding.layoutBatchItemActions.visibility = View.GONE
            }

            binding.batchListContainer.addView(itemBinding.root)
        }
    }

    private fun exportBatchCsv() {
        val results = viewModel.batchResults.value
            ?.filter { it.result != null } ?: return
        if (results.isEmpty()) return toast("No successful results to export")

        binding.btnExportBatchCsv.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    DataExporter.exportBatchCsv(requireContext(), results)
                }
                toast("Batch CSV saved: ${file.name}")
            } catch (e: Exception) {
                toast("Export failed: ${e.message}")
            } finally {
                binding.btnExportBatchCsv.isEnabled = true
            }
        }
    }

    /** Navigate to HomeFragment, clearing the entire back stack. Works regardless
     *  of how deep the current back stack is or how we arrived here. */
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

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
