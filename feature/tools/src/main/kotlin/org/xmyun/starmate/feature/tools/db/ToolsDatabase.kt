package org.xmyun.starmate.feature.tools.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/** 听歌记录：只有歌名、歌手、应用和时间。 */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val artist: String?,
    val app: String?,
    val atMillis: Long,
)

/** 计步传感器的累计值采样。 */
@Entity(tableName = "step_samples")
data class StepSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMillis: Long,
    val value: Long,
)

@Dao
interface TrackDao {
    @Insert
    suspend fun insert(track: TrackEntity): Long

    @Query("SELECT * FROM tracks ORDER BY id DESC LIMIT 1")
    suspend fun last(): TrackEntity?

    @Query("SELECT * FROM tracks ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<TrackEntity>

    @Query("DELETE FROM tracks WHERE id NOT IN (SELECT id FROM tracks ORDER BY id DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int
}

@Dao
interface StepSampleDao {
    @Insert
    suspend fun insert(sample: StepSampleEntity): Long

    /** [from, to) 内的采样，按时间正序。 */
    @Query("SELECT * FROM step_samples WHERE atMillis >= :from AND atMillis < :to ORDER BY atMillis ASC, id ASC")
    suspend fun between(from: Long, to: Long): List<StepSampleEntity>

    /** [after, before) 内最晚的一个采样，给当天第一段增量做基线。 */
    @Query("SELECT * FROM step_samples WHERE atMillis >= :after AND atMillis < :before ORDER BY atMillis DESC, id DESC LIMIT 1")
    suspend fun lastBetween(after: Long, before: Long): StepSampleEntity?

    /** [from, before) 内最早的一个采样。 */
    @Query("SELECT * FROM step_samples WHERE atMillis >= :from AND atMillis < :before ORDER BY atMillis ASC, id ASC LIMIT 1")
    suspend fun firstBetween(from: Long, before: Long): StepSampleEntity?

    @Query("DELETE FROM step_samples WHERE atMillis < :before")
    suspend fun deleteBefore(before: Long)
}

@Database(entities = [TrackEntity::class, StepSampleEntity::class], version = 1, exportSchema = false)
abstract class ToolsDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun stepSampleDao(): StepSampleDao

    companion object {
        const val FILE_NAME = "tools.db"

        /** name 只传文件名，不传绝对路径（同 ChatDatabase）。 */
        fun build(
            context: Context,
            name: String = FILE_NAME,
            driver: SQLiteDriver = BundledSQLiteDriver(),
        ): ToolsDatabase =
            Room.databaseBuilder(context.applicationContext, ToolsDatabase::class.java, name)
                .setDriver(driver)
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
    }
}
