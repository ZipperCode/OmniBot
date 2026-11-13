package cn.com.omnimind.bot.omniflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OmniFlowAppExperienceNativeTest {
    @Test
    fun `sanitizeErrorCode cleans error codes and rejects noisy exceptions to fallback`() {
        assertEquals("app_experience_store_invalid", sanitizeErrorCode("app_experience_store_invalid"))
        assertEquals("app_experience_revision_conflict", sanitizeErrorCode("app_experience_revision_conflict"))
        assertEquals("app_experience_not_found", sanitizeErrorCode("app_experience_not_found"))
        assertEquals("app_experience_capacity_reached", sanitizeErrorCode("app_experience_capacity_reached"))
        assertEquals("experience_update_failed", sanitizeErrorCode(""))
        assertEquals("experience_update_failed", sanitizeErrorCode("   "))
        assertEquals("experience_update_failed", sanitizeErrorCode(null))

        val noisyError = "java.lang.IllegalArgumentException: /data/user/0/cn.com.omnimind/files/store.json corrupted\n\tat cn.com..."
        val sanitized = sanitizeErrorCode(noisyError)
        assertEquals("experience_update_failed", sanitized)
        assertFalse("error code must not contain file paths", sanitized.contains("/data/user/0"))
        assertFalse("error code must not contain file names", sanitized.contains("store.json"))
        assertFalse("error code must not contain stack traces", sanitized.contains("\tat"))
    }
}
