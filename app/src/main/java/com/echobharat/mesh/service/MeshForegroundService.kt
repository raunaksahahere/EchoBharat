package com.echobharat.mesh.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.echobharat.MainActivity
import com.echobharat.R
import com.echobharat.mesh.transport.BluetoothMeshService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MeshForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "echobharat_mesh_service"
        private const val NOTIFICATION_ID = 10001

        const val ACTION_START = "com.echobharat.mesh.service.START"
        const val ACTION_STOP = "com.echobharat.mesh.service.STOP"
        const val ACTION_QUIT = "com.echobharat.mesh.service.QUIT"
        const val ACTION_UPDATE_NOTIFICATION = "com.echobharat.mesh.service.UPDATE_NOTIFICATION"

        fun start(context: Context) {
            try {
                val intent = Intent(context, MeshForegroundService::class.java).apply { action = ACTION_START }

                // Only launch as an FGS when onStartCommand can promote immediately.
                val shouldStartForeground = shouldStartAsForeground(context)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (shouldStartForeground) {
                        context.startForegroundService(intent)
                    } else {
                        android.util.Log.i(
                            "MeshForegroundService",
                            "Not starting service on API>=26 (shouldStartForeground=$shouldStartForeground)"
                        )
                    }
                } else {
                    if (MeshServicePreferences.isBackgroundEnabled(true)) {
                        context.startService(intent)
                    } else {
                        android.util.Log.i("MeshForegroundService", "Background disabled; not starting service (pre-O)")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MeshForegroundService", "FGS_START_FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }

        private fun shouldStartAsForeground(context: Context): Boolean {
            return MeshServicePreferences.isBackgroundEnabled(true) &&
                    hasBluetoothPermissionsStatic(context)
        }

        private fun hasBluetoothPermissionsStatic(ctx: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.BLUETOOTH_ADVERTISE) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                val fine = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val coarse = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                fine || coarse
            }
        }

    }

    private lateinit var notificationManager: NotificationManagerCompat
    private var updateJob: Job? = null
    private val meshService: BluetoothMeshService?
        get() = MeshServiceHolder.meshService
    private val unifiedMeshService: com.echobharat.mesh.transport.MeshService?
        get() = MeshServiceHolder.unifiedMeshService
    private val serviceJob = Job()
    // Service lifecycle callbacks and notification state are main-thread confined.
    private val scope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)
    private var isInForeground: Boolean = false
    private var isShuttingDown: Boolean = false
    private var lastNotifiedPeerCount: Int? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = NotificationManagerCompat.from(this)

        // MUST run before any startForeground() call. Posting a foreground-service
        // notification against a channel that was never created makes the system kill
        // the process with CannotPostForegroundServiceNotificationException on O+.
        createChannel()

        if (unifiedMeshService != null || meshService != null) {
            Log.i("MeshForegroundService", "Reusing existing MeshService instance from holder")
        } else {
            val created = MeshServiceHolder.getOrCreate(applicationContext)
            Log.i("MeshForegroundService", "Created new BluetoothMeshService via holder")
            MeshServiceHolder.attach(created)
        }
        MeshServiceHolder.getUnifiedOrCreate(applicationContext)

        // Notification content is driven by peer-state changes
        updateJob = scope.launch {
            com.echobharat.services.AppStateStore.peers
                .map { peers -> peers.distinct().size }
                .distinctUntilChanged()
                .collect { peerCount ->
                    if (isInForeground) updateNotification(force = false)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isShuttingDown && intent?.action == ACTION_START) {
            AppShutdownCoordinator.cancelPendingShutdown()
            isShuttingDown = false
        }
        if (isShuttingDown && intent?.action != ACTION_QUIT) {
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                // Stop FGS and mesh cleanly
                updateJob?.cancel()
                try { unifiedMeshService?.stopServices() ?: meshService?.stopServices() } catch (_: Exception) { }
                try { MeshServiceHolder.clear() } catch (_: Exception) { }
                try { stopForeground(true) } catch (_: Exception) { }
                clearMeshNotifications()
                isInForeground = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_QUIT -> {
                isShuttingDown = true
                updateJob?.cancel()
                updateJob = null
                try { stopForeground(true) } catch (_: Exception) { }
                clearMeshNotifications()
                isInForeground = false
                // Fully stop all background activity, stop Tor (without changing setting), then kill the app
                AppShutdownCoordinator.requestFullShutdownAndKill(
                    app = application,
                    mesh = unifiedMeshService,
                    notificationManager = notificationManager,
                    stopForeground = {
                        try { stopForeground(true) } catch (_: Exception) { }
                        isInForeground = false
                    },
                    stopService = { stopSelf() }
                )
                return START_NOT_STICKY
            }
            ACTION_UPDATE_NOTIFICATION -> {
                // If we became eligible and are not in foreground yet, promote once
                if (MeshServicePreferences.isBackgroundEnabled(true) && hasAllRequiredPermissions() && !isInForeground) {
                    val count = getUnifiedActivePeerCount()
                    val n = buildNotification(count)
                    startForegroundCompat(n)
                    isInForeground = true
                    lastNotifiedPeerCount = count
                } else {
                    updateNotification(force = true)
                }
            }
            else -> { /* ACTION_START or null */ }
        }

        // Ensure mesh is running (only after permissions are granted)
        ensureMeshStarted()

        // Promote exactly once when eligible, otherwise stay background (or stop).
        // POST_NOTIFICATIONS is intentionally not an eligibility requirement: Android 13+
        // still allows foreground services and exposes them in the system task manager.
        if (MeshServicePreferences.isBackgroundEnabled(true) && hasAllRequiredPermissions() && !isInForeground) {
            val count = getUnifiedActivePeerCount()
            val notification = buildNotification(count)
            startForegroundCompat(notification)
            isInForeground = true
            lastNotifiedPeerCount = count
        }

        return START_STICKY
    }

    private fun ensureMeshStarted() {
        if (isShuttingDown) return

        try {
            android.util.Log.d("MeshForegroundService", "Ensuring mesh service is started")
            val service = MeshServiceHolder.getUnifiedOrCreate(applicationContext)
            service.startServices()
        } catch (e: Exception) {
            android.util.Log.e("MeshForegroundService", "Failed to start mesh service: ${e.message}")
        }
    }

    private fun updateNotification(force: Boolean) {
        if (isShuttingDown) {
            clearMeshNotifications()
            return
        }
        val count = getUnifiedActivePeerCount()
        if (MeshServicePreferences.isBackgroundEnabled(true) && hasAllRequiredPermissions()) {
            if (lastNotifiedPeerCount != count) {
                startForegroundCompat(buildNotification(count))
                lastNotifiedPeerCount = count
            }
        } else if (force) {
            // If disabled and forced, make sure to remove any prior foreground state
            try { stopForeground(false) } catch (_: Exception) { }
            clearMeshNotifications()
            isInForeground = false
            lastNotifiedPeerCount = null
        }
    }

    private fun clearMeshNotifications() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun hasAllRequiredPermissions(): Boolean {
        // For starting FGS with connectedDevice|dataSync, we need:
        // - Foreground service permissions (declared in manifest)
        // - One of the device-related permissions (we request BL perms at runtime)
        // POST_NOTIFICATIONS controls notification-drawer visibility, not FGS eligibility.
        return hasBluetoothPermissions()
    }

    private fun getUnifiedActivePeerCount(): Int {
        return try {
            unifiedMeshService?.getActivePeerCount() ?: meshService?.getActivePeerCount() ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_ADVERTISE) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            // Prior to S, scanning requires location permissions
            val fine = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val coarse = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            fine || coarse
        }
    }

    private fun buildNotification(activePeers: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        // Action: Quit Bitchat
        val quitIntent = Intent(this, MeshForegroundService::class.java).apply { action = ACTION_QUIT }
        val quitPendingIntent = PendingIntent.getService(
            this, 1, quitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val title = getString(R.string.app_name)
        val content = getString(R.string.mesh_service_notification_content, activePeers)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            // Android rejects adaptive launcher icons for foreground-service notifications.
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            // Add an action button that appears when notification is expanded
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_action_quit_bitchat),
                quitPendingIntent
            )
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.mesh_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.mesh_service_channel_desc)
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun startForegroundCompat(notification: Notification) {
        // Defensive: the channel is created in onCreate(), but a user can delete it from
        // system settings. Re-asserting is a cheap no-op and keeps startForeground() legal.
        createChannel()

        if (Build.VERSION.SDK_INT >= 34) {
            val type = if (hasLocationPermission()) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            try {
                startForeground(NOTIFICATION_ID, notification, type)
            } catch (e: SecurityException) {
                // Fallback for cases where "While In Use" permission exists but background start is restricted
                if (type and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0) {
                     android.util.Log.w("MeshForegroundService", "Failed to start with LOCATION type, falling back to CONNECTED_DEVICE: ${e.message}")
                    try {
                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                    } catch (fallback: Exception) {
                        android.util.Log.e("MeshForegroundService", "FGS_PROMOTION_FAILED: ${fallback.javaClass.simpleName}: ${fallback.message}", fallback)
                        stopSelf()
                    }
                } else {
                    android.util.Log.e("MeshForegroundService", "FGS_PROMOTION_FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
                    stopSelf()
                }
            }
        } else {
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                android.util.Log.e("MeshForegroundService", "FGS_PROMOTION_FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        updateJob?.cancel()
        updateJob = null
        // Cancel the service coroutine scope to prevent leaks
        try { serviceJob.cancel() } catch (_: Exception) { }
        // Best-effort ensure we are not marked foreground
        if (isInForeground) {
            try { stopForeground(true) } catch (_: Exception) { }
            isInForeground = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
