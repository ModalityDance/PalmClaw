package com.palmclaw.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.WindowCompat
import com.palmclaw.ui.theme.PalmClawTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val vm: ChatViewModel = viewModel(factory = ChatViewModel.factory(application))
            val chromeState = vm.chromeState.collectAsStateWithLifecycle().value
            CompositionLocalProvider(
                LocalUiLanguage provides if (chromeState.useChinese) UiLanguage.Chinese else UiLanguage.English
            ) {
                PalmClawTheme(darkTheme = chromeState.darkTheme) {
                    ChatScreen(vm = vm)
                }
            }
        }
    }
}
