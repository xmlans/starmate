package org.xmyun.starmate.feature.mobileuse.service

import android.content.Context
import android.provider.Telephony
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.ModelRouter
import org.xmyun.starmate.core.model.UsageLedger
import org.xmyun.starmate.feature.mobileuse.R
import org.xmyun.starmate.feature.mobileuse.agent.PhoneTaskRunner
import org.xmyun.starmate.feature.mobileuse.agent.PolicyProvider
import org.xmyun.starmate.feature.mobileuse.agent.RawPromptSource
import org.xmyun.starmate.feature.mobileuse.guard.BlockList
import org.xmyun.starmate.feature.mobileuse.guard.Guard
import org.xmyun.starmate.feature.mobileuse.guard.Policy
import org.xmyun.starmate.feature.mobileuse.log.RoomActionLogger
import org.xmyun.starmate.feature.mobileuse.overlay.AccessibilityTaskOverlay
import org.xmyun.starmate.feature.mobileuse.prefs.MobileUsePrefs
import org.xmyun.starmate.feature.mobileuse.tool.PhoneTaskRunnerFactory

/** 从 res/raw/blocked_packages.txt 读内置黑名单，进程内只解析一次。 */
@Singleton
class BlockListSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val blockList: BlockList by lazy {
        val text = context.resources.openRawResource(R.raw.blocked_packages)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        BlockList.parse(text)
    }
}

/** 每一步重新取白名单、默认短信应用和桌面应用。 */
class AndroidPolicyProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: MobileUsePrefs,
    private val blockLists: BlockListSource,
    private val labels: AppLabels,
) : PolicyProvider {
    override suspend fun current(): Policy = withContext(Dispatchers.Default) {
        Policy(
            allowlist = prefs.currentAllowlist(),
            blockList = blockLists.blockList,
            ownPackage = context.packageName,
            defaultSmsPackage = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull(),
            launcherPackages = runCatching { labels.launcherPackages() }.getOrDefault(emptySet()),
            labelOf = { labels.labelOf(it) },
        )
    }
}

class DefaultRunnerFactory @Inject constructor(
    private val router: ModelRouter,
    private val ledger: UsageLedger,
    private val device: AccessibilityDeviceController,
    private val overlay: AccessibilityTaskOverlay,
    private val guard: Guard,
    private val policies: AndroidPolicyProvider,
    private val logger: RoomActionLogger,
    private val prompts: RawPromptSource,
    private val clock: Clock,
    private val prefs: MobileUsePrefs,
    private val diag: DiagLog,
    private val returner: AndroidAppReturner,
) : PhoneTaskRunnerFactory {
    override suspend fun create(): PhoneTaskRunner = PhoneTaskRunner(
        router = router,
        ledger = ledger,
        device = device,
        overlay = overlay,
        guard = guard,
        policies = policies,
        logger = logger,
        prompts = prompts,
        clock = clock,
        limits = prefs.limits.first(),
        diag = diag,
        returner = returner,
    )
}
