package cn.com.omnimind.bot.agent.development

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class LsposedProjectTemplateServiceTest {

    private lateinit var context: Context
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        tempDir = File(context.cacheDir, "test_project_dir_${System.currentTimeMillis()}")
        tempDir.mkdirs()
    }

    @Test
    fun createProject_rejectsInvalidPackageName() {
        val targetDir = File(tempDir, "InvalidModule")
        val result = LsposedProjectTemplateService.createProject(
            context = context,
            targetDir = targetDir,
            packageName = "invalid-pkg-name", // hyphens not allowed in Java package
            moduleName = "InvalidModule"
        )

        assertFalse(result.success)
        assertNotNull(result.errorMessage)
    }

    @Test
    fun createProject_rejectsExistingNonEmptyDirectory() {
        val existingDir = File(tempDir, "ExistingModule")
        existingDir.mkdirs()
        File(existingDir, "important_user_code.java").writeText("// do not overwrite")

        val result = LsposedProjectTemplateService.createProject(
            context = context,
            targetDir = existingDir,
            packageName = "com.test.module",
            moduleName = "ExistingModule"
        )

        assertFalse(result.success)
        assertTrue(result.errorMessage?.contains("已存在且非空") == true)
        assertEquals("// do not overwrite", File(existingDir, "important_user_code.java").readText())
    }

    @Test
    fun importProject_detectsBuildRouteCorrectly() {
        // Test Gradle project detection
        val gradleDir = File(tempDir, "GradleProject")
        gradleDir.mkdirs()
        File(gradleDir, "gradlew").writeText("#!/bin/sh\n")
        File(gradleDir, "build.gradle.kts").writeText("// gradle")

        val targetImport1 = File(tempDir, "ImportedGradle")
        val res1 = LsposedProjectTemplateService.importProject(context, gradleDir, targetImport1)
        assertTrue(res1.success)
        assertEquals("gradle", res1.buildRoute)
        assertTrue(res1.hasGradleWrapper)

        // Test No-Gradle project detection
        val noGradleDir = File(tempDir, "NoGradleProject")
        noGradleDir.mkdirs()
        File(noGradleDir, "build-apk.sh").writeText("#!/bin/sh\n")

        val targetImport2 = File(tempDir, "ImportedNoGradle")
        val res2 = LsposedProjectTemplateService.importProject(context, noGradleDir, targetImport2)
        assertTrue(res2.success)
        assertEquals("no_gradle", res2.buildRoute)

        // Test custom (unknown) build system without injecting templates
        val plainDir = File(tempDir, "PlainJavaProject")
        plainDir.mkdirs()
        File(plainDir, "SomeClass.java").writeText("class SomeClass {}")

        val targetImport3 = File(tempDir, "ImportedPlain")
        val res3 = LsposedProjectTemplateService.importProject(context, plainDir, targetImport3)
        assertTrue(res3.success)
        assertEquals("custom", res3.buildRoute)
        assertFalse(res3.hasBuildScript)
    }

    @Test
    fun importProject_safelyPreservesSourceDirectoryWithoutModifications() {
        val sourceDir = File(tempDir, "OriginalSource")
        sourceDir.mkdirs()
        val canary = File(sourceDir, "canary.txt")
        canary.writeText("untouchable_content")

        val targetDir = File(tempDir, "IsolatedTarget")
        val result = LsposedProjectTemplateService.importProject(context, sourceDir, targetDir)

        assertTrue(result.success)
        assertTrue("Source directory canary must remain completely intact", canary.isFile)
        assertEquals("untouchable_content", canary.readText())
        assertTrue("Target directory must receive imported copy", File(targetDir, "canary.txt").isFile)
    }

    @Test
    fun importProject_rejectsExistingNonEmptyTargetDirectory() {
        val sourceDir = File(tempDir, "SourceA")
        sourceDir.mkdirs()
        File(sourceDir, "source.txt").writeText("source")

        val targetDir = File(tempDir, "TargetA")
        targetDir.mkdirs()
        File(targetDir, "existing.txt").writeText("do not touch")

        val result = LsposedProjectTemplateService.importProject(context, sourceDir, targetDir)
        assertFalse(result.success)
        assertTrue(result.errorMessage?.contains("已存在且非空") == true)
        assertEquals("do not touch", File(targetDir, "existing.txt").readText())
    }
}
