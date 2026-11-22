package org.xmyun.starmate.feature.mobileuse.log

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goal: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
    val outcome: String? = null,
    val detail: String? = null,
)

@Entity(
    tableName = "steps",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("task_id")],
)
data class StepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "task_id") val taskId: Long,
    val at: Long,
    @ColumnInfo(name = "package_name") val packageName: String?,
    @ColumnInfo(name = "app_label") val appLabel: String?,
    val action: String,
    val verdict: String,
    val detail: String?,
)

/** 操作日志库 mobileuse.db。只存在本机，不参与导出（里面有屏幕上的文字和输入过的内容）。 */
@Database(entities = [TaskEntity::class, StepEntity::class], version = 1, exportSchema = false)
abstract class ActionLogDatabase : RoomDatabase() {
    abstract fun dao(): ActionLogDao

    companion object {
        const val FILE_NAME = "mobileuse.db"

        /** name 只传文件名，不传绝对路径（同 core/data 的做法）。 */
        fun build(
            context: Context,
            name: String = FILE_NAME,
            driver: SQLiteDriver = BundledSQLiteDriver(),
        ): ActionLogDatabase =
            Room.databaseBuilder(context.applicationContext, ActionLogDatabase::class.java, name)
                .setDriver(driver)
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
    }
}
