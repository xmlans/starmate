package org.xmyun.starmate.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

@Database(
    entities = [MessageEntity::class, CompanionEntity::class, UsageEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        const val FILE_NAME = "chat.db"

        /**
         * name 是数据库目录下的文件名，对应文件为 `context.getDatabasePath(name)`。
         * 不传绝对路径：Room 内部会再调一次 getDatabasePath，Windows 宿主上的绝对路径会被拒绝。
         * driver 可替换，便于在 JVM 单元测试里换实现。
         */
        fun build(
            context: Context,
            name: String = FILE_NAME,
            driver: SQLiteDriver = BundledSQLiteDriver(),
        ): ChatDatabase =
            Room.databaseBuilder(context.applicationContext, ChatDatabase::class.java, name)
                .setDriver(driver)
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
    }
}
