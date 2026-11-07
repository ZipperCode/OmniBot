package cn.com.omnimind.bot.agent.development

import org.junit.Assert.*
import org.junit.Test

class AndroidSdkInstallerTest {

    private val sampleXml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <sdk:sdk-repository xmlns:sdk="http://schemas.android.com/sdk/android/repository/11">
            <remotePackage path="platforms;android-34">
                <type-details/>
                <revision><major>2</major></revision>
                <display-name>Android SDK Platform 34</display-name>
                <archives>
                    <archive>
                        <complete>
                            <size>65432100</size>
                            <checksum type="sha1">aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa</checksum>
                            <url>platform-34_r02.zip</url>
                        </complete>
                    </archive>
                </archives>
            </remotePackage>
            <remotePackage path="platforms;android-35">
                <type-details/>
                <revision><major>1</major></revision>
                <display-name>Android SDK Platform 35</display-name>
                <archives>
                    <archive>
                        <complete>
                            <size>56894024</size>
                            <checksum type="sha1">83a2169527f6b955c4d09ba8b8c2dfa9a622b1cb</checksum>
                            <url>platform-35_r01.zip</url>
                        </complete>
                    </archive>
                </archives>
            </remotePackage>
        </sdk:sdk-repository>
    """.trimIndent()

    @Test
    fun parsePlatformArchive_extractsExactMetadataForRequestedApi() {
        val archive35 = AndroidSdkManager.parsePlatformArchive(sampleXml, 35)
        assertNotNull(archive35)
        assertEquals("platform-35_r01.zip", archive35!!.file)
        assertEquals(56894024L, archive35.bytes)
        assertEquals("83a2169527f6b955c4d09ba8b8c2dfa9a622b1cb", archive35.sha1)

        val archive34 = AndroidSdkManager.parsePlatformArchive(sampleXml, 34)
        assertNotNull(archive34)
        assertEquals("platform-34_r02.zip", archive34!!.file)
        assertEquals(65432100L, archive34.bytes)
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", archive34.sha1)
    }

    @Test
    fun parsePlatformArchive_returnsNullForMissingApi() {
        val archive25 = AndroidSdkManager.parsePlatformArchive(sampleXml, 25)
        assertNull(archive25)

        val archive99 = AndroidSdkManager.parsePlatformArchive(sampleXml, 99)
        assertNull(archive99)
    }

    @Test
    fun parsePlatformArchive_rejectsPathTraversalAndMaliciousFilenames() {
        val maliciousXmls = listOf(
            // Path traversal via parent directories
            sampleXml.replace("platform-35_r01.zip", "../../../etc/shadow.zip"),
            sampleXml.replace("platform-35_r01.zip", "..\\..\\windows.zip"),
            // Absolute path
            sampleXml.replace("platform-35_r01.zip", "/data/local/tmp/fake.zip"),
            // External URL scheme
            sampleXml.replace("platform-35_r01.zip", "https://evil.com/fake.zip"),
            // Non-zip file extension
            sampleXml.replace("platform-35_r01.zip", "platform-35_r01.tar.gz"),
            sampleXml.replace("platform-35_r01.zip", "platform-35_r01.sh")
        )

        maliciousXmls.forEach { xml ->
            val result = AndroidSdkManager.parsePlatformArchive(xml, 35)
            assertNull("Should reject unsafe archive path in XML", result)
        }
    }
}
