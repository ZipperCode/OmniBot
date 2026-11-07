package cn.com.omnimind.bot.agent.runtime

import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class AcpTurnLifecycleRegressionTest {
    @Test
    fun `success error and cancellation release same session for the next message`() {
        val owner = AcpTurnOwnershipRegistry()
        listOf("end_turn", "error", "cancelled").forEachIndexed { index, status ->
            val turn = "turn-$index"
            assertTrue(owner.reserve("session", turn, "request-$index") is AcpTurnReservation.Started)
            assertNotNull(owner.finish("session", turn, status))
            assertFalse(owner.hasActiveTurns())
        }
        assertTrue(owner.reserve("session", "next", "next-request") is AcpTurnReservation.Started)
    }

    @Test
    fun `duplicate sends and late completion cannot replay or release a newer turn`() {
        val owner = AcpTurnOwnershipRegistry()
        owner.reserve("session", "first", "request-1")
        val duplicate = owner.reserve("session", "incorrect-new-id", "request-1")
        assertEquals("first", (duplicate as AcpTurnReservation.InFlight).record.turnId)
        assertTrue(owner.reserve("session", "second", "request-2") is AcpTurnReservation.Busy)
        owner.finish("session", "first", "end_turn")
        assertTrue(owner.reserve("session", "second", "request-2") is AcpTurnReservation.Started)
        assertNull(owner.finish("session", "first", "error"))
        assertEquals("second", owner.activeTurnId("session"))
        assertTrue(owner.reserve("session", "third", "request-1") is AcpTurnReservation.Completed)
    }

    @Test
    fun `disconnect releases only its transport scope and retains duplicate protection`() {
        val store = AcpTurnOwnershipStore()
        val local = AcpTurnOwnershipRegistry(store, "local")
        val remote = AcpTurnOwnershipRegistry(store, "remote")
        local.reserve("same-id", "local-turn", "request")
        remote.reserve("same-id", "remote-turn", "request")
        assertEquals(1, local.finishAll("error").size)
        assertEquals("remote-turn", remote.activeTurnId("same-id"))
        assertTrue(local.reserve("same-id", "retry", "request") is AcpTurnReservation.Completed)
        assertTrue(local.reserve("same-id", "new-turn", "new-request") is AcpTurnReservation.Started)
    }

    @Test
    fun `cancellation before prompt prevents sending but after admission delegates to ACP`() {
        val preparation = Job()
        val before = AcpPromptExecution(preparation)
        assertFalse(before.requestCancellation())
        assertTrue(preparation.isCancelled)
        val prompt = Job()
        before.attachPromptJob(prompt)
        assertTrue(prompt.isCancelled)
        assertFalse(before.tryStartPrompt())

        val activePreparation = Job()
        val activePrompt = Job()
        val after = AcpPromptExecution(activePreparation)
        after.attachPromptJob(activePrompt)
        assertTrue(after.tryStartPrompt())
        assertTrue(after.requestCancellation())
        assertFalse(activePrompt.isCancelled)
        assertFalse(activePreparation.isCancelled)
        activePrompt.cancel()
        activePreparation.cancel()
    }

    @Test
    fun `transport disconnect distinguishes transport teardown from user cancellation`() {
        val execution = AcpPromptExecution(null)
        assertFalse(execution.isTransportCancelled())
        assertFalse(execution.isUserCancellationRequested())

        execution.cancelForTransport(kotlinx.coroutines.CancellationException("ACP runtime disconnected"))
        assertTrue(execution.isTransportCancelled())
        assertFalse(execution.isUserCancellationRequested())

        val userCancelExecution = AcpPromptExecution(null)
        userCancelExecution.requestCancellation()
        assertFalse(userCancelExecution.isTransportCancelled())
        assertTrue(userCancelExecution.isUserCancellationRequested())
    }


    @Test
    fun `turns in different sessions do not block each other`() {
        val store = AcpTurnOwnershipStore()
        val owner = AcpTurnOwnershipRegistry(store, "default")
        val reservation1 = owner.reserve("session-A", "turn-A", "req-A")
        val reservation2 = owner.reserve("session-B", "turn-B", "req-B")
        assertTrue(reservation1 is AcpTurnReservation.Started)
        assertTrue(reservation2 is AcpTurnReservation.Started)
        assertEquals("turn-A", owner.activeTurnId("session-A"))
        assertEquals("turn-B", owner.activeTurnId("session-B"))
    }
}
