package org.xmyun.starmate.core.model

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SqliteImportTest {

    private val dir: File = Files.createTempDirectory("sqlite-import").toFile()
    private val dbFile = File(dir, "data/app.db")
    private val source = File(dir, "archive/app.db")
    private var closedBlocks = 0

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun sqliteBytes(marker: String): ByteArray =
        "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + marker.toByteArray()

    private fun write(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    private suspend fun replace(verify: suspend () -> Unit) = SqliteImport.replace(
        name = "app",
        source = source,
        dbFile = dbFile,
        whileClosed = { block ->
            closedBlocks++
            block()
        },
        verify = verify,
    )

    @Test
    fun replacesDatabaseAndDropsStaleSideFiles() = runTest {
        write(dbFile, sqliteBytes("old"))
        write(File(dbFile.path + "-wal"), "old wal".toByteArray())
        write(File(dbFile.path + "-shm"), "old shm".toByteArray())
        write(source, sqliteBytes("new"))

        replace(verify = {})

        assertTrue(dbFile.readBytes().contentEquals(sqliteBytes("new")))
        // 旧库的日志不能留下来套到新库上
        assertFalse(File(dbFile.path + "-wal").exists())
        assertFalse(File(dbFile.path + "-shm").exists())
        assertFalse(File(dbFile.path + ".import-bak").exists())
        assertEquals(1, closedBlocks)
    }

    @Test
    fun worksWhenThereIsNoExistingDatabase() = runTest {
        write(source, sqliteBytes("new"))

        replace(verify = {})

        assertTrue(dbFile.readBytes().contentEquals(sqliteBytes("new")))
    }

    @Test
    fun restoresOriginalWhenNewDatabaseCannotBeOpened() = runTest {
        write(dbFile, sqliteBytes("old"))
        write(source, sqliteBytes("new"))

        try {
            replace(verify = { throw IllegalStateException("schema mismatch") })
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("app database in archive cannot be opened", e.message)
        }

        assertTrue(dbFile.readBytes().contentEquals(sqliteBytes("old")))
        assertFalse(File(dbFile.path + ".import-bak").exists())
        // 换入一次、换回一次，两次都在关库状态下做
        assertEquals(2, closedBlocks)
    }

    @Test
    fun rejectsFileThatIsNotSqliteWithoutTouchingTheDatabase() = runTest {
        write(dbFile, sqliteBytes("old"))
        write(source, "PK not a database".toByteArray())

        try {
            replace(verify = {})
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("app database in archive is not a SQLite file", e.message)
        }

        assertTrue(dbFile.readBytes().contentEquals(sqliteBytes("old")))
        assertEquals(0, closedBlocks)
    }

    @Test
    fun rejectsMissingFileWithoutTouchingTheDatabase() = runTest {
        write(dbFile, sqliteBytes("old"))

        try {
            replace(verify = {})
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("app database missing in archive", e.message)
        }

        assertTrue(dbFile.readBytes().contentEquals(sqliteBytes("old")))
        assertEquals(0, closedBlocks)
    }
}
