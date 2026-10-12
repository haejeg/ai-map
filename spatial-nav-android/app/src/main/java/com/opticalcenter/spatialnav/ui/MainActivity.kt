package com.opticalcenter.spatialnav.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {
    private val viewModel: NavViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        // Always-dark UI: light status/navigation bar icons on a transparent bar.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        setContent {
            AppTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val mapController = remember { MapController() }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { grants ->
                    if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                        grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                    ) {
                        viewModel.startTracking()
                    } else if (grants.containsKey(Manifest.permission.ACCESS_FINE_LOCATION)) {
                        viewModel.showNotice("Location denied; using a simulated position.")
                    }
                }

                LaunchedEffect(Unit) {
                    val missing = STARTUP_PERMISSIONS.filterNot(::isGranted)
                    if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
                }

                var showSettings by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = showSettings) { showSettings = false }
                LaunchedEffect(showSettings) { if (showSettings) viewModel.checkUnverifiedKeys() }

                NavScreen(
                    state = state,
                    mapController = mapController,
                    actions = NavActions(
                        onToggleListening = {
                            if (isGranted(Manifest.permission.RECORD_AUDIO)) {
                                viewModel.toggleListening()
                            } else {
                                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                            }
                        },
                        onAsk = viewModel::ask,
                        onSearch = viewModel::search,
                        onClearSearch = viewModel::clearSearch,
                        onNavigateTo = viewModel::navigateTo,
                        onStopNavigation = viewModel::stopNavigation,
                        onToggleTraffic = viewModel::toggleTraffic,
                        onDismissNotice = viewModel::dismissNotice,
                        onOpenSettings = { showSettings = true },
                    ),
                )

                // Drawn over the map rather than replacing it, so the MapView (and its loaded
                // tiles and camera) stays alive while settings are open.
                if (showSettings) {
                    SettingsScreen(
                        keys = state.keys,
                        simulateHeading = state.simulateHeading,
                        heading = state.telemetry.pose.heading,
                        actions = SettingsActions(
                            onBack = { showSettings = false },
                            onSave = viewModel::saveKey,
                            onTest = viewModel::testKey,
                            onRemove = viewModel::removeKey,
                            onSimulateHeading = viewModel::setSimulateHeading,
                            onManualHeading = viewModel::setManualHeading,
                        ),
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.startTracking()
    }

    override fun onStop() {
        viewModel.stopTracking()
        super.onStop()
    }

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        val STARTUP_PERMISSIONS = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
        )
    }
}
