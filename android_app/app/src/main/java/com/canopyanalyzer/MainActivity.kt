package com.canopyanalyzer

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.setupWithNavController
import com.canopyanalyzer.databinding.ActivityMainBinding
import com.canopyanalyzer.viewmodel.AnalysisViewModel
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: AnalysisViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply persisted night mode before inflation so there's no flash
        val prefs = getSharedPreferences("canopy_prefs", MODE_PRIVATE)
        AppCompatDelegate.setDefaultNightMode(
            prefs.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        )
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        val bottomNav: BottomNavigationView = binding.bottomNavigation

        // setupWithNavController handles tab highlighting via addOnDestinationChangedListener.
        // We override setOnItemSelectedListener below to fix back-stack navigation.
        bottomNav.setupWithNavController(navController)

        // Override tab-click navigation so that tapping Analyze or Visualize while on
        // the Results screen pops the back stack instead of trying to push a new instance
        // (which fails because ResultsFragment has no outgoing action to those destinations).
        // Sub-destinations that are not bottom-nav items — tapping any tab from
        // these screens must navigate cleanly without crashing.
        val homeChildDestinations = setOf(R.id.batchResultsFragment)

        bottomNav.setOnItemSelectedListener { item ->
            val currentId = navController.currentDestination?.id

            when {
                // Tapping Analyze from any sub-destination: pop to home, clearing stack
                item.itemId == R.id.homeFragment && currentId in homeChildDestinations -> {
                    if (!navController.popBackStack(R.id.homeFragment, false)) {
                        navController.navigate(R.id.homeFragment, null,
                            NavOptions.Builder().setPopUpTo(R.id.nav_graph, true).build())
                    }
                    true
                }
                // Tapping Visualize from Results: pop results, land back on Visualize
                item.itemId == R.id.visualizationFragment && currentId == R.id.resultsFragment -> {
                    navController.popBackStack()
                    true
                }
                // Tapping Visualize from BatchResults: pop to Visualize if in stack
                item.itemId == R.id.visualizationFragment && currentId == R.id.batchResultsFragment -> {
                    if (!navController.popBackStack(R.id.visualizationFragment, false)) {
                        NavigationUI.onNavDestinationSelected(item, navController)
                    }
                    true
                }
                // All other tab taps: default behaviour
                else -> NavigationUI.onNavDestinationSelected(item, navController)
            }
        }

        // Manage tab enablement based on analysis results
        viewModel.result.observe(this) { result ->
            val hasResult = result != null
            bottomNav.menu.findItem(R.id.visualizationFragment).isEnabled = hasResult
            bottomNav.menu.findItem(R.id.resultsFragment).isEnabled = hasResult
        }

        // Also enable Visualize tab during mask-adjustment (result not yet available)
        viewModel.uiState.observe(this) { state ->
            val isMaskAdjust = state is com.canopyanalyzer.viewmodel.AnalysisViewModel.UiState.MaskAdjust
            if (isMaskAdjust) bottomNav.menu.findItem(R.id.visualizationFragment).isEnabled = true
        }

        // Keep tabs correctly highlighted for sub-destinations that are not in the menu.
        // BatchResults and Settings are launched from Home — Home tab stays active.
        // Setting isChecked directly does NOT trigger OnItemSelectedListener.
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val tabId = when (destination.id) {
                R.id.homeFragment,
                R.id.batchResultsFragment   -> R.id.homeFragment
                R.id.visualizationFragment  -> R.id.visualizationFragment
                R.id.resultsFragment        -> R.id.resultsFragment
                R.id.savedResultsFragment   -> R.id.savedResultsFragment
                R.id.settingsFragment       -> R.id.settingsFragment
                else                        -> null
            }
            if (tabId != null) {
                bottomNav.menu.findItem(tabId)?.isChecked = true
                bottomNav.menu.findItem(R.id.homeFragment)?.isEnabled = true
            }
        }
    }
}
