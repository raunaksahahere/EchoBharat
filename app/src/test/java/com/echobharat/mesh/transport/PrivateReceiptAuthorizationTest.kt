package com.echobharat.mesh.transport

import com.echobharat.mesh.model.BitchatMessage
import com.echobharat.mesh.model.DeliveryStatus
import com.echobharat.services.AppStateStore
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import sun.misc.Unsafe
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

class PrivateReceiptAuthorizationTest {
    private fun fixture(): Pair<MessageHandlerDelegate, MutableMap<String, Any>> {
        // Exercise the real receipt callbacks without constructing a Bluetooth stack or
        // loading persisted identities. Only the fields used by these callbacks are needed.
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").let {
            it.isAccessible = true
            it.get(null) as Unsafe
        }
        val service = unsafe.allocateInstance(BluetoothMeshService::class.java) as BluetoothMeshService
        val pendingClass = BluetoothMeshService::class.java.declaredClasses.single { it.simpleName == "AwaitingAck" }
        val pending = pendingClass.getDeclaredConstructor(String::class.java, String::class.java)
            .apply { isAccessible = true }.newInstance("recipient", "private text")
        val map = ConcurrentHashMap<String, Any>().apply { put("message", pending) }
        BluetoothMeshService::class.java.getDeclaredField("awaitingAck")
            .apply { isAccessible = true }.set(service, map)
        BluetoothMeshService::class.java.getDeclaredField("receiptRecipients")
            .apply { isAccessible = true }.set(service, mutableMapOf("message" to "recipient"))
        // Locate the actual anonymous delegate, not a copy of its authorization logic.
        val callbackClass = (1..10).mapNotNull {
            runCatching { Class.forName("${BluetoothMeshService::class.java.name}\$setupDelegates\$$it") }.getOrNull()
        }.single { MessageHandlerDelegate::class.java.isAssignableFrom(it) }
        val callback = callbackClass.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(service) as MessageHandlerDelegate
        AppStateStore.addPrivateMessage("recipient", BitchatMessage(
            id = "message", sender = "Me", content = "private text", timestamp = Date(),
            isRelay = false, isPrivate = true, senderPeerID = "me"
        ))
        return callback to map
    }

    @After fun cleanUp() { AppStateStore.clear() }

    @Test
    fun `authenticated non-recipient cannot stop retransmission with a delivery ack`() {
        val (callback, pending) = fixture()
        callback.onDeliveryAckReceived("message", "attacker")
        assertTrue("foreign ack removed the real recipient's retry", pending.containsKey("message"))
        assertFalse(AppStateStore.privateMessages.value["recipient"]!!.single().deliveryStatus is DeliveryStatus.Delivered)
    }

    @Test
    fun `authenticated non-recipient cannot forge read status`() {
        val (callback, _) = fixture()
        callback.onReadReceiptReceived("message", "attacker")
        assertFalse(AppStateStore.privateMessages.value["recipient"]!!.single().deliveryStatus is DeliveryStatus.Read)
    }

    @Test
    fun `read receipts remain authorized after delivery removed the retry entry`() {
        val (callback, _) = fixture()
        callback.onDeliveryAckReceived("message", "recipient")
        callback.onReadReceiptReceived("message", "attacker")
        assertTrue(AppStateStore.privateMessages.value["recipient"]!!.single().deliveryStatus is DeliveryStatus.Delivered)
        callback.onReadReceiptReceived("message", "recipient")
        assertTrue(AppStateStore.privateMessages.value["recipient"]!!.single().deliveryStatus is DeliveryStatus.Read)
    }

    @Test
    fun `unknown message receipt is ignored`() {
        val (callback, pending) = fixture()
        callback.onDeliveryAckReceived("unknown", "recipient")
        assertTrue(pending.containsKey("message"))
    }

    @Test
    fun `intended recipient can acknowledge delivery`() {
        val (callback, pending) = fixture()
        callback.onDeliveryAckReceived("message", "recipient")
        assertTrue(pending.isEmpty())
        assertTrue(AppStateStore.privateMessages.value["recipient"]!!.single().deliveryStatus is DeliveryStatus.Delivered)
    }
}
