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

    @Test
    fun `withDisplayLeases locks multiple displays in ascending order and releases in reverse order`() = runBlocking {
        val display1 = 1
        val display3 = 3
        val owner = "owner-multi"

        DisplayInputCoordinator.withDisplayLeases(listOf(display3, display1), owner) {
            assertTrue(DisplayInputCoordinator.isLeaseHeld(display1))
            assertTrue(DisplayInputCoordinator.isLeaseHeld(display3))
            assertEquals(owner, DisplayInputCoordinator.currentLease(display1)?.ownerId)
            assertEquals(owner, DisplayInputCoordinator.currentLease(display3)?.ownerId)
        }

        assertFalse(DisplayInputCoordinator.isLeaseHeld(display1))
        assertFalse(DisplayInputCoordinator.isLeaseHeld(display3))
    }

    @Test
    fun `withDisplayLeases fails fast with display_busy when any display is held by another owner and rolls back immediately`() = runBlocking {
        val display1 = 1
        val display3 = 3
        val ownerA = "owner-A"
        val ownerB = "owner-B"

        // Owner A holds display 3
        DisplayInputCoordinator.acquireLease(display3, ownerA)
        assertTrue(DisplayInputCoordinator.isLeaseHeld(display3))

        // Owner B attempts dual lease on [1, 3] -> must fail fast with display_busy
        var failedWithBusy = false
        try {
            DisplayInputCoordinator.withDisplayLeases(listOf(display1, display3), ownerB) {
                // should not reach here
            }
        } catch (e: IllegalStateException) {
            if (e.message == "display_busy") {
                failedWithBusy = true
            }
        }

        assertTrue("Must fail fast with display_busy", failedWithBusy)
        // Display 1 must have been rolled back and NOT held by ownerB
        assertFalse("Display 1 must be released immediately on rollback", DisplayInputCoordinator.isLeaseHeld(display1))
        // Display 3 remains held by ownerA
        assertTrue(DisplayInputCoordinator.isLeaseHeld(display3))
        assertEquals(ownerA, DisplayInputCoordinator.currentLease(display3)?.ownerId)

        DisplayInputCoordinator.releaseLease(display3, ownerA)
    }

    @Test
    fun `withDisplayLeases preserves reentrancy for displays already held by same owner without releasing outer lock`() = runBlocking {
        val display1 = 1
        val display2 = 2
        val owner = "owner-reentrant"

        DisplayInputCoordinator.withDisplayLease(display1, owner) {
            assertTrue(DisplayInputCoordinator.isLeaseHeld(display1))

            DisplayInputCoordinator.withDisplayLeases(listOf(display1, display2), owner) {
                assertTrue(DisplayInputCoordinator.isLeaseHeld(display1))
                assertTrue(DisplayInputCoordinator.isLeaseHeld(display2))
            }

            // After withDisplayLeases finishes, display2 is released, but display1 remains held by outer lease
            assertFalse(DisplayInputCoordinator.isLeaseHeld(display2))
            assertTrue(DisplayInputCoordinator.isLeaseHeld(display1))
        }

        assertFalse(DisplayInputCoordinator.isLeaseHeld(display1))
        assertFalse(DisplayInputCoordinator.isLeaseHeld(display2))
    }

    @Test
    fun `withDisplayLeases deduplicates identical display IDs`() = runBlocking {
        val display = 5
        val owner = "owner-dedup"

        DisplayInputCoordinator.withDisplayLeases(listOf(display, display, display), owner) {
            assertTrue(DisplayInputCoordinator.isLeaseHeld(display))
            assertEquals(owner, DisplayInputCoordinator.currentLease(display)?.ownerId)
        }

        assertFalse(DisplayInputCoordinator.isLeaseHeld(display))
    }

    @Test
    fun `concurrent contention on overlapping displays fails fast without orphan locks`() = runBlocking {
        val displayA = 10
        val displayB = 20
        val displayC = 30

        val owner1 = "worker-1"
        val owner2 = "worker-2"
        val owner3 = "worker-3"

        // Worker 1 acquires dual lease on [displayA, displayB]
        val worker1Started = CompletableDeferred<Unit>()
        val worker1Release = CompletableDeferred<Unit>()
        val worker1Done = CompletableDeferred<Unit>()

        val job1 = launch(Dispatchers.Default) {
            DisplayInputCoordinator.withDisplayLeases(listOf(displayA, displayB), owner1) {
                worker1Started.complete(Unit)
                worker1Release.await()
            }
            worker1Done.complete(Unit)
        }

        worker1Started.await()

        // Contender 2 attempts [displayB, displayC] -> must fail fast with display_busy because displayB is held
        var worker2Busy = false
        try {
            DisplayInputCoordinator.withDisplayLeases(listOf(displayB, displayC), owner2) {
                // should not reach here
            }
        } catch (e: IllegalStateException) {
            if (e.message == "display_busy") worker2Busy = true
        }
        assertTrue("Worker 2 must fail fast with display_busy", worker2Busy)
        // Ensure displayC was rolled back and is NOT held by worker2
        assertFalse(DisplayInputCoordinator.isLeaseHeld(displayC))

        // Contender 3 attempts [displayA, displayC] -> must fail fast because displayA is held
        var worker3Busy = false
        try {
            DisplayInputCoordinator.withDisplayLeases(listOf(displayA, displayC), owner3) {
                // should not reach here
            }
        } catch (e: IllegalStateException) {
            if (e.message == "display_busy") worker3Busy = true
        }
        assertTrue("Worker 3 must fail fast with display_busy", worker3Busy)
        assertFalse(DisplayInputCoordinator.isLeaseHeld(displayC))

        // Release worker 1
        worker1Release.complete(Unit)
        worker1Done.await()
        job1.join()

        // Now that worker 1 released, worker 2 can acquire all three displays without deadlock
        var worker2AcquiredAll = false
        DisplayInputCoordinator.withDisplayLeases(listOf(displayA, displayB, displayC), owner2) {
            worker2AcquiredAll = true
            assertTrue(DisplayInputCoordinator.isLeaseHeld(displayA))
            assertTrue(DisplayInputCoordinator.isLeaseHeld(displayB))
            assertTrue(DisplayInputCoordinator.isLeaseHeld(displayC))
        }
        assertTrue("Worker 2 successfully acquired all displays after release", worker2AcquiredAll)
        assertFalse(DisplayInputCoordinator.isLeaseHeld(displayA))
        assertFalse(DisplayInputCoordinator.isLeaseHeld(displayB))
        assertFalse(DisplayInputCoordinator.isLeaseHeld(displayC))
    }
}
