package cn.com.omnimind.bot.omniflow

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniFlowDeveloperOverrideTest {
    @Test
    fun `paths remain inside the omniflow Python package`() {
        assertEquals("omniflow/vlm/planner.py", normalizedPythonPath("vlm/planner.py"))
        assertEquals("omniflow/runtime/engine.py", normalizedPythonPath("./omniflow/runtime/engine.py"))
        assertThrows(IllegalArgumentException::class.java) {
            normalizedPythonPath("../omnitransfer/runtime.py")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizedPythonPath("omniflow/vlm/prompt.txt")
        }
    }

    @Test
    fun `first edit copies full package and clear restores pinned mode`() {
        val temporary = Files.createTempDirectory("omniflow-override-test").toFile()
        val base = temporary.resolve("base/python")
        base.resolve("omniflow/vlm/planner.py").apply {
            parentFile.mkdirs()
            writeText("VALUE = 'stable'\n")
        }
        base.resolve("omniflow/runtime/engine.py").apply {
            parentFile.mkdirs()
            writeText("ENGINE = 'stable'\n")
        }
        base.resolve("schemas/oob/oob_canonical_actions.v1.json").apply {
            parentFile.mkdirs()
            writeText("{\"tools\":[]}")
        }
        val store = OmniFlowDeveloperOverrideStore(temporary.resolve("override"))

        store.apply(base, "runtime-v1", "vlm/planner.py", "VALUE = 'edited'\n")

        assertEquals("VALUE = 'edited'\n", store.read("vlm/planner.py"))
        assertEquals("ENGINE = 'stable'\n", store.read("runtime/engine.py"))
        assertEquals(
            "{\"tools\":[]}",
            temporary.resolve("override/python/schemas/oob/oob_canonical_actions.v1.json")
                .readText(),
        )
        assertEquals(listOf("omniflow/vlm/planner.py"), store.status("runtime-v1").modifiedFiles)
        assertTrue(store.clear())
        assertFalse(store.status("runtime-v1").enabled)
    }

    @Test
    fun `runtime rebase preserves only explicitly modified files`() {
        val temporary = Files.createTempDirectory("omniflow-rebase-test").toFile()
        val baseV1 = temporary.resolve("base-v1/python")
        val baseV2 = temporary.resolve("base-v2/python")
        for ((base, planner, engine) in listOf(
            Triple(baseV1, "PLANNER = 1\n", "ENGINE = 1\n"),
            Triple(baseV2, "PLANNER = 2\n", "ENGINE = 2\n"),
        )) {
            base.resolve("omniflow/vlm/planner.py").apply {
                parentFile.mkdirs()
                writeText(planner)
            }
            base.resolve("omniflow/runtime/engine.py").apply {
                parentFile.mkdirs()
                writeText(engine)
            }
        }
        val store = OmniFlowDeveloperOverrideStore(temporary.resolve("override"))
        store.apply(baseV1, "runtime-v1", "vlm/planner.py", "PLANNER = 99\n")

        store.rebaseIfPresent(baseV2, "runtime-v2")

        assertEquals("PLANNER = 99\n", store.read("vlm/planner.py"))
        assertEquals("ENGINE = 2\n", store.read("runtime/engine.py"))
        assertEquals(
            listOf("omniflow/vlm/planner.py"),
            store.status("runtime-v2").modifiedFiles,
        )
    }

    @Test
    fun `failed first edit can restore stable content without marking modification`() {
        val temporary = Files.createTempDirectory("omniflow-rollback-test").toFile()
        val base = temporary.resolve("base/python")
        base.resolve("omniflow/vlm/planner.py").apply {
            parentFile.mkdirs()
            writeText("VALUE = 'stable'\n")
        }
        val store = OmniFlowDeveloperOverrideStore(temporary.resolve("override"))
        val stable = base.resolve("omniflow/vlm/planner.py").readText()
        store.apply(base, "runtime-v1", "vlm/planner.py", "not valid Python")

        store.restore("vlm/planner.py", stable, keepModified = false)

        assertEquals(stable, store.read("vlm/planner.py"))
        assertTrue(store.status("runtime-v1").modifiedFiles.isEmpty())
        assertFalse(store.status("runtime-v1").enabled)
    }

    @Test
    fun `sequential edits to the same file replace content atomically without duplicating modified list`() {
        val temporary = Files.createTempDirectory("omniflow-sequential-test").toFile()
        val base = temporary.resolve("base/python")
        base.resolve("omniflow/vlm/planner.py").apply {
            parentFile.mkdirs()
            writeText("VALUE = 'v0'\n")
        }
        val store = OmniFlowDeveloperOverrideStore(temporary.resolve("override"))

        store.apply(base, "runtime-v1", "vlm/planner.py", "VALUE = 'v1'\n")
        assertEquals("VALUE = 'v1'\n", store.read("vlm/planner.py"))

        store.apply(base, "runtime-v1", "vlm/planner.py", "VALUE = 'v2'\n")
        assertEquals("VALUE = 'v2'\n", store.read("vlm/planner.py"))
        assertEquals(listOf("omniflow/vlm/planner.py"), store.status("runtime-v1").modifiedFiles)
    }

    @Test
    fun `failed subsequent edit restores previously modified content and keeps modification marked`() {
        val temporary = Files.createTempDirectory("omniflow-subsequent-rollback-test").toFile()
        val base = temporary.resolve("base/python")
        base.resolve("omniflow/vlm/planner.py").apply {
            parentFile.mkdirs()
            writeText("VALUE = 'base'\n")
        }
        val store = OmniFlowDeveloperOverrideStore(temporary.resolve("override"))
        store.apply(base, "runtime-v1", "vlm/planner.py", "VALUE = 'valid_override'\n")

        val previous = store.read("vlm/planner.py")
        store.apply(base, "runtime-v1", "vlm/planner.py", "invalid python syntax")
        store.restore("vlm/planner.py", previous, keepModified = true)

        assertEquals("VALUE = 'valid_override'\n", store.read("vlm/planner.py"))
        assertEquals(listOf("omniflow/vlm/planner.py"), store.status("runtime-v1").modifiedFiles)
        assertTrue(store.status("runtime-v1").enabled)
    }

    @Test
    fun `rebase with multiple modified files preserves modifications and updates unchanged files`() {
        val temporary = Files.createTempDirectory("omniflow-multi-rebase-test").toFile()
        val baseV1 = temporary.resolve("base-v1/python")
        val baseV2 = temporary.resolve("base-v2/python")
        for ((base, planner, engine, config) in listOf(
            listOf(baseV1, "PLANNER = 1\n", "ENGINE = 1\n", "CONFIG = 1\n"),
            listOf(baseV2, "PLANNER = 2\n", "ENGINE = 2\n", "CONFIG = 2\n"),
        )) {
            val b = base as java.io.File
            b.resolve("omniflow/vlm/planner.py").apply {
                parentFile.mkdirs()
                writeText(planner as String)
            }
            b.resolve("omniflow/runtime/engine.py").apply {
                parentFile.mkdirs()
                writeText(engine as String)
            }
            b.resolve("omniflow/core/config.py").apply {
                parentFile.mkdirs()
                writeText(config as String)
            }
        }
        val store = OmniFlowDeveloperOverrideStore(temporary.resolve("override"))
        store.apply(baseV1, "runtime-v1", "vlm/planner.py", "PLANNER = 99\n")
        store.apply(baseV1, "runtime-v1", "core/config.py", "CONFIG = 88\n")

        store.rebaseIfPresent(baseV2, "runtime-v2")

        assertEquals("PLANNER = 99\n", store.read("vlm/planner.py"))
        assertEquals("CONFIG = 88\n", store.read("core/config.py"))
        assertEquals("ENGINE = 2\n", store.read("runtime/engine.py"))
        assertEquals(
            listOf("omniflow/core/config.py", "omniflow/vlm/planner.py"),
            store.status("runtime-v2").modifiedFiles,
        )
    }

    @Test
    fun `replaceDirectory preserves existing files without deletion when backup move fails`() {
        val temporary = Files.createTempDirectory("omniflow-backup-fail-test").toFile()
        val destination = temporary.resolve("override")
        val destinationFile = destination.resolve("python/omniflow/vlm/planner.py").apply {
            parentFile.mkdirs()
            writeText("USER_CUSTOM_OVERRIDE = 'do_not_lose'\n")
        }
        val source = temporary.resolve("override.tmp-12345")
        source.resolve("python/omniflow/vlm/planner.py").apply {
            parentFile.mkdirs()
            writeText("NEW_CONTENT = 'new'\n")
        }

        val exception = assertThrows(IllegalArgumentException::class.java) {
            replaceDirectory(source, destination, moveDir = { from, to ->
                if (to.name.contains(".bak-")) {
                    throw java.io.IOException("simulated_backup_failure")
                }
                Files.move(from.toPath(), to.toPath())
            })
        }
        assertEquals("omniflow_override_backup_failed", exception.message)
        assertTrue(destination.isDirectory)
        assertEquals("USER_CUSTOM_OVERRIDE = 'do_not_lose'\n", destinationFile.readText())
        assertFalse(source.exists())
    }

    @Test
    fun `replaceDirectory retains backup directory when install and restore both fail`() {
        val temporary = Files.createTempDirectory("omniflow-restore-fail-test").toFile()
        val destination = temporary.resolve("override")
        destination.resolve("python/omniflow/vlm/planner.py").apply {
            parentFile.mkdirs()
            writeText("ORIGINAL_USER_CODE = 1\n")
        }
        val source = temporary.resolve("override.tmp-99999")
        source.resolve("python/omniflow/vlm/planner.py").apply {
            parentFile.mkdirs()
            writeText("BROKEN_INSTALL = 2\n")
        }

        val exception = assertThrows(IllegalStateException::class.java) {
            replaceDirectory(source, destination, moveDir = { from, to ->
                if (from == destination && to.name.contains(".bak-")) {
                    Files.move(from.toPath(), to.toPath())
                } else if (from == source && to == destination) {
                    throw java.io.IOException("simulated_install_failure")
                } else if (from.name.contains(".bak-") && to == destination) {
                    throw java.io.IOException("simulated_restore_failure")
                }
            })
        }
        assertEquals("omniflow_override_restore_failed", exception.message)
        val backups = temporary.listFiles { f -> f.isDirectory && f.name.startsWith("override.bak-") }
        assertTrue(!backups.isNullOrEmpty())
        assertEquals(
            "ORIGINAL_USER_CODE = 1\n",
            backups!!.first().resolve("python/omniflow/vlm/planner.py").readText(),
        )
    }
}
