package org.xmyun.starmate.feature.chat.common

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmyun.starmate.core.model.DataPortable

/** 头像图片参与导出导入。角色资料里存的是绝对路径，同一应用在新手机上路径相同。 */
class AvatarPortable @Inject constructor(
    @ApplicationContext private val context: Context,
) : DataPortable {

    override val id: String = AvatarStorage.DIR

    private val dir: File get() = File(context.filesDir, AvatarStorage.DIR)

    override suspend fun exportTo(dir: File) = withContext(Dispatchers.IO) {
        this@AvatarPortable.dir.listFiles()?.filter { it.isFile }?.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
        Unit
    }

    override suspend fun importFrom(dir: File) = withContext(Dispatchers.IO) {
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        val target = this@AvatarPortable.dir
        target.mkdirs()
        target.listFiles()?.forEach { it.delete() }
        files.forEach { it.copyTo(File(target, it.name), overwrite = true) }
        Unit
    }
}
