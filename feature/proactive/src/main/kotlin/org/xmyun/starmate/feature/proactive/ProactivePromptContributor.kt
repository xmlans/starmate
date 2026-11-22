package org.xmyun.starmate.feature.proactive

import javax.inject.Inject
import kotlinx.coroutines.flow.first
import org.xmyun.starmate.core.model.PromptContributor
import org.xmyun.starmate.core.model.PromptOrder
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.feature.proactive.platform.ProactiveTexts
import org.xmyun.starmate.feature.proactive.prefs.ProactivePrefs

/** 聊天时告诉她什么时候该约、什么时候不该约。固定文字，逐字不变。 */
class ProactivePromptContributor @Inject constructor(
    private val prefs: ProactivePrefs,
    private val texts: ProactiveTexts,
) : PromptContributor {
    override val order: Int = PromptOrder.PROACTIVE

    override suspend fun stable(purpose: TurnPurpose): String? =
        if (purpose == TurnPurpose.CHAT && prefs.enabled.first()) texts.chatGuide() else null
}
