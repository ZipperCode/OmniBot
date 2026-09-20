package cn.com.omnimind.bot.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentPermissionSupportTest {
    @Test
    fun `maps accessibility aliases to the stable authorization id`() {
        assertEquals(
            listOf("accessibility"),
            resolveAgentPermissionIds(
                listOf("无障碍权限", "Android GUI 无障碍权限", "Accessibility")
            )
        )
    }

    @Test
    fun `maps the other shared permission aliases`() {
        assertEquals(
            listOf("overlay", "installed_apps", "privileged", "public_storage"),
            resolveAgentPermissionIds(
                listOf(
                    "Overlay",
                    "Installed Apps Access",
                    "Privileged Source",
                    "Public Storage Access",
                )
            )
        )
    }
}
