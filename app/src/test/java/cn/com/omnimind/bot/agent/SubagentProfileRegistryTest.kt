package cn.com.omnimind.bot.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentProfileRegistryTest {

    @Test
    fun `runtime config requireProfile rejects unknown or disabled profile without silent fallback`() {
        val config = XiaowanSubagentConfig()
        assertTrue(runCatching { config.requireProfile("does-not-exist") }.isFailure)
        assertTrue(runCatching { config.requireProfile("") }.isFailure)

        val disabledPlanner = config.profiles.map {
            if (it.id == "planner") it.copy(enabled = false) else it
        }
        val configWithDisabled = config.copy(profiles = disabledPlanner)
        assertTrue(runCatching { configWithDisabled.requireProfile("planner") }.isFailure)
        assertEquals("general", configWithDisabled.requireProfile("general").id)
    }
}
