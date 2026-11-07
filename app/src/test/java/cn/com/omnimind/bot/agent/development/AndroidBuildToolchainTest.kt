package cn.com.omnimind.bot.agent.development

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AndroidBuildToolchainTest {

    @Test
    fun rewriteLauncher_replacesHardcodedTermuxPathWithPrefixVariable() {
        val originalScript = """
            #!/bin/sh
            exec /data/data/com.termux/files/usr/bin/dalvikvm \
                -cp /data/data/com.termux/files/usr/share/java/d8.jar \
                com.android.tools.r8.D8 "$@"
        """.trimIndent()

        val rewritten = AndroidBuildToolchain.rewriteLauncher("d8", originalScript)
        assertNotNull(rewritten)
        assertTrue(rewritten!!.startsWith("#!/bin/sh\n"))
        assertTrue(rewritten.contains("\${PREFIX:-/usr}/share/java/d8.jar"))
        assertFalse(rewritten.contains("/data/data/com.termux/files/usr/share/java/d8.jar"))
    }

    @Test
    fun rewriteLauncher_ignoresNonMatchingOrAlreadyUpdatedScript() {
        val nonMatchingScript = """
            #!/bin/sh
            echo "custom tool"
        """.trimIndent()

        assertNull(AndroidBuildToolchain.rewriteLauncher("d8", nonMatchingScript))
        assertNull(AndroidBuildToolchain.rewriteLauncher("unknown_tool", nonMatchingScript))
    }

    @Test
    fun validateJarClasses_verifiesExistenceOfRequiredClasses() {
        val tempJar = File.createTempFile("test_toolchain", ".jar")
        try {
            ZipOutputStream(FileOutputStream(tempJar)).use { zos ->
                zos.putNextEntry(ZipEntry("com/android/tools/r8/D8.class"))
                zos.write(ByteArray(100))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("com/android/tools/r8/R8.class"))
                zos.write(ByteArray(100))
                zos.closeEntry()
            }

            assertTrue(AndroidBuildToolchain.validateD8Jar(tempJar))
            assertFalse(AndroidBuildToolchain.validateApkSignerJar(tempJar))
        } finally {
            tempJar.delete()
        }
    }
}
