package com.echobharat

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.echobharat.identity.IdentityManager
import com.echobharat.mesh.EchoBharatMeshManager
import com.echobharat.mesh.transport.BleReadiness
import com.echobharat.mesh.service.MeshForegroundService
import com.echobharat.ui.languages.LanguagePacksScreen
import com.echobharat.schema.Peer
import com.echobharat.ui.home.HomeScreen
import com.echobharat.ui.onboarding.OnboardingScreen
import com.echobharat.ui.verification.ContactVerificationDialog
import com.echobharat.ui.theme.SurfaceBg
import com.echobharat.ui.theme.EchoBharatTheme
import com.echobharat.ui.transceiver.TransceiverScreen

enum class AppScreen {
    ONBOARDING,

    /** Peer-first landing screen: search, the people in range, and distress. */
    HOME,

    /** One conversation with one peer. Only reachable by picking someone on HOME. */
    PEER_CHAT,
    LANGUAGES
}

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "EchoBharatPermissions"
    }

    private lateinit var identityManager: IdentityManager
    private lateinit var meshManager: EchoBharatMeshManager

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { Log.i(TAG, "Bluetooth enable prompt answered: resultCode=${it.resultCode}") }

    private var askedToEnableBluetooth = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Log.i(TAG, "Runtime permission result: " + permissions.entries.joinToString {
            "${it.key.substringAfterLast('.')}=${it.value}"
        })
        if (hasMeshPermissions()) {
            MeshForegroundService.start(this)
            meshManager.startMesh()
            ensureBluetoothOn()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        identityManager = IdentityManager.getInstance(this)
        meshManager = EchoBharatMeshManager.getInstance(this)

        requestRequiredPermissions()

        setContent {
            EchoBharatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = SurfaceBg
                ) {
                    // Which peer PEER_CHAT is about. Held here rather than in the
                    // screen so returning from Language Packs lands back in the same
                    // conversation instead of dropping to the peer list. Saveable, so
                    // rotating the phone mid-conversation no longer lands on HOME.
                    var activePeer by rememberSaveable { mutableStateOf<Peer?>(null) }
                    var verificationPeer by remember { mutableStateOf<Peer?>(null) }
                    var currentScreen by rememberSaveable {
                        mutableStateOf(
                            if (identityManager.hasIdentity()) AppScreen.HOME else AppScreen.ONBOARDING
                        )
                    }

                    // System back walks the app's own screens instead of closing it: from a
                    // chat or Language Packs it returns where the user came from, and only
                    // HOME hands back to the launcher.
                    BackHandler(enabled = currentScreen == AppScreen.PEER_CHAT || currentScreen == AppScreen.LANGUAGES) {
                        currentScreen = if (currentScreen == AppScreen.LANGUAGES && activePeer != null) {
                            AppScreen.PEER_CHAT
                        } else {
                            activePeer = null
                            AppScreen.HOME
                        }
                    }

                    when (currentScreen) {
                        AppScreen.ONBOARDING -> {
                            OnboardingScreen(
                                identityManager = identityManager,
                                onComplete = {
                                    MeshForegroundService.start(this)
                                    meshManager.startMesh()
                                    currentScreen = AppScreen.HOME
                                }
                            )
                        }

                        AppScreen.HOME -> {
                            HomeScreen(
                                meshManager = meshManager,
                                onOpenPeer = { peer ->
                                    activePeer = peer
                                    currentScreen = AppScreen.PEER_CHAT
                                },
                                onOpenLanguages = {
                                    currentScreen = AppScreen.LANGUAGES
                                },
                                onVerifyPeer = { verificationPeer = it },
                                onPanicWipe = {
                                    verificationPeer = null
                                    activePeer = null
                                    meshManager.panicWipe()
                                    currentScreen = AppScreen.ONBOARDING
                                }
                            )
                        }

                        AppScreen.PEER_CHAT -> {
                            // A conversation cannot exist without a peer; if one is
                            // somehow missing, fall back rather than showing an
                            // addressee-less screen that would broadcast.
                            val peer = activePeer
                            if (peer == null) {
                                currentScreen = AppScreen.HOME
                            } else {
                                TransceiverScreen(
                                    meshManager = meshManager,
                                    peer = peer,
                                    onBack = {
                                        activePeer = null
                                        currentScreen = AppScreen.HOME
                                    },
                                    onOpenLanguages = {
                                        currentScreen = AppScreen.LANGUAGES
                                    },
                                    onVerifyPeer = { verificationPeer = it }
                                )
                            }
                        }

                        AppScreen.LANGUAGES -> {
                            LanguagePacksScreen(
                                onBack = {
                                    currentScreen =
                                        if (activePeer != null) AppScreen.PEER_CHAT
                                        else AppScreen.HOME
                                }
                            )
                        }
                    }

                    verificationPeer?.let { target ->
                        ContactVerificationDialog(
                            meshManager = meshManager,
                            peer = target,
                            onDismiss = { verificationPeer = null }
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        askedToEnableBluetooth = false
        if (identityManager.hasIdentity() && hasMeshPermissions()) {
            MeshForegroundService.start(this)
            meshManager.startMesh()
            ensureBluetoothOn()
        }
    }

    /**
     * Gets Bluetooth switched on without a trip to Settings.
     *
     * Android 13+ forbids an app from toggling Bluetooth itself, so on those phones the best
     * available is the system's one-tap "Allow" dialog, shown once per visit to the app. Older
     * phones are switched on silently. The mesh restarts on its own when the adapter comes up
     * (see EchoBharatMeshManager). Wi-Fi is not involved: the mesh is Bluetooth LE only.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun ensureBluetoothOn() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        if (adapter.isEnabled) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            try {
                if (adapter.enable()) return
            } catch (e: Exception) {
                Log.w(TAG, "Silent Bluetooth enable refused: ${e.message}")
            }
        }
        if (askedToEnableBluetooth) return
        askedToEnableBluetooth = true
        try {
            bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (e: Exception) {
            Log.w(TAG, "Could not show the Bluetooth enable prompt: ${e.message}")
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = getNeededPermissions()
        if (permissions.isNotEmpty()) {
            Log.i(TAG, "Requesting runtime permissions: ${permissions.joinToString()}")
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            if (identityManager.hasIdentity() && hasMeshPermissions()) {
                MeshForegroundService.start(this)
                meshManager.startMesh()
            }
        }
    }

    private fun getNeededPermissions(): List<String> {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // Location is requested on every API level, not just pre-31. BLUETOOTH_SCAN is declared
        // without `neverForLocation`, so Android treats our scans as location-deriving and
        // withholds every scan result until location permission is granted — silently, with no
        // callback and no error. Asking only below API 31 left Android 12+ phones scanning into
        // the void. BleReadiness.isLocationRequiredForScan follows the manifest flag, so this
        // stays correct if that flag is ever added.
        if (BleReadiness.isLocationRequiredForScan(this)) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        permissions.add(Manifest.permission.RECORD_AUDIO)

        return permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasMeshPermissions(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        Log.i(TAG, "BLE runtime permission check: ${if (missing.isEmpty()) "all granted" else "missing $missing"}")

        // Mesh start is gated on the Bluetooth permissions only: without location this phone can
        // still advertise and serve GATT, so half a mesh beats none. The missing half is surfaced
        // on screen by MeshReadinessNotice instead of silently degrading.
        Log.i(TAG, "Discovery readiness: ${BleReadiness.check(this).summary()}")
        return missing.isEmpty()
    }
}
