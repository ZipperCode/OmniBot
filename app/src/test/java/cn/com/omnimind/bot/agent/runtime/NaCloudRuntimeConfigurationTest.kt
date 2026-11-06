package cn.com.omnimind.bot.agent.runtime

import android.content.Context
import cn.com.omnimind.bot.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock

/** Run for both develop and production variants, including productionDebug. */
class NaCloudRuntimeConfigurationTest {
    @get:Rule val directory = TemporaryFolder()
    private val develop = BuildConfig.FLAVOR_version == "develop"

    private fun withRuntime(test: (Context, NaCloudRuntime) -> Unit) {
        val context = acpProfileStoreTestContext(directory.root)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = NaCloudRuntime(
            context, scope, mock(AgentSessionBindingRepository::class.java),
            AcpAgentProfileStore(context), emit = {},
        )
        try { test(context, runtime) } finally { scope.cancel() }
    }

    @Test fun freshInstallUsesAccountConnectionAndOnlyDevelopExposesTestConfiguration() = withRuntime { context, runtime ->
        assertEquals("managed", runtime.config()["connectionMode"])
        assertEquals(BuildConfig.CLOUD_URL, runtime.config()["baseUrl"])
        assertEquals("", runtime.config()["apiKey"])
        assertEquals(develop, runtime.config()["developmentAvailable"])
        val store = AcpAgentProfileStore(context)
        val na = store.list().single { it.id == NA_AGENT_ID }
        assertEquals(develop, na.toPayload(health = store.health(na.id))["configurationAvailable"])
    }

    @Test fun productionIgnoresStoredTestConnectionForRuntimeAndHistoryOwnership() = withRuntime { context, runtime ->
        val address = "https://standalone.example.test"
        context.getSharedPreferences("na_cloud_connection", Context.MODE_PRIVATE).edit()
            .putBoolean("development", true).putString("baseUrl", address).commit()
        val config = runtime.config()
        val standaloneOwner = NaCloudConnection(address).prefix
        if (develop) {
            assertEquals("development", config["connectionMode"])
            assertEquals(address, config["baseUrl"])
            assertEquals(standaloneOwner, NaConversationAccess.currentOwnerPrefix(context))
        } else {
            assertEquals("managed", config["connectionMode"])
            assertEquals(BuildConfig.CLOUD_URL, config["baseUrl"])
            assertEquals("", config["apiKey"])
            assertNotEquals(standaloneOwner, NaConversationAccess.currentOwnerPrefix(context))
        }
    }

    @Test fun nativeConfigurationRejectsStandaloneOutsideDevelopBeforeWritingPreferences() = withRuntime { context, runtime ->
        val args = mapOf("connectionMode" to "development", "baseUrl" to "https://standalone.example.test", "apiKey" to "fixture-token")
        if (develop) {
            val config = runtime.configure(args)
            assertEquals("development", config["connectionMode"])
            assertEquals(args["baseUrl"], config["baseUrl"])
            assertEquals(args["apiKey"], config["apiKey"])
        } else {
            val preferences = context.getSharedPreferences("na_cloud_connection", Context.MODE_PRIVATE)
            val before = preferences.all
            assertThrows(IllegalArgumentException::class.java) { runtime.configure(args) }
            assertEquals(before, preferences.all)
            assertEquals("managed", runtime.config()["connectionMode"])
        }
    }

    @Test fun returningToAccountConnectionDisablesStoredTestMode() = withRuntime { context, runtime ->
        context.getSharedPreferences("na_cloud_connection", Context.MODE_PRIVATE).edit()
            .putBoolean("development", true).putString("baseUrl", "https://standalone.example.test").commit()
        val config = runtime.configure(mapOf("connectionMode" to "managed"))
        assertEquals("managed", config["connectionMode"])
        assertEquals(BuildConfig.CLOUD_URL, config["baseUrl"])
        assertEquals("", config["apiKey"])
        assertFalse(context.getSharedPreferences("na_cloud_connection", Context.MODE_PRIVATE).getBoolean("development", true))
    }
}
