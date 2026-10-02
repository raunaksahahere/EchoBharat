package com.echobharat.mesh

import android.content.ContextWrapper
import com.echobharat.schema.EchoBharatMessage
import com.echobharat.schema.MessageType
import org.junit.Assert.*
import org.junit.Test
import sun.misc.Unsafe

class SosManagerTest {
    // onReceived only uses local state. Allocate the unused radio collaborator without
    // starting Android services, reading identity storage, or requiring a device.
    private fun manager(): SosManager {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").let {
            it.isAccessible = true
            it.get(null) as Unsafe
        }
        val mesh = unsafe.allocateInstance(EchoBharatMeshManager::class.java) as EchoBharatMeshManager
        return SosManager(ContextWrapper(null), mesh)
    }

    private fun sos(sender: String = "origin") = EchoBharatMessage(
        msgId = "emergency", type = MessageType.SOS, srcLang = "en", text = "Help",
        senderName = sender, senderId = sender, deviceModel = "Test",
        expiresAt = System.currentTimeMillis() + 60_000
    )

    private fun resolved(sender: String) = sos(sender).copy(
        msgId = "resolve-$sender", type = MessageType.SOS_RESOLVED, refMsgId = "emergency"
    )

    @Test
    fun `foreign resolve cannot cancel an active distress call`() {
        val manager = manager()
        manager.onReceived(sos())
        manager.onReceived(resolved("attacker"))
        assertEquals(1, manager.announcements.value.size)
        manager.onReceived(resolved("origin"))
        assertTrue(manager.announcements.value.isEmpty())
    }

    @Test
    fun `foreign resolve cannot erase a valid tombstone before delayed SOS`() {
        val manager = manager()
        manager.onReceived(resolved("origin"))
        manager.onReceived(resolved("attacker"))
        manager.onReceived(sos())
        assertTrue("resolved SOS must not resurrect", manager.announcements.value.isEmpty())
    }

    @Test
    fun `foreign resolve after cancellation cannot resurrect the distress call`() {
        val manager = manager()
        manager.onReceived(sos())
        manager.onReceived(resolved("origin"))
        manager.onReceived(resolved("attacker"))
        manager.onReceived(sos())
        assertTrue("resolved SOS must not resurrect", manager.announcements.value.isEmpty())
    }

    @Test
    fun `out of order foreign resolve does not silence another sender`() {
        val manager = manager()
        manager.onReceived(resolved("attacker"))
        manager.onReceived(sos())
        assertEquals("origin", manager.announcements.value.single().message.senderId)
    }
}
