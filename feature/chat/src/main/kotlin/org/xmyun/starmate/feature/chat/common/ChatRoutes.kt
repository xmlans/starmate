package org.xmyun.starmate.feature.chat.common

import org.xmyun.starmate.core.ui.contract.StarmateRoutes

/** 本模块的全部路由。 */
object ChatRoutes {
    const val CHAT = StarmateRoutes.CHAT
    const val SETTINGS = StarmateRoutes.SETTINGS

    const val ONBOARDING_WELCOME = "onboarding/welcome"
    const val ONBOARDING_ADULT = "onboarding/adult"
    const val ONBOARDING_MODEL = "onboarding/model"
    const val ONBOARDING_COMPANION = "onboarding/companion"
    const val ONBOARDING_PERMISSIONS = "onboarding/permissions"

    const val SETTINGS_MODELS = "settings/models"
    const val PROVIDER_ARG = "providerId"
    const val SETTINGS_PROVIDER = "settings/models/provider/{$PROVIDER_ARG}"
    const val NEW_PROVIDER = "new"
    const val SETTINGS_COMPANION = "settings/companion"
    const val SETTINGS_APPEARANCE = "settings/appearance"
    const val SETTINGS_USAGE = "settings/usage"
    const val SETTINGS_DATA = "settings/data"
    const val SETTINGS_DIAG = "settings/diag"
    const val SETTINGS_CONSENTS = "settings/consents"

    fun provider(id: String?): String = "settings/models/provider/${id ?: NEW_PROVIDER}"
}
