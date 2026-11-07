package cn.com.omnimind.androidgui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DisplayInputCoordinatorTest {

    @Before
    @After
    fun cleanup() {
        DisplayInputCoordinator.resetForTesting()
    }

    @Test
    fun `exclusive multi-step lease queues second task on same display`() = runBlocking {
        val displayId = 0
        val owner1 = "run-1"
        val owner2 = "run-2"

        val lease1 = DisplayInputCoordinator.acquireLease(displayId, owner1)
        assertEquals(displayId, lease1.displayId)
        assertEquals(owner1, lease1.ownerId)
        assertTrue(DisplayInputCoordinator.isLeaseHeld(displayId))

        // Attempting to acquire lease on same display by a different owner times out
        val timeoutResult = runCatching {
            DisplayInputCoordinator.acquireLease(displayId, owner2, timeoutMs = 100L)
        }
        assertTrue(timeoutResult.isFailure)

        // Once released, owner2 can acquire the lease
        DisplayInputCoordinator.releaseLease(displayId, owner1)
        assertFalse(DisplayInputCoordinator.isLeaseHeld(displayId))

        val lease2 = DisplayInputCoordinator.acquireLease(displayId, owner2, timeoutMs = 500L)
        assertEquals(owner2, lease2.ownerId)
        DisplayInputCoordinator.releaseLease(displayId, owner2)
    }

    @Test
    fun `independent displays run concurrently without blocking`() = runBlocking {
        val display0 = 0
        val display1 = 1
        val owner0 = "task-display-0"
        val owner1 = "task-display-1"

        // Display 0 lease acquired
        DisplayInputCoordinator.acquireLease(display0, owner0)
        assertTrue(DisplayInputCoordinator.isLeaseHeld(display0))

        // Display 1 lease acquired immediately without being blocked by Display 0
        val lease1 = DisplayInputCoordinator.acquireLease(display1, owner1, timeoutMs = 100L)
        assertEquals(display1, lease1.displayId)
        assertEquals(owner1, lease1.ownerId)
        assertTrue(DisplayInputCoordinator.isLeaseHeld(display1))

        DisplayInputCoordinator.releaseLease(display0, owner0)
        DisplayInputCoordinator.releaseLease(display1, owner1)
    }

    @Test
    fun `reentrant locks with same ownerId execute without deadlock`() = runBlocking {
        val displayId = 0
        val owner = "session-1:run-1"

        var nestedActionExecuted = false
        DisplayInputCoordinator.withDisplayLease(displayId, owner) {
            // Nested action reusing the same ownerId proceeds reentrantly
            DisplayInputCoordinator.withDisplayLock(displayId, ownerId = owner) {
                nestedActionExecuted = true
            }
        }

        assertTrue(nestedActionExecuted)
        assertFalse(DisplayInputCoordinator.isLeaseHeld(displayId))
    }

    @Test
    fun `cancellation by ownerId cancels only target owner without affecting others`() = runBlocking {
        val display0 = 0
        val ownerA = "owner-A"
        val jobA = Job()

        val display1 = 1
        val ownerB = "owner-B"
        val jobB = Job()

        DisplayInputCoordinator.registerActiveJob(display0, ownerA, jobA)
        DisplayInputCoordinator.registerActiveJob(display1, ownerB, jobB)

        // Canceling ownerA on display0
        DisplayInputCoordinator.cancelActiveOperation(display0, ownerId = ownerA)
        assertTrue(jobA.isCancelled)
        assertFalse(jobB.isCancelled)

        // Attempting to cancel with non-matching owner does not cancel jobB
        DisplayInputCoordinator.cancelActiveOperation(display1, ownerId = "unrelated-owner")
        assertFalse(jobB.isCancelled)

        // Canceling with matching owner cancels jobB
        DisplayInputCoordinator.cancelActiveOperation(display1, ownerId = ownerB)
        assertTrue(jobB.isCancelled)
    }

    @Test
    fun `releasing short input lease does not destroy session virtual display`() = runBlocking {
        val displayId = 101
        val sessionOwner = "acp-session-1"
        val shortLeaseOwner = "acp-session-1:turn-1:tool-call-1"

        DisplayInputCoordinator.registerVirtualDisplayOwnership(displayId, sessionOwner)
        assertEquals(sessionOwner, DisplayInputCoordinator.getVirtualDisplayOwner(displayId))

        // Acquiring and releasing a short input lease must NOT destroy the session virtual display
        DisplayInputCoordinator.withDisplayLease(displayId, shortLeaseOwner) {
            assertTrue(DisplayInputCoordinator.isLeaseHeld(displayId))
        }
        assertFalse(DisplayInputCoordinator.isLeaseHeld(displayId))

        // Session virtual display remains registered and alive
        assertEquals(sessionOwner, DisplayInputCoordinator.getVirtualDisplayOwner(displayId))

        // Cross-session attempt to release must fail
        val crossRelease = DisplayInputCoordinator.releaseVirtualDisplayOwnership(displayId, "acp-session-2")
        assertFalse(crossRelease)
        assertEquals(sessionOwner, DisplayInputCoordinator.getVirtualDisplayOwner(displayId))

        // Explicit session release succeeds
        val validRelease = DisplayInputCoordinator.releaseVirtualDisplayOwnership(displayId, sessionOwner)
        assertTrue(validRelease)
        assertEquals(null, DisplayInputCoordinator.getVirtualDisplayOwner(displayId))
    }
}
