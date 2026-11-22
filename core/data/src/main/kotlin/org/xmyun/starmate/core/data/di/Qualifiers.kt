package org.xmyun.starmate.core.data.di

import javax.inject.Qualifier

/** 名为 `secrets` 的 Preferences DataStore，只给 DataStoreSecretStore 用。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SecretsDataStore

/** 名为 `companion_prefs` 的 Preferences DataStore。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CompanionPrefsDataStore
