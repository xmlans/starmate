package org.xmyun.starmate

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import org.xmyun.starmate.core.model.AppState

@HiltAndroidApp
class StarmateApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    // 启动时就注入，保证前后台状态从进程一开始就被观察。
    @Inject
    lateinit var appState: AppState

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
