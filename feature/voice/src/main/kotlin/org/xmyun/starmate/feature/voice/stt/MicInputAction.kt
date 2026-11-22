package org.xmyun.starmate.feature.voice.stt

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import javax.inject.Inject
import org.xmyun.starmate.core.ui.contract.ChatInputAction
import org.xmyun.starmate.feature.voice.R

/** 输入栏的麦克风按钮：按一下开始说，再按一下结束，识别结果填进输入框。 */
class MicInputAction @Inject constructor(
    private val controller: MicInputController,
) : ChatInputAction {

    override val order: Int = 10

    @Composable
    override fun Button(insertText: (String) -> Unit) {
        val context = LocalContext.current
        val state by controller.state.collectAsState()
        val currentInsert by rememberUpdatedState(insertText)

        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                controller.toggle()
            } else {
                Toast.makeText(context, R.string.voice_mic_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }

        LaunchedEffect(controller) {
            controller.events.collect { event ->
                when (event) {
                    is MicEvent.Recognized -> currentInsert(event.text)
                    is MicEvent.Failed -> Toast.makeText(context, failureMessage(event.kind), Toast.LENGTH_SHORT).show()
                }
            }
        }
        DisposableEffect(controller) {
            onDispose { controller.cancel() }
        }

        val onClick = {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (state == MicState.IDLE && !granted) {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                controller.toggle()
            }
        }

        when (state) {
            MicState.IDLE -> IconButton(onClick = onClick) {
                Icon(Icons.Outlined.Mic, contentDescription = stringResource(R.string.voice_mic_start))
            }
            MicState.RECORDING -> {
                val pulse by rememberInfiniteTransition(label = "mic").animateFloat(
                    initialValue = 1f,
                    targetValue = 0.45f,
                    animationSpec = infiniteRepeatable(tween(durationMillis = 700), RepeatMode.Reverse),
                    label = "micPulse",
                )
                IconButton(
                    onClick = onClick,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(
                        Icons.Outlined.Stop,
                        contentDescription = stringResource(R.string.voice_mic_stop),
                        modifier = Modifier.alpha(pulse),
                    )
                }
            }
            MicState.TRANSCRIBING -> {
                val description = stringResource(R.string.voice_mic_transcribing)
                IconButton(onClick = {}, enabled = false) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp).semantics { contentDescription = description },
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
    }

    private fun failureMessage(kind: SttFailure): Int = when (kind) {
        SttFailure.NO_SPEECH -> R.string.voice_mic_no_speech
        SttFailure.NETWORK -> R.string.voice_mic_network
        SttFailure.UNAVAILABLE -> R.string.voice_mic_unavailable
        SttFailure.SERVICE, SttFailure.RECORDER, SttFailure.UNKNOWN -> R.string.voice_mic_failed
    }
}
