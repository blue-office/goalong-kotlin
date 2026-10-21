package me.goalong.sample

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.goalong.sdk.ApplyMethod
import me.goalong.sdk.GoAlong
import me.goalong.sdk.compose.GoAlongCodeController
import me.goalong.sdk.compose.GoAlongCodeInput
import me.goalong.sdk.compose.GoAlongReferralEntry

class MainActivity : ComponentActivity() {
    private var deepLinkCode by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                SampleScreen(deepLinkCode)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.data?.let { uri -> GoAlong.handleDeepLink(uri)?.let { deepLinkCode = it } }
    }
}

@Composable
private fun SampleScreen(deepLinkCode: String?) {
    val scope = rememberCoroutineScope()
    val controller = remember { GoAlongCodeController(scope) }
    DisposableEffect(controller) { onDispose { controller.dispose() } }
    LaunchedEffect(deepLinkCode) {
        deepLinkCode?.let { controller.fill(it, ApplyMethod.DEEP_LINK) }
    }
    var events by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Unit) {
        GoAlong.events.collect { events = events + it.toString() }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("GoAlong SDK sample", style = MaterialTheme.typography.headlineSmall)

            Text("GoAlongReferralEntry", style = MaterialTheme.typography.labelLarge)
            // 受付中のコードが無い(偽の appId では取得に失敗する)ときは何も出さない
            GoAlongReferralEntry()

            HorizontalDivider()

            Text("GoAlongCodeInput", style = MaterialTheme.typography.labelLarge)
            GoAlongCodeInput(controller = controller, onSkip = {})

            HorizontalDivider()

            Text(
                text = buildString {
                    appendLine("appId: ${BuildConfig.GOALONG_APP_ID}")
                    appendLine("installReferrer: ${GoAlong.installReferrerState}")
                    appendLine("attributes: ${runCatching { GoAlong.attributionAttributes() }.getOrElse { it.toString() }}")
                    events.forEach { appendLine("event: $it") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
