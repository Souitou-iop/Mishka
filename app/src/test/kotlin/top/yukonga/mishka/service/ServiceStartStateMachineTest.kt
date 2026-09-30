package top.yukonga.mishka.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceStartStateMachineTest {

    @Test
    fun duplicateFreshRequestIsIgnoredUntilCompletion() {
        val machine = ServiceStartStateMachine()
        val first = machine.request(attachOnly = false) ?: error("first request rejected")

        assertNull(machine.request(attachOnly = false))

        machine.complete(first.token)
        assertNotNull(machine.request(attachOnly = false))
    }

    @Test
    fun freshRequestSupersedesAttachOnlyRequest() {
        val machine = ServiceStartStateMachine()
        val attach = machine.request(attachOnly = true) ?: error("attach request rejected")
        val fresh = machine.request(attachOnly = false) ?: error("fresh request rejected")

        assertEquals(attach.token, fresh.superseded)
        assertNull(machine.request(attachOnly = true))
    }

    @Test
    fun completingSupersededRequestDoesNotReleaseFreshRequest() {
        val machine = ServiceStartStateMachine()
        val attach = machine.request(attachOnly = true) ?: error("attach request rejected")
        val fresh = machine.request(attachOnly = false) ?: error("fresh request rejected")

        machine.complete(attach.token)

        assertNull(machine.request(attachOnly = false))
        machine.complete(fresh.token)
        assertNotNull(machine.request(attachOnly = false))
    }
}
