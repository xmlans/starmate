package org.xmyun.starmate.core.ui.theme

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// 进程内唯一的 DataStore 实例，文件名 theme_prefs。
private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "theme_prefs")

/** 记住用户选的主题色。Hilt 单例，也可以直接用 ThemePrefs(context) 构造，底层共用同一个 DataStore。 */
@Singleton
class ThemePrefs internal constructor(private val store: DataStore<Preferences>) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(context.applicationContext.themeDataStore)

    /** 当前主题色。没选过或读到未知值时为 ThemeAccent.Default（浅粉）。 */
    val accent: Flow<ThemeAccent> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { ThemeAccent.fromName(it[KEY_ACCENT]) }
        .distinctUntilChanged()

    suspend fun setAccent(accent: ThemeAccent) {
        store.edit { it[KEY_ACCENT] = accent.name }
    }

    internal companion object {
        val KEY_ACCENT = stringPreferencesKey("accent")
    }
}
