package com.canopyanalyzer.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.canopyanalyzer.R
import com.canopyanalyzer.databinding.FragmentSettingsBinding
import com.canopyanalyzer.model.*
import com.canopyanalyzer.viewmodel.AnalysisViewModel

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AnalysisViewModel by activityViewModels()

    /** True while populateFromSettings() is running — prevents listeners from marking dirty */
    private var isPopulating = false
    /** True when the user has changed a field but not yet saved */
    private var hasUnsavedChanges = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupDropdowns()

        // Populate from ViewModel (isPopulating guard prevents false dirty marking)
        viewModel.settings.observe(viewLifecycleOwner) { s -> populateFromSettings(s) }

        // ── Spinners ────────────────────────────────────────────────────────
        binding.spinnerMask.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val mode = MaskMode.values().firstOrNull { it.label == s?.toString() } ?: return
                updateMaskUiVisibility(mode)
                markDirty()
            }
        })

        binding.spinnerThreshold.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                binding.layoutManualThreshold.visibility =
                    if (s?.toString() == ThresholdMethod.MANUAL.label) View.VISIBLE else View.GONE
                markDirty()
            }
        })

        binding.spinnerChannel.addTextChangedListener(dirtyWatcher())
        binding.spinnerCameraPreset.addTextChangedListener(dirtyWatcher())
        binding.spinnerLens.addTextChangedListener(dirtyWatcher())

        // ── Switches ─────────────────────────────────────────────────────────
        binding.switchStretch.setOnCheckedChangeListener  { _, _ -> markDirty() }
        binding.switchCircular.setOnCheckedChangeListener { _, _ -> markDirty() }

        // ── EditTexts ────────────────────────────────────────────────────────
        binding.etMaskXc.addTextChangedListener(dirtyWatcher())
        binding.etMaskYc.addTextChangedListener(dirtyWatcher())
        binding.etMaskRc.addTextChangedListener(dirtyWatcher())
        binding.etManualThreshold.addTextChangedListener(dirtyWatcher())
        binding.etMaxVza.addTextChangedListener(dirtyWatcher())
        binding.etStartVza.addTextChangedListener(dirtyWatcher())
        binding.etEndVza.addTextChangedListener(dirtyWatcher())

        // ── Sliders ──────────────────────────────────────────────────────────
        binding.sliderGamma.addOnChangeListener { _, value, _ ->
            binding.tvGammaValue.text = "%.2f".format(value)
            markDirty()
        }
        binding.sliderRings.addOnChangeListener { _, value, _ ->
            binding.tvRingsValue.text = "${value.toInt()}"
            markDirty()
        }
        binding.sliderSegments.addOnChangeListener { _, value, _ ->
            binding.tvSegmentsValue.text = "${value.toInt()}"
            markDirty()
        }

        // ── Buttons ──────────────────────────────────────────────────────────
        binding.btnSaveSettings.setOnClickListener { saveSettings() }
        binding.btnCancel.setOnClickListener { handleBack() }

        binding.btnResetDefaults.setOnClickListener {
            viewModel.updateSettings(AnalysisSettings())
            // populateFromSettings() fires via observer; clear dirty state explicitly
            hasUnsavedChanges = false
            binding.tvUnsavedBanner.visibility = View.GONE
            Toast.makeText(requireContext(), "Settings reset to defaults", Toast.LENGTH_SHORT).show()
        }

        // ── Hardware/gesture back ─────────────────────────────────────────────
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { handleBack() }
            }
        )
    }

    // ─── Dirty tracking ──────────────────────────────────────────────────────

    private fun markDirty() {
        if (isPopulating) return
        if (!hasUnsavedChanges) {
            hasUnsavedChanges = true
            binding.tvUnsavedBanner.visibility = View.VISIBLE
        }
    }

    /** Minimal TextWatcher that calls markDirty() in afterTextChanged */
    private fun dirtyWatcher() = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) { markDirty() }
    }

    private fun clearDirty() {
        hasUnsavedChanges = false
        binding.tvUnsavedBanner.visibility = View.GONE
    }

    // ─── Back press handling ─────────────────────────────────────────────────

    private fun handleBack() {
        if (hasUnsavedChanges) {
            AlertDialog.Builder(requireContext())
                .setTitle("Unsaved Changes")
                .setMessage("You have unsaved changes. Save before leaving?")
                .setPositiveButton("Save")    { _, _ -> saveSettings() }
                .setNegativeButton("Discard") { _, _ -> findNavController().popBackStack() }
                .setNeutralButton("Stay",    null)
                .show()
        } else {
            findNavController().popBackStack()
        }
    }

    // ─── Dropdown setup ──────────────────────────────────────────────────────

    /**
     * ArrayAdapter whose filter always returns ALL items regardless of the current
     * text value. Without this, MaterialAutoCompleteTextView filters the dropdown
     * to only show items that match the currently displayed text, making it
     * impossible to switch from one option to another.
     */
    private fun noFilterAdapter(items: List<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(
            requireContext(), android.R.layout.simple_dropdown_item_1line, items
        ) {
            override fun getFilter(): Filter = object : Filter() {
                override fun performFiltering(constraint: CharSequence?) =
                    FilterResults().apply { values = items; count = items.size }
                override fun publishResults(constraint: CharSequence?, results: FilterResults?) =
                    notifyDataSetChanged()
            }
        }
    }

    private val darkModeLabels = listOf("Follow System", "Light", "Dark")

    private fun nightModeToLabel(mode: Int) = when (mode) {
        AppCompatDelegate.MODE_NIGHT_NO  -> "Light"
        AppCompatDelegate.MODE_NIGHT_YES -> "Dark"
        else                             -> "Follow System"
    }

    private fun labelToNightMode(label: String) = when (label) {
        "Light" -> AppCompatDelegate.MODE_NIGHT_NO
        "Dark"  -> AppCompatDelegate.MODE_NIGHT_YES
        else    -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    private fun setupDropdowns() {
        binding.spinnerDarkMode.setAdapter(noFilterAdapter(darkModeLabels))

        // Populate from persisted preference
        val prefs = requireContext().getSharedPreferences("canopy_prefs", Context.MODE_PRIVATE)
        val currentMode = prefs.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        binding.spinnerDarkMode.setText(nightModeToLabel(currentMode), false)

        // Apply immediately on selection (no Save needed for theme)
        binding.spinnerDarkMode.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (isPopulating) return
                val mode = labelToNightMode(s?.toString() ?: "Follow System")
                prefs.edit().putInt("night_mode", mode).apply()
                AppCompatDelegate.setDefaultNightMode(mode)
                // Activity recreates automatically — no further action needed
            }
        })

        binding.spinnerChannel.setAdapter(noFilterAdapter(
            ChannelType.values().map { it.label }))

        binding.spinnerLens.setAdapter(noFilterAdapter(
            LensType.values().map { it.label }))

        binding.spinnerThreshold.setAdapter(noFilterAdapter(
            ThresholdMethod.values().map { it.label }))

        binding.spinnerMask.setAdapter(noFilterAdapter(
            MaskMode.values().map { it.label }))

        binding.spinnerCameraPreset.setAdapter(noFilterAdapter(
            CameraPreset.values().map { it.label }))
    }

    private fun updateMaskUiVisibility(mode: MaskMode) {
        binding.layoutManualMask.visibility =
            if (mode == MaskMode.MANUAL) View.VISIBLE else View.GONE
        binding.layoutCameraPreset.visibility =
            if (mode == MaskMode.CAMERA_PRESET) View.VISIBLE else View.GONE
        binding.switchCircular.visibility =
            if (mode == MaskMode.AUTO || mode == MaskMode.MANUAL) View.VISIBLE else View.GONE
    }

    // ─── Populate from ViewModel ─────────────────────────────────────────────

    private fun populateFromSettings(s: AnalysisSettings) {
        isPopulating = true
        binding.spinnerChannel.setText(s.channel.label, false)
        binding.sliderGamma.value = s.gamma.coerceIn(0.1f, 5f)
        binding.tvGammaValue.text = "%.2f".format(s.gamma)
        binding.switchStretch.isChecked = s.stretch
        binding.switchCircular.isChecked = s.circularFisheye

        binding.spinnerMask.setText(s.maskMode.label, false)
        updateMaskUiVisibility(s.maskMode)

        binding.etMaskXc.setText(s.maskXc.toString())
        binding.etMaskYc.setText(s.maskYc.toString())
        binding.etMaskRc.setText(s.maskRc.toString())

        binding.spinnerCameraPreset.setText(s.cameraPreset.label, false)

        binding.spinnerThreshold.setText(s.thresholdMethod.label, false)
        binding.layoutManualThreshold.visibility =
            if (s.thresholdMethod == ThresholdMethod.MANUAL) View.VISIBLE else View.GONE
        binding.etManualThreshold.setText(s.manualThreshold.toString())

        binding.spinnerLens.setText(s.lensType.label, false)
        binding.etMaxVza.setText(s.maxVZA.toInt().toString())
        binding.etStartVza.setText(s.startVZA.toInt().toString())
        binding.etEndVza.setText(s.endVZA.toInt().toString())
        binding.sliderRings.value = s.nRings.toFloat()
        binding.sliderSegments.value = s.nSegments.toFloat()
        binding.tvRingsValue.text = "${s.nRings}"
        binding.tvSegmentsValue.text = "${s.nSegments}"
        isPopulating = false
    }

    // ─── Save ────────────────────────────────────────────────────────────────

    private fun saveSettings() {
        try {
            val channel = ChannelType.values().firstOrNull {
                it.label == binding.spinnerChannel.text.toString()
            } ?: ChannelType.BLUE_3

            val gamma = binding.sliderGamma.value
            val maskMode = MaskMode.values().firstOrNull {
                it.label == binding.spinnerMask.text.toString()
            } ?: MaskMode.AUTO

            val maskXc = binding.etMaskXc.text.toString().toIntOrNull() ?: 0
            val maskYc = binding.etMaskYc.text.toString().toIntOrNull() ?: 0
            val maskRc = binding.etMaskRc.text.toString().toIntOrNull() ?: 0

            if (maskMode == MaskMode.MANUAL && maskRc <= 0) {
                toast("For Manual mode, mask radius (rc) must be > 0")
                return
            }

            val cameraPreset = CameraPreset.values().firstOrNull {
                it.label == binding.spinnerCameraPreset.text.toString()
            } ?: CameraPreset.COOLPIX950_FCE8

            val threshMethod = ThresholdMethod.values().firstOrNull {
                it.label == binding.spinnerThreshold.text.toString()
            } ?: ThresholdMethod.GLOBAL_OTSU

            val manualThresh = binding.etManualThreshold.text.toString().toIntOrNull() ?: 128
            if (threshMethod == ThresholdMethod.MANUAL && (manualThresh < 0 || manualThresh > 255)) {
                toast("Manual threshold must be 0–255")
                return
            }

            val lens = LensType.values().firstOrNull {
                it.label == binding.spinnerLens.text.toString()
            } ?: LensType.FC_E8

            val maxVZA   = binding.etMaxVza.text.toString().toFloatOrNull() ?: 90f
            val startVZA = binding.etStartVza.text.toString().toFloatOrNull() ?: 0f
            val endVZA   = binding.etEndVza.text.toString().toFloatOrNull() ?: 70f

            if (startVZA >= endVZA) { toast("Start VZA must be < End VZA"); return }
            if (endVZA > maxVZA) { toast("End VZA must be ≤ Max VZA ($maxVZA°)"); return }
            if (maxVZA <= 0f || maxVZA > 90f) { toast("Max VZA must be between 1 and 90°"); return }

            val newSettings = AnalysisSettings(
                channel         = channel,
                gamma           = gamma,
                stretch         = binding.switchStretch.isChecked,
                maskMode        = maskMode,
                maskXc          = maskXc,
                maskYc          = maskYc,
                maskRc          = maskRc,
                circularFisheye = binding.switchCircular.isChecked,
                cameraPreset    = cameraPreset,
                thresholdMethod = threshMethod,
                manualThreshold = manualThresh,
                lensType        = lens,
                maxVZA          = maxVZA,
                startVZA        = startVZA,
                endVZA          = endVZA,
                nRings          = binding.sliderRings.value.toInt(),
                nSegments       = binding.sliderSegments.value.toInt()
            )

            viewModel.updateSettings(newSettings)
            clearDirty()
            toast("Settings saved")
            findNavController().popBackStack()
        } catch (e: Exception) {
            toast("Error saving settings: ${e.message}")
        }
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
