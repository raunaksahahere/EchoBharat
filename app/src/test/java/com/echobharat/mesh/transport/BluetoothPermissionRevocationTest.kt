package com.echobharat.mesh.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothPermissionRevocationTest {
    private val address = "02:00:00:00:00:01"

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private class Platform {
        val context = mock<Context>()
        val manager = mock<BluetoothManager>()
        val adapter = mock<BluetoothAdapter>()
        val scanner = mock<BluetoothLeScanner>()
        val advertiser = mock<BluetoothLeAdvertiser>()
        val permissions = mock<BluetoothPermissionManager>()
        val power = mock<PowerManager>()

        init {
            whenever(context.getSystemService(Context.BLUETOOTH_SERVICE)).thenReturn(manager)
            whenever(manager.adapter).thenReturn(adapter)
            whenever(adapter.isEnabled).thenReturn(true)
            whenever(adapter.bluetoothLeScanner).thenReturn(scanner)
            whenever(adapter.bluetoothLeAdvertiser).thenReturn(advertiser)
            whenever(permissions.hasBluetoothPermissions()).thenReturn(true)
        }
    }

    @Test fun `cleanup still closes when disconnect permission was revoked`() {
        val gatt = mock<BluetoothGatt>()
        doThrow(SecurityException("revoked")).whenever(gatt).disconnect()
        BluetoothGattCleanup.close(gatt)
        verify(gatt).disconnect()
        verify(gatt).close()
    }

    @Test fun `cleanup tolerates revoked close permission and null handles`() {
        val gatt = mock<BluetoothGatt>()
        doThrow(SecurityException("revoked")).whenever(gatt).close()
        BluetoothGattCleanup.close(gatt)
        BluetoothGattCleanup.close(null)
        verify(gatt).close()
    }

    @Test fun `tracker stop closes snapshot and clears routes even when permission is revoked`() = runTest {
        val tracker = BluetoothConnectionTracker(this, mock())
        val device = mock<BluetoothDevice>()
        whenever(device.address).thenReturn(address)
        val gatt = mock<BluetoothGatt>()
        doThrow(SecurityException("revoked")).whenever(gatt).disconnect()
        doThrow(SecurityException("revoked")).whenever(gatt).close()
        tracker.addDeviceConnection(address, BluetoothConnectionTracker.DeviceConnection(
            device, gatt, isClient = true, linkID = "link"
        ))
        tracker.addSubscribedDevice(device)
        tracker.observePeerIfCurrent(address, "link", "peer")
        tracker.stop()
        verify(gatt).close()
        assertEquals(0, tracker.getConnectedDeviceCount())
        assertTrue(tracker.getSubscribedDevices().isEmpty())
        assertTrue(tracker.addressPeerMap.isEmpty())
    }

    @Test fun `scan stop clears state when stopScan throws after revocation`() = runTest {
        val p = Platform()
        val client = BluetoothGattClientManager(p.context, this, BluetoothConnectionTracker(this, p.power),
            p.permissions, p.power, null)
        val callback = mock<ScanCallback>()
        setField(client, "isActive", true)
        setField(client, "isCurrentlyScanning", true)
        setField(client, "scanCallback", callback)
        whenever(p.permissions.hasBluetoothPermissions()).thenReturn(false)
        doThrow(SecurityException("revoked")).whenever(p.scanner).stopScan(callback)
        client.stop()
        verify(p.scanner).stopScan(callback)
        assertEquals(false, field(client, "isCurrentlyScanning"))
        assertEquals(false, field(client, "isActive"))
        assertNull(field(client, "scanCallback"))
    }

    @Test fun `delayed MTU request rechecks permission and releases pending GATT`() = runTest {
        val p = Platform()
        val tracker = BluetoothConnectionTracker(this, p.power)
        val client = BluetoothGattClientManager(p.context, this, tracker, p.permissions, p.power, null)
        setField(client, "isActive", true)
        val device = mock<BluetoothDevice>()
        val gatt = mock<BluetoothGatt>()
        whenever(device.address).thenReturn(address)
        whenever(gatt.device).thenReturn(device)
        whenever(p.adapter.getRemoteDevice(address)).thenReturn(device)
        val callback = argumentCaptor<BluetoothGattCallback>()
        whenever(device.connectGatt(eq(p.context), eq(false), callback.capture(), eq(BluetoothDevice.TRANSPORT_LE)))
            .thenReturn(gatt)
        assertTrue(client.connectToAddress(address))
        callback.firstValue.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        runCurrent()
        whenever(p.permissions.hasBluetoothPermissions()).thenReturn(false)
        doThrow(SecurityException("revoked")).whenever(gatt).disconnect()
        advanceTimeBy(201)
        runCurrent()
        verify(gatt, never()).requestMtu(any())
        verify(gatt).disconnect()
        verify(gatt).close()
        assertTrue((field(client, "clientGatts") as Map<*, *>).isEmpty())
        assertEquals(0, tracker.getConnectedDeviceCount())
        client.stop()
    }

    @Test fun `server stop clears advertising and routes even when all platform cleanup is denied`() = runTest {
        val p = Platform()
        val tracker = BluetoothConnectionTracker(this, p.power)
        val delegate = mock<BluetoothConnectionManagerDelegate>()
        val server = BluetoothGattServerManager(p.context, this, tracker, p.permissions, p.power, delegate, "peer")
        val gattServer = mock<BluetoothGattServer>()
        val device = mock<BluetoothDevice>()
        val callback = mock<AdvertiseCallback>()
        whenever(device.address).thenReturn(address)
        setField(server, "isActive", true)
        setField(server, "gattServer", gattServer)
        setField(server, "advertiseCallback", callback)
        tracker.addDeviceConnection(address, BluetoothConnectionTracker.DeviceConnection(device, linkID = "link"))
        tracker.observePeerIfCurrent(address, "link", "peer")
        whenever(p.permissions.hasBluetoothPermissions()).thenReturn(false)
        doThrow(SecurityException("revoked")).whenever(p.advertiser).stopAdvertising(callback)
        doThrow(SecurityException("revoked")).whenever(gattServer).cancelConnection(device)
        doThrow(SecurityException("revoked")).whenever(gattServer).close()
        server.stop()
        verify(gattServer).close()
        verify(delegate).onDeviceDisconnected(device, "link", "peer")
        assertNull(server.getGattServer())
        assertNull(field(server, "advertiseCallback"))
        assertEquals(false, field(server, "isActive"))
        assertEquals(0, tracker.getConnectedDeviceCount())
        assertTrue(tracker.addressPeerMap.isEmpty())
    }
}
