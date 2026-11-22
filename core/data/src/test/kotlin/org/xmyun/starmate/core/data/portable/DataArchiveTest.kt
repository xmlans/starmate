package org.xmyun.starmate.core.data.portable

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmyun.starmate.core.data.MutableClock
import org.xmyun.starmate.core.data.appContext
import org.xmyun.starmate.core.model.DataPortable

@RunWith(RobolectricTestRunner::class)
class DataArchiveTest {

    /** 内容放在内存里的假模块：导出时写文件，导入时读回。 */
    private class FakePortable(override val id: String, var files: Map<String, String>) : DataPortable {
        var importCalls = 0

        override suspend fun exportTo(dir: File) {
            files.forEach { (path, text) ->
                File(dir, path).apply { parentFile?.mkdirs() }.writeText(text)
            }
        }

        override suspend fun importFrom(dir: File) {
            importCalls++
            files = dir.walkTopDown().filter { it.isFile }
                .associate { it.relativeTo(dir).invariantSeparatorsPath to it.readText() }
        }
    }

    private val clock = MutableClock(Instant.parse("2026-11-11T08:00:00Z"))

    private fun archive(vararg portables: DataPortable) = DataArchive(appContext(), portables.toSet(), clock)

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val validManifest = """{"formatVersion":1,"appVersion":"1.0","exportedAt":"2026-11-11T08:00:00Z"}"""

    @Test
    fun exportThenImportRoundTrips() = runTest {
        val a = FakePortable("alpha", mapOf("a.txt" to "hello", "sub/dir/b.txt" to "世界"))
        val b = FakePortable("beta", mapOf("data.json" to "{\"x\":1}"))
        val out = ByteArrayOutputStream()
        archive(a, b).export(out)

        // 包里有 manifest，且版本为 1
        val names = mutableListOf<String>()
        var manifestText = ""
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                names += e.name
                if (e.name == DataArchive.MANIFEST_NAME) manifestText = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertTrue(DataArchive.MANIFEST_NAME in names)
        assertTrue("alpha/sub/dir/b.txt" in names)
        assertTrue(manifestText.contains("\"formatVersion\":1"))
        assertTrue(manifestText.contains("2026-11-11T08:00:00Z"))

        val originalA = a.files
        val originalB = b.files
        a.files = emptyMap()
        b.files = mapOf("stale.txt" to "x")
        archive(a, b).import(ByteArrayInputStream(out.toByteArray()))

        assertEquals(originalA, a.files)
        assertEquals(originalB, b.files)
    }

    @Test
    fun unknownFormatVersionThrowsWithoutImporting() = runTest {
        val a = FakePortable("alpha", emptyMap())
        val bytes = zipOf(
            DataArchive.MANIFEST_NAME to """{"formatVersion":99,"appVersion":"9","exportedAt":"x"}""",
            "alpha/a.txt" to "hi",
        )
        try {
            archive(a).import(ByteArrayInputStream(bytes))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
        assertEquals(0, a.importCalls)
    }

    @Test
    fun missingManifestThrows() = runTest {
        val a = FakePortable("alpha", emptyMap())
        try {
            archive(a).import(ByteArrayInputStream(zipOf("alpha/a.txt" to "hi")))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
        assertEquals(0, a.importCalls)
    }

    @Test
    fun parentTraversalEntryRejected() = runTest {
        val a = FakePortable("alpha", emptyMap())
        val escaped = File(appContext().cacheDir, "evil.txt")
        escaped.delete()
        val bytes = zipOf(
            DataArchive.MANIFEST_NAME to validManifest,
            "alpha/../../evil.txt" to "pwned",
        )
        try {
            archive(a).import(ByteArrayInputStream(bytes))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
        assertEquals(0, a.importCalls)
        assertFalse(escaped.exists())
    }

    @Test
    fun absolutePathEntryRejected() = runTest {
        val a = FakePortable("alpha", emptyMap())
        val bytes = zipOf(DataArchive.MANIFEST_NAME to validManifest, "/tmp/evil.txt" to "pwned")
        try {
            archive(a).import(ByteArrayInputStream(bytes))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
        assertEquals(0, a.importCalls)
    }

    private fun leftoverWorkDirs() =
        appContext().cacheDir.listFiles { f -> f.name.startsWith("archive-") }.orEmpty().size

    @Test
    fun totalBytesLimitRejected() = runTest {
        val a = FakePortable("alpha", emptyMap())
        val bytes = zipOf(
            DataArchive.MANIFEST_NAME to validManifest,
            "alpha/a.txt" to "x".repeat(600),
            "alpha/b.txt" to "y".repeat(600),
        )
        val archive = archive(a).apply { maxTotalBytes = 1000 }
        try {
            archive.import(ByteArrayInputStream(bytes))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
        assertEquals(0, a.importCalls)
        assertEquals(0, leftoverWorkDirs())
    }

    @Test
    fun entryCountLimitRejected() = runTest {
        val a = FakePortable("alpha", emptyMap())
        val bytes = zipOf(
            DataArchive.MANIFEST_NAME to validManifest,
            "alpha/1.txt" to "1",
            "alpha/2.txt" to "2",
            "alpha/3.txt" to "3",
        )
        val archive = archive(a).apply { maxEntries = 3 }
        try {
            archive.import(ByteArrayInputStream(bytes))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
        assertEquals(0, a.importCalls)
        assertEquals(0, leftoverWorkDirs())

        // 恰好在上限内时正常导入
        archive(a).apply { maxEntries = 4 }.import(ByteArrayInputStream(bytes))
        assertEquals(1, a.importCalls)
    }

    @Test
    fun workDirectoriesAreCleanedUp() = runTest {
        val a = FakePortable("alpha", mapOf("a.txt" to "hello"))
        val out = ByteArrayOutputStream()
        archive(a).export(out)
        archive(a).import(ByteArrayInputStream(out.toByteArray()))
        val leftovers = appContext().cacheDir.listFiles { f -> f.name.startsWith("archive-") }.orEmpty()
        assertEquals(0, leftovers.size)
    }
}
