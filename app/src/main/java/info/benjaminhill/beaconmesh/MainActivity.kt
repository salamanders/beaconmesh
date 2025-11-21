package info.benjaminhill.beaconmesh

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import info.benjaminhill.beaconmesh.data.BeaconManager
import info.benjaminhill.beaconmesh.data.MeshRepository
import info.benjaminhill.beaconmesh.domain.DeviceIdentity
import info.benjaminhill.beaconmesh.ui.EnsurePermissions
import info.benjaminhill.beaconmesh.ui.MeshScreen
import info.benjaminhill.beaconmesh.ui.MeshViewModel
import info.benjaminhill.beaconmesh.ui.theme.BeaconMeshTheme
import timber.log.Timber

class MainActivity : ComponentActivity() {

    // Simple manual DI
    private lateinit var viewModel: MeshViewModel
    private lateinit var beaconManager: BeaconManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Safe way to init Timber without relying on generated BuildConfig which might be flaky in this setup
        Timber.plant(Timber.DebugTree())

        val deviceIdentity = DeviceIdentity(applicationContext)
        beaconManager = BeaconManager(applicationContext, lifecycleScope)
        val meshRepository = MeshRepository(deviceIdentity)

        val factory = MeshViewModel.Factory(meshRepository, beaconManager)
        viewModel = ViewModelProvider(this, factory)[MeshViewModel::class.java]

        enableEdgeToEdge()

        setContent {
            BeaconMeshTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->

                    val permissions = mutableListOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        permissions.add(Manifest.permission.BLUETOOTH_SCAN)
                        permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
                        permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
                    }

                    EnsurePermissions(
                        context = this,
                        permissions = permissions.toTypedArray(),
                        onPermissionsGranted = {
                            // Start the radio when permissions are ready
                            viewModel.startRadio()
                        },
                        onPermissionsDenied = {
                            // In a real app, show a rationale or "Open Settings" button
                        }
                    )

                    MeshScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.stopRadio()
    }
}
