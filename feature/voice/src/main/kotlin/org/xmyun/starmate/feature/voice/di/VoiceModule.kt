package org.xmyun.starmate.feature.voice.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import org.xmyun.starmate.core.model.DataPortable
import org.xmyun.starmate.core.model.PromptContributor
import org.xmyun.starmate.core.model.ReplyPostProcessor
import org.xmyun.starmate.core.model.TurnListener
import org.xmyun.starmate.core.ui.contract.AttachmentRenderer
import org.xmyun.starmate.core.ui.contract.ChatInputAction
import org.xmyun.starmate.core.ui.contract.FeatureEntry
import org.xmyun.starmate.feature.voice.AndroidVoiceLocale
import org.xmyun.starmate.feature.voice.RawVoicePromptTexts
import org.xmyun.starmate.feature.voice.VoiceDataPortable
import org.xmyun.starmate.feature.voice.VoiceFeatureEntry
import org.xmyun.starmate.feature.voice.VoiceLocale
import org.xmyun.starmate.feature.voice.VoicePostProcessor
import org.xmyun.starmate.feature.voice.VoicePromptContributor
import org.xmyun.starmate.feature.voice.VoicePromptTexts
import org.xmyun.starmate.feature.voice.cache.AudioDurationProbe
import org.xmyun.starmate.feature.voice.cache.MediaDurationProbe
import org.xmyun.starmate.feature.voice.play.ExoVoicePlayer
import org.xmyun.starmate.feature.voice.play.VoicePlayer
import org.xmyun.starmate.feature.voice.stt.DefaultSttEngineFactory
import org.xmyun.starmate.feature.voice.stt.MediaRecorderRecorder
import org.xmyun.starmate.feature.voice.stt.MicInputAction
import org.xmyun.starmate.feature.voice.stt.Recorder
import org.xmyun.starmate.feature.voice.stt.SttEngineFactory
import org.xmyun.starmate.feature.voice.tts.DefaultTtsEngineProvider
import org.xmyun.starmate.feature.voice.tts.TtsEngineProvider
import org.xmyun.starmate.feature.voice.tts.VoiceJobSink
import org.xmyun.starmate.feature.voice.tts.VoiceMessageLocator
import org.xmyun.starmate.feature.voice.tts.VoiceSynthesizer
import org.xmyun.starmate.feature.voice.ui.VoiceAttachmentRenderer

@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceModule {

    // ---- 对底座的自注册 ----

    @Binds @IntoSet
    abstract fun postProcessor(impl: VoicePostProcessor): ReplyPostProcessor

    @Binds @IntoSet
    abstract fun promptContributor(impl: VoicePromptContributor): PromptContributor

    @Binds @IntoSet
    abstract fun turnListener(impl: VoiceMessageLocator): TurnListener

    @Binds @IntoSet
    abstract fun renderer(impl: VoiceAttachmentRenderer): AttachmentRenderer

    @Binds @IntoSet
    abstract fun inputAction(impl: MicInputAction): ChatInputAction

    @Binds @IntoSet
    abstract fun entry(impl: VoiceFeatureEntry): FeatureEntry

    @Binds @IntoSet
    abstract fun dataPortable(impl: VoiceDataPortable): DataPortable

    // ---- 模块内部的接口 ----

    @Binds
    abstract fun ttsEngines(impl: DefaultTtsEngineProvider): TtsEngineProvider

    @Binds
    abstract fun jobSink(impl: VoiceSynthesizer): VoiceJobSink

    @Binds
    abstract fun durationProbe(impl: MediaDurationProbe): AudioDurationProbe

    @Binds
    abstract fun player(impl: ExoVoicePlayer): VoicePlayer

    @Binds
    abstract fun recorder(impl: MediaRecorderRecorder): Recorder

    @Binds
    abstract fun sttEngines(impl: DefaultSttEngineFactory): SttEngineFactory

    @Binds
    abstract fun locale(impl: AndroidVoiceLocale): VoiceLocale

    @Binds
    abstract fun promptTexts(impl: RawVoicePromptTexts): VoicePromptTexts
}
