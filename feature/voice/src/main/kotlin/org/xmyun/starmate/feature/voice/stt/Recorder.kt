package org.xmyun.starmate.feature.voice.stt

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/** 麦克风录音。只能真机验证。 */
interface Recorder {
    /** 开始录 AAC（m4a）到 target。到达 maxDurationMs 时自动停止并回调 onMaxDuration。 */
    fun start(target: File, maxDurationMs: Int, onMaxDuration: () -> Unit): Boolean

    /** 停止录音，返回录到的文件是否可用。 */
    fun stop(): Boolean

    fun cancel()
}

class MediaRecorderRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) : Recorder {

    private var recorder: MediaRecorder? = null
    private var target: File? = null
    private var autoStopped = false

    override fun start(target: File, maxDurationMs: Int, onMaxDuration: () -> Unit): Boolean {
        cancel()
        target.parentFile?.mkdirs()
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(SAMPLE_RATE)
            r.setAudioEncodingBitRate(BIT_RATE)
            r.setMaxDuration(maxDurationMs)
            r.setOutputFile(target.absolutePath)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    autoStopped = true
                    onMaxDuration()
                }
            }
            r.prepare()
            r.start()
            recorder = r
            this.target = target
            autoStopped = false
            true
        } catch (e: Exception) {
            r.release()
            target.delete()
            false
        }
    }

    override fun stop(): Boolean {
        val r = recorder ?: return false
        val file = target
        recorder = null
        target = null
        val ok = try {
            // 到达上限时录音器已自己停下，再调 stop 会抛异常，但文件是完整的
            if (!autoStopped) r.stop()
            true
        } catch (e: Exception) {
            false
        } finally {
            r.release()
        }
        return ok && file != null && file.isFile && file.length() > 0
    }

    override fun cancel() {
        val r = recorder ?: return
        recorder = null
        try {
            r.stop()
        } catch (e: Exception) {
            // 刚开始就取消时 stop 会抛异常，忽略
        } finally {
            r.release()
        }
        target?.delete()
        target = null
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val BIT_RATE = 32_000
    }
}
