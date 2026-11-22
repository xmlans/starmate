package org.xmyun.starmate

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.xmyun.starmate.core.ui.contract.AppNavigator

@Singleton
class DefaultAppNavigator @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppNavigator {

    override fun intentFor(route: String): Intent =
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    companion object {
        const val EXTRA_ROUTE = "route"
    }
}
