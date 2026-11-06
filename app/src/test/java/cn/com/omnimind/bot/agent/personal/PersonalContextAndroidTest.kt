package cn.com.omnimind.bot.agent.personal

import android.Manifest
import android.app.Application
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.CalendarContract
import android.provider.ContactsContract
import cn.com.omnimind.bot.agent.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class PersonalContextAndroidTest {
    private lateinit var context: TestContext
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Before fun setup() { context = TestContext(RuntimeEnvironment.getApplication()) }

    @Test fun `read-only calendar authorization permits reads and rejects writes without prompting`() = runBlocking {
        context.permissions += Manifest.permission.READ_CALENDAR
        val provider = CalendarProvider()
        ShadowContentResolver.registerProviderInternal("com.android.calendar", provider)
        assertTrue(AgentCalendarToolService(context).hasCalendarPermissions())
        assertFalse(AgentCalendarToolService(context).hasCalendarPermissions(write = true))
        val service = PersonalContextService(context)
        val read = service.execute("calendar_event_list", json("""{"startAt":"2026-10-13T00:00:00Z","endAt":"2026-10-14T00:00:00Z","limit":1}"""))
        assertEquals(true, read["success"])
        assertEquals(true, read["hasMore"])
        val denied = service.execute("calendar_event_delete", json("""{"eventId":"1"}"""))
        assertEquals("permission_required", denied["code"])
        assertEquals("calendar_write", denied["capabilityId"])
        assertEquals(0, provider.writes)
        context.permissions.clear()
        assertEquals("permission_required", service.execute("calendar_event_list", json("{}"))["code"])
    }

    @Test fun `calendar null cursor is unavailable not an empty calendar`() = runBlocking {
        context.permissions += Manifest.permission.READ_CALENDAR
        val provider = CalendarProvider().apply { returnNull = true }
        ShadowContentResolver.registerProviderInternal("com.android.calendar", provider)
        val result = PersonalContextService(context).execute("calendar_event_list", json("{}"))
        assertEquals(false, result["success"])
        assertFalse(result.containsKey("items"))
    }

    @Test fun `all-day calendar events use UTC dates and exclusive end`() {
        val provider = CalendarProvider()
        ShadowContentResolver.registerProviderInternal("com.android.calendar", provider)
        AgentCalendarToolService(context).createEvent(CalendarEventCreateRequest("Test", "2026-10-13T00:00:00+08:00", "2026-10-14T00:00:00+08:00", "1", null, null, "Asia/Shanghai", true, emptyList()))
        assertEquals(Instant.parse("2026-10-13T00:00:00Z").toEpochMilli(), provider.inserted!!.getAsLong(CalendarContract.Events.DTSTART))
        assertEquals(Instant.parse("2026-10-14T00:00:00Z").toEpochMilli(), provider.inserted!!.getAsLong(CalendarContract.Events.DTEND))
        assertEquals("UTC", provider.inserted!!.getAsString(CalendarContract.Events.EVENT_TIMEZONE))
    }

    @Test fun `media distinguishes selected photos from full library`() {
        context.permissions += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        assertEquals("partial", PersonalContextAccess(context).snapshot("media_visual").status)
        context.permissions += listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        assertEquals("granted", PersonalContextAccess(context).snapshot("media_visual").status)
        context.permissions.clear()
        assertEquals("denied", PersonalContextAccess(context).snapshot("media_visual").status)
    }

    @Test fun `restricted SMS is different from normal denial`() {
        assertEquals("restricted", PersonalContextAccess(context).snapshot("sms").status)
        context.permissions += Manifest.permission.READ_SMS
        assertEquals("granted", PersonalContextAccess(context).snapshot("sms").status)
    }

    @Test fun `approximate foreground location does not imply background access`() {
        context.permissions += Manifest.permission.ACCESS_COARSE_LOCATION
        assertEquals(listOf("approximate"), PersonalContextAccess(context).snapshot("location").scope)
        assertEquals("denied", PersonalContextAccess(context).snapshot("background_location").status)
    }

    @Test fun `notification updates deduplicate and older records expire on reads`() {
        val history = PersonalNotificationHistory.get(context)
        history.clear(0)
        val now = System.currentTimeMillis()
        history.record(ContextNotification("same", "test.app", "first", "old", now - 1000, now, false), now)
        history.record(ContextNotification("same", "test.app", "updated", "new", now - 1000, now, false), now)
        history.record(ContextNotification("expired", "test.app", "old", null, PersonalNotificationHistory.cutoff(now) - 1, now, false), now)
        val result = history.query(json("{}"), now)
        assertEquals(1, result["count"])
        val rows = result["items"] as List<*>
        assertEquals("updated", (rows.single() as Map<*, *>)["title"])
        assertEquals(0, history.query(json("{}"), now + 8L * 86400000)["count"])
        history.clear()
        assertEquals(0, history.query(json("{}"), now)["count"])
    }

    @Test fun `contact write rejects stale identity and read-only accounts before mutation`() = runBlocking {
        context.permissions += listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        val provider = ContactProvider()
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider)
        val service = PersonalContextService(context)
        val stale = service.execute("contacts_delete", json("""{"rawContactId":"7","version":2}"""))
        assertEquals("stale_record", stale["code"])
        provider.readOnly = true
        val readonly = service.execute("contacts_delete", json("""{"rawContactId":"7","version":3}"""))
        assertEquals("read_only", readonly["code"])
        assertEquals(0, provider.writes)
    }

    @Test fun `contact updates use a single batch and do not replay failed writes`() = runBlocking {
        context.permissions += listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        val provider = ContactProvider()
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider)
        val service = PersonalContextService(context)
        val update = service.execute("contacts_update", json("""{"rawContactId":"7","version":3,"phones":["12345"]}"""))
        assertEquals(true, update["success"])
        assertEquals(1, provider.batches)
        assertTrue(provider.lastBatch.first().isAssertQuery)
        assertEquals(3, provider.lastBatch.size)
        assertEquals(listOf("7", ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE),
            requireNotNull(provider.lastBatch[1].resolveSelectionArgsBackReferences(emptyArray(), 0)).toList())
        provider.failBatch = true
        val failed = service.execute("contacts_delete", json("""{"rawContactId":"7","version":3}"""))
        assertEquals(false, failed["success"])
        assertEquals(2, provider.batches)
        assertEquals(false, failed["retryable"])
    }

    @Test fun `document grants are revoked without granting arbitrary content URIs`() {
        val documents = PersonalContextDocuments(context)
        val uri = Uri.parse("content://test.documents/document/report")
        documents.remember(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(documents.grants().contains(uri.toString()))
        documents.requireReadable(uri)
        assertThrows(PersonalContextException::class.java) { documents.requireReadable(Uri.parse("content://sms/1")) }
        documents.revokeAll()
        assertTrue(documents.grants().isEmpty())
        assertThrows(PersonalContextException::class.java) { documents.requireReadable(uri) }
    }

    @Test fun `organization edit keeps other organization rows and omitted fields`() = runBlocking {
        context.permissions += listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        val provider = ContactProvider()
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider)
        val result = PersonalContextService(context).execute("contacts_update", json("""{"rawContactId":"7","version":3,"jobTitle":"Engineer"}"""))
        assertEquals(true, result["success"])
        assertEquals(2, provider.lastBatch.size)
        val update = provider.lastBatch.last()
        assertTrue(update.isUpdate)
        assertEquals("content://com.android.contacts/data/99", update.uri.toString())
        val values = requireNotNull(update.resolveValueBackReferences(emptyArray(), 0))
        assertEquals("Engineer", values.getAsString(ContactsContract.CommonDataKinds.Organization.TITLE))
        assertFalse(values.containsKey(ContactsContract.CommonDataKinds.Organization.COMPANY))
    }

    @Test fun `clearing history excludes notification work queued before clear`() {
        val history = PersonalNotificationHistory.get(context)
        val now = System.currentTimeMillis()
        history.clear(now)
        history.record(ContextNotification("queued", "test.app", "old", null, now - 100, now - 1, false), now)
        assertEquals(0, history.query(json("{}"), now)["count"])
    }

    @Test fun `cancelled content query cancels the provider signal`() = runBlocking {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val provider = object : CalendarProvider() {
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?, cancellationSignal: CancellationSignal?): Cursor? {
                cancellationSignal!!.setOnCancelListener { cancelled.countDown() }
                started.countDown()
                cancelled.await(5, TimeUnit.SECONDS)
                cancellationSignal.throwIfCanceled()
                return super.query(uri, projection, selection, selectionArgs, sortOrder)
            }
        }
        ShadowContentResolver.registerProviderInternal("com.android.calendar", provider)
        val job = launch(Dispatchers.IO) { context.contentResolver.contextQuery(CalendarContract.Events.CONTENT_URI, arrayOf("_id")) { _, _ -> true } }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
        assertTrue(job.isCancelled)
    }

    private class TestContext(base: Context) : ContextWrapper(base) {
        val permissions = mutableSetOf<String>()
        private val pm = Mockito.mock(PackageManager::class.java).apply {
            Mockito.`when`(resolveContentProvider(Mockito.anyString(), Mockito.anyInt())).thenReturn(ProviderInfo())
            Mockito.`when`(getWhitelistedRestrictedPermissions(Mockito.anyString(), Mockito.anyInt())).thenReturn(emptySet())
        }
        override fun getPackageManager(): PackageManager = pm
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int = if (permission in permissions) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        override fun getApplicationContext(): Context = this
    }

    private open class CalendarProvider : ContentProvider() {
        var writes = 0
        var inserted: ContentValues? = null
        var returnNull = false
        override fun onCreate() = true
        override fun getType(uri: Uri) = "vnd.android.cursor.dir/event"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            if (returnNull) return null
            val columns = projection ?: arrayOf("_id")
            return MatrixCursor(columns).apply {
                if (uri.path.orEmpty().contains("instances")) for (id in 1..3) addRow(arrayOf<Any?>(id.toLong(), 1L, "Event $id", 1791849600000L, 1791853200000L, "Place", 0, "UTC"))
                else if (columns.contains(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)) addRow(columns.map { column -> when (column) {
                    CalendarContract.Calendars._ID -> 1L
                    CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL -> CalendarContract.Calendars.CAL_ACCESS_OWNER
                    CalendarContract.Calendars.VISIBLE, CalendarContract.Calendars.IS_PRIMARY -> 1
                    CalendarContract.Calendars.CALENDAR_COLOR -> 0
                    else -> "Test"
                } })
            }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri { writes++; inserted = values; return ContentUris.withAppendedId(uri, 1) }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { writes++; return 1 }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int { writes++; return 1 }
    }

    private class ContactProvider : CalendarProvider() {
        var readOnly = false
        var batches = 0
        var failBatch = false
        var lastBatch = emptyList<ContentProviderOperation>()
        override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
            batches++
            lastBatch = operations.toList()
            if (failBatch) throw OperationApplicationException("Provider refused the batch")
            return operations.map { ContentProviderResult(1) }.toTypedArray()
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(projection!!).apply {
                if (projection.size == 1 && projection[0] == ContactsContract.Data._ID) addRow(arrayOf(99L))
                else addRow(arrayOf(if (readOnly) 1 else 0, 3L, 0))
            }
    }
}
