package org.xmyun.starmate.feature.tools.contacts

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmyun.starmate.feature.tools.common.WriteOutcome

/** 只有这三个字段，其他联系人信息一律不读。 */
data class ContactInfo(
    val name: String,
    val phones: List<String>,
    val birthday: String?,
)

interface ContactsGateway {
    suspend fun search(name: String, limit: Int): List<ContactInfo>

    /** 打开拨号盘并填好号码，不会直接拨出。 */
    fun dial(number: String): WriteOutcome
}

class AndroidContactsGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) : ContactsGateway {

    override suspend fun search(name: String, limit: Int): List<ContactInfo> = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return@withContext emptyList()
        }
        val resolver = context.contentResolver
        val ids = LinkedHashMap<Long, String>()
        val filterUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(name))
        resolver.query(
            filterUri,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext() && ids.size < limit) {
                val display = c.getString(1)?.trim().orEmpty()
                if (display.isNotEmpty()) ids[c.getLong(0)] = display
            }
        }
        if (ids.isEmpty()) return@withContext emptyList()

        val phones = HashMap<Long, MutableList<String>>()
        val birthdays = HashMap<Long, String>()
        val placeholders = ids.keys.joinToString(",") { "?" }
        // 只取电话和生日两种数据行
        val selection = "${ContactsContract.Data.CONTACT_ID} IN ($placeholders) AND (" +
            "${ContactsContract.Data.MIMETYPE} = ? OR (${ContactsContract.Data.MIMETYPE} = ? AND " +
            "${ContactsContract.CommonDataKinds.Event.TYPE} = ${ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY}))"
        val selectionArgs = ids.keys.map { it.toString() } + listOf(
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
        )
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1),
            selection, selectionArgs.toTypedArray(), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val value = c.getString(2)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                if (c.getString(1) == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE) {
                    val list = phones.getOrPut(id) { mutableListOf() }
                    if (list.none { it.filter(Char::isDigit) == value.filter(Char::isDigit) }) list += value
                } else {
                    birthdays.putIfAbsent(id, value)
                }
            }
        }
        ids.map { (id, display) -> ContactInfo(display, phones[id].orEmpty(), birthdays[id]) }
    }

    override fun dial(number: String): WriteOutcome {
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return WriteOutcome.NO_APP
        return try {
            context.startActivity(intent)
            WriteOutcome.OK
        } catch (e: ActivityNotFoundException) {
            WriteOutcome.NO_APP
        } catch (e: Exception) {
            WriteOutcome.FAILED
        }
    }
}
