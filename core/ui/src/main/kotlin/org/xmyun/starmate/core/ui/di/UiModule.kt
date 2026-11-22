package org.xmyun.starmate.core.ui.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import org.xmyun.starmate.core.ui.contract.AttachmentRenderer
import org.xmyun.starmate.core.ui.contract.ChatInputAction
import org.xmyun.starmate.core.ui.contract.FeatureEntry
import org.xmyun.starmate.core.ui.contract.PermissionInvite

/** 声明 UI 契约的多重绑定集合，没有任何模块贡献时注入空集合。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class UiModule {
    @Multibinds
    abstract fun attachmentRenderers(): Set<AttachmentRenderer>

    @Multibinds
    abstract fun chatInputActions(): Set<ChatInputAction>

    @Multibinds
    abstract fun featureEntries(): Set<FeatureEntry>

    @Multibinds
    abstract fun permissionInvites(): Set<PermissionInvite>
}
