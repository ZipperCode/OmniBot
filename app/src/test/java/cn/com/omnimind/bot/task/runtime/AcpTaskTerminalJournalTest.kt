package cn.com.omnimind.bot.task.runtime

import android.app.Application
import android.content.Context
import android.os.UserManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class AcpTaskTerminalJournalTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("acp_task_terminal_journal", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("acp_task_terminal_journal", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `identities containing separators cannot collide across sessions`() {
        assertTrue(AcpTaskTerminalJournal.recordAdmission(context, "a:b", "c", "d", "r1"))
        assertTrue(AcpTaskTerminalJournal.recordAdmission(context, "a", "b:c", "d", "r2"))
        assertTrue(AcpTaskTerminalJournal.recordTerminal(context, "a:b", "c", "d", "completed"))
        val completed = AcpTaskTerminalJournal.find(context, "a:b", "c", "d")
        assertTrue(completed is AcpTerminalLookupResult.TerminalFound)
        assertTrue(AcpTaskTerminalJournal.find(context, "a", "b:c", "d") is AcpTerminalLookupResult.AdmittedInFlight)
        assertTrue(AcpTaskTerminalJournal.findByRequestId(context, "a", "b:c", "r1") is AcpTerminalLookupResult.Unknown)
    }

    @Test
    fun `admission replay cannot erase or replace the first official terminal`() {
        assertTrue(AcpTaskTerminalJournal.recordAdmission(context, "a", "s", "t", "r"))
        assertTrue(AcpTaskTerminalJournal.recordTerminal(context, "a", "s", "t", "error", "失败"))
        assertTrue(AcpTaskTerminalJournal.recordAdmission(context, "a", "s", "t", "r"))
        assertFalse(AcpTaskTerminalJournal.recordTerminal(context, "a", "s", "t", "completed"))
        val result = AcpTaskTerminalJournal.findByRequestId(context, "a", "s", "r") as AcpTerminalLookupResult.TerminalFound
        assertEquals("error", result.entry.terminalStatus)
        assertEquals("失败", result.entry.terminalError)
    }

    @Test
    fun `one host request cannot silently bind to two turns`() {
        assertTrue(AcpTaskTerminalJournal.recordAdmission(context, "a", "s", "t1", "r"))
        assertFalse(AcpTaskTerminalJournal.recordAdmission(context, "a", "s", "t2", "r"))
        val result = AcpTaskTerminalJournal.findByRequestId(context, "a", "s", "r") as AcpTerminalLookupResult.AdmittedInFlight
        assertEquals("t1", result.entry.turnId)
    }

    @Test
    fun `corrupt durable evidence is preserved and never becomes an empty successful journal`() {
        val preferences = context.getSharedPreferences("acp_task_terminal_journal", Context.MODE_PRIVATE)
        preferences.edit().putString("journal_entries", "broken-json").commit()
        assertTrue(AcpTaskTerminalJournal.find(context, "a", "s", "t") is AcpTerminalLookupResult.Unknown)
        assertFalse(AcpTaskTerminalJournal.recordAdmission(context, "a", "s", "t", "r"))
        assertEquals("broken-json", preferences.getString("journal_entries", null))
    }

    @Test
    fun `locked credential storage never grants or persists a terminal`() {
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        assertFalse(AcpTaskTerminalJournal.recordAdmission(context, "a", "s", "t", "r"))
        assertFalse(AcpTaskTerminalJournal.recordTerminal(context, "a", "s", "t", "completed"))
        assertTrue(AcpTaskTerminalJournal.find(context, "a", "s", "t") is AcpTerminalLookupResult.Unknown)
    }
}
