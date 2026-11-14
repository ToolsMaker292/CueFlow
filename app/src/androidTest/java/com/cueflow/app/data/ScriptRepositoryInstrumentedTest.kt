package com.cueflow.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScriptRepositoryInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun tempDir(): File = File(
        context.cacheDir,
        "repo-test-${UUID.randomUUID()}",
    ).apply { mkdirs() }

    private fun seed(id: String = "seed", title: String = "Seed") = Script(
        id = id,
        title = title,
        content = "Body",
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    @Test
    fun persistsAndReloadsAtomically() {
        runBlocking {
            val dir = tempDir()
            val first = ScriptRepository(dir)
            first.load(seed())
            first.upsert(
                Script(
                    id = "new",
                    title = "Persisted",
                    content = "Hello 本地存储",
                    createdAt = 2_000L,
                    updatedAt = 2_000L,
                ),
            )

            val second = ScriptRepository(dir)
            second.load(seed("unused", "Unused"))
            assertEquals(2, second.scripts.value.size)
            assertEquals("Hello 本地存储", second.scripts.value.first { it.id == "new" }.content)
            assertFalse(second.loadError.value)

            dir.deleteRecursively()
        }
    }

    @Test
    fun importMergesAndKeepsNewerContent() {
        runBlocking {
            val sourceDir = tempDir()
            val source = ScriptRepository(sourceDir)
            source.load(seed())
            val backup = source.exportJson()

            val targetDir = tempDir()
            val target = ScriptRepository(targetDir)
            target.load(seed("other", "Other"))
            val report = target.importJson(backup)
            assertEquals(1, report.added)
            assertEquals(0, report.updated)
            assertEquals(2, target.scripts.value.size)

            // Importing the same, older backup again is a no-op.
            val secondReport = target.importJson(backup)
            assertEquals(0, secondReport.changed)

            sourceDir.deleteRecursively()
            targetDir.deleteRecursively()
        }
    }

    @Test
    fun corruptLibraryIsPreservedAndReseeded() {
        runBlocking {
            val dir = tempDir()
            val bad = File(dir, "scripts.json")
            bad.writeText("{ this is not valid json")

            val repo = ScriptRepository(dir)
            repo.load(seed("recovered", "Recovered"))
            assertTrue(repo.loadError.value)
            assertEquals(1, repo.scripts.value.size)
            assertEquals("Recovered", repo.scripts.value.single().title)
            assertTrue(dir.listFiles().orEmpty().any { it.name.startsWith("scripts-corrupt-") })

            dir.deleteRecursively()
        }
    }
}
