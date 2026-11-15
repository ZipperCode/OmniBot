package cn.com.omnimind.bot.agent.tool.handlers

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import cn.com.omnimind.bot.util.AndroidAutomationPermissionGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class VlmPermissionWaitRegressionTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        setAccessibility(false)
        ShadowSettings.setCanDrawOverlays(false)
    }

    @After
    fun tearDown() {
        setAccessibility(false)
        ShadowSettings.setCanDrawOverlays(false)
    }

    private fun setAccessibility(enabled: Boolean) {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            if (enabled) ComponentName(context.packageName, "cn.com.omnimind.accessibility.service.AssistsService").flattenToString() else "",
        )
    }

    @Test
    fun `缺权限时不执行后续动作且仅提示一次`() = runBlocking {
        var prompts = 0
        var actions = 0
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            AndroidAutomationPermissionGate.awaitPermissions(context, checkStop = {}) { missing ->
                prompts++
                assertEquals(listOf("accessibility", "overlay"), missing.missingIds)
            }
            actions++
        }
        try {
            assertTrue(waiting.isActive)
            assertEquals(1, prompts)
            assertEquals(0, actions)
        } finally {
            waiting.cancelAndJoin()
        }
    }

    @Test
    fun `部分授权继续等待全部授权后原调用只执行一次`() = runBlocking {
        var prompts = 0
        var checks = 0
        var actions = 0
        val partialPoll = CompletableDeferred<Unit>()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            AndroidAutomationPermissionGate.awaitPermissions(
                context,
                checkStop = { if (++checks == 2) partialPoll.complete(Unit) },
                onMissing = { prompts++ },
            )
            actions++
        }
        try {
            setAccessibility(true)
            withTimeout(5000L) { partialPoll.await() }
            assertFalse(waiting.isCompleted)
            assertEquals(0, actions)
            ShadowSettings.setCanDrawOverlays(true)
            withTimeout(5000L) { waiting.await() }
            assertEquals(1, prompts)
            assertEquals(1, actions)
        } finally {
            waiting.cancelAndJoin()
        }
    }

    @Test
    fun `等待中取消后迟到授权不会执行原动作`() = runBlocking {
        var actions = 0
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            AndroidAutomationPermissionGate.awaitPermissions(context, checkStop = {}, onMissing = {})
            actions++
        }
        waiting.cancelAndJoin()
        setAccessibility(true)
        ShadowSettings.setCanDrawOverlays(true)
        assertTrue(waiting.isCancelled)
        assertEquals(0, actions)
    }

    @Test
    fun `已有授权不打开授权页直接执行`() = runBlocking {
        setAccessibility(true)
        ShadowSettings.setCanDrawOverlays(true)
        var prompts = 0
        var actions = 0
        AndroidAutomationPermissionGate.awaitPermissions(context, checkStop = {}, onMissing = { prompts++ })
        actions++
        assertEquals(0, prompts)
        assertEquals(1, actions)
    }
}
