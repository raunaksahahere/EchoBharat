package com.echobharat.mesh.transport

import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.echobharat.mesh.protocol.BitchatPacket
import com.echobharat.util.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages GATT server operations, advertising, and server-side connections
 */
class BluetoothGattServerManager(
    private val context: Context,
    private val connectionScope: CoroutineScope,
    private val connectionTracker: BluetoothConnectionTracker,
    private val permissionManager: BluetoothPermissionManager,
    private val powerManager: PowerManager,
    private val delegate: BluetoothConnectionManagerDelegate?,
    private val myPeerID: String
) {
    
    companion object {
        private const val TAG = "BluetoothGattServerManager"
        // Self-healing advertising recovery tuning
        private const val ADVERTISE_RETRY_BASE_MS = 3_000L      // base backoff for transient advertise failures
        private const val ADVERTISE_MAX_RETRY_DELAY_MS = 30_000L // cap on backoff delay
    }
    
    // Core Bluetooth components
    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleAdvertiser: BluetoothLeAdvertiser?
        get() = try { bluetoothAdapter?.bluetoothLeAdvertiser } catch (_: SecurityException) { null }

    private fun isBluetoothEnabled(): Boolean = try {
        bluetoothAdapter?.isEnabled == true
    } catch (_: SecurityException) {
        false
    }

    private fun canUseServer(): Boolean {
        if (!isActive) return false
        if (permissionManager.hasBluetoothPermissions()) return true
        stop()
        return false
    }

    private fun sendResponse(device: BluetoothDevice, requestId: Int, status: Int) {
        if (!canUseServer()) return
        try {
            gattServer?.sendResponse(device, requestId, status, 0, null)
        } catch (_: SecurityException) {
            stop()
        }
    }
    
    // GATT server for peripheral mode
    private var gattServer: BluetoothGattServer? = null
    private val serverLinkIDs = ConcurrentHashMap<String, String>()
    private var characteristic: BluetoothGattCharacteristic? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var advertiseRetryCount = 0
    
    // State management
    private var isActive = false

    private fun isBleTransportEnabled(): Boolean {
        return try {
            com.echobharat.ui.debug.DebugSettingsManager.getInstance().bleEnabled.value
        } catch (_: Exception) {
            try { com.echobharat.ui.debug.DebugPreferenceManager.getBleEnabled(true) } catch (_: Exception) { true }
        }
    }

    private fun isServerRoleEnabled(): Boolean {
        return isBleTransportEnabled() &&
            (try { com.echobharat.ui.debug.DebugSettingsManager.getInstance().gattServerEnabled.value } catch (_: Exception) { true })
    }

    /**
     * Disconnect a specific device (used by ConnectionManager to enforce overall limits)
     */
    fun disconnectDevice(device: BluetoothDevice) {
        try {
            gattServer?.cancelConnection(device)
        } catch (_: SecurityException) {
            stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting device ${device.address}: ${e.message}")
        }
    }
    
    /**
     * Start GATT server
     */
    fun start(): Boolean {
        // Respect debug setting
        if (!isServerRoleEnabled()) {
            Log.i(TAG, "Server start skipped: BLE/GATT Server disabled in debug settings")
            return false
        }

        if (!permissionManager.hasBluetoothPermissions()) {
            stop()
            Log.e(TAG, "Missing Bluetooth permissions")
            return false
        }
        if (isActive) return true

        if (!isBluetoothEnabled()) {
            Log.e(TAG, "Bluetooth is not enabled")
            return false
        }
        
        if (bleAdvertiser == null) {
            Log.e(TAG, "BLE advertiser not available")
            return false
        }
        
        isActive = true
        
        connectionScope.launch {
            setupGattServer()
            delay(300) // Brief delay to ensure GATT server is ready
            startAdvertising()
        }
        
        return true
    }
    
    /**
     * Stop GATT server
     */
    fun stop() {
        isActive = false
        stopAdvertising()
        val server = gattServer
        gattServer = null
        characteristic = null
        serverLinkIDs.clear()
        val connections = connectionTracker.getConnectedDevices().values.filter { !it.isClient }
        connections.forEach { connection ->
            try {
                server?.cancelConnection(connection.device)
            } catch (_: SecurityException) {
                // Revocation must not prevent local cleanup or closing the server.
            }
            val address = connection.device.address
            val peerID = connectionTracker.addressPeerMap[address]
            if (connectionTracker.cleanupDeviceConnectionIfCurrent(address, connection.linkID)) {
                delegate?.onDeviceDisconnected(connection.device, connection.linkID, peerID)
            }
        }
        try {
            server?.close()
        } catch (_: SecurityException) {
            // The platform owns revoked handles; all local state is already released.
        }
        Log.i(TAG, "GATT server stopped")
    }
    
    /**
     * Get GATT server instance
     */
    fun getGattServer(): BluetoothGattServer? = gattServer
    
    /**
     * Get characteristic instance
     */
    fun getCharacteristic(): BluetoothGattCharacteristic? = characteristic
    
    /**
     * Setup GATT server with proper sequencing
     */
    @Suppress("DEPRECATION")
    private fun setupGattServer() {
        if (!canUseServer()) return

        val serverCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                // Guard against callbacks after service shutdown
                if (!canUseServer()) return

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "Connected to ${device.address} (server)")
                        val linkID = UUID.randomUUID().toString()
                        serverLinkIDs[device.address] = linkID
                        
                        // Get best available RSSI (scan RSSI for server connections)
                        val rssi = connectionTracker.getBestRSSI(device.address) ?: Int.MIN_VALUE
                        
                        val deviceConn = BluetoothConnectionTracker.DeviceConnection(
                            device = device,
                            rssi = rssi,
                            isClient = false,
                            linkID = linkID
                        )
                        connectionTracker.addDeviceConnection(device.address, deviceConn)

                        connectionScope.launch {
                            delay(1000)
                            if (canUseServer()) {
                                delegate?.onDeviceConnected(device)
                            }
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Disconnected from ${device.address} (server)")
                        val linkID = serverLinkIDs.remove(device.address)
                        // Capture the observed peer before cleanup drops the address mapping.
                        val disconnectedPeerID = connectionTracker.addressPeerMap[device.address]
                        if (linkID != null) {
                            connectionTracker.cleanupDeviceConnectionIfCurrent(device.address, linkID)
                        }
                        // Notify delegate about device disconnection so higher layers can update direct flags
                        delegate?.onDeviceDisconnected(device, linkID, disconnectedPeerID)
                    }
                }
            }
            
            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                // Guard against callbacks after service shutdown
                if (!canUseServer()) return

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "Server: Failed to add service: ${service.uuid}, status: $status")
                }
            }
            
            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                // Guard against callbacks after service shutdown
                if (!canUseServer()) return

                if (characteristic.uuid == AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID) {
                    val linkID = serverLinkIDs[device.address]
                    if (linkID == null) {
                        Log.d(TAG, "Server: Dropping packet from stale connection ${device.address}")
                        if (responseNeeded) {
                            sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE)
                        }
                        return
                    }
                    val packet = BitchatPacket.fromBinaryData(value)
                    if (packet != null) {
                        val peerID = packet.senderID.take(8).toByteArray().joinToString("") { "%02x".format(it) }
                        delegate?.onPacketReceived(packet, peerID, device, linkID)
                    } else {
                        Log.d(TAG, "Server: Failed to parse packet from ${device.address}, size: ${value.size} bytes")
                    }
                    
                    if (responseNeeded) {
                        sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS)
                    }
                }
            }
            
            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                // Guard against callbacks after service shutdown
                if (!canUseServer()) return

                if (BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.contentEquals(value)) {
                    connectionTracker.addSubscribedDevice(device)

                    connectionScope.launch {
                        delay(100)
                        if (canUseServer()) {
                            delegate?.onDeviceConnected(device)
                        }
                    }
                }
                
                if (responseNeeded) {
                    sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS)
                }
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                if (!canUseServer()) return
                connectionTracker.recordMtu(device.address, mtu)
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                if (!canUseServer()) return
                delegate?.onGattServerNotificationComplete(
                    device.address,
                    serverLinkIDs[device.address],
                    status
                )
            }
        }
        
        // Proper cleanup sequencing to prevent race conditions
        gattServer?.let { server ->
            try {
                server.close()
            } catch (_: SecurityException) {
                stop()
                return
            } catch (e: Exception) {
                Log.w(TAG, "Error closing existing GATT server: ${e.message}")
            }
        }

        // Small delay to ensure cleanup is complete
        Thread.sleep(100)

        if (!isActive) {
            return
        }
        
        // Create new server
        gattServer = try {
            bluetoothManager.openGattServer(context, serverCallback)
        } catch (_: SecurityException) {
            stop()
            return
        }
        if (gattServer == null) {
            stop()
            return
        }
        
        // Create characteristic with notification support
        characteristic = BluetoothGattCharacteristic(
            AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or 
            BluetoothGattCharacteristic.PROPERTY_WRITE or 
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or 
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        
        val descriptor = BluetoothGattDescriptor(
            AppConstants.Mesh.Gatt.DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        characteristic?.addDescriptor(descriptor)
        
        val service = BluetoothGattService(AppConstants.Mesh.Gatt.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        
        try {
            gattServer?.addService(service)
        } catch (_: SecurityException) {
            stop()
            return
        }
        
        Log.i(TAG, "GATT server setup complete")
    }
    
    /**
     * Start advertising
     */
    @Suppress("DEPRECATION")
    private fun startAdvertising() {
        // Respect debug setting
        val enabled = isServerRoleEnabled()

        // Guard conditions – never throw here to avoid crashing the app from a background
        // coroutine. Every one of these leaves the phone undiscoverable, so every one says so:
        // the !isActive path used to return in silence, which looked identical to advertising
        // that had started fine.
        if (!permissionManager.hasBluetoothPermissions()) {
            BleDiagnostics.advertiseBlocked(
                "missing permissions: " +
                    permissionManager.missingPermissions().joinToString { it.substringAfterLast('.') }
            )
            return
        }
        if (bluetoothAdapter == null) {
            BleDiagnostics.advertiseBlocked("bluetoothAdapter is null")
            return
        }
        if (!isBluetoothEnabled()) {
            BleDiagnostics.advertiseBlocked("Bluetooth adapter is off")
            return
        }
        if (!isActive) {
            BleDiagnostics.advertiseBlocked("server manager not active")
            return
        }
        if (!enabled) {
            BleDiagnostics.advertiseBlocked("GATT server disabled via debug settings")
            return
        }
        val advertiser = bleAdvertiser
        if (advertiser == null) {
            BleDiagnostics.advertiseBlocked("BLE advertiser not available on this device")
            return
        }
        if (!bluetoothAdapter.isMultipleAdvertisementSupported) {
            BleDiagnostics.advertiseBlocked("multiple advertisement not supported on this device")
            return
        }

        val settings = powerManager.getAdvertiseSettings()
        
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID))
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()
            
        // Add stable identity (first 8 bytes of peerID) to Scan Response
        // This allows scanners to deduplicate devices even if MAC address rotates
        val peerIDBytes = try {
            myPeerID.chunked(2).map { it.toInt(16).toByte() }.toByteArray().take(8).toByteArray()
        } catch (e: Exception) {
            ByteArray(0)
        }
        
        val scanResponse = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID), peerIDBytes)
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()
        
        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                advertiseRetryCount = 0
                BleDiagnostics.advertiseStarted(powerManager.profile.value.mode.name)
            }

            override fun onStartFailure(errorCode: Int) {
                BleDiagnostics.advertiseFailed(errorCode)
                // Previously this only logged, so if advertising failed this device became
                // undiscoverable until a manual BLE toggle. Retry transient failures with backoff.
                when (errorCode) {
                    ADVERTISE_FAILED_ALREADY_STARTED -> Unit // already advertising, no retry
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> Unit // config issue, not retrying
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> Unit // unsupported, not retrying
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> {
                        scheduleAdvertiseRestart("too-many-advertisers")
                    }
                    ADVERTISE_FAILED_INTERNAL_ERROR -> {
                        scheduleAdvertiseRestart("internal-error")
                    }
                    else -> {
                        scheduleAdvertiseRestart("unknown-$errorCode")
                    }
                }
            }
        }
        
        try {
            BleDiagnostics.advertiseRequested(
                serviceUuid = AppConstants.Mesh.Gatt.SERVICE_UUID,
                mode = powerManager.profile.value.mode.name
            )
            advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
        } catch (se: SecurityException) {
            stop()
            BleDiagnostics.advertiseBlocked("SecurityException (missing permission?): ${se.message}")
        } catch (e: Exception) {
            BleDiagnostics.advertiseBlocked("startAdvertising threw: ${e.message}")
        }
    }
    
    /**
     * Stop advertising
     */
    @Suppress("DEPRECATION")
    private fun stopAdvertising() {
        try {
            advertiseCallback?.let { cb ->
                bleAdvertiser?.stopAdvertising(cb)
                BleDiagnostics.advertiseStopped("stopAdvertising() called")
            }
        } catch (_: SecurityException) {
            Log.i(TAG, "Advertising permission revoked during stop")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping advertising: ${e.message}")
        } finally {
            // Dropped so a restart cannot hand the stack a callback it already retired.
            advertiseCallback = null
        }
    }
    
    /**
     * Schedule an advertising restart with incremental backoff after a transient failure.
     */
    private fun scheduleAdvertiseRestart(reason: String) {
        advertiseRetryCount++
        val delayMs = (ADVERTISE_RETRY_BASE_MS * advertiseRetryCount).coerceAtMost(ADVERTISE_MAX_RETRY_DELAY_MS)
        Log.w(TAG, "Scheduling advertising restart in ${delayMs}ms (attempt $advertiseRetryCount, reason=$reason)")
        connectionScope.launch {
            delay(delayMs)
            if (isActive && isServerRoleEnabled()) {
                stopAdvertising()
                delay(100)
                startAdvertising()
            }
        }
    }

    /**
     * Restart advertising (for power mode changes)
     */
    fun restartAdvertising() {
        // Respect debug setting
        val enabled = isServerRoleEnabled()
        if (!isActive || !enabled) {
            stopAdvertising()
            return
        }

        connectionScope.launch {
            stopAdvertising()
            delay(100)
            startAdvertising()
        }
    }
}
