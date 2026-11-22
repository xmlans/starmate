package org.xmyun.starmate.feature.proactive.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "cares", indices = [Index("status")])
data class CareEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val topic: String,
    val reason: String,
    val scheduledFor: Long,
    val nextAttemptAt: Long,
    val status: String,
    val attempts: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "send_log", indices = [Index("at")])
data class SendLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val source: String,
    val careId: Long?,
    val topic: String?,
    val outcome: String,
    val reason: String?,
    val text: String?,
)

@Dao
interface ProactiveDao {

    // ---- cares ----

    @Insert
    suspend fun insertCare(care: CareEntity): Long

    @Update
    suspend fun updateCare(care: CareEntity)

    @Query("SELECT * FROM cares WHERE id = :id")
    suspend fun care(id: Long): CareEntity?

    @Query("SELECT * FROM cares WHERE status = :status ORDER BY scheduledFor ASC, id ASC")
    suspend fun caresWithStatus(status: String): List<CareEntity>

    @Query("SELECT * FROM cares WHERE status = :status ORDER BY scheduledFor ASC, id ASC")
    fun observeCaresWithStatus(status: String): Flow<List<CareEntity>>

    @Query("SELECT COUNT(*) FROM cares WHERE status = :status")
    suspend fun countCares(status: String): Int

    @Query("SELECT COUNT(*) FROM cares")
    suspend fun careCount(): Int

    // ---- send log ----

    @Insert
    suspend fun insertLog(entry: SendLogEntity): Long

    @Query("SELECT * FROM send_log ORDER BY at DESC, id DESC LIMIT :limit")
    fun observeRecentLogs(limit: Int): Flow<List<SendLogEntity>>

    @Query("SELECT COUNT(*) FROM send_log WHERE outcome = :outcome AND at >= :fromMillis")
    suspend fun countLogsSince(outcome: String, fromMillis: Long): Int

    @Query("SELECT COUNT(*) FROM send_log WHERE outcome = :outcome AND at > :afterMillis")
    suspend fun countLogsAfter(outcome: String, afterMillis: Long): Int

    @Query("SELECT MAX(at) FROM send_log WHERE outcome = :outcome")
    suspend fun lastLogAt(outcome: String): Long?

    @Query("DELETE FROM send_log WHERE id NOT IN (SELECT id FROM send_log ORDER BY at DESC, id DESC LIMIT :keep)")
    suspend fun pruneLogs(keep: Int)
}

@Database(entities = [CareEntity::class, SendLogEntity::class], version = 1, exportSchema = false)
abstract class ProactiveDatabase : RoomDatabase() {
    abstract fun dao(): ProactiveDao

    companion object {
        const val FILE_NAME = "proactive.db"

        /** name 只传文件名（同 ChatDatabase：Windows 宿主上绝对路径会被拒绝）。 */
        fun build(
            context: Context,
            name: String = FILE_NAME,
            driver: SQLiteDriver = BundledSQLiteDriver(),
        ): ProactiveDatabase =
            Room.databaseBuilder(context.applicationContext, ProactiveDatabase::class.java, name)
                .setDriver(driver)
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
    }
}
