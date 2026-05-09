package com.canopyanalyzer.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.canopyanalyzer.databinding.FragmentSavedResultsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.canopyanalyzer.databinding.ItemSavedResultBinding
import com.canopyanalyzer.export.SavedResultEntry
import com.canopyanalyzer.export.SavedResultsManager

class SavedResultsFragment : Fragment() {

    private var _binding: FragmentSavedResultsBinding? = null
    private val binding get() = _binding!!

    // Entry selected for comparison (first pick)
    private var compareFirst: SavedResultEntry? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSavedResultsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnClearCompare.setOnClickListener {
            compareFirst = null
            loadAndDisplay()
        }
        loadAndDisplay()
    }

    override fun onResume() {
        super.onResume()
        // Refresh list every time the tab is opened
        loadAndDisplay()
    }

    private fun loadAndDisplay() {
        viewLifecycleOwner.lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                SavedResultsManager.loadAll(requireContext())
            }
            displayEntries(entries)
        }
    }

    private fun displayEntries(entries: List<SavedResultEntry>) {
        if (compareFirst != null && entries.none { it.id == compareFirst?.id }) {
            compareFirst = null
        }
        binding.listContainer.removeAllViews()
        updateCompareStatus()

        if (entries.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.scrollSaved.visibility = View.GONE
            return
        }

        binding.tvEmpty.visibility = View.GONE
        binding.scrollSaved.visibility = View.VISIBLE

        val inflater = LayoutInflater.from(requireContext())
        for (entry in entries) {
            val itemBinding = ItemSavedResultBinding.inflate(inflater, binding.listContainer, false)

            itemBinding.tvSavedImageName.text = entry.imageName
            itemBinding.tvSavedTimestamp.text  = entry.timestamp
            itemBinding.tvSavedLe.text   = "Le = ${"%.2f".format(entry.le)}"
            itemBinding.tvSavedL.text    = "L = ${"%.2f".format(entry.l)}"
            itemBinding.tvSavedDifn.text = "DIFN = ${"%.1f".format(entry.difn)}%"
            itemBinding.tvSavedModes.text = "Mask: ${entry.maskMode}  ·  Thresh: ${entry.thresholdMode}"

            itemBinding.root.setOnClickListener { showDetails(entry) }
            itemBinding.btnViewDetails.setOnClickListener { showDetails(entry) }
            itemBinding.btnDeleteSaved.setOnClickListener { confirmDelete(entry) }
            itemBinding.btnCompareSaved.setOnClickListener { handleCompareSelection(entry) }

            // Long-press to select for comparison
            itemBinding.root.setOnLongClickListener {
                handleCompareSelection(entry)
                true
            }

            // Highlight if selected as first comparison target
            val isSelected = compareFirst?.id == entry.id
            itemBinding.root.strokeWidth = if (isSelected) 4 else 0
            itemBinding.btnCompareSaved.text = when {
                isSelected -> "Cancel Compare"
                compareFirst == null -> "Compare"
                else -> "Compare With Selected"
            }

            binding.listContainer.addView(itemBinding.root)
        }
    }

    private fun updateCompareStatus() {
        val first = compareFirst
        if (first == null) {
            binding.tvCompareStatus.text = "No comparison selected"
            binding.btnClearCompare.visibility = View.GONE
        } else {
            binding.tvCompareStatus.text =
                "Selected for comparison: ${first.imageName}. Choose one more result to compare."
            binding.btnClearCompare.visibility = View.VISIBLE
        }
    }

    private fun handleCompareSelection(entry: SavedResultEntry) {
        val first = compareFirst
        when {
            first == null -> {
                compareFirst = entry
                Toast.makeText(
                    requireContext(),
                    "\"${entry.imageName}\" selected for comparison",
                    Toast.LENGTH_SHORT
                ).show()
                loadAndDisplay()
            }
            first.id == entry.id -> {
                compareFirst = null
                loadAndDisplay()
            }
            else -> {
                showComparison(first, entry)
                compareFirst = null
                loadAndDisplay()
            }
        }
    }

    private fun showDetails(e: SavedResultEntry) {
        val text = buildString {
            appendLine("=== ${e.imageName} ===")
            appendLine("Saved: ${e.timestamp}")
            appendLine()
            appendLine("── Canopy Attributes ──")
            appendLine("Le (Effective LAI) : ${"%.2f".format(e.le)}")
            appendLine("L  (True LAI)      : ${"%.2f".format(e.l)}")
            appendLine("LX (Clumping)      : ${"%.2f".format(e.lx)}")
            appendLine("LXG1               : ${"%.2f".format(e.lxg1)}")
            appendLine("LXG2               : ${"%.2f".format(e.lxg2)}")
            appendLine("DIFN (%)           : ${"%.1f".format(e.difn)}")
            appendLine("MTA.ell (°)        : ${"%.1f".format(e.mtaEll)}")
            appendLine("x (ellipsoidal)    : ${"%.2f".format(e.x)}")
            appendLine()
            appendLine("── Settings ──")
            appendLine("Channel : ${e.channel}")
            appendLine("Gamma   : ${e.gamma}")
            appendLine("Stretch : ${if (e.stretch) "Yes" else "No"}")
            appendLine("Mask    : ${e.maskInfo}")
            appendLine("Lens    : ${e.lens}")
            appendLine("VZA     : ${e.vzaRange}")
            appendLine("Rings   : ${e.ringsSegments}")
            appendLine("Thresh. : ${e.threshold}")
            if (e.gapFracTable.isNotEmpty()) {
                appendLine()
                appendLine("── Gap Fraction Table ──")
                append(e.gapFracTable)
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle(e.imageName)
            .setMessage(text)
            .setPositiveButton("Close", null)
            .show()
            // Make message use monospace font for the table
            .also { dlg ->
                dlg?.window?.decorView?.post {
                    dlg.findViewById<TextView>(android.R.id.message)
                        ?.typeface = android.graphics.Typeface.MONOSPACE
                }
            }
    }

    private fun showComparison(a: SavedResultEntry, b: SavedResultEntry) {
        fun diff(va: Double, vb: Double): String {
            val d = vb - va
            return if (d >= 0) "(+%.2f)".format(d) else "(%.2f)".format(d)
        }
        val text = buildString {
            appendLine("%-22s  %-10s  %-10s  Diff".format("Metric", shorten(a.imageName), shorten(b.imageName)))
            appendLine("-".repeat(58))
            appendLine("%-22s  %-10.2f  %-10.2f  %s".format("Le (Eff. LAI)", a.le, b.le, diff(a.le, b.le)))
            appendLine("%-22s  %-10.2f  %-10.2f  %s".format("L  (True LAI)", a.l, b.l, diff(a.l, b.l)))
            appendLine("%-22s  %-10.2f  %-10.2f  %s".format("LX (Clumping)", a.lx, b.lx, diff(a.lx, b.lx)))
            appendLine("%-22s  %-10.2f  %-10.2f  %s".format("LXG1", a.lxg1, b.lxg1, diff(a.lxg1, b.lxg1)))
            appendLine("%-22s  %-10.2f  %-10.2f  %s".format("LXG2", a.lxg2, b.lxg2, diff(a.lxg2, b.lxg2)))
            appendLine("%-22s  %-10.1f  %-10.1f  %s".format("DIFN (%)", a.difn, b.difn, diff(a.difn, b.difn)))
            appendLine("%-22s  %-10.1f  %-10.1f  %s".format("MTA.ell (°)", a.mtaEll, b.mtaEll, diff(a.mtaEll, b.mtaEll)))
            appendLine("%-22s  %-10.2f  %-10.2f  %s".format("x (ellipsoidal)", a.x, b.x, diff(a.x, b.x)))
            appendLine()
            appendLine("A: ${a.imageName}  ·  ${a.timestamp}")
            appendLine("B: ${b.imageName}  ·  ${b.timestamp}")
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Comparison")
            .setMessage(text)
            .setPositiveButton("Close", null)
            .show()
            .also { dlg ->
                dlg?.window?.decorView?.post {
                    dlg.findViewById<TextView>(android.R.id.message)
                        ?.typeface = android.graphics.Typeface.MONOSPACE
                }
            }
    }

    private fun shorten(name: String) = if (name.length > 8) name.take(6) + ".." else name

    private fun confirmDelete(entry: SavedResultEntry) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Result")
            .setMessage("Delete saved result for \"${entry.imageName}\"?")
            .setPositiveButton("Delete") { _, _ ->
                SavedResultsManager.delete(requireContext(), entry.id)
                loadAndDisplay()
                Toast.makeText(requireContext(), "Result deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
