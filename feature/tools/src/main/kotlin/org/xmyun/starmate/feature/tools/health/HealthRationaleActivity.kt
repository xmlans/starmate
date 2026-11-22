package org.xmyun.starmate.feature.tools.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.xmyun.starmate.core.ui.component.SettingsScaffold
import org.xmyun.starmate.core.ui.theme.StarmateTheme
import org.xmyun.starmate.feature.tools.R

/** Health Connect 要求的权限说明页：说明读哪些数据、怎么用。 */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StarmateTheme {
                SettingsScaffold(title = stringResource(R.string.tools_hc_rationale_title), onBack = { finish() }) { padding ->
                    Column(
                        Modifier
                            .padding(padding)
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(stringResource(R.string.tools_hc_rationale_body), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
