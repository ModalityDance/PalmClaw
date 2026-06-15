package com.palmclaw.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import com.palmclaw.ui.theme.PalmClawTheme
import kotlinx.coroutines.delay

private const val STARTUP_MIN_VISIBLE_MS = 800L
private const val STARTUP_MAX_VISIBLE_MS = 2_200L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val vm: ChatViewModel = viewModel(factory = ChatViewModel.factory(application))
            val chromeState = vm.chromeState.collectAsStateWithLifecycle().value
            val startupState = vm.startupState.collectAsStateWithLifecycle().value
            var startupMinElapsed by remember(startupState.startedAtMs) { mutableStateOf(false) }
            var startupMaxElapsed by remember(startupState.startedAtMs) { mutableStateOf(false) }
            LaunchedEffect(startupState.startedAtMs) {
                val elapsed = System.currentTimeMillis() - startupState.startedAtMs
                if (elapsed < STARTUP_MIN_VISIBLE_MS) {
                    delay(STARTUP_MIN_VISIBLE_MS - elapsed)
                }
                startupMinElapsed = true
            }
            LaunchedEffect(startupState.startedAtMs) {
                val elapsed = System.currentTimeMillis() - startupState.startedAtMs
                if (elapsed < STARTUP_MAX_VISIBLE_MS) {
                    delay(STARTUP_MAX_VISIBLE_MS - elapsed)
                }
                startupMaxElapsed = true
            }
            val showStartup = !((startupState.ready && startupMinElapsed) || startupMaxElapsed)
            CompositionLocalProvider(
                LocalUiLanguage provides if (chromeState.useChinese) UiLanguage.Chinese else UiLanguage.English
            ) {
                PalmClawTheme(darkTheme = chromeState.darkTheme) {
                    if (showStartup) {
                        StartupScreen(message = startupState.message)
                    } else {
                        ChatScreen(vm = vm)
                    }
                }
            }
        }
    }
}
