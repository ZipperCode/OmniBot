package cn.com.omnimind.bot.ui.channel

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FileSaveChannelOfficePreviewTest {
    @Test
    fun `concurrent inline previews parse serially away from the caller thread`() = runBlocking {
        val caller = Thread.currentThread()
        val started = CountDownLatch(1)
        val secondStarted = CountDownLatch(2)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val channel = FileSaveChannel { path, _ ->
            assertNotSame(caller, Thread.currentThread())
            val count = active.incrementAndGet()
            maximum.accumulateAndGet(count, ::maxOf)
            started.countDown()
            secondStarted.countDown()
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS))
                mapOf("path" to path)
            } finally {
                active.decrementAndGet()
            }
        }
        val previews = (1..4).map { index ->
            async(start = CoroutineStart.UNDISPATCHED) {
                channel.readOfficePreview("doc-$index", "office_word")
            }
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertFalse("A second document must not allocate its DOM while the first is held", secondStarted.await(150, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
        }
        assertEquals((1..4).map { mapOf("path" to "doc-$it") }, previews.awaitAll())
        assertEquals(1, maximum.get())
        assertEquals(0, active.get())
    }

    @Test
    fun `cancelling a queued preview neither parses it nor blocks the next card`() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val parsed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val channel = FileSaveChannel { path, _ ->
            parsed.add(path)
            if (path == "first") {
                started.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
            mapOf("path" to path)
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            channel.readOfficePreview("first", "office_word")
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
                channel.readOfficePreview("cancelled", "office_word")
            }
            cancelled.cancelAndJoin()
            val last = async(start = CoroutineStart.UNDISPATCHED) {
                channel.readOfficePreview("last", "office_word")
            }
            release.countDown()
            assertEquals(mapOf("path" to "first"), first.await())
            assertEquals(mapOf("path" to "last"), last.await())
            assertEquals(listOf("first", "last"), parsed)
        } finally {
            release.countDown()
        }
    }
}
