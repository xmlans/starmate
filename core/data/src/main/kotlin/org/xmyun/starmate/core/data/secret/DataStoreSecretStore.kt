package org.xmyun.starmate.core.data.secret

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.xmyun.starmate.core.data.di.SecretsDataStore
import org.xmyun.starmate.core.model.SecretStore

/** 值经 [SecretCipher] 加密后 Base64 存进名为 `secrets` 的 Preferences DataStore。 */
@Singleton
class DataStoreSecretStore @Inject constructor(
    @SecretsDataStore private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) : SecretStore {

    override suspend fun get(name: String): String? {
        val prefs = dataStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .first()
        val encoded = prefs[keyOf(name)] ?: return null
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return withContext(Dispatchers.IO) { cipher.decrypt(bytes) }?.toString(Charsets.UTF_8)
    }

    override suspend fun set(name: String, value: String?) {
        val key = keyOf(name)
        if (value == null) {
            dataStore.edit { it.remove(key) }
            return
        }
        val encrypted = withContext(Dispatchers.IO) { cipher.encrypt(value.toByteArray(Charsets.UTF_8)) }
        val encoded = Base64.getEncoder().encodeToString(encrypted)
        dataStore.edit { it[key] = encoded }
    }

    private fun keyOf(name: String) = stringPreferencesKey(name)

    companion object {
        const val DATASTORE_NAME = "secrets"
    }
}
