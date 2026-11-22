package org.xmyun.starmate

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import org.xmyun.starmate.core.model.AppEdition
import org.xmyun.starmate.core.ui.contract.FeatureEntry
import org.xmyun.starmate.core.ui.contract.LocalAppEdition
import org.xmyun.starmate.core.ui.theme.StarmateTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var entries: Set<@JvmSuppressWildcards FeatureEntry>

    @Inject
    lateinit var edition: AppEdition

    // 通知等外部入口要求打开的页面，导航完成后清空。
    private val pendingRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            pendingRoute.value = intent.routeExtra()
        }
        setContent {
            CompositionLocalProvider(LocalAppEdition provides edition) {
                StarmateTheme {
                    val route by pendingRoute.collectAsState()
                    MainScaffold(
                        entries = entries,
                        pendingRoute = route,
                        onRouteConsumed = { pendingRoute.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRoute.value = intent.routeExtra()
    }

    private fun Intent.routeExtra(): String? = getStringExtra(DefaultAppNavigator.EXTRA_ROUTE)
}
