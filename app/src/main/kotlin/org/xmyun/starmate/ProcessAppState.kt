package org.xmyun.starmate

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.xmyun.starmate.core.model.AppState

/** 整个进程是否有界面在前台。 */
@Singleton
class ProcessAppState @Inject constructor() : AppState, DefaultLifecycleObserver {

    private val foreground = MutableStateFlow(false)
    override val isForeground: StateFlow<Boolean> = foreground

    init {
        // addObserver 必须在主线程调用，而这个类可能在后台线程被首次注入。
        val register = Runnable { ProcessLifecycleOwner.get().lifecycle.addObserver(this) }
        if (Looper.myLooper() == Looper.getMainLooper()) register.run()
        else Handler(Looper.getMainLooper()).post(register)
    }

    override fun onStart(owner: LifecycleOwner) {
        foreground.value = true
    }

    override fun onStop(owner: LifecycleOwner) {
        foreground.value = false
    }
}
