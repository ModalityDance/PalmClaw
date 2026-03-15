package com.palmclaw.ui

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.palmclaw.R
import com.palmclaw.config.AppLimits
import com.palmclaw.config.AppSession
import com.palmclaw.providers.ProviderCatalog
import com.palmclaw.tools.AndroidUserActionBridge
import com.palmclaw.tools.AndroidUserActionRequester
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private fun openExternalUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
private fun channelDisplayLabel(channel: String): String {
    return when (channel.trim().lowercase(Locale.getDefault())) {
        "telegram" -> uiLabel("Telegram")
        "discord" -> uiLabel("Discord")
        "slack" -> uiLabel("Slack")
        "feishu" -> uiLabel("Feishu")
        "email" -> uiLabel("Email")
        "wecom" -> uiLabel("WeCom")
        else -> uiLabel(
            channel.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
            }
        )
    }
}

@Composable
private fun uiLabel(text: String): String =
    localizedText(text, useChinese = LocalUiLanguage.current == UiLanguage.Chinese)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel) {
    val context = LocalContext.current
    val state by vm.uiState.collectAsStateWithLifecycle()
    val isChinese = state.settingsUseChinese
    if (!state.onboardingCompleted) {
        var onboardingStepName by rememberSaveable { mutableStateOf(OnboardingStep.Language.name) }
        val onboardingStep = runCatching { OnboardingStep.valueOf(onboardingStepName) }
            .getOrDefault(OnboardingStep.Language)
        FirstRunOnboardingScreen(
            state = state,
            step = onboardingStep,
            onStepChange = { onboardingStepName = it.name },
            onLanguageSelected = vm::setUiLanguage,
            onProviderChange = vm::onSettingsProviderChanged,
            onBaseUrlChange = vm::onSettingsBaseUrlChanged,
            onModelChange = vm::onSettingsModelChanged,
            onApiKeyChange = vm::onSettingsApiKeyChanged,
            onUserDisplayNameChange = vm::onOnboardingUserDisplayNameChanged,
            onAgentDisplayNameChange = vm::onOnboardingAgentDisplayNameChanged,
            onTestProvider = vm::testProviderSettings,
            onComplete = vm::completeOnboarding
        )
        return
    }
    val settingsSnackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val uiScope = rememberCoroutineScope()
    val hostActivity = context as? ComponentActivity
    var pendingPermissionResult by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var pendingBluetoothEnableResult by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var pendingUserConfirmResult by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var pendingUserConfirmTitle by remember { mutableStateOf("") }
    var pendingUserConfirmMessage by remember { mutableStateOf("") }
    var pendingUserConfirmLabel by remember {
        mutableStateOf(localizedText("Continue", useChinese = isChinese))
    }
    var pendingUserCancelLabel by remember {
        mutableStateOf(localizedText("Cancel", useChinese = isChinese))
    }
    val requestPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val grantedAll = result.values.all { it }
        pendingPermissionResult?.invoke(grantedAll)
        pendingPermissionResult = null
    }
    val requestEnableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val enabled = result.resultCode == Activity.RESULT_OK
        pendingBluetoothEnableResult?.invoke(enabled)
        pendingBluetoothEnableResult = null
    }
    val displayedAssistantText = remember { mutableStateMapOf<Long, String>() }
    val seenMessageIds = remember { mutableStateMapOf<Long, Boolean>() }
    var initializedMessages by rememberSaveable { mutableStateOf(false) }
    var generationAnchorMessageId by rememberSaveable { mutableStateOf<Long?>(null) }
    var mainSurfaceName by rememberSaveable { mutableStateOf(MainSurface.Chat.name) }
    var revealApiKey by rememberSaveable { mutableStateOf(false) }
    var showHeartbeatEditor by rememberSaveable { mutableStateOf(false) }
    var showCreateSessionDialog by rememberSaveable { mutableStateOf(false) }
    var createSessionName by rememberSaveable { mutableStateOf("") }
    var pendingRenameSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameSessionName by rememberSaveable { mutableStateOf("") }
    var pendingDeleteSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var sessionSettingsSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var sessionSettingsPageName by rememberSaveable { mutableStateOf(SessionSettingsPage.Menu.name) }
    var bindingEnabledDraft by rememberSaveable { mutableStateOf(true) }
    var bindingChannelDraft by rememberSaveable { mutableStateOf("") }
    var bindingChatIdDraft by rememberSaveable { mutableStateOf("") }
    var bindingTelegramBotTokenDraft by rememberSaveable { mutableStateOf("") }
    var bindingTelegramAllowedChatIdDraft by rememberSaveable { mutableStateOf("") }
    var bindingDiscordBotTokenDraft by rememberSaveable { mutableStateOf("") }
    var bindingDiscordResponseModeDraft by rememberSaveable { mutableStateOf("mention") }
    var bindingDiscordAllowedUserIdsDraft by rememberSaveable { mutableStateOf("") }
    var bindingSlackBotTokenDraft by rememberSaveable { mutableStateOf("") }
    var bindingSlackAppTokenDraft by rememberSaveable { mutableStateOf("") }
    var bindingSlackResponseModeDraft by rememberSaveable { mutableStateOf("mention") }
    var bindingSlackAllowedUserIdsDraft by rememberSaveable { mutableStateOf("") }
    var bindingFeishuAppIdDraft by rememberSaveable { mutableStateOf("") }
    var bindingFeishuAppSecretDraft by rememberSaveable { mutableStateOf("") }
    var bindingFeishuEncryptKeyDraft by rememberSaveable { mutableStateOf("") }
    var bindingFeishuVerificationTokenDraft by rememberSaveable { mutableStateOf("") }
    var bindingFeishuAllowedOpenIdsDraft by rememberSaveable { mutableStateOf("") }
    var bindingEmailConsentGrantedDraft by rememberSaveable { mutableStateOf(true) }
    var bindingEmailImapHostDraft by rememberSaveable { mutableStateOf("imap.gmail.com") }
    var bindingEmailImapPortDraft by rememberSaveable { mutableStateOf("993") }
    var bindingEmailImapUsernameDraft by rememberSaveable { mutableStateOf("") }
    var bindingEmailImapPasswordDraft by rememberSaveable { mutableStateOf("") }
    var bindingEmailSmtpHostDraft by rememberSaveable { mutableStateOf("smtp.gmail.com") }
    var bindingEmailSmtpPortDraft by rememberSaveable { mutableStateOf("587") }
    var bindingEmailSmtpUsernameDraft by rememberSaveable { mutableStateOf("") }
    var bindingEmailSmtpPasswordDraft by rememberSaveable { mutableStateOf("") }
    var bindingEmailFromAddressDraft by rememberSaveable { mutableStateOf("") }
    var bindingEmailAutoReplyEnabledDraft by rememberSaveable { mutableStateOf(true) }
    var bindingWeComBotIdDraft by rememberSaveable { mutableStateOf("") }
    var bindingWeComSecretDraft by rememberSaveable { mutableStateOf("") }
    var bindingWeComAllowedUserIdsDraft by rememberSaveable { mutableStateOf("") }
    var bindingChannelMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var bindingDiscordResponseModeMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var bindingSlackResponseModeMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var telegramAdvancedExpanded by rememberSaveable { mutableStateOf(false) }
    var discordAdvancedExpanded by rememberSaveable { mutableStateOf(false) }
    var slackAdvancedExpanded by rememberSaveable { mutableStateOf(false) }
    var feishuAdvancedExpanded by rememberSaveable { mutableStateOf(false) }
    var emailAdvancedExpanded by rememberSaveable { mutableStateOf(false) }
    var weComAdvancedExpanded by rememberSaveable { mutableStateOf(false) }
    var settingsPageName by rememberSaveable { mutableStateOf(SettingsPanelPage.Home.name) }
    var visibleHistoryRounds by rememberSaveable { mutableStateOf(HISTORY_ROUNDS_PAGE_SIZE) }
    var hasInitialJumpToBottom by rememberSaveable { mutableStateOf(false) }
    var followLatest by rememberSaveable { mutableStateOf(true) }
    var isLoadingOlderHistory by rememberSaveable { mutableStateOf(false) }
    var olderHistoryLoadingStartedAtMs by rememberSaveable { mutableStateOf(0L) }
    var pendingHistoryRestore by remember { mutableStateOf<HistoryRestoreRequest?>(null) }
    val expandedToolMessages = remember { mutableStateMapOf<Long, Boolean>() }
    var previewAudioPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var previewAudioRef by rememberSaveable { mutableStateOf<String?>(null) }
    var previewAudioDurationMs by rememberSaveable { mutableStateOf(0) }
    var previewAudioPositionMs by rememberSaveable { mutableStateOf(0) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val mainSurface = runCatching { MainSurface.valueOf(mainSurfaceName) }
        .getOrDefault(MainSurface.Chat)
    val settingsPage = runCatching { SettingsPanelPage.valueOf(settingsPageName) }
        .getOrDefault(SettingsPanelPage.Home)
    val settingsPageTitle = settingsPage.title(isChinese)
    val settingsPageSubtitle = settingsPage.subtitle(isChinese)
    val sessionSettingsPage = runCatching { SessionSettingsPage.valueOf(sessionSettingsPageName) }
        .getOrDefault(SessionSettingsPage.Menu)
    val dismissSessionSettings = {
        bindingChannelMenuExpanded = false
        bindingDiscordResponseModeMenuExpanded = false
        bindingSlackResponseModeMenuExpanded = false
        vm.clearTelegramChatDiscovery()
        vm.clearFeishuChatDiscovery()
        vm.clearEmailSenderDiscovery()
        vm.clearWeComChatDiscovery()
        telegramAdvancedExpanded = false
        discordAdvancedExpanded = false
        slackAdvancedExpanded = false
        feishuAdvancedExpanded = false
        emailAdvancedExpanded = false
        weComAdvancedExpanded = false
        sessionSettingsSessionId = null
        sessionSettingsPageName = SessionSettingsPage.Menu.name
    }
    val canHandleBack = remember(
        pendingUserConfirmResult,
        showCreateSessionDialog,
        pendingRenameSessionId,
        pendingDeleteSessionId,
        sessionSettingsSessionId,
        sessionSettingsPage,
        showHeartbeatEditor,
        drawerState.currentValue,
        mainSurface,
        settingsPage
    ) {
        pendingUserConfirmResult != null ||
            showCreateSessionDialog ||
            pendingRenameSessionId != null ||
            pendingDeleteSessionId != null ||
            sessionSettingsSessionId != null ||
            showHeartbeatEditor ||
            drawerState.currentValue == DrawerValue.Open ||
            mainSurface == MainSurface.Settings
    }
    BackHandler(enabled = canHandleBack) {
        when {
            pendingUserConfirmResult != null -> {
                val cb = pendingUserConfirmResult
                pendingUserConfirmResult = null
                cb?.invoke(false)
            }
            showCreateSessionDialog -> {
                createSessionName = ""
                showCreateSessionDialog = false
            }
            pendingRenameSessionId != null -> pendingRenameSessionId = null
            pendingDeleteSessionId != null -> pendingDeleteSessionId = null
            sessionSettingsSessionId != null && sessionSettingsPage != SessionSettingsPage.Menu -> {
                bindingChannelMenuExpanded = false
                bindingDiscordResponseModeMenuExpanded = false
                bindingSlackResponseModeMenuExpanded = false
                sessionSettingsPageName = SessionSettingsPage.Menu.name
            }
            sessionSettingsSessionId != null -> dismissSessionSettings()
            showHeartbeatEditor -> showHeartbeatEditor = false
            drawerState.currentValue == DrawerValue.Open -> {
                uiScope.launch { drawerState.close() }
            }
            mainSurface == MainSurface.Settings && settingsPage != SettingsPanelPage.Home -> {
                settingsPageName = SettingsPanelPage.Home.name
            }
            mainSurface == MainSurface.Settings -> {
                mainSurfaceName = MainSurface.Chat.name
                uiScope.launch { drawerState.open() }
            }
        }
    }

    DisposableEffect(hostActivity, state.alwaysOnEnabled, state.alwaysOnKeepScreenAwake) {
        val activity = hostActivity
        if (activity != null) {
            if (state.alwaysOnEnabled && state.alwaysOnKeepScreenAwake) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(mainSurface, settingsPage) {
        if (mainSurface != MainSurface.Settings || settingsPage != SettingsPanelPage.AlwaysOn) return@LaunchedEffect
        while (
            mainSurfaceName == MainSurface.Settings.name &&
            settingsPageName == SettingsPanelPage.AlwaysOn.name
        ) {
            vm.refreshAlwaysOnDiagnostics()
            delay(5_000L)
        }
    }

    val actionRequester = remember(hostActivity) {
        object : AndroidUserActionRequester {
            override fun requestPermissions(
                permissions: Array<String>,
                onResult: (grantedAll: Boolean) -> Unit
            ) {
                if (hostActivity == null) {
                    onResult(false)
                    return
                }
                if (pendingPermissionResult != null) {
                    onResult(false)
                    return
                }
                pendingPermissionResult = onResult
                runCatching { requestPermissionsLauncher.launch(permissions) }
                    .onFailure {
                        pendingPermissionResult = null
                        onResult(false)
                    }
            }

            override fun requestEnableBluetooth(onResult: (enabled: Boolean) -> Unit) {
                if (hostActivity == null) {
                    onResult(false)
                    return
                }
                if (pendingBluetoothEnableResult != null) {
                    onResult(false)
                    return
                }
                pendingBluetoothEnableResult = onResult
                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                runCatching { requestEnableBluetoothLauncher.launch(intent) }
                    .onFailure {
                        pendingBluetoothEnableResult = null
                        onResult(false)
                    }
            }

            override fun openBluetoothSettings(onResult: (opened: Boolean) -> Unit) {
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
                    .onSuccess { onResult(true) }
                    .onFailure { onResult(false) }
            }

            override fun requestUserConfirmation(
                title: String,
                message: String,
                confirmLabel: String,
                cancelLabel: String,
                onResult: (confirmed: Boolean) -> Unit
            ) {
                if (pendingUserConfirmResult != null) {
                    onResult(false)
                    return
                }
                pendingUserConfirmTitle = title
                pendingUserConfirmMessage = message
                pendingUserConfirmLabel = confirmLabel.ifBlank {
                    localizedText("Continue", useChinese = isChinese)
                }
                pendingUserCancelLabel = cancelLabel.ifBlank {
                    localizedText("Cancel", useChinese = isChinese)
                }
                pendingUserConfirmResult = onResult
            }
        }
    }

    DisposableEffect(actionRequester) {
        AndroidUserActionBridge.register(actionRequester)
        onDispose {
            AndroidUserActionBridge.unregister(actionRequester)
            pendingPermissionResult?.invoke(false)
            pendingPermissionResult = null
            pendingBluetoothEnableResult?.invoke(false)
            pendingBluetoothEnableResult = null
            pendingUserConfirmResult?.invoke(false)
            pendingUserConfirmResult = null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { previewAudioPlayer?.stop() }
            runCatching { previewAudioPlayer?.release() }
            previewAudioPlayer = null
            previewAudioRef = null
            previewAudioDurationMs = 0
            previewAudioPositionMs = 0
        }
    }

    if (pendingUserConfirmResult != null) {
        AlertDialog(
            onDismissRequest = {
                val cb = pendingUserConfirmResult
                pendingUserConfirmResult = null
                cb?.invoke(false)
            },
            title = { Text(pendingUserConfirmTitle.ifBlank { uiLabel("Confirm") }) },
            text = { Text(pendingUserConfirmMessage) },
            confirmButton = {
                TextButton(onClick = {
                    val cb = pendingUserConfirmResult
                    pendingUserConfirmResult = null
                    cb?.invoke(true)
                }) {
                    Text(pendingUserConfirmLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    val cb = pendingUserConfirmResult
                    pendingUserConfirmResult = null
                    cb?.invoke(false)
                }) {
                    Text(pendingUserCancelLabel)
                }
            }
        )
    }

    if (showCreateSessionDialog) {
        AlertDialog(
            onDismissRequest = { showCreateSessionDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text(tr("Create Session", "")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = tr(
                            "Create a separate workspace for a task, person, or channel.",
                            ""
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = createSessionName,
                        onValueChange = { createSessionName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(tr("Session Name", "")) },
                        placeholder = { Text(tr("Example: Research", "")) }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.createSession(createSessionName)
                        createSessionName = ""
                        showCreateSessionDialog = false
                        uiScope.launch { drawerState.close() }
                    },
                    enabled = createSessionName.isNotBlank()
                ) {
                    Text(tr("Create", ""))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    createSessionName = ""
                    showCreateSessionDialog = false
                }) {
                    Text(tr("Cancel", ""))
                }
            }
        )
    }

    pendingRenameSessionId?.let { sessionId ->
        val item = state.sessions.firstOrNull { it.id == sessionId && !it.isLocal }
        if (item != null) {
            AlertDialog(
                onDismissRequest = {
                    pendingRenameSessionId = null
                    renameSessionName = ""
                },
                title = { Text(uiLabel("Rename Session")) },
                text = {
                    OutlinedTextField(
                        value = renameSessionName,
                        onValueChange = { renameSessionName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(uiLabel("Session Name")) }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.renameSession(sessionId, renameSessionName)
                        pendingRenameSessionId = null
                        renameSessionName = ""
                    }) {
                        Text(uiLabel("Save"))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        pendingRenameSessionId = null
                        renameSessionName = ""
                    }) {
                        Text(uiLabel("Cancel"))
                    }
                }
            )
        } else {
            pendingRenameSessionId = null
            renameSessionName = ""
        }
    }

    pendingDeleteSessionId?.let { sessionId ->
        val item = state.sessions.firstOrNull { it.id == sessionId }
        if (item != null) {
            AlertDialog(
                onDismissRequest = { pendingDeleteSessionId = null },
                title = { Text(uiLabel("Delete Session")) },
                text = { Text(uiLabel("Delete session '%s'? This cannot be undone.").format(item.title)) },
                confirmButton = {
                    TextButton(onClick = {
                        vm.deleteSession(sessionId)
                        pendingDeleteSessionId = null
                    }) {
                        Text(uiLabel("Delete"))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteSessionId = null }) {
                        Text(uiLabel("Cancel"))
                    }
                }
            )
        } else {
            pendingDeleteSessionId = null
        }
    }

    sessionSettingsSessionId?.let { sessionId ->
        val item = state.sessions.firstOrNull { it.id == sessionId }
        if (item != null) {
            val normalizedChannel = bindingChannelDraft.trim().lowercase()
            val channelLabel = when (normalizedChannel) {
                "telegram" -> uiLabel("Telegram")
                "discord" -> uiLabel("Discord")
                "slack" -> uiLabel("Slack")
                "feishu" -> uiLabel("Feishu")
                "email" -> uiLabel("Email")
                "wecom" -> uiLabel("WeCom")
                else -> uiLabel("None")
            }
            val connected = state.settingsConnectedChannels.firstOrNull { it.sessionId == sessionId }
            val hasPendingDetection = when (normalizedChannel) {
                "feishu" -> bindingFeishuAppIdDraft.isNotBlank() && bindingFeishuAppSecretDraft.isNotBlank() && bindingChatIdDraft.isBlank()
                "email" -> bindingEmailConsentGrantedDraft &&
                    bindingEmailImapHostDraft.isNotBlank() &&
                    bindingEmailImapUsernameDraft.isNotBlank() &&
                    bindingEmailSmtpHostDraft.isNotBlank() &&
                    bindingEmailSmtpUsernameDraft.isNotBlank() &&
                    bindingChatIdDraft.isBlank()
                "wecom" -> bindingWeComBotIdDraft.isNotBlank() && bindingWeComSecretDraft.isNotBlank() && bindingChatIdDraft.isBlank()
                else -> false
            }
            val connectionModeLabel = when {
                item.isLocal && bindingChannelDraft.isBlank() -> uiLabel("Local only")
                bindingChannelDraft.isBlank() -> uiLabel("Local only")
                bindingChatIdDraft.isNotBlank() -> channelLabel
                hasPendingDetection -> if (state.settingsUseChinese) "${channelLabel}（待检测）" else "$channelLabel (pending)"
                else -> channelLabel
            }
            val targetLabel = when {
                bindingChannelDraft.isBlank() -> uiLabel("This session stays local.")
                bindingChatIdDraft.isNotBlank() -> bindingChatIdDraft.trim()
                hasPendingDetection -> uiLabel("Waiting for detection")
                else -> tr("Not set", "")
            }
            val connectionStatusLabel = when {
                bindingChannelDraft.isBlank() -> tr("Local only", "")
                connected?.status?.isNotBlank() == true -> connected.status
                hasPendingDetection -> tr("Pending detection", "")
                bindingEnabledDraft -> tr("Configured", "")
                else -> tr("Off", "")
            }
            val sessionSettingsScrollState = rememberScrollState()
            AlertDialog(
                onDismissRequest = dismissSessionSettings,
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                textContentColor = MaterialTheme.colorScheme.onSurface,
                title = {
                    Text(
                        text = when (sessionSettingsPage) {
                            SessionSettingsPage.Menu -> tr("Session Settings", "")
                            SessionSettingsPage.Configure -> tr("Channels & Configuration", "")
                            SessionSettingsPage.Diagnostics -> tr("Connection Diagnostics", "")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                text = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 460.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(sessionSettingsScrollState),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (sessionSettingsPage == SessionSettingsPage.Menu) {
                                SettingsSectionCard(
                                    title = tr("Connection", ""),
                                    subtitle = tr("Current routing and status.", ""),
                                    actions = {
                                        SettingsSectionIconButton(
                                            icon = Icons.Rounded.Refresh,
                                            contentDescription = tr("Refresh connection status", ""),
                                            onClick = vm::refreshSessionConnectionStatus
                                        )
                                    }
                                ) {
                                    SettingsValueRow(tr("Route", ""), connectionModeLabel)
                                    SettingsValueRow(tr("Target", ""), targetLabel)
                                    SettingsValueRow(
                                        tr("State", ""),
                                        if (bindingEnabledDraft || bindingChannelDraft.isBlank()) tr("Enabled", "") else tr("Off", "")
                                    )
                                    SettingsValueRow(tr("Status", ""), connectionStatusLabel)
                                }
                                SettingsSectionCard(
                                    title = tr("Configure", ""),
                                    subtitle = tr("Channel settings.", ""),
                                    actions = {
                                        SettingsSectionIconButton(
                                            icon = Icons.Rounded.KeyboardArrowUp,
                                            contentDescription = tr("Open channel settings", ""),
                                            onClick = { sessionSettingsPageName = SessionSettingsPage.Configure.name },
                                            rotateZ = 90f
                                        )
                                    }
                                ) {
                                    SettingsValueRow(tr("Channel", ""), channelLabel.ifBlank { tr("Not selected", "") })
                                }
                            } else if (sessionSettingsPage == SessionSettingsPage.Diagnostics) {
                                SettingsSectionCard(
                                    title = tr("Connection", ""),
                                    subtitle = tr("Current routing and status.", "")
                                ) {
                                    SettingsValueRow(tr("Route", ""), connectionModeLabel)
                                    SettingsValueRow(tr("Target", ""), targetLabel)
                                    SettingsValueRow(
                                        tr("State", ""),
                                        if (bindingEnabledDraft || bindingChannelDraft.isBlank()) tr("Enabled", "") else tr("Off", "")
                                    )
                                    SettingsValueRow(tr("Status", ""), connectionStatusLabel)
                                }
                            } else {
                                Surface(
                                    tonalElevation = 1.dp,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = uiLabel("Current Route"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = when {
                                                bindingChannelDraft.isBlank() -> uiLabel("Not connected")
                                                bindingChatIdDraft.isNotBlank() ->
                                                    "${bindingChannelDraft.trim().lowercase()}:${bindingChatIdDraft.trim()}"
                                                bindingChannelDraft.equals("feishu", ignoreCase = true) &&
                                                    bindingFeishuAppIdDraft.isNotBlank() &&
                                                    bindingFeishuAppSecretDraft.isNotBlank() ->
                                                    "${uiLabel("Feishu")}: ${uiLabel("Pending detection")}"
                                                bindingChannelDraft.equals("email", ignoreCase = true) &&
                                                    bindingEmailConsentGrantedDraft &&
                                                    bindingEmailImapHostDraft.isNotBlank() &&
                                                    bindingEmailImapUsernameDraft.isNotBlank() &&
                                                    bindingEmailImapPasswordDraft.isNotBlank() &&
                                                    bindingEmailSmtpHostDraft.isNotBlank() &&
                                                    bindingEmailSmtpUsernameDraft.isNotBlank() &&
                                                    bindingEmailSmtpPasswordDraft.isNotBlank() ->
                                                    "${uiLabel("Email")}: ${uiLabel("Pending detection")}"
                                                bindingChannelDraft.equals("wecom", ignoreCase = true) &&
                                                    bindingWeComBotIdDraft.isNotBlank() &&
                                                    bindingWeComSecretDraft.isNotBlank() ->
                                                    "${uiLabel("WeCom")}: ${uiLabel("Pending detection")}"
                                                else -> uiLabel("Not connected")
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                                ExposedDropdownMenuBox(
                                    expanded = bindingChannelMenuExpanded,
                                    onExpandedChange = { bindingChannelMenuExpanded = it }
                                ) {
                                    OutlinedTextField(
                                        value = channelLabel,
                                        onValueChange = {},
                                        readOnly = true,
                                        modifier = Modifier
                                            .menuAnchor()
                                            .fillMaxWidth(),
                                    label = { Text(uiLabel("Select Channel")) },
                                        trailingIcon = {
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = bindingChannelMenuExpanded)
                                        }
                                    )
                                    ExposedDropdownMenu(
                                        expanded = bindingChannelMenuExpanded,
                                        onDismissRequest = { bindingChannelMenuExpanded = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(uiLabel("None")) },
                                            onClick = {
                                                bindingChannelDraft = ""
                                                bindingChatIdDraft = ""
                                                bindingTelegramBotTokenDraft = ""
                                                bindingTelegramAllowedChatIdDraft = ""
                                                bindingDiscordBotTokenDraft = ""
                                                bindingEnabledDraft = true
                                                bindingDiscordResponseModeDraft = "mention"
                                                bindingDiscordAllowedUserIdsDraft = ""
                                                bindingSlackBotTokenDraft = ""
                                                bindingSlackAppTokenDraft = ""
                                                bindingSlackResponseModeDraft = "mention"
                                                bindingSlackAllowedUserIdsDraft = ""
                                                bindingFeishuAppIdDraft = ""
                                                bindingFeishuAppSecretDraft = ""
                                                bindingFeishuEncryptKeyDraft = ""
                                                bindingFeishuVerificationTokenDraft = ""
                                                bindingFeishuAllowedOpenIdsDraft = ""
                                                bindingEmailConsentGrantedDraft = true
                                                bindingEmailImapHostDraft = "imap.gmail.com"
                                                bindingEmailImapPortDraft = "993"
                                                bindingEmailImapUsernameDraft = ""
                                                bindingEmailImapPasswordDraft = ""
                                                bindingEmailSmtpHostDraft = "smtp.gmail.com"
                                                bindingEmailSmtpPortDraft = "587"
                                                bindingEmailSmtpUsernameDraft = ""
                                                bindingEmailSmtpPasswordDraft = ""
                                                bindingEmailFromAddressDraft = ""
                                                bindingEmailAutoReplyEnabledDraft = true
                                                bindingWeComBotIdDraft = ""
                                                bindingWeComSecretDraft = ""
                                                bindingWeComAllowedUserIdsDraft = ""
                                                bindingSlackResponseModeMenuExpanded = false
                                                bindingChannelMenuExpanded = false
                                                vm.clearTelegramChatDiscovery()
                                                vm.clearFeishuChatDiscovery()
                                                vm.clearEmailSenderDiscovery()
                                                vm.clearWeComChatDiscovery()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(uiLabel("Telegram")) },
                                            onClick = {
                                                bindingChannelDraft = "telegram"
                                                bindingDiscordResponseModeMenuExpanded = false
                                                bindingSlackResponseModeMenuExpanded = false
                                                bindingChannelMenuExpanded = false
                                                vm.clearFeishuChatDiscovery()
                                                vm.clearEmailSenderDiscovery()
                                                vm.clearWeComChatDiscovery()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(uiLabel("Discord")) },
                                            onClick = {
                                                bindingChannelDraft = "discord"
                                                if (bindingDiscordResponseModeDraft.isBlank()) {
                                                    bindingDiscordResponseModeDraft = "mention"
                                                }
                                                bindingSlackResponseModeMenuExpanded = false
                                                bindingChannelMenuExpanded = false
                                                vm.clearTelegramChatDiscovery()
                                                vm.clearFeishuChatDiscovery()
                                                vm.clearEmailSenderDiscovery()
                                                vm.clearWeComChatDiscovery()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(uiLabel("Slack")) },
                                            onClick = {
                                                bindingChannelDraft = "slack"
                                                if (bindingSlackResponseModeDraft.isBlank()) {
                                                    bindingSlackResponseModeDraft = "mention"
                                                }
                                                bindingDiscordResponseModeMenuExpanded = false
                                                bindingChannelMenuExpanded = false
                                                vm.clearTelegramChatDiscovery()
                                                vm.clearFeishuChatDiscovery()
                                                vm.clearEmailSenderDiscovery()
                                                vm.clearWeComChatDiscovery()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(uiLabel("Feishu")) },
                                            onClick = {
                                                bindingChannelDraft = "feishu"
                                                bindingDiscordResponseModeMenuExpanded = false
                                                bindingSlackResponseModeMenuExpanded = false
                                                bindingChannelMenuExpanded = false
                                                vm.clearTelegramChatDiscovery()
                                                vm.clearFeishuChatDiscovery()
                                                vm.clearEmailSenderDiscovery()
                                                vm.clearWeComChatDiscovery()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(uiLabel("Email")) },
                                            onClick = {
                                                bindingChannelDraft = "email"
                                                bindingDiscordResponseModeMenuExpanded = false
                                                bindingSlackResponseModeMenuExpanded = false
                                                bindingChannelMenuExpanded = false
                                                vm.clearTelegramChatDiscovery()
                                                vm.clearFeishuChatDiscovery()
                                                vm.clearEmailSenderDiscovery()
                                                vm.clearWeComChatDiscovery()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(uiLabel("WeCom")) },
                                            onClick = {
                                                bindingChannelDraft = "wecom"
                                                bindingDiscordResponseModeMenuExpanded = false
                                                bindingSlackResponseModeMenuExpanded = false
                                                bindingChannelMenuExpanded = false
                                                vm.clearTelegramChatDiscovery()
                                                vm.clearFeishuChatDiscovery()
                                                vm.clearEmailSenderDiscovery()
                                                vm.clearWeComChatDiscovery()
                                            }
                                        )
                                    }
                                }
                                if (sessionSettingsPage == SessionSettingsPage.Configure && normalizedChannel == "telegram") {
                            SessionSetupStepCard(
                                step = 1,
                                text = uiLabel("Open BotFather, send /newbot, then create a bot and copy its HTTP API token.")
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SettingsActionButton(
                                    text = uiLabel("BotFather"),
                                    icon = Icons.Rounded.Description,
                                    onClick = { openExternalUrl(context, "https://t.me/BotFather") }
                                )
                                SettingsActionButton(
                                    text = uiLabel("Guide"),
                                    icon = Icons.Rounded.Description,
                                    onClick = { openExternalUrl(context, "https://core.telegram.org/bots#6-botfather") }
                                )
                            }
                            SettingsInfoBlock(
                                label = uiLabel("Token example"),
                                value = "123456789:AAExampleBotTokenAbCdEfGhIjKlMnOpQrStUv"
                            )
                            OutlinedTextField(
                                value = bindingTelegramBotTokenDraft,
                                onValueChange = { bindingTelegramBotTokenDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(uiLabel("Telegram Bot Token")) },
                                visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation()
                            )
                            SessionSetupStepCard(
                                step = 2,
                                text = uiLabel("Paste the token, then tap Save at the bottom. This starts Telegram polling.")
                            )
                            SessionSetupStepCard(
                                step = 3,
                                text = uiLabel("From the Telegram account you want to bind, send one message to the bot.")
                            )
                            SessionSetupStepCard(
                                step = 4,
                                text = uiLabel("Tap Detect Chats, choose the conversation, then tap Save again to finish binding.")
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SettingsActionButton(
                                    text = uiLabel("Detect Chats"),
                                    icon = Icons.Rounded.Refresh,
                                    onClick = {
                                        vm.discoverTelegramChatsForBinding(bindingTelegramBotTokenDraft)
                                    },
                                    enabled = bindingTelegramBotTokenDraft.isNotBlank() && !state.sessionBindingTelegramDiscovering
                                )
                                if (state.sessionBindingTelegramDiscovering) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text(
                                        text = uiLabel("Detecting..."),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (state.sessionBindingTelegramCandidates.isNotEmpty()) {
                                state.sessionBindingTelegramCandidates.forEach { candidate ->
                                    Surface(
                                        tonalElevation = 1.dp,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                bindingChatIdDraft = candidate.chatId
                                                bindingTelegramAllowedChatIdDraft = candidate.chatId
                                            }
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = candidate.title,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = "${candidate.kind}: ${candidate.chatId}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    text = uiLabel("No detected chats yet."),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            SettingsAdvancedSection(
                                expanded = telegramAdvancedExpanded,
                                onToggle = { telegramAdvancedExpanded = !telegramAdvancedExpanded }
                            ) {
                                OutlinedTextField(
                                    value = bindingChatIdDraft,
                                    onValueChange = { bindingChatIdDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text(uiLabel("Telegram Chat ID")) },
                                    placeholder = { Text(uiLabel("Filled automatically after Detect Chats")) }
                                )
                                OutlinedTextField(
                                    value = bindingTelegramAllowedChatIdDraft,
                                    onValueChange = { bindingTelegramAllowedChatIdDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text(uiLabel("Allowed Chat ID")) },
                                    placeholder = { Text(uiLabel("Usually same as chat ID")) }
                                )
                            }
                                } else if (sessionSettingsPage == SessionSettingsPage.Configure && normalizedChannel == "discord") {
                            SessionSetupStepCard(
                                step = 1,
                                text = uiLabel("Open the Discord Developer Portal, create an application, then open Bot and add a bot.")
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SettingsActionButton(
                                    text = uiLabel("Developer Portal"),
                                    icon = Icons.Rounded.Description,
                                    onClick = { openExternalUrl(context, "https://discord.com/developers/applications") }
                                )
                            }
                            SettingsInfoBlock(
                                label = uiLabel("Token example"),
                                value = "MTIzNDU2Nzg5MDEyMzQ1Njc4.GExample.AbcDefGhIjKlMnOpQrStUvWxYz"
                            )
                            OutlinedTextField(
                                value = bindingDiscordBotTokenDraft,
                                onValueChange = { bindingDiscordBotTokenDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(uiLabel("Discord Bot Token")) },
                                visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation()
                            )
                            SessionSetupStepCard(
                                step = 2,
                                text = uiLabel("In Bot settings, enable MESSAGE CONTENT INTENT. If you plan to use an allow list, enable SERVER MEMBERS INTENT too.")
                            )
                            SessionSetupStepCard(
                                step = 3,
                                text = uiLabel("Invite the bot to your server from OAuth2 URL Generator. Use scope bot and permissions Send Messages and Read Message History.")
                            )
                            SessionSetupStepCard(
                                step = 4,
                                text = uiLabel("Enable Developer Mode in Discord. Right-click your avatar and Copy User ID if you want an allow list. Right-click the target channel and Copy Channel ID.")
                            )
                            SettingsInfoBlock(
                                label = uiLabel("User ID example"),
                                value = "123456789012345678"
                            )
                            OutlinedTextField(
                                value = bindingChatIdDraft,
                                onValueChange = { bindingChatIdDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(uiLabel("Target Channel ID")) },
                                placeholder = { Text(uiLabel("Example: 123456789012345678")) }
                            )
                            SessionSetupStepCard(
                                step = 5,
                                text = uiLabel("Choose how the bot should respond in this channel.")
                            )
                            ExposedDropdownMenuBox(
                                expanded = bindingDiscordResponseModeMenuExpanded,
                                onExpandedChange = { bindingDiscordResponseModeMenuExpanded = it }
                            ) {
                                OutlinedTextField(
                                    value = uiLabel(bindingDiscordResponseModeDraft.ifBlank { "mention" }),
                                    onValueChange = {},
                                    readOnly = true,
                                    modifier = Modifier
                                        .menuAnchor()
                                        .fillMaxWidth(),
                                    label = { Text(uiLabel("Response Mode")) },
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = bindingDiscordResponseModeMenuExpanded)
                                    },
                                    singleLine = true
                                )
                                ExposedDropdownMenu(
                                    expanded = bindingDiscordResponseModeMenuExpanded,
                                    onDismissRequest = { bindingDiscordResponseModeMenuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                            text = { Text(uiLabel("mention")) },
                                        onClick = {
                                            bindingDiscordResponseModeDraft = "mention"
                                            bindingDiscordResponseModeMenuExpanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                            text = { Text(uiLabel("open")) },
                                        onClick = {
                                            bindingDiscordResponseModeDraft = "open"
                                            bindingDiscordResponseModeMenuExpanded = false
                                        }
                                    )
                                }
                            }
                            SettingsInfoBlock(
                                label = uiLabel("Response modes"),
                                value = uiLabel("mention: reply only when @mentioned. open: reply to all messages in this channel.")
                            )
                            SessionSetupStepCard(
                                step = 6,
                                text = uiLabel("Optional: add Allowed User IDs. If set, only those users can trigger replies. DMs still require the sender to be in the allow list.")
                            )
                            SettingsAdvancedSection(
                                expanded = discordAdvancedExpanded,
                                onToggle = { discordAdvancedExpanded = !discordAdvancedExpanded }
                            ) {
                                OutlinedTextField(
                                    value = bindingDiscordAllowedUserIdsDraft,
                                    onValueChange = { bindingDiscordAllowedUserIdsDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2,
                                    maxLines = 4,
                                    label = { Text(uiLabel("Allowed User IDs")) },
                                    placeholder = { Text(uiLabel("One ID per line or comma-separated")) }
                                )
                            }
                            SessionSetupStepCard(
                                step = 7,
                                text = uiLabel("After filling the fields, tap Save at the bottom to start the Discord connection.")
                            )
                                } else if (sessionSettingsPage == SessionSettingsPage.Configure && normalizedChannel == "slack") {
                            SessionSetupStepCard(
                                step = 1,
                                text = uiLabel("Create a Slack app from scratch, then enable Socket Mode.")
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SettingsActionButton(
                                    text = uiLabel("Slack API"),
                                    icon = Icons.Rounded.Description,
                                    onClick = { openExternalUrl(context, "https://api.slack.com/apps") }
                                )
                            }
                            SessionSetupStepCard(
                                step = 2,
                                text = uiLabel("Turn on Socket Mode, then create an app-level token with connections:write.")
                            )
                            SettingsInfoBlock(
                                label = uiLabel("App token example"),
                                value = "xapp-1-A1234567890-1234567890-abcdefghijklmnopqrstuvwxyz"
                            )
                            OutlinedTextField(
                                value = bindingSlackAppTokenDraft,
                                onValueChange = { bindingSlackAppTokenDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(uiLabel("Slack App Token (xapp)")) },
                                visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation()
                            )
                            SessionSetupStepCard(
                                step = 3,
                                text = uiLabel("Add bot scopes chat:write, reactions:write, app_mentions:read, then enable Event Subscriptions for message and app mention events.")
                            )
                            SessionSetupStepCard(
                                step = 4,
                                text = uiLabel("Install the app to your workspace, then copy the bot token.")
                            )
                            SettingsInfoBlock(
                                label = uiLabel("Bot token example"),
                                value = "xoxb-123456789012-123456789012-abcdefghijklmnopqrstuvwxyz"
                            )
                            OutlinedTextField(
                                value = bindingSlackBotTokenDraft,
                                onValueChange = { bindingSlackBotTokenDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(uiLabel("Slack Bot Token (xoxb)")) },
                                visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation()
                            )
                            SessionSetupStepCard(
                                step = 5,
                                text = uiLabel("Enter the target conversation ID. Slack channel, group, and DM IDs usually start with C, G, or D.")
                            )
                            OutlinedTextField(
                                value = bindingChatIdDraft,
                                onValueChange = { bindingChatIdDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(uiLabel("Target Channel ID")) },
                                placeholder = { Text(uiLabel("Example: C123ABC45 or D123ABC45")) }
                            )
                            SessionSetupStepCard(
                                step = 6,
                                text = uiLabel("Choose how the bot should respond in this conversation.")
                            )
                            ExposedDropdownMenuBox(
                                expanded = bindingSlackResponseModeMenuExpanded,
                                onExpandedChange = { bindingSlackResponseModeMenuExpanded = it }
                            ) {
                                OutlinedTextField(
                                    value = uiLabel(bindingSlackResponseModeDraft.ifBlank { "mention" }),
                                    onValueChange = {},
                                    readOnly = true,
                                    modifier = Modifier
                                        .menuAnchor()
                                        .fillMaxWidth(),
                                    label = { Text(uiLabel("Response Mode")) },
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = bindingSlackResponseModeMenuExpanded)
                                    },
                                    singleLine = true
                                )
                                ExposedDropdownMenu(
                                    expanded = bindingSlackResponseModeMenuExpanded,
                                    onDismissRequest = { bindingSlackResponseModeMenuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                            text = { Text(uiLabel("mention")) },
                                        onClick = {
                                            bindingSlackResponseModeDraft = "mention"
                                            bindingSlackResponseModeMenuExpanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                            text = { Text(uiLabel("open")) },
                                        onClick = {
                                            bindingSlackResponseModeDraft = "open"
                                            bindingSlackResponseModeMenuExpanded = false
                                        }
                                    )
                                }
                            }
                            SettingsAdvancedSection(
                                expanded = slackAdvancedExpanded,
                                onToggle = { slackAdvancedExpanded = !slackAdvancedExpanded }
                            ) {
                                OutlinedTextField(
                                    value = bindingSlackAllowedUserIdsDraft,
                                    onValueChange = { bindingSlackAllowedUserIdsDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2,
                                    maxLines = 4,
                                    label = { Text(uiLabel("Allowed User IDs")) },
                                    placeholder = { Text(uiLabel("One ID per line or comma-separated")) }
                                )
                            }
                            SessionSetupStepCard(
                                step = 7,
                                text = uiLabel("After filling the fields, tap Save at the bottom to start the Slack connection.")
                            )
                                } else if (sessionSettingsPage == SessionSettingsPage.Configure && normalizedChannel == "feishu") {
                            SessionSetupStepCard(
                                step = 1,
                                text = uiLabel("Create a Feishu app in Feishu Open Platform, enable Bot capability, then copy App ID and App Secret.")
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SettingsActionButton(
                                    text = uiLabel("Open Platform"),
                                    icon = Icons.Rounded.Description,
                                    onClick = { openExternalUrl(context, "https://open.feishu.cn/") }
                                )
                            }
                            SettingsInfoBlock(
                                label = uiLabel("Connection mode"),
                                value = uiLabel("Uses WebSocket long connection. No webhook or public IP required.")
                            )
                            OutlinedTextField(
                                value = bindingFeishuAppIdDraft,
                                onValueChange = { bindingFeishuAppIdDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(uiLabel("Feishu App ID")) }
                            )
                            OutlinedTextField(
                                value = bindingFeishuAppSecretDraft,
                                onValueChange = { bindingFeishuAppSecretDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(uiLabel("Feishu App Secret")) },
                                visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation()
                            )
                            SessionSetupStepCard(
                                step = 2,
                                text = uiLabel("Select Long Connection mode, add event im.message.receive_v1, then grant the required messaging permissions.")
                            )
                            SessionSetupStepCard(
                                step = 3,
                                text = uiLabel("Publish the app, then tap Save at the bottom to start the long connection.")
                            )
                            SessionSetupStepCard(
                                step = 4,
                                text = uiLabel("After Save, send one message to the bot from Feishu.")
                            )
                            SessionSetupStepCard(
                                step = 5,
                                text = uiLabel("Tap Detect Chats, then choose the conversation to bind.")
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SettingsActionButton(
                                    text = uiLabel("Detect Chats"),
                                    icon = Icons.Rounded.Refresh,
                                    onClick = { vm.discoverFeishuChatsForBinding() },
                                    enabled = !state.sessionBindingFeishuDiscovering
                                )
                                if (state.sessionBindingFeishuDiscovering) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text(
                                        text = uiLabel("Detecting..."),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (state.sessionBindingFeishuCandidates.isNotEmpty()) {
                                state.sessionBindingFeishuCandidates.forEach { candidate ->
                                    Surface(
                                        tonalElevation = 1.dp,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                bindingChatIdDraft = candidate.chatId
                                            }
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = candidate.title,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = "${candidate.kind}: ${candidate.chatId}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (candidate.note.isNotBlank()) {
                                                Text(
                                                    text = candidate.note,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    text = uiLabel("No detected chats yet."),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            SettingsAdvancedSection(
                                expanded = feishuAdvancedExpanded,
                                onToggle = { feishuAdvancedExpanded = !feishuAdvancedExpanded }
                            ) {
                                OutlinedTextField(
                                    value = bindingFeishuEncryptKeyDraft,
                                    onValueChange = { bindingFeishuEncryptKeyDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text(uiLabel("Encrypt Key")) }
                                )
                                OutlinedTextField(
                                    value = bindingFeishuVerificationTokenDraft,
                                    onValueChange = { bindingFeishuVerificationTokenDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text(uiLabel("Verification Token")) }
                                )
                                OutlinedTextField(
                                    value = bindingChatIdDraft,
                                    onValueChange = { bindingChatIdDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text(uiLabel("Target ID")) },
                                    placeholder = { Text(uiLabel("Private: ou_xxx, Group: oc_xxx")) }
                                )
                                OutlinedTextField(
                                    value = bindingFeishuAllowedOpenIdsDraft,
                                    onValueChange = { bindingFeishuAllowedOpenIdsDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2,
                                    maxLines = 4,
                                    label = { Text(uiLabel("Allowed Open IDs")) },
                                    placeholder = { Text(uiLabel("One open_id per line, or * to allow all")) }
                                )
                            }
                                } else if (sessionSettingsPage == SessionSettingsPage.Configure && normalizedChannel == "email") {
                            SessionSetupStepCard(
                                step = 1,
                                text = uiLabel("Prepare a mailbox for the bot. IMAP is used to read mail and SMTP is used to send replies.")
                            )
                            SettingsInfoBlock(
                                label = uiLabel("Common setup"),
                                value = uiLabel("Gmail usually works with IMAP + SMTP app passwords.")
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(uiLabel("Mailbox consent granted"))
                                PalmClawSwitch(
                                    checked = bindingEmailConsentGrantedDraft,
                                    onCheckedChange = { bindingEmailConsentGrantedDraft = it }
                                )
                            }
                            SessionSetupStepCard(
                                step = 2,
                                text = uiLabel("Enter IMAP settings for receiving mail.")
                            )
                            OutlinedTextField(
                                value = bindingEmailImapHostDraft,
                                onValueChange = { bindingEmailImapHostDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(uiLabel("IMAP Host")) }
                            )
                            OutlinedTextField(
                                value = bindingEmailImapPortDraft,
                                onValueChange = { bindingEmailImapPortDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(uiLabel("IMAP Port")) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            OutlinedTextField(
                                value = bindingEmailImapUsernameDraft,
                                onValueChange = {
                                    bindingEmailImapUsernameDraft = it
                                    if (bindingEmailFromAddressDraft.isBlank()) {
                                        bindingEmailFromAddressDraft = it
                                    }
                                    if (bindingEmailSmtpUsernameDraft.isBlank()) {
                                        bindingEmailSmtpUsernameDraft = it
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(uiLabel("IMAP Username")) }
                            )
                            OutlinedTextField(
                                value = bindingEmailImapPasswordDraft,
                                onValueChange = { bindingEmailImapPasswordDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(uiLabel("IMAP Password / App Password")) },
                                visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation()
                            )
                            SessionSetupStepCard(
                                step = 3,
                                text = uiLabel("Enter SMTP settings for replies.")
                            )
                            OutlinedTextField(
                                value = bindingEmailSmtpHostDraft,
                                onValueChange = { bindingEmailSmtpHostDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(uiLabel("SMTP Host")) }
                            )
                            OutlinedTextField(
                                value = bindingEmailSmtpPortDraft,
                                onValueChange = { bindingEmailSmtpPortDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(uiLabel("SMTP Port")) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            OutlinedTextField(
                                value = bindingEmailSmtpUsernameDraft,
                                onValueChange = { bindingEmailSmtpUsernameDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(uiLabel("SMTP Username")) }
                            )
                            OutlinedTextField(
                                value = bindingEmailSmtpPasswordDraft,
                                onValueChange = { bindingEmailSmtpPasswordDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(uiLabel("SMTP Password / App Password")) },
                                visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation()
                            )
                            OutlinedTextField(
                                value = bindingEmailFromAddressDraft,
                                onValueChange = { bindingEmailFromAddressDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(uiLabel("From Address")) }
                            )
                            SessionSetupStepCard(
                                step = 4,
                                text = uiLabel("Tap Save once to start mailbox polling, then send one email to this account.")
                            )
                            SessionSetupStepCard(
                                step = 5,
                                text = uiLabel("Tap Detect Senders, choose the sender address to bind, then tap Save again.")
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SettingsActionButton(
                                    text = uiLabel("Detect Senders"),
                                    icon = Icons.Rounded.Refresh,
                                    onClick = {
                                        vm.discoverEmailSendersForBinding(
                                            consentGranted = bindingEmailConsentGrantedDraft,
                                            imapHost = bindingEmailImapHostDraft,
                                            imapPort = bindingEmailImapPortDraft,
                                            imapUsername = bindingEmailImapUsernameDraft,
                                            imapPassword = bindingEmailImapPasswordDraft,
                                            smtpHost = bindingEmailSmtpHostDraft,
                                            smtpPort = bindingEmailSmtpPortDraft,
                                            smtpUsername = bindingEmailSmtpUsernameDraft,
                                            smtpPassword = bindingEmailSmtpPasswordDraft,
                                            fromAddress = bindingEmailFromAddressDraft,
                                            autoReplyEnabled = bindingEmailAutoReplyEnabledDraft
                                        )
                                    },
                                    enabled = bindingEmailConsentGrantedDraft &&
                                        bindingEmailImapHostDraft.isNotBlank() &&
                                        bindingEmailImapUsernameDraft.isNotBlank() &&
                                        bindingEmailImapPasswordDraft.isNotBlank() &&
                                        !state.sessionBindingEmailDiscovering
                                )
                                if (state.sessionBindingEmailDiscovering) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text(
                                        text = uiLabel("Detecting..."),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (state.sessionBindingEmailCandidates.isNotEmpty()) {
                                state.sessionBindingEmailCandidates.forEach { candidate ->
                                    Surface(
                                        tonalElevation = 1.dp,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                bindingChatIdDraft = candidate.email
                                            }
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = candidate.email,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Medium
                                            )
                                            if (candidate.subject.isNotBlank()) {
                                                Text(
                                                    text = "${tr("Last subject", "")}: ${candidate.subject}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            if (candidate.note.isNotBlank()) {
                                                Text(
                                                    text = candidate.note,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    text = uiLabel("No detected senders yet."),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            SettingsAdvancedSection(
                                expanded = emailAdvancedExpanded,
                                onToggle = { emailAdvancedExpanded = !emailAdvancedExpanded }
                            ) {
                                SettingsToggleRow(
                                    title = uiLabel("Auto reply"),
                                    checked = bindingEmailAutoReplyEnabledDraft,
                                    onCheckedChange = { bindingEmailAutoReplyEnabledDraft = it }
                                )
                                OutlinedTextField(
                                    value = bindingChatIdDraft,
                                    onValueChange = { bindingChatIdDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text(uiLabel("Sender Email Address")) },
                                    placeholder = { Text(uiLabel("someone@example.com")) }
                                )
                            }
                                } else if (sessionSettingsPage == SessionSettingsPage.Configure && normalizedChannel == "wecom") {
                            SessionSetupStepCard(
                                step = 1,
                                text = uiLabel("Create a WeCom AI Bot, choose API mode with long connection, then copy Bot ID and Secret.")
                            )
                            SettingsInfoBlock(
                                label = uiLabel("Connection mode"),
                                value = uiLabel("Uses long connection. No public URL is required.")
                            )
                            OutlinedTextField(
                                value = bindingWeComBotIdDraft,
                                onValueChange = { bindingWeComBotIdDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(uiLabel("WeCom Bot ID")) }
                            )
                            OutlinedTextField(
                                value = bindingWeComSecretDraft,
                                onValueChange = { bindingWeComSecretDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(uiLabel("WeCom Secret")) },
                                visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation()
                            )
                            SessionSetupStepCard(
                                step = 2,
                                text = uiLabel("Tap Save once to start the long connection.")
                            )
                            SessionSetupStepCard(
                                step = 3,
                                text = uiLabel("Open the bot in WeCom and send one message so the app can detect the conversation.")
                            )
                            SessionSetupStepCard(
                                step = 4,
                                text = uiLabel("Tap Detect Chats, choose the detected conversation, then tap Save again.")
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SettingsActionButton(
                                    text = uiLabel("Detect Chats"),
                                    icon = Icons.Rounded.Refresh,
                                    onClick = { vm.discoverWeComChatsForBinding() },
                                    enabled = !state.sessionBindingWeComDiscovering
                                )
                                if (state.sessionBindingWeComDiscovering) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text(
                                        text = uiLabel("Detecting..."),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (state.sessionBindingWeComCandidates.isNotEmpty()) {
                                state.sessionBindingWeComCandidates.forEach { candidate ->
                                    Surface(
                                        tonalElevation = 1.dp,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                bindingChatIdDraft = candidate.chatId
                                            }
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = candidate.title,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = "${candidate.kind}: ${candidate.chatId}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (candidate.note.isNotBlank()) {
                                                Text(
                                                    text = candidate.note,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    text = uiLabel("No detected chats yet."),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            SettingsAdvancedSection(
                                expanded = weComAdvancedExpanded,
                                onToggle = { weComAdvancedExpanded = !weComAdvancedExpanded }
                            ) {
                                OutlinedTextField(
                                    value = bindingChatIdDraft,
                                    onValueChange = { bindingChatIdDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text(uiLabel("Target ID")) },
                                    placeholder = { Text(uiLabel("userId or detected chatId")) }
                                )
                                OutlinedTextField(
                                    value = bindingWeComAllowedUserIdsDraft,
                                    onValueChange = { bindingWeComAllowedUserIdsDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2,
                                    maxLines = 4,
                                    label = { Text(uiLabel("Allowed User IDs")) },
                                    placeholder = { Text(uiLabel("One user ID per line, or * to allow all")) }
                                )
                            }
                                } else if (sessionSettingsPage == SessionSettingsPage.Configure) {
                                    Text(
                                        text = uiLabel("Select a channel to configure binding for this session."),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        if (
                            sessionSettingsPage == SessionSettingsPage.Configure &&
                            sessionSettingsScrollState.maxValue > 0 &&
                            sessionSettingsScrollState.value < sessionSettingsScrollState.maxValue
                        ) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                tonalElevation = 2.dp,
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.95f),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = uiLabel("More settings below"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    when (sessionSettingsPage) {
                        SessionSettingsPage.Configure -> {
                            Button(
                                onClick = {
                                    vm.saveSessionChannelBinding(
                                        sessionId = sessionId,
                                        enabled = bindingEnabledDraft,
                                        channel = bindingChannelDraft,
                                        chatId = bindingChatIdDraft,
                                        telegramBotToken = bindingTelegramBotTokenDraft,
                                        telegramAllowedChatId = bindingTelegramAllowedChatIdDraft,
                                        discordBotToken = bindingDiscordBotTokenDraft,
                                        discordResponseMode = bindingDiscordResponseModeDraft,
                                        discordAllowedUserIds = bindingDiscordAllowedUserIdsDraft,
                                        slackBotToken = bindingSlackBotTokenDraft,
                                        slackAppToken = bindingSlackAppTokenDraft,
                                        slackResponseMode = bindingSlackResponseModeDraft,
                                        slackAllowedUserIds = bindingSlackAllowedUserIdsDraft,
                                        feishuAppId = bindingFeishuAppIdDraft,
                                        feishuAppSecret = bindingFeishuAppSecretDraft,
                                        feishuEncryptKey = bindingFeishuEncryptKeyDraft,
                                        feishuVerificationToken = bindingFeishuVerificationTokenDraft,
                                        feishuAllowedOpenIds = bindingFeishuAllowedOpenIdsDraft,
                                        emailConsentGranted = bindingEmailConsentGrantedDraft,
                                        emailImapHost = bindingEmailImapHostDraft,
                                        emailImapPort = bindingEmailImapPortDraft,
                                        emailImapUsername = bindingEmailImapUsernameDraft,
                                        emailImapPassword = bindingEmailImapPasswordDraft,
                                        emailSmtpHost = bindingEmailSmtpHostDraft,
                                        emailSmtpPort = bindingEmailSmtpPortDraft,
                                        emailSmtpUsername = bindingEmailSmtpUsernameDraft,
                                        emailSmtpPassword = bindingEmailSmtpPasswordDraft,
                                        emailFromAddress = bindingEmailFromAddressDraft,
                                        emailAutoReplyEnabled = bindingEmailAutoReplyEnabledDraft,
                                        wecomBotId = bindingWeComBotIdDraft,
                                        wecomSecret = bindingWeComSecretDraft,
                                        wecomAllowedUserIds = bindingWeComAllowedUserIdsDraft
                                    )
                                    bindingChannelMenuExpanded = false
                                    bindingDiscordResponseModeMenuExpanded = false
                                    bindingSlackResponseModeMenuExpanded = false
                                    vm.clearTelegramChatDiscovery()
                                    vm.clearFeishuChatDiscovery()
                                    vm.clearEmailSenderDiscovery()
                                    vm.clearWeComChatDiscovery()
                                    sessionSettingsSessionId = null
                                    sessionSettingsPageName = SessionSettingsPage.Menu.name
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.36f),
                                    disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.56f)
                                )
                            ) {
                                Text(uiLabel("Save"))
                            }
                        }

                        else -> {
                            OutlinedButton(
                                onClick = {
                                    bindingChannelMenuExpanded = false
                                    bindingDiscordResponseModeMenuExpanded = false
                                    bindingSlackResponseModeMenuExpanded = false
                                    vm.clearTelegramChatDiscovery()
                                    vm.clearFeishuChatDiscovery()
                                    vm.clearEmailSenderDiscovery()
                                    vm.clearWeComChatDiscovery()
                                    sessionSettingsSessionId = null
                                    sessionSettingsPageName = SessionSettingsPage.Menu.name
                                },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.32f),
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)
                                )
                            ) {
                                Text(uiLabel("Close"))
                            }
                        }
                    }
                },
                dismissButton = {
                    if (sessionSettingsPage != SessionSettingsPage.Menu) {
                        OutlinedButton(
                            onClick = {
                                bindingChannelMenuExpanded = false
                                bindingDiscordResponseModeMenuExpanded = false
                                bindingSlackResponseModeMenuExpanded = false
                                vm.clearTelegramChatDiscovery()
                                vm.clearFeishuChatDiscovery()
                                vm.clearEmailSenderDiscovery()
                                vm.clearWeComChatDiscovery()
                                sessionSettingsPageName = SessionSettingsPage.Menu.name
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.32f),
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)
                            )
                        ) {
                            Text(uiLabel("Back"))
                        }
                    }
                }
            )
        } else {
            bindingChannelMenuExpanded = false
            bindingDiscordResponseModeMenuExpanded = false
            bindingSlackResponseModeMenuExpanded = false
            vm.clearTelegramChatDiscovery()
            vm.clearFeishuChatDiscovery()
            vm.clearEmailSenderDiscovery()
            vm.clearWeComChatDiscovery()
            sessionSettingsSessionId = null
            sessionSettingsPageName = SessionSettingsPage.Menu.name
        }
    }

    val userRoundStartIndices = remember(state.messages) {
        state.messages.mapIndexedNotNull { index, message ->
            if (message.role == "user") index else null
        }
    }
    val totalRounds = userRoundStartIndices.size
    val clampedVisibleRounds = when {
        totalRounds <= 0 -> HISTORY_ROUNDS_PAGE_SIZE
        totalRounds <= HISTORY_ROUNDS_PAGE_SIZE -> totalRounds
        else -> visibleHistoryRounds.coerceIn(HISTORY_ROUNDS_PAGE_SIZE, totalRounds)
    }
    val hiddenRounds = if (totalRounds > clampedVisibleRounds) totalRounds - clampedVisibleRounds else 0
    val historyWindowStartIndex = if (hiddenRounds > 0) userRoundStartIndices[hiddenRounds] else 0
    val visibleMessages = if (historyWindowStartIndex > 0) {
        state.messages.subList(historyWindowStartIndex, state.messages.size)
    } else {
        state.messages
    }
    val canLoadOlderHistory = hiddenRounds > 0
    val showHistoryStatus = visibleMessages.isNotEmpty()
    val headerItemCount = if (showHistoryStatus) 1 else 0

    val hasAssistantOutputAfterAnchor = run {
        val anchor = generationAnchorMessageId
        if (anchor == null) {
            false
        } else {
            state.messages.any { message ->
                if (message.id <= anchor || message.role != "assistant") {
                    false
                } else {
                    (displayedAssistantText[message.id] ?: message.content).isNotBlank()
                }
            }
        }
    }
    val showProcessingBubble = state.isGenerating && !hasAssistantOutputAfterAnchor
    val extraTailItemCount = if (showProcessingBubble) 1 else 0
    val totalItems = visibleMessages.size + headerItemCount + extraTailItemCount
    val tailIndex = if (totalItems <= 0) -1 else totalItems - 1
    val scrollIndicator by remember(
        totalItems,
        listState.firstVisibleItemIndex,
        listState.firstVisibleItemScrollOffset
    ) {
        derivedStateOf {
            val visibleCount = listState.layoutInfo.visibleItemsInfo.size
            val totalCount = listState.layoutInfo.totalItemsCount
            if (totalCount <= 0 || visibleCount <= 0 || totalCount <= visibleCount) {
                null
            } else {
                val maxIndex = (totalCount - visibleCount).coerceAtLeast(1)
                val rawProgress = (listState.firstVisibleItemIndex.toFloat() / maxIndex.toFloat())
                    .coerceIn(0f, 1f)
                ScrollIndicatorUi(
                    thumbFraction = 0.16f,
                    progress = rawProgress
                )
            }
        }
    }
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val imeVisible = imeBottomPx > 0
    var inputBarSurfaceHeightPx by remember { mutableStateOf(0) }
    val tailVisibleGapPx = with(density) { CHAT_TAIL_VISIBLE_GAP.roundToPx() }
    val chatInputBarClearance = with(density) {
        val fallback = CHAT_INPUT_BAR_CLEARANCE.roundToPx()
        val outerVerticalPadding = 8.dp.roundToPx()
        val overlayHeight = maxOf(inputBarSurfaceHeightPx + outerVerticalPadding, fallback)
        val obstructionPx = overlayHeight + if (imeVisible) imeBottomPx else 0
        (obstructionPx.toDp() - 10.dp).coerceAtLeast(52.dp) + CHAT_TAIL_VISIBLE_GAP
    }
    val chatInputBarClearancePx = with(density) { chatInputBarClearance.roundToPx() }
    val isNearTail by remember(
        visibleMessages.size,
        headerItemCount,
        showProcessingBubble,
        listState.firstVisibleItemIndex,
        listState.firstVisibleItemScrollOffset,
        tailVisibleGapPx,
        chatInputBarClearancePx
    ) {
        derivedStateOf {
            if (totalItems <= 0) return@derivedStateOf true
            val tailItem = listState.layoutInfo.visibleItemsInfo.lastOrNull { it.index == tailIndex }
                ?: return@derivedStateOf false
            val desiredBottom = listState.layoutInfo.viewportEndOffset - chatInputBarClearancePx
            (tailItem.offset + tailItem.size) <= (desiredBottom + 1)
        }
    }
    var programmaticScrolling by remember { mutableStateOf(false) }
    var scrollToLatestAfterSend by remember { mutableStateOf(false) }
    val autoScrollMutex = remember { Mutex() }
    suspend fun moveToLatest(animated: Boolean) {
        if (tailIndex < 0) return
        autoScrollMutex.withLock {
            programmaticScrolling = true
            try {
                if (animated) {
                    listState.animateScrollToItem(tailIndex)
                } else {
                    listState.scrollToItem(tailIndex)
                }
                repeat(3) {
                    val tailItem = listState.layoutInfo.visibleItemsInfo
                        .lastOrNull { it.index == tailIndex }
                        ?: return@repeat
                    val desiredBottom = listState.layoutInfo.viewportEndOffset - chatInputBarClearancePx
                    val remaining = (tailItem.offset + tailItem.size) - desiredBottom
                    if (remaining <= 1) return@repeat
                    listState.scrollBy(remaining.toFloat())
                }
            } finally {
                programmaticScrolling = false
            }
        }
    }

    var nearTailBeforeImeOpen by rememberSaveable { mutableStateOf(true) }
    val showScrollToLatestButton = totalItems > 0 && !isNearTail
    val openAttachment: (UiMediaAttachment) -> Unit = { attachment ->
        toAttachmentUri(attachment.reference)?.let { uri ->
            val mime = mediaMimeTypeForKind(attachment.kind)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { context.startActivity(Intent.createChooser(intent, "Open media")) }
        }
    }
    val toggleAudioPreview: (UiMediaAttachment) -> Unit = { attachment ->
        val sameRefPlaying = previewAudioRef == attachment.reference &&
            runCatching { previewAudioPlayer?.isPlaying == true }.getOrDefault(false)
        if (sameRefPlaying) {
            runCatching { previewAudioPlayer?.stop() }
            runCatching { previewAudioPlayer?.release() }
            previewAudioPlayer = null
            previewAudioRef = null
            previewAudioDurationMs = 0
            previewAudioPositionMs = 0
        } else {
            runCatching {
                runCatching { previewAudioPlayer?.stop() }
                runCatching { previewAudioPlayer?.release() }
                val player = MediaPlayer()
                val raw = attachment.reference.trim()
                val uri = toAttachmentUri(raw)
                if (uri != null && (
                        raw.startsWith("content://", true) ||
                            raw.startsWith("file://", true) ||
                            raw.startsWith("http://", true) ||
                            raw.startsWith("https://", true)
                        )
                ) {
                    player.setDataSource(context, uri)
                } else {
                    player.setDataSource(raw)
                }
                player.prepare()
                previewAudioDurationMs = runCatching { player.duration }.getOrDefault(0).coerceAtLeast(0)
                previewAudioPositionMs = 0
                player.setOnCompletionListener {
                    runCatching { it.release() }
                    previewAudioPositionMs = previewAudioDurationMs
                    previewAudioPlayer = null
                    previewAudioRef = null
                }
                player.start()
                previewAudioPlayer = player
                previewAudioRef = attachment.reference
            }.onFailure {
                runCatching { previewAudioPlayer?.release() }
                previewAudioPlayer = null
                previewAudioRef = null
                previewAudioDurationMs = 0
                previewAudioPositionMs = 0
            }
        }
    }

    LaunchedEffect(previewAudioPlayer, previewAudioRef) {
        val player = previewAudioPlayer ?: return@LaunchedEffect
        while (previewAudioPlayer === player) {
            val duration = runCatching { player.duration }.getOrDefault(0).coerceAtLeast(0)
            val position = runCatching { player.currentPosition }.getOrDefault(0).coerceAtLeast(0)
            previewAudioDurationMs = duration
            previewAudioPositionMs = position.coerceAtMost(duration)
            if (!runCatching { player.isPlaying }.getOrDefault(false)) {
                break
            }
            delay(250)
        }
    }

    LaunchedEffect(state.isGenerating) {
        if (state.isGenerating) {
            generationAnchorMessageId = state.messages.lastOrNull()?.id
        } else {
            generationAnchorMessageId = null
        }
    }

    LaunchedEffect(listState.isScrollInProgress, isNearTail, programmaticScrolling) {
        if (programmaticScrolling) {
            if (isNearTail) {
                followLatest = true
            }
            return@LaunchedEffect
        }
        if (listState.isScrollInProgress && !isNearTail) {
            followLatest = false
        } else if (isNearTail) {
            followLatest = true
        }
    }
    LaunchedEffect(imeVisible, isNearTail) {
        if (!imeVisible) {
            nearTailBeforeImeOpen = isNearTail
        }
    }
    LaunchedEffect(imeBottomPx, nearTailBeforeImeOpen, tailIndex, chatInputBarClearancePx) {
        if (imeBottomPx > 0 && nearTailBeforeImeOpen && tailIndex >= 0) {
            followLatest = true
            delay(16)
            moveToLatest(animated = false)
        }
    }
    LaunchedEffect(scrollToLatestAfterSend, state.messages.lastOrNull()?.id, tailIndex) {
        if (!scrollToLatestAfterSend || tailIndex < 0) return@LaunchedEffect
        moveToLatest(animated = false)
        scrollToLatestAfterSend = false
    }
    LaunchedEffect(state.messages) {
        if (!initializedMessages) {
            if (state.messages.isEmpty()) {
                return@LaunchedEffect
            }
            state.messages.forEach { message ->
                seenMessageIds[message.id] = true
                if (message.role == "assistant") {
                    displayedAssistantText[message.id] = message.content
                }
            }
            initializedMessages = true
            return@LaunchedEffect
        }

        state.messages.forEach { message ->
            val known = seenMessageIds[message.id] == true
            if (known) {
                if (message.role == "assistant") {
                    val shown = displayedAssistantText[message.id]
                    if (shown != message.content) {
                        displayedAssistantText[message.id] = message.content
                    }
                }
                return@forEach
            }

            seenMessageIds[message.id] = true
            if (message.role != "assistant") return@forEach

            displayedAssistantText[message.id] = message.content
        }

        val validIds = state.messages.asSequence().map { it.id }.toSet()
        seenMessageIds.keys.toList().forEach { id ->
            if (id !in validIds) {
                seenMessageIds.remove(id)
            }
        }
        displayedAssistantText.keys.toList().forEach { id ->
            if (id !in validIds) {
                displayedAssistantText.remove(id)
            }
        }
    }

    LaunchedEffect(
        pendingHistoryRestore,
        visibleMessages.size,
        headerItemCount
    ) {
        val restore = pendingHistoryRestore ?: return@LaunchedEffect
        val localIndex = visibleMessages.indexOfFirst { it.id == restore.anchorMessageId }
        if (localIndex < 0) {
            val elapsed = System.currentTimeMillis() - olderHistoryLoadingStartedAtMs
            val remain = HISTORY_LOADING_MIN_VISIBLE_MS - elapsed
            if (remain > 0) delay(remain)
            pendingHistoryRestore = null
            isLoadingOlderHistory = false
            olderHistoryLoadingStartedAtMs = 0L
            return@LaunchedEffect
        }
        listState.scrollToItem(
            index = (headerItemCount + localIndex).coerceAtLeast(0),
            scrollOffset = -restore.anchorOffsetFromTop.coerceAtLeast(0)
        )
        val elapsed = System.currentTimeMillis() - olderHistoryLoadingStartedAtMs
        val remain = HISTORY_LOADING_MIN_VISIBLE_MS - elapsed
        if (remain > 0) delay(remain)
        pendingHistoryRestore = null
        isLoadingOlderHistory = false
        olderHistoryLoadingStartedAtMs = 0L
    }

    LaunchedEffect(
        hasInitialJumpToBottom,
        listState.firstVisibleItemIndex,
        listState.firstVisibleItemScrollOffset,
        canLoadOlderHistory,
        isLoadingOlderHistory,
        clampedVisibleRounds,
        totalRounds,
        visibleMessages.size,
        visibleMessages.firstOrNull()?.id
    ) {
        if (!hasInitialJumpToBottom) return@LaunchedEffect
        if (!canLoadOlderHistory || isLoadingOlderHistory) return@LaunchedEffect
        val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        if (!atTop) return@LaunchedEffect

        delay(HISTORY_LOAD_TRIGGER_DELAY_MS)
        val stillAtTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        if (!stillAtTop || !canLoadOlderHistory || isLoadingOlderHistory) return@LaunchedEffect

        val nextVisibleRounds = (clampedVisibleRounds + HISTORY_ROUNDS_PAGE_SIZE).coerceAtMost(totalRounds)
        if (nextVisibleRounds == clampedVisibleRounds) return@LaunchedEffect

        val firstVisibleInfo = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index >= headerItemCount }
        val anchorMessageId = firstVisibleInfo?.let { info ->
            visibleMessages.getOrNull(info.index - headerItemCount)?.id
        } ?: visibleMessages.firstOrNull()?.id ?: return@LaunchedEffect
        val anchorOffsetFromTop = firstVisibleInfo?.let { info ->
            (info.offset - listState.layoutInfo.viewportStartOffset).coerceAtLeast(0)
        } ?: 0

        isLoadingOlderHistory = true
        olderHistoryLoadingStartedAtMs = System.currentTimeMillis()
        followLatest = false
        pendingHistoryRestore = HistoryRestoreRequest(
            anchorMessageId = anchorMessageId,
            anchorOffsetFromTop = anchorOffsetFromTop
        )
        visibleHistoryRounds = nextVisibleRounds
    }

    LaunchedEffect(
        state.messages.lastOrNull()?.id,
        showProcessingBubble,
        followLatest,
        isNearTail
    ) {
        if (tailIndex < 0) return@LaunchedEffect
        if (!hasInitialJumpToBottom) {
            moveToLatest(animated = false)
            hasInitialJumpToBottom = true
            return@LaunchedEffect
        }
        if (!followLatest) return@LaunchedEffect
        if (isNearTail) return@LaunchedEffect
        moveToLatest(animated = true)
    }

    LaunchedEffect(mainSurface, settingsPage) {
        if (mainSurface != MainSurface.Settings) return@LaunchedEffect
        when (settingsPage) {
            SettingsPanelPage.Cron -> vm.refreshCronJobs()
            SettingsPanelPage.Runtime -> vm.refreshAgentLogs()
            else -> Unit
        }
    }
    LaunchedEffect(state.settingsInfo, mainSurface) {
        val info = state.settingsInfo?.trim().orEmpty()
        if (info.isBlank() || mainSurface != MainSurface.Settings) return@LaunchedEffect
        settingsSnackbarHostState.currentSnackbarData?.dismiss()
        val isError = info.contains("failed", ignoreCase = true) ||
            info.contains("error", ignoreCase = true)
        settingsSnackbarHostState.showSnackbar(
            message = info,
            withDismissAction = true,
            duration = if (isError) SnackbarDuration.Long else SnackbarDuration.Short
        )
        vm.clearSettingsInfo()
    }

    if (showHeartbeatEditor) {
        LaunchedEffect(state.settingsHeartbeatDoc) {
            delay(650)
            vm.saveHeartbeatDocument(showSuccessMessage = false, showErrorMessage = false)
        }
        ModalBottomSheet(onDismissRequest = { showHeartbeatEditor = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = uiLabel("Edit HEARTBEAT.md"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = state.settingsHeartbeatDoc,
                    onValueChange = vm::onSettingsHeartbeatDocChanged,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 12,
                    maxLines = 20,
                    singleLine = false
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SettingsActionButton(
                        text = uiLabel("Close"),
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        onClick = { showHeartbeatEditor = false }
                    )
                    if (state.settingsSaving) {
                        Text(
                            text = uiLabel("Saving..."),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = mainSurface == MainSurface.Chat,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(320.dp),
                drawerContainerColor = MaterialTheme.colorScheme.background,
                drawerContentColor = MaterialTheme.colorScheme.onBackground
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = tr("Sessions", ""),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { showCreateSessionDialog = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Add,
                                contentDescription = tr("Create session", ""),
                                modifier = Modifier.size(19.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(state.sessions, key = { it.id }) { session ->
                            val selected = session.id == state.currentSessionId
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.selectSession(session.id)
                                        mainSurfaceName = MainSurface.Chat.name
                                        uiScope.launch { drawerState.close() }
                                    },
                                tonalElevation = if (selected) 2.dp else 0.dp,
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (session.isLocal) tr("LOCAL", "") else session.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                                        )
                                        when {
                                            session.isLocal -> Text(
                                                text = tr("Local chat for administration.", ""),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (selected) {
                                                    MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.74f)
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )

                                            session.boundChannel.isNotBlank() -> Text(
                                                text = run {
                                                    val boundChannelLabel = channelDisplayLabel(session.boundChannel)
                                                    val offLabel = uiLabel("Off")
                                                    buildString {
                                                        append(boundChannelLabel)
                                                        if (!session.boundEnabled) append(" 路 $offLabel")
                                                    }
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (selected) {
                                                    MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.74f)
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    if (!session.isLocal) {
                                        MinimalActionIconButton(
                                            onClick = {
                                                renameSessionName = session.title
                                                pendingRenameSessionId = session.id
                                            }
                                        ) {
                                            Icon(
                                                Icons.Outlined.Edit,
                                                contentDescription = uiLabel("Rename session")
                                            )
                                        }
                                        MinimalActionIconButton(
                                            onClick = {
                                                val draft = vm.getSessionChannelDraft(session.id)
                                                bindingEnabledDraft = draft.enabled
                                                bindingChannelDraft = draft.channel
                                                bindingChatIdDraft = draft.chatId
                                                bindingTelegramBotTokenDraft = draft.telegramBotToken
                                                bindingTelegramAllowedChatIdDraft = draft.telegramAllowedChatId
                                                bindingDiscordBotTokenDraft = draft.discordBotToken
                                                bindingSlackBotTokenDraft = draft.slackBotToken
                                                bindingSlackAppTokenDraft = draft.slackAppToken
                                                bindingFeishuAppIdDraft = draft.feishuAppId
                                                bindingFeishuAppSecretDraft = draft.feishuAppSecret
                                                bindingFeishuEncryptKeyDraft = draft.feishuEncryptKey
                                                bindingFeishuVerificationTokenDraft = draft.feishuVerificationToken
                                                bindingEmailConsentGrantedDraft = draft.emailConsentGranted
                                                bindingEmailImapHostDraft = draft.emailImapHost
                                                bindingEmailImapPortDraft = draft.emailImapPort
                                                bindingEmailImapUsernameDraft = draft.emailImapUsername
                                                bindingEmailImapPasswordDraft = draft.emailImapPassword
                                                bindingEmailSmtpHostDraft = draft.emailSmtpHost
                                                bindingEmailSmtpPortDraft = draft.emailSmtpPort
                                                bindingEmailSmtpUsernameDraft = draft.emailSmtpUsername
                                                bindingEmailSmtpPasswordDraft = draft.emailSmtpPassword
                                                bindingEmailFromAddressDraft = draft.emailFromAddress
                                                bindingEmailAutoReplyEnabledDraft = draft.emailAutoReplyEnabled
                                                bindingWeComBotIdDraft = draft.wecomBotId
                                                bindingWeComSecretDraft = draft.wecomSecret
                                                bindingChannelMenuExpanded = false
                                                bindingDiscordResponseModeMenuExpanded = false
                                                bindingSlackResponseModeMenuExpanded = false
                                                vm.clearTelegramChatDiscovery()
                                                vm.clearFeishuChatDiscovery()
                                                vm.clearEmailSenderDiscovery()
                                                vm.clearWeComChatDiscovery()
                                                bindingDiscordResponseModeDraft = draft.discordResponseMode
                                                bindingDiscordAllowedUserIdsDraft = draft.discordAllowedUserIds
                                                bindingSlackResponseModeDraft = draft.slackResponseMode
                                                bindingSlackAllowedUserIdsDraft = draft.slackAllowedUserIds
                                                bindingFeishuAllowedOpenIdsDraft = draft.feishuAllowedOpenIds
                                                bindingWeComAllowedUserIdsDraft = draft.wecomAllowedUserIds
                                                sessionSettingsPageName = SessionSettingsPage.Menu.name
                                                sessionSettingsSessionId = session.id
                                            }
                                        ) {
                                            Icon(
                                                Icons.Outlined.Settings,
                                                contentDescription = uiLabel("Configure session channel")
                                            )
                                        }
                                        MinimalActionIconButton(onClick = { pendingDeleteSessionId = session.id }) {
                                            Icon(
                                                Icons.Outlined.DeleteOutline,
                                                contentDescription = uiLabel("Delete session")
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.openSettings()
                                settingsPageName = SettingsPanelPage.Home.name
                                mainSurfaceName = MainSurface.Settings.name
                                uiScope.launch { drawerState.close() }
                            },
                        tonalElevation = 0.dp,
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.Settings,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.82f)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = tr("Settings", ""),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            topBar = {
                when (mainSurface) {
                    MainSurface.Chat -> TopAppBar(
                        modifier = Modifier.height(72.dp),
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        title = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.palmclaw_mark),
                                    contentDescription = null,
                                    modifier = Modifier.size(38.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Column {
                                    Text(
                                        text = "PalmClaw",
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (state.currentSessionId == AppSession.LOCAL_SESSION_ID) {
                                            tr("LOCAL", "")
                                        } else {
                                            state.currentSessionTitle
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = { uiScope.launch { drawerState.open() } }
                            ) {
                                Icon(Icons.Rounded.Menu, contentDescription = tr("Open menu", ""))
                            }
                        }
                    )

                    MainSurface.Settings -> TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        title = {
                            Column {
                                Text(
                                    text = settingsPageTitle,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = settingsPageSubtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = {
                                    if (settingsPage == SettingsPanelPage.Home) {
                                        mainSurfaceName = MainSurface.Chat.name
                                        uiScope.launch { drawerState.open() }
                                    } else {
                                        settingsPageName = SettingsPanelPage.Home.name
                                    }
                                }
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = tr("Back", ""))
                            }
                        },
                        actions = {
                            if (settingsPage == SettingsPanelPage.Home) {
                                IconButton(onClick = vm::toggleUiLanguage) {
                                    Icon(
                                        Icons.Rounded.Translate,
                                        contentDescription = tr("Switch language", ""),
                                        tint = if (state.settingsUseChinese) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                                IconButton(onClick = vm::toggleUiTheme) {
                                    Icon(
                                        imageVector = if (state.settingsDarkTheme) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                                        contentDescription = tr("Toggle theme", ""),
                                        tint = if (state.settingsDarkTheme) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                            if (state.settingsSaving) {
                                Text(
                                    text = tr("Saving...", ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    )
                }
            }
        ) { padding ->
            when (mainSurface) {
                MainSurface.Chat -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            contentPadding = PaddingValues(
                                start = 3.dp,
                                end = 3.dp,
                                bottom = chatInputBarClearance
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (showHistoryStatus) {
                                item(key = "history-status") {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (isLoadingOlderHistory) {
                                            CircularProgressIndicator(
                                                modifier = Modifier
                                                    .size(14.dp)
                                                    .padding(end = 6.dp),
                                                strokeWidth = 2.dp
                                            )
                                            Text(
                                                text = uiLabel("Loading chat..."),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        } else {
                                            val statusText = if (canLoadOlderHistory) {
                                                uiLabel("Chat")
                                            } else {
                                                uiLabel("Beginning of chat")
                                            }
                                            Text(
                                                text = statusText,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                        items(
                            items = visibleMessages,
                            key = { it.id },
                            contentType = { message ->
                                when {
                                    message.role == "user" -> "user"
                                    message.role == "tool" -> "tool"
                                    message.role == "system" -> "system"
                                    else -> "assistant"
                                }
                            }
                        ) { message ->
                            val isUser = message.role == "user"
                            val isTool = message.role == "tool"
                            val isSystem = message.role == "system"
                            val messageExpanded = isTool && expandedToolMessages[message.id] == true
                            val bubbleColors = when {
                                isUser -> ChatBubbleColors(
                                    container = MaterialTheme.colorScheme.primaryContainer,
                                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                                    header = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.88f),
                                    time = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                                )

                                isTool -> ChatBubbleColors(
                                    container = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f),
                                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                                    header = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.88f),
                                    time = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f)
                                )

                                isSystem -> ChatBubbleColors(
                                    container = MaterialTheme.colorScheme.tertiaryContainer,
                                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                                    header = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.88f),
                                    time = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.72f)
                                )

                                else -> ChatBubbleColors(
                                    container = MaterialTheme.colorScheme.surface,
                                    content = MaterialTheme.colorScheme.onSurface,
                                    header = MaterialTheme.colorScheme.onSurfaceVariant,
                                    time = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
                                )
                            }
                            val visibleContent = if (message.role == "assistant") {
                                displayedAssistantText[message.id] ?: message.content
                            } else if (isTool && message.isCollapsible) {
                                if (messageExpanded) message.expandedContent.orEmpty() else message.content
                            } else {
                                message.content
                            }
                            if (isUser) {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Surface(
                                        color = bubbleColors.container,
                                        contentColor = bubbleColors.content,
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.width(340.dp)
                                    ) {
                                        CompositionLocalProvider(LocalChatBubbleColors provides bubbleColors) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                            ) {
                                                ChatBubbleHeader(
                                                    label = state.userDisplayName.ifBlank { tr("You", "") },
                                                    createdAt = message.createdAt
                                                )
                                                MarkdownText(
                                                    markdown = visibleContent,
                                                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                                    inlineCodeBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                                                    quoteBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.56f),
                                                    codeBlockBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f),
                                                    fillMaxWidth = false,
                                                    contentColor = bubbleColors.content
                                                )
                                                if (message.attachments.isNotEmpty()) {
                                                    MediaAttachmentList(
                                                        attachments = message.attachments,
                                                        currentPreviewAudioRef = previewAudioRef,
                                                        currentPreviewAudioDurationMs = previewAudioDurationMs,
                                                        currentPreviewAudioPositionMs = previewAudioPositionMs,
                                                        onOpenAttachment = openAttachment,
                                                        onToggleAudioPreview = toggleAudioPreview
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            } else if (isTool) {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Surface(
                                        color = bubbleColors.container,
                                        contentColor = bubbleColors.content,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.width(340.dp)
                                    ) {
                                        CompositionLocalProvider(LocalChatBubbleColors provides bubbleColors) {
                                            Column(
                                                modifier = Modifier.padding(
                                                    start = 12.dp,
                                                    end = 12.dp,
                                                    top = 1.dp,
                                                    bottom = 10.dp
                                                )
                                            ) {
                                                ChatBubbleHeader(
                                                    label = tr("Tool", ""),
                                                    createdAt = message.createdAt,
                                                    topPadding = 7.dp
                                                )
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    if (message.isCollapsible && !messageExpanded) {
                                                        Text(
                                                            text = message.content,
                                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                                fontSize = 14.sp,
                                                                lineHeight = 14.sp
                                                            ),
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            modifier = Modifier.weight(1f),
                                                            color = bubbleColors.content
                                                        )
                                                    } else {
                                                        Spacer(modifier = Modifier.weight(1f))
                                                    }
                                                    if (message.isCollapsible) {
                                                        CompactTextAction(
                                                            label = uiLabel(if (messageExpanded) "Hide" else "Details"),
                                                            expanded = messageExpanded,
                                                            onClick = {
                                                                expandedToolMessages[message.id] = !messageExpanded
                                                            }
                                                        )
                                                    }
                                                }
                                                if (message.isCollapsible) {
                                                    if (messageExpanded) {
                                                        MarkdownText(
                                                            markdown = visibleContent,
                                                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                                            inlineCodeBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                                                            quoteBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.56f),
                                                            codeBlockBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f),
                                                            contentColor = bubbleColors.content
                                                        )
                                                    }
                                                } else {
                                                    MarkdownText(
                                                        markdown = visibleContent,
                                                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                                        inlineCodeBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                                                        quoteBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.56f),
                                                        codeBlockBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f),
                                                        contentColor = bubbleColors.content
                                                    )
                                                }
                                                val showAttachments = message.attachments.isNotEmpty() &&
                                                    (!message.isCollapsible || messageExpanded)
                                                if (showAttachments) {
                                                    MediaAttachmentList(
                                                        attachments = message.attachments,
                                                        currentPreviewAudioRef = previewAudioRef,
                                                        currentPreviewAudioDurationMs = previewAudioDurationMs,
                                                        currentPreviewAudioPositionMs = previewAudioPositionMs,
                                                        onOpenAttachment = openAttachment,
                                                        onToggleAudioPreview = toggleAudioPreview
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Surface(
                                        color = bubbleColors.container,
                                        contentColor = bubbleColors.content,
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.width(340.dp)
                                    ) {
                                        CompositionLocalProvider(LocalChatBubbleColors provides bubbleColors) {
                                            Column(
                                                modifier = Modifier
                                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                                            ) {
                                                ChatBubbleHeader(
                                                    label = if (isSystem) {
                                                        tr("System", "")
                                                    } else {
                                                        state.agentDisplayName.ifBlank { "PalmClaw" }
                                                    },
                                                    createdAt = message.createdAt
                                                )
                                                MarkdownText(
                                                    markdown = visibleContent,
                                                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                                    inlineCodeBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                                                    quoteBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.56f),
                                                    codeBlockBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f),
                                                    contentColor = bubbleColors.content
                                                )
                                                if (message.attachments.isNotEmpty()) {
                                                    MediaAttachmentList(
                                                        attachments = message.attachments,
                                                        currentPreviewAudioRef = previewAudioRef,
                                                        currentPreviewAudioDurationMs = previewAudioDurationMs,
                                                        currentPreviewAudioPositionMs = previewAudioPositionMs,
                                                        onOpenAttachment = openAttachment,
                                                        onToggleAudioPreview = toggleAudioPreview
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (showProcessingBubble) {
                            item(key = "processing-indicator") {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.width(340.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier
                                                    .size(14.dp)
                                                    .padding(end = 2.dp),
                                                strokeWidth = 2.dp
                                            )
                                            Text(
                                                uiLabel("Processing...")
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (listState.isScrollInProgress) scrollIndicator?.let { indicator ->
                        val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
                        val thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                        Canvas(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .offset(x = 8.dp)
                                .fillMaxHeight()
                                .padding(vertical = 12.dp)
                                .padding(end = 0.dp)
                                .width(4.dp)
                        ) {
                            drawRoundRect(
                                color = trackColor,
                                cornerRadius = CornerRadius(size.width / 2f, size.width / 2f)
                            )
                            val thumbHeight = size.height * indicator.thumbFraction
                            val maxTop = (size.height - thumbHeight).coerceAtLeast(0f)
                            val top = maxTop * indicator.progress
                            drawRoundRect(
                                color = thumbColor,
                                topLeft = Offset(0f, top),
                                size = Size(size.width, thumbHeight),
                                cornerRadius = CornerRadius(size.width / 2f, size.width / 2f)
                            )
                        }
                    }

                    if (showScrollToLatestButton) {
                        SmallFloatingActionButton(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = chatInputBarClearance - 8.dp)
                                .size(34.dp),
                            onClick = {
                                if (tailIndex >= 0) {
                                    followLatest = true
                                    uiScope.launch { moveToLatest(animated = true) }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = uiLabel("Scroll to latest"),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp, vertical = 8.dp)
                            .imePadding(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .align(Alignment.CenterVertically)
                                .onSizeChanged { inputBarSurfaceHeightPx = it.height },
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                            tonalElevation = 2.dp,
                            shadowElevation = 6.dp,
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 46.dp)
                                    .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 6.dp),
                                    contentAlignment = Alignment.TopStart
                                ) {
                                    if (state.input.isBlank()) {
                                        Text(
                                            text = tr("Ask anything", ""),
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontSize = 14.sp,
                                                lineHeight = 18.sp
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    BasicTextField(
                                        value = state.input,
                                        onValueChange = vm::onInputChanged,
                                        singleLine = false,
                                        maxLines = 6,
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                                            fontSize = 14.sp,
                                            lineHeight = 18.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        ),
                                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                val isStopState = state.isGenerating
                                Surface(
                                    color = if (isStopState) {
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                                    } else {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    },
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            if (state.isGenerating) {
                                                vm.stopGeneration()
                                            } else {
                                                followLatest = true
                                                scrollToLatestAfterSend = true
                                                vm.sendMessage()
                                            }
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        if (isStopState) {
                                            Box(
                                                modifier = Modifier
                                                    .size(11.dp)
                                                    .background(
                                                        color = MaterialTheme.colorScheme.error,
                                                        shape = RoundedCornerShape(2.dp)
                                                    )
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Rounded.KeyboardArrowUp,
                                                contentDescription = uiLabel("Send"),
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                }

                MainSurface.Settings -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        SettingsContent(
                            state = state,
                            page = settingsPage,
                            onNavigate = { target -> settingsPageName = target.name },
                            revealApiKey = revealApiKey,
                            onRevealToggle = { revealApiKey = !revealApiKey },
                            onProviderChange = vm::onSettingsProviderChanged,
                            onModelChange = vm::onSettingsModelChanged,
                            onApiKeyChange = vm::onSettingsApiKeyChanged,
                            onBaseUrlChange = vm::onSettingsBaseUrlChanged,
                            onTestProvider = vm::testProviderSettings,
                            onClearProviderTokenStats = vm::clearProviderTokenUsageStats,
                            onMaxRoundsChange = vm::onSettingsMaxRoundsChanged,
                            onToolResultMaxCharsChange = vm::onSettingsToolResultMaxCharsChanged,
                            onMemoryConsolidationWindowChange = vm::onSettingsMemoryConsolidationWindowChanged,
                            onLlmCallTimeoutSecondsChange = vm::onSettingsLlmCallTimeoutSecondsChanged,
                            onDefaultToolTimeoutSecondsChange = vm::onSettingsDefaultToolTimeoutSecondsChanged,
                            onContextMessagesChange = vm::onSettingsContextMessagesChanged,
                            onAlwaysOnEnabledChange = vm::onAlwaysOnEnabledChanged,
                            onAlwaysOnKeepScreenAwakeChange = vm::onAlwaysOnKeepScreenAwakeChanged,
                            onRefreshAlwaysOnStatus = vm::refreshAlwaysOnDiagnostics,
                            onCronEnabledChange = vm::onSettingsCronEnabledChanged,
                            onCronMinEveryMsChange = vm::onSettingsCronMinEveryMsChanged,
                            onCronMaxJobsChange = vm::onSettingsCronMaxJobsChanged,
                            onRefreshCronJobs = vm::refreshCronJobs,
                            onSetCronJobEnabled = vm::setCronJobEnabled,
                            onRunCronJobNow = vm::runCronJobNow,
                            onRemoveCronJob = vm::removeCronJob,
                            onHeartbeatEnabledChange = vm::onSettingsHeartbeatEnabledChanged,
                            onHeartbeatIntervalSecondsChange = vm::onSettingsHeartbeatIntervalSecondsChanged,
                            onSetSessionChannelEnabled = vm::setSessionChannelEnabled,
                            onMcpEnabledChange = vm::onSettingsMcpEnabledChanged,
                            onAddMcpServer = vm::addSettingsMcpServer,
                            onRemoveMcpServer = vm::removeSettingsMcpServer,
                            onMcpServerNameChange = vm::updateSettingsMcpServerName,
                            onMcpServerUrlChange = vm::updateSettingsMcpServerUrl,
                            onMcpAuthTokenChange = vm::updateSettingsMcpServerAuthToken,
                            onMcpToolTimeoutSecondsChange = vm::updateSettingsMcpServerTimeout,
                            onTriggerHeartbeatNow = vm::triggerHeartbeatNow,
                            onOpenHeartbeatEditor = {
                                vm.loadHeartbeatDocument()
                                showHeartbeatEditor = true
                            },
                            onRefreshCronLogs = vm::refreshCronLogs,
                            onClearCronLogs = vm::clearCronLogs,
                            onRefreshAgentLogs = vm::refreshAgentLogs,
                            onClearAgentLogs = vm::clearAgentLogs,
                            onSaveCurrentPage = { target ->
                                when (target) {
                                    SettingsPanelPage.AlwaysOn -> vm.saveAlwaysOnSettings(showSuccessMessage = false, showErrorMessage = false)
                                    SettingsPanelPage.Provider -> vm.saveProviderSettings(showSuccessMessage = false, showErrorMessage = false)
                                    SettingsPanelPage.Runtime -> vm.saveAgentRuntimeSettings(showSuccessMessage = false, showErrorMessage = false)
                                    SettingsPanelPage.Cron -> vm.saveCronSettings(showSuccessMessage = false, showErrorMessage = false)
                                    SettingsPanelPage.Heartbeat -> vm.saveHeartbeatSettings(showSuccessMessage = false, showErrorMessage = false)
                                    SettingsPanelPage.Channels -> vm.saveChannelsSettings(showSuccessMessage = false, showErrorMessage = false)
                                    SettingsPanelPage.Mcp -> vm.saveMcpSettings(showSuccessMessage = false, showErrorMessage = false)
                                    else -> Unit
                                }
                            }
                        )
                    }
                    SnackbarHost(
                        hostState = settingsSnackbarHostState,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}
@Composable
private fun MediaAttachmentList(
    attachments: List<UiMediaAttachment>,
    currentPreviewAudioRef: String?,
    currentPreviewAudioDurationMs: Int,
    currentPreviewAudioPositionMs: Int,
    onOpenAttachment: (UiMediaAttachment) -> Unit,
    onToggleAudioPreview: (UiMediaAttachment) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        attachments.forEach { attachment ->
            when (attachment.kind) {
                UiMediaKind.Image -> {
                    ImageAttachmentCard(
                        attachment = attachment,
                        onOpenAttachment = onOpenAttachment
                    )
                }
                UiMediaKind.Video -> {
                    VideoAttachmentCard(
                        attachment = attachment,
                        onOpenAttachment = onOpenAttachment
                    )
                }
                UiMediaKind.Audio -> {
                    val isPlaying = currentPreviewAudioRef == attachment.reference
                    AudioAttachmentCard(
                        attachment = attachment,
                        isPlaying = isPlaying,
                        durationMs = if (isPlaying) currentPreviewAudioDurationMs else 0,
                        positionMs = if (isPlaying) currentPreviewAudioPositionMs else 0,
                        onTogglePlayback = { onToggleAudioPreview(attachment) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageAttachmentCard(
    attachment: UiMediaAttachment,
    onOpenAttachment: (UiMediaAttachment) -> Unit
) {
    val uri = remember(attachment.reference) { toAttachmentUri(attachment.reference) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 220.dp)
                    .clickable { onOpenAttachment(attachment) }
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { ctx ->
                        ImageView(ctx).apply {
                            adjustViewBounds = true
                            scaleType = ImageView.ScaleType.CENTER_CROP
                        }
                    },
                    update = { imageView ->
                        if (uri == null) {
                            imageView.setImageResource(android.R.drawable.ic_menu_report_image)
                        } else {
                            runCatching { imageView.setImageURI(uri) }
                                .onFailure {
                                    imageView.setImageResource(android.R.drawable.ic_menu_report_image)
                                }
                        }
                    }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = attachment.label,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onOpenAttachment(attachment) }) {
                    Text(uiLabel("Preview"))
                }
            }
        }
    }
}

@Composable
private fun VideoAttachmentCard(
    attachment: UiMediaAttachment,
    onOpenAttachment: (UiMediaAttachment) -> Unit
) {
    val context = LocalContext.current
    val uri = remember(attachment.reference) { toAttachmentUri(attachment.reference) }
    val videoBackgroundArgb = MaterialTheme.colorScheme.surfaceVariant.toArgb()
    val exoPlayer = remember(attachment.reference, uri) {
        uri?.let { mediaUri ->
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(mediaUri))
                prepare()
                playWhenReady = false
            }
        }
    }
    var isPlaying by rememberSaveable(attachment.reference) { mutableStateOf(false) }
    var durationMs by rememberSaveable(attachment.reference) { mutableStateOf(0) }
    var positionMs by rememberSaveable(attachment.reference) { mutableStateOf(0) }

    DisposableEffect(exoPlayer) {
        val player = exoPlayer
        if (player == null) {
            onDispose {}
        } else {
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        durationMs = player.duration.coerceAtLeast(0L).toInt()
                    }
                    if (state == Player.STATE_ENDED) {
                        positionMs = durationMs
                        isPlaying = false
                    }
                }
            }
            player.addListener(listener)
            onDispose {
                player.removeListener(listener)
                runCatching { player.release() }
                isPlaying = false
            }
        }
    }

    LaunchedEffect(exoPlayer, isPlaying) {
        val player = exoPlayer ?: return@LaunchedEffect
        if (!isPlaying) return@LaunchedEffect
        while (isPlaying) {
            val duration = player.duration.coerceAtLeast(0L).toInt()
            val position = player.currentPosition.coerceAtLeast(0L).toInt()
            durationMs = duration
            positionMs = position.coerceAtMost(duration)
            delay(250)
        }
    }

    val toggleVideoPlayback: () -> Unit = toggle@{
        val player = exoPlayer ?: return@toggle
        if (isPlaying) {
            positionMs = player.currentPosition.coerceAtLeast(0L).toInt()
            player.pause()
            isPlaying = false
        } else {
            runCatching {
                if (positionMs > 0) {
                    player.seekTo(positionMs.toLong().coerceAtMost(player.duration.coerceAtLeast(0L)))
                }
                player.play()
                isPlaying = true
            }.onFailure {
                player.pause()
                isPlaying = false
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp, max = 220.dp)
            ) {
                if (exoPlayer == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = uiLabel("Video unavailable"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                player = exoPlayer
                                setShutterBackgroundColor(videoBackgroundArgb)
                                setBackgroundColor(videoBackgroundArgb)
                            }
                        },
                        update = { view ->
                            if (view.player !== exoPlayer) {
                                view.player = exoPlayer
                            }
                        }
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = uiLabel("Video"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = attachment.label,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (durationMs > 0) {
                val progress = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                SimpleProgressBar(progress = progress)
                Text(
                    text = "${formatDuration(positionMs)} / ${formatDuration(durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = toggleVideoPlayback) {
                    Text(uiLabel(if (isPlaying) "Pause" else "Play"))
                }
                TextButton(onClick = { onOpenAttachment(attachment) }) {
                    Text(uiLabel("Open"))
                }
            }
        }
    }
}

@Composable
private fun AudioAttachmentCard(
    attachment: UiMediaAttachment,
    isPlaying: Boolean,
    durationMs: Int,
    positionMs: Int,
    onTogglePlayback: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = uiLabel("Audio"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = attachment.label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (durationMs > 0) {
                val progress = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                SimpleProgressBar(progress = progress)
                Text(
                    text = "${formatDuration(positionMs)} / ${formatDuration(durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onTogglePlayback) {
                    Text(uiLabel(if (isPlaying) "Stop" else "Play"))
                }
            }
        }
    }
}

@Composable
private fun SimpleProgressBar(progress: Float) {
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
    val fill = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
    ) {
        drawRoundRect(
            color = track,
            cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
        )
        drawRoundRect(
            color = fill,
            size = Size(size.width * progress.coerceIn(0f, 1f), size.height),
            cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
        )
    }
}

private fun formatDuration(valueMs: Int): String {
    val totalSec = (valueMs.coerceAtLeast(0) / 1000)
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

private fun toAttachmentUri(reference: String): Uri? {
    val raw = reference.trim()
    if (raw.isBlank()) return null
    return when {
        raw.startsWith("content://", true) ||
            raw.startsWith("file://", true) ||
            raw.startsWith("http://", true) ||
            raw.startsWith("https://", true) -> Uri.parse(raw)
        else -> Uri.fromFile(File(raw))
    }
}

private fun mediaMimeTypeForKind(kind: UiMediaKind): String {
    return when (kind) {
        UiMediaKind.Image -> "image/*"
        UiMediaKind.Video -> "video/*"
        UiMediaKind.Audio -> "audio/*"
    }
}

@Composable
private fun ScrollableLogWindow(
    title: String,
    content: String,
    emptyText: String,
    actions: @Composable (() -> Unit)? = null
) {
    val logScrollState = rememberScrollState()
    Surface(
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = uiLabel(title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                actions?.invoke()
            }
            Surface(
                tonalElevation = 0.dp,
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.58f),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 132.dp, max = 220.dp)
            )
            {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(logScrollState)
                        .padding(horizontal = 12.dp, vertical = 11.dp)
                ) {
                    Text(
                        text = content.ifBlank { uiLabel(emptyText) },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            lineHeight = 18.sp
                        ),
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.92f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionSetupStepCard(
    step: Int,
    text: String
) {
    Surface(
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = CircleShape
            ) {
                Text(
                    text = step.toString(),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = uiLabel(text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CompactTextAction(
    label: String,
    expanded: Boolean = false,
    onClick: () -> Unit
) {
    val bubbleColors = LocalChatBubbleColors.current
    val actionColor = if (bubbleColors.content == Color.Unspecified) {
        MaterialTheme.colorScheme.primary
    } else {
        bubbleColors.content
    }
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 3.dp, top = 0.dp, bottom = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                lineHeight = 12.sp
            ),
            color = actionColor
        )
        Icon(
            imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(9.dp),
            tint = actionColor
        )
    }
}

@Composable
private fun MinimalActionIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(32.dp)
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            Box(
                modifier = Modifier.size(18.dp),
                contentAlignment = Alignment.Center
            ) {
                content()
            }
        }
    }
}

@Composable
private fun PalmClawSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            checkedBorderColor = MaterialTheme.colorScheme.primary,
            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            disabledCheckedThumbColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
            disabledCheckedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
            disabledUncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            disabledUncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
        )
    )
}

@Composable
private fun AlwaysOnStatusRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = uiLabel(label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = uiLabel(value),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = uiLabel(title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    subtitle?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = uiLabel(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (actions != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun SettingsActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.32f),
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            disabledContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.14f),
            disabledContentColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.42f)
        ),
        border = BorderStroke(
            1.dp,
            if (enabled) {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
            }
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        modifier = Modifier.height(32.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(uiLabel(text), maxLines = 1)
    }
}

@Composable
private fun SettingsSectionIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    rotateZ: Float = 0f
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Box(
            modifier = Modifier.size(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = rotateZ }
            )
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = uiLabel(title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = uiLabel(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        PalmClawSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SettingsValueRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = uiLabel(label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = uiLabel(value),
            style = MaterialTheme.typography.labelLarge,
            color = valueColor,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

@Composable
private fun SettingsInfoBlock(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    maxLines: Int = Int.MAX_VALUE
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = uiLabel(label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = uiLabel(value),
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettingsAdvancedSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    SettingsSectionCard(
        title = tr("Advanced", ""),
        subtitle = subtitle ?: tr("Optional fields only.", ""),
        actions = {
            SettingsSectionIconButton(
                icon = Icons.Rounded.KeyboardArrowUp,
                contentDescription = if (expanded) tr("Collapse advanced", "") else tr("Expand advanced", ""),
                onClick = onToggle,
                rotateZ = if (expanded) 0f else 180f
            )
        }
    ) {
        if (expanded) {
            content()
        }
    }
}

private enum class MainSurface {
    Chat,
    Settings
}

private enum class OnboardingStep {
    Language,
    Provider,
    Identity
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FirstRunOnboardingScreen(
    state: ChatUiState,
    step: OnboardingStep,
    onStepChange: (OnboardingStep) -> Unit,
    onLanguageSelected: (Boolean) -> Unit,
    onProviderChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onUserDisplayNameChange: (String) -> Unit,
    onAgentDisplayNameChange: (String) -> Unit,
    onTestProvider: () -> Unit,
    onComplete: () -> Unit
) {
    val providerOptions = remember { ProviderCatalog.all() }
    var providerMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val stepOrder = remember { OnboardingStep.entries.toList() }
    val stepIndex = stepOrder.indexOf(step).coerceAtLeast(0)
    val canMoveNext = when (step) {
        OnboardingStep.Language -> true
        OnboardingStep.Provider -> state.settingsBaseUrl.isNotBlank() &&
            state.settingsModel.isNotBlank() &&
            state.settingsApiKey.isNotBlank()
        OnboardingStep.Identity -> state.onboardingUserDisplayName.trim().isNotBlank() &&
            state.onboardingAgentDisplayName.trim().isNotBlank()
    }
    val nextStep = stepOrder.getOrNull(stepIndex + 1)
    val previousStep = stepOrder.getOrNull(stepIndex - 1)

    BackHandler(enabled = previousStep != null) {
        previousStep?.let(onStepChange)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                OnboardingHeroCard(
                    title = "PalmClaw",
                    subtitle = tr(
                        "PalmClaw is the private AI assistant on your phone: simple, safe, and ready anytime.",
                        ""
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    stepOrder.forEachIndexed { index, item ->
                        OnboardingStepChip(
                            modifier = Modifier.weight(1f),
                            title = "${index + 1} " + when (item) {
                                OnboardingStep.Language -> tr("Language", "")
                                OnboardingStep.Provider -> tr("Provider", "")
                                OnboardingStep.Identity -> tr("Names", "")
                            },
                            active = index == stepIndex
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (step) {
                        OnboardingStep.Language -> {
                            SettingsSectionCard(
                                title = tr("Language", ""),
                                subtitle = tr(
                                    "Choose your app language. You can change it later.",
                                    ""
                                )
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OnboardingChoiceCard(
                                        modifier = Modifier.weight(1f),
                                        title = "English",
                                        subtitle = "App UI in English",
                                        selected = !state.settingsUseChinese,
                                        onClick = { onLanguageSelected(false) }
                                    )
                                    OnboardingChoiceCard(
                                        modifier = Modifier.weight(1f),
                                        title = "中文",
                                        subtitle = "应用界面使用中文",
                                        selected = state.settingsUseChinese,
                                        onClick = { onLanguageSelected(true) }
                                    )
                                }
                            }
                        }

                        OnboardingStep.Provider -> {
                            val selectedProvider = ProviderCatalog.resolve(state.settingsProvider)
                            SettingsSectionCard(
                                title = tr("Provider", ""),
                                subtitle = tr(
                                    "Connect the model provider used for chat and tools.",
                                    ""
                                )
                            ) {
                                ExposedDropdownMenuBox(
                                    expanded = providerMenuExpanded,
                                    onExpandedChange = { providerMenuExpanded = !providerMenuExpanded }
                                ) {
                                    OutlinedTextField(
                                        value = selectedProvider.title,
                                        onValueChange = {},
                                        modifier = Modifier
                                            .menuAnchor()
                                            .fillMaxWidth(),
                                        readOnly = true,
                                        label = { Text(tr("Provider", "")) },
                                        trailingIcon = {
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenuExpanded)
                                        },
                                        singleLine = true
                                    )
                                    DropdownMenu(
                                        expanded = providerMenuExpanded,
                                        onDismissRequest = { providerMenuExpanded = false }
                                    ) {
                                        providerOptions.forEach { option ->
                                            DropdownMenuItem(
                                                text = { Text(option.title) },
                                                onClick = {
                                                    onProviderChange(option.id)
                                                    providerMenuExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                                OutlinedTextField(
                                    value = state.settingsBaseUrl,
                                    onValueChange = onBaseUrlChange,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(tr("Base URL", "")) },
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = state.settingsModel,
                                    onValueChange = onModelChange,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(tr("Model", "")) },
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = state.settingsApiKey,
                                    onValueChange = onApiKeyChange,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(tr("API Key", "API Key")) },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation()
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    SettingsActionButton(
                                        text = if (state.settingsProviderTesting) tr("Testing...", "") else tr("Test API", ""),
                                        icon = Icons.Rounded.Refresh,
                                        onClick = onTestProvider,
                                        enabled = !state.settingsProviderTesting
                                    )
                                }
                            }
                        }

                        OnboardingStep.Identity -> {
                            SettingsSectionCard(
                                title = tr("Names", ""),
                                subtitle = tr(
                                    "Choose the names used in chat. You can update them later.",
                                    ""
                                )
                            ) {
                                OutlinedTextField(
                                    value = state.onboardingUserDisplayName,
                                    onValueChange = onUserDisplayNameChange,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(tr("What should the agent call you?", "")) },
                                    placeholder = { Text(if (state.settingsUseChinese) "\u4F60" else "You") },
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = state.onboardingAgentDisplayName,
                                    onValueChange = onAgentDisplayNameChange,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(tr("What is the agent's name?", "")) },
                                    placeholder = { Text("PalmClaw") },
                                    singleLine = true
                                )
                            }
                        }
                    }

                    state.settingsInfo?.takeIf { it.isNotBlank() }?.let { info ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Text(
                                text = info,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                previousStep?.let {
                    OnboardingNavIconButton(
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = tr("Back", ""),
                        filled = false,
                        onClick = { onStepChange(it) }
                    )
                } ?: Spacer(modifier = Modifier.size(52.dp))
                Spacer(modifier = Modifier.weight(1f))
                if (nextStep != null) {
                    OnboardingNavIconButton(
                        icon = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = tr("Next", ""),
                        filled = true,
                        enabled = canMoveNext,
                        onClick = { onStepChange(nextStep) }
                    )
                } else {
                    OnboardingNavIconButton(
                        icon = Icons.Rounded.PlayArrow,
                        contentDescription = if (state.settingsSaving) {
                            tr("Saving...", "")
                        } else {
                            tr("Start Chat", "")
                        },
                        filled = true,
                        enabled = canMoveNext && !state.settingsSaving,
                        loading = state.settingsSaving,
                        onClick = onComplete
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingHeroCard(
    title: String,
    subtitle: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.palmclaw_mark),
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f)
            )
        }
    }
}

@Composable
private fun OnboardingNavIconButton(
    icon: ImageVector,
    contentDescription: String,
    filled: Boolean,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = if (filled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentColor = if (filled) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        border = if (filled) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.36f))
        },
        tonalElevation = if (filled) 4.dp else 0.dp
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = if (enabled) {
                        LocalContentColor.current
                    } else {
                        LocalContentColor.current.copy(alpha = 0.38f)
                    }
                )
            }
        }
    }
}

@Composable
private fun OnboardingStepChip(
    modifier: Modifier = Modifier,
    title: String,
    active: Boolean
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (active) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun OnboardingChoiceCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        tonalElevation = if (selected) 2.dp else 0.dp,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.42f)
            }
        ),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surface
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 108.dp)
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private enum class SessionSettingsPage {
    Menu,
    Configure,
    Diagnostics
}

private enum class SettingsPanelPage {
    Home,
    AlwaysOn,
    Runtime,
    Provider,
    Channels,
    Cron,
    Heartbeat,
    Mcp,
    Guide;

    fun title(isChinese: Boolean): String = when (this) {
        Home -> localizedText("Settings", useChinese = isChinese)
        AlwaysOn -> localizedText("Always-on", useChinese = isChinese)
        Runtime -> localizedText("Runtime", useChinese = isChinese)
        Provider -> localizedText("Provider", useChinese = isChinese)
        Channels -> localizedText("Channels", useChinese = isChinese)
        Cron -> "Cron"
        Heartbeat -> localizedText("Heartbeat", useChinese = isChinese)
        Mcp -> "MCP"
        Guide -> localizedText("User Guide", useChinese = isChinese)
    }

    fun subtitle(isChinese: Boolean): String = when (this) {
        Home -> localizedText("Choose a section.", useChinese = isChinese)
        AlwaysOn -> localizedText("Background service and reliability.", useChinese = isChinese)
        Runtime -> localizedText("Limits and logs.", useChinese = isChinese)
        Provider -> localizedText("Endpoint and model.", useChinese = isChinese)
        Channels -> localizedText("Session routes.", useChinese = isChinese)
        Cron -> localizedText("Jobs and limits.", useChinese = isChinese)
        Heartbeat -> localizedText("Interval and doc.", useChinese = isChinese)
        Mcp -> localizedText("Remote servers.", useChinese = isChinese)
        Guide -> localizedText("Core features and how to use them.", useChinese = isChinese)
    }
}

private data class SettingsMenuItem(
    val page: SettingsPanelPage,
    val title: String,
    val subtitle: String
)

@Composable
private fun AlwaysOnModeContent(
    state: ChatUiState,
    onEnabledChange: (Boolean) -> Unit,
    onKeepScreenAwakeChange: (Boolean) -> Unit,
    onRefreshStatus: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            tonalElevation = 1.dp,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = tr("Keep channels alive in background.", ""),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = tr("Best with charging and battery optimization off.", ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.settingsInfo?.takeIf { it.isNotBlank() }?.let { info ->
                    Text(
                        text = info,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Surface(
            tonalElevation = 1.dp,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tr("Always-on Service", ""),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    PalmClawSwitch(
                        checked = state.alwaysOnEnabled,
                        onCheckedChange = onEnabledChange
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tr("Keep Screen Awake", ""),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    PalmClawSwitch(
                        checked = state.alwaysOnKeepScreenAwake,
                        onCheckedChange = onKeepScreenAwakeChange
                    )
                }
            }
        }

        Surface(
            tonalElevation = 1.dp,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = tr("Status", ""),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                AlwaysOnStatusRow(uiLabel("Service"), uiLabel(if (state.alwaysOnServiceRunning) "Running" else "Off"))
                AlwaysOnStatusRow(uiLabel("Gateway"), uiLabel(if (state.alwaysOnGatewayRunning) "Ready" else "Stopped"))
                AlwaysOnStatusRow(uiLabel("Adapters"), state.alwaysOnActiveAdapterCount.toString())
                AlwaysOnStatusRow(uiLabel("Network"), uiLabel(if (state.alwaysOnNetworkConnected) "Connected" else "Offline"))
                AlwaysOnStatusRow(uiLabel("Charging"), uiLabel(if (state.alwaysOnCharging) "Yes" else "No"))
                AlwaysOnStatusRow(
                    uiLabel("Battery optimization"),
                    uiLabel(if (state.alwaysOnBatteryOptimizationIgnored) "Ignored" else "On")
                )
                AlwaysOnStatusRow(
                    uiLabel("Notification"),
                    uiLabel(if (state.alwaysOnNotificationActive) "Visible" else "Hidden")
                )
                if (state.alwaysOnLastError.isNotBlank()) {
                    Text(
                        text = "${uiLabel("Last Error")}: ${state.alwaysOnLastError}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SettingsActionButton(
                        text = uiLabel("Refresh"),
                        icon = Icons.Rounded.Refresh,
                        onClick = onRefreshStatus
                    )
                    SettingsActionButton(
                        text = uiLabel("Battery"),
                        icon = Icons.Outlined.Settings,
                        onClick = {
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    state: ChatUiState,
    page: SettingsPanelPage,
    onNavigate: (SettingsPanelPage) -> Unit,
    revealApiKey: Boolean,
    onRevealToggle: () -> Unit,
    onProviderChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onTestProvider: () -> Unit,
    onClearProviderTokenStats: () -> Unit,
    onMaxRoundsChange: (String) -> Unit,
    onToolResultMaxCharsChange: (String) -> Unit,
    onMemoryConsolidationWindowChange: (String) -> Unit,
    onLlmCallTimeoutSecondsChange: (String) -> Unit,
    onDefaultToolTimeoutSecondsChange: (String) -> Unit,
    onContextMessagesChange: (String) -> Unit,
    onCronEnabledChange: (Boolean) -> Unit,
    onCronMinEveryMsChange: (String) -> Unit,
    onCronMaxJobsChange: (String) -> Unit,
    onRefreshCronJobs: () -> Unit,
    onSetCronJobEnabled: (String, Boolean) -> Unit,
    onRunCronJobNow: (String) -> Unit,
    onRemoveCronJob: (String) -> Unit,
    onHeartbeatEnabledChange: (Boolean) -> Unit,
    onHeartbeatIntervalSecondsChange: (String) -> Unit,
    onSetSessionChannelEnabled: (String, Boolean) -> Unit,
    onMcpEnabledChange: (Boolean) -> Unit,
    onAddMcpServer: () -> Unit,
    onRemoveMcpServer: (String) -> Unit,
    onMcpServerNameChange: (String, String) -> Unit,
    onMcpServerUrlChange: (String, String) -> Unit,
    onMcpAuthTokenChange: (String, String) -> Unit,
    onMcpToolTimeoutSecondsChange: (String, String) -> Unit,
    onTriggerHeartbeatNow: () -> Unit,
    onOpenHeartbeatEditor: () -> Unit,
    onRefreshCronLogs: () -> Unit,
    onClearCronLogs: () -> Unit,
    onRefreshAgentLogs: () -> Unit,
    onClearAgentLogs: () -> Unit,
    onSaveCurrentPage: (SettingsPanelPage) -> Unit,
    onAlwaysOnEnabledChange: (Boolean) -> Unit,
    onAlwaysOnKeepScreenAwakeChange: (Boolean) -> Unit,
    onRefreshAlwaysOnStatus: () -> Unit
) {
    val menuItems = listOf(
        SettingsMenuItem(SettingsPanelPage.AlwaysOn, tr("Always-on", ""), tr("Background service and reliability", "")),
        SettingsMenuItem(SettingsPanelPage.Runtime, tr("Runtime", ""), tr("Limits and logs", "")),
        SettingsMenuItem(SettingsPanelPage.Provider, tr("Provider", ""), tr("Endpoint and model", "")),
        SettingsMenuItem(SettingsPanelPage.Channels, tr("Channels", ""), tr("Session routes", "")),
        SettingsMenuItem(SettingsPanelPage.Cron, "Cron", tr("Jobs and limits", "")),
        SettingsMenuItem(SettingsPanelPage.Heartbeat, tr("Heartbeat", ""), tr("Interval and doc", "")),
        SettingsMenuItem(SettingsPanelPage.Mcp, "MCP", tr("Remote servers", "")),
        SettingsMenuItem(SettingsPanelPage.Guide, tr("User Guide", ""), tr("Core features and how to use them.", ""))
    )
    var showCronLogs by rememberSaveable(page) { mutableStateOf(false) }
    var providerMenuExpanded by rememberSaveable(page) { mutableStateOf(false) }
    var guideSectionName by rememberSaveable(page) { mutableStateOf(UserGuideSection.Overview.name) }
    val providerOptions = remember { ProviderCatalog.all() }
    val guideSection = runCatching { UserGuideSection.valueOf(guideSectionName) }
        .getOrDefault(UserGuideSection.Overview)
    val autoSaveKey: Any? = when (page) {
        SettingsPanelPage.AlwaysOn -> listOf(
            state.alwaysOnEnabled,
            state.alwaysOnKeepScreenAwake
        )
        SettingsPanelPage.Provider -> listOf(
            state.settingsProvider,
            state.settingsBaseUrl,
            state.settingsModel,
            state.settingsApiKey
        )
        SettingsPanelPage.Runtime -> listOf(
            state.settingsMaxToolRounds,
            state.settingsToolResultMaxChars,
            state.settingsMemoryConsolidationWindow,
            state.settingsLlmCallTimeoutSeconds,
            state.settingsLlmConnectTimeoutSeconds,
            state.settingsLlmReadTimeoutSeconds,
            state.settingsDefaultToolTimeoutSeconds,
            state.settingsContextMessages,
            state.settingsToolArgsPreviewMaxChars
        )
        SettingsPanelPage.Cron -> listOf(
            state.settingsCronEnabled,
            state.settingsCronMinEveryMs,
            state.settingsCronMaxJobs
        )
        SettingsPanelPage.Heartbeat -> listOf(
            state.settingsHeartbeatEnabled,
            state.settingsHeartbeatIntervalSeconds
        )
        SettingsPanelPage.Mcp -> listOf(
            state.settingsMcpEnabled,
            state.settingsMcpServers
        )
        else -> null
    }
    var autoSavePrimed by rememberSaveable(page) { mutableStateOf(false) }

    LaunchedEffect(page, autoSaveKey) {
        if (autoSaveKey == null) return@LaunchedEffect
        if (!autoSavePrimed) {
            autoSavePrimed = true
            return@LaunchedEffect
        }
        delay(650)
        onSaveCurrentPage(page)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when (page) {
            SettingsPanelPage.Home -> {
                menuItems.forEach { item ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigate(item.page) },
                        tonalElevation = 1.dp,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = item.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            SettingsPanelPage.AlwaysOn -> {
                AlwaysOnModeContent(
                    state = state,
                    onEnabledChange = onAlwaysOnEnabledChange,
                    onKeepScreenAwakeChange = onAlwaysOnKeepScreenAwakeChange,
                    onRefreshStatus = onRefreshAlwaysOnStatus
                )
            }

            SettingsPanelPage.Provider -> {
                val selectedProvider = ProviderCatalog.resolve(state.settingsProvider)
                SettingsSectionCard(
                    title = uiLabel("Provider"),
                    subtitle = uiLabel("Model, endpoint, and API key.")
                ) {
                    ExposedDropdownMenuBox(
                        expanded = providerMenuExpanded,
                        onExpandedChange = { providerMenuExpanded = !providerMenuExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedProvider.title,
                            onValueChange = {},
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                            readOnly = true,
                            label = { Text(uiLabel("Provider")) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenuExpanded)
                            },
                            singleLine = true
                        )
                        DropdownMenu(
                            expanded = providerMenuExpanded,
                            onDismissRequest = { providerMenuExpanded = false }
                        ) {
                            providerOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.title) },
                                    onClick = {
                                        onProviderChange(option.id)
                                        providerMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = state.settingsBaseUrl,
                        onValueChange = onBaseUrlChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(uiLabel("Base URL")) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = state.settingsModel,
                        onValueChange = onModelChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(uiLabel("Model")) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = state.settingsApiKey,
                        onValueChange = onApiKeyChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(uiLabel("API Key")) },
                        singleLine = true,
                        visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SettingsActionButton(
                            text = if (revealApiKey) uiLabel("Hide Key") else uiLabel("Show Key"),
                            icon = if (revealApiKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            onClick = onRevealToggle
                        )
                        SettingsActionButton(
                            text = if (state.settingsProviderTesting) uiLabel("Testing...") else uiLabel("Test API"),
                            icon = Icons.Rounded.Refresh,
                            onClick = onTestProvider,
                            enabled = !state.settingsProviderTesting
                        )
                    }
                }
                val inputTokens = state.settingsTokenInput.coerceAtLeast(0L)
                val outputTokens = state.settingsTokenOutput.coerceAtLeast(0L)
                val totalTokens = state.settingsTokenTotal.coerceAtLeast(0L)
                val cachedInputTokens = state.settingsTokenCachedInput.coerceAtLeast(0L)
                val requests = state.settingsTokenRequests.coerceAtLeast(0L)
                val cacheHitRate = if (inputTokens > 0L) {
                    (cachedInputTokens.toDouble() / inputTokens.toDouble()) * 100.0
                } else {
                    0.0
                }
                SettingsSectionCard(
                    title = uiLabel("Token Usage"),
                    subtitle = uiLabel("Current totals for this provider."),
                    actions = {
                        SettingsActionButton(
                            text = uiLabel("Clear"),
                            icon = Icons.Outlined.DeleteOutline,
                            onClick = onClearProviderTokenStats
                        )
                    }
                ) {
                    SettingsValueRow(uiLabel("Input"), inputTokens.toString())
                    SettingsValueRow(uiLabel("Output"), outputTokens.toString())
                    SettingsValueRow(uiLabel("Total"), totalTokens.toString())
                    SettingsValueRow(uiLabel("Cached Input"), cachedInputTokens.toString())
                    SettingsValueRow(uiLabel("Cache Hit Rate"), "${"%.1f".format(cacheHitRate)}%")
                    SettingsValueRow(uiLabel("Requests"), requests.toString())
                }
            }

            SettingsPanelPage.Runtime -> {
                val contextMessagesValue = state.settingsContextMessages.toIntOrNull()
                    ?.coerceIn(AppLimits.MIN_CONTEXT_MESSAGES, AppLimits.MAX_CONTEXT_MESSAGES)
                    ?: AppLimits.DEFAULT_CONTEXT_MESSAGES
                RuntimeSliderSetting(
                    title = uiLabel("Context Messages"),
                    description = uiLabel("Recent messages kept for each model turn."),
                    value = contextMessagesValue,
                    min = AppLimits.MIN_CONTEXT_MESSAGES,
                    max = AppLimits.MAX_CONTEXT_MESSAGES,
                    stepSize = 1,
                    onValueChange = { onContextMessagesChange(it.toString()) }
                )

                val maxRoundsValue = state.settingsMaxToolRounds.toIntOrNull()
                    ?.coerceIn(AppLimits.MIN_MAX_TOOL_ROUNDS, AppLimits.MAX_MAX_TOOL_ROUNDS)
                    ?: AppLimits.DEFAULT_MAX_TOOL_ROUNDS
                RuntimeSliderSetting(
                    title = uiLabel("Max Tool Rounds"),
                    description = uiLabel("How many tool-call loops one run may use."),
                    value = maxRoundsValue,
                    min = AppLimits.MIN_MAX_TOOL_ROUNDS,
                    max = AppLimits.MAX_MAX_TOOL_ROUNDS,
                    stepSize = 1,
                    onValueChange = { onMaxRoundsChange(it.toString()) }
                )

                val toolResultMaxCharsValue = state.settingsToolResultMaxChars.toIntOrNull()
                    ?.coerceIn(AppLimits.MIN_TOOL_RESULT_MAX_CHARS, AppLimits.MAX_TOOL_RESULT_MAX_CHARS)
                    ?: AppLimits.DEFAULT_TOOL_RESULT_MAX_CHARS
                RuntimeSliderSetting(
                    title = uiLabel("Tool Result Max Chars"),
                    description = uiLabel("Maximum tool output kept in chat context."),
                    value = toolResultMaxCharsValue,
                    min = AppLimits.MIN_TOOL_RESULT_MAX_CHARS,
                    max = AppLimits.MAX_TOOL_RESULT_MAX_CHARS,
                    stepSize = 100,
                    onValueChange = { onToolResultMaxCharsChange(it.toString()) }
                )

                val llmCallTimeoutValue = state.settingsLlmCallTimeoutSeconds.toIntOrNull()
                    ?.coerceIn(
                        AppLimits.MIN_LLM_CALL_TIMEOUT_SECONDS,
                        AppLimits.MAX_LLM_CALL_TIMEOUT_SECONDS
                    )
                    ?: AppLimits.DEFAULT_LLM_CALL_TIMEOUT_SECONDS
                RuntimeSliderSetting(
                    title = uiLabel("LLM Call Timeout (sec)"),
                    description = uiLabel("Time limit for one model request."),
                    value = llmCallTimeoutValue,
                    min = AppLimits.MIN_LLM_CALL_TIMEOUT_SECONDS,
                    max = AppLimits.MAX_LLM_CALL_TIMEOUT_SECONDS,
                    stepSize = 5,
                    onValueChange = { onLlmCallTimeoutSecondsChange(it.toString()) }
                )

                val toolTimeoutValue = state.settingsDefaultToolTimeoutSeconds.toIntOrNull()
                    ?.coerceIn(AppLimits.MIN_TOOL_TIMEOUT_SECONDS, AppLimits.MAX_TOOL_TIMEOUT_SECONDS)
                    ?: AppLimits.DEFAULT_TOOL_TIMEOUT_SECONDS
                RuntimeSliderSetting(
                    title = uiLabel("Default Tool Timeout (sec)"),
                    description = uiLabel("Fallback limit when a tool has no own timeout."),
                    value = toolTimeoutValue,
                    min = AppLimits.MIN_TOOL_TIMEOUT_SECONDS,
                    max = AppLimits.MAX_TOOL_TIMEOUT_SECONDS,
                    stepSize = 5,
                    onValueChange = { onDefaultToolTimeoutSecondsChange(it.toString()) }
                )

                val memoryWindowValue = state.settingsMemoryConsolidationWindow.toIntOrNull()
                    ?.coerceIn(
                        AppLimits.MIN_MEMORY_CONSOLIDATION_WINDOW,
                        AppLimits.MAX_MEMORY_CONSOLIDATION_WINDOW
                    )
                    ?: AppLimits.DEFAULT_MEMORY_CONSOLIDATION_WINDOW
                RuntimeSliderSetting(
                    title = uiLabel("Memory Consolidation Window"),
                    description = uiLabel("Message threshold before memory is compacted."),
                    value = memoryWindowValue,
                    min = AppLimits.MIN_MEMORY_CONSOLIDATION_WINDOW,
                    max = AppLimits.MAX_MEMORY_CONSOLIDATION_WINDOW,
                    stepSize = 10,
                    onValueChange = { onMemoryConsolidationWindowChange(it.toString()) }
                )

                ScrollableLogWindow(
                    title = uiLabel("Agent Logs"),
                    content = state.settingsAgentLogs,
                    emptyText = uiLabel("No agent logs yet."),
                    actions = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SettingsActionButton(
                                text = uiLabel("Refresh"),
                                icon = Icons.Rounded.Refresh,
                                onClick = onRefreshAgentLogs
                            )
                            SettingsActionButton(
                                text = uiLabel("Clear"),
                                icon = Icons.Outlined.DeleteOutline,
                                onClick = onClearAgentLogs
                            )
                        }
                    }
                )
            }

            SettingsPanelPage.Cron -> {
                SettingsSectionCard(
                    title = uiLabel("Scheduler"),
                    subtitle = uiLabel("Enable cron and set basic limits.")
                ) {
                    SettingsToggleRow(
                        title = uiLabel("Cron scheduler"),
                        checked = state.settingsCronEnabled,
                        onCheckedChange = onCronEnabledChange
                    )
                    OutlinedTextField(
                        value = state.settingsCronMinEveryMs,
                        onValueChange = onCronMinEveryMsChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(uiLabel("Min Interval (ms)")) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = state.settingsCronMaxJobs,
                        onValueChange = onCronMaxJobsChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(uiLabel("Max Jobs")) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                SettingsSectionCard(
                    title = uiLabel("Jobs"),
                    subtitle = uiLabel("Scheduled jobs and recent state."),
                    actions = {
                        SettingsActionButton(
                            text = uiLabel("Refresh"),
                            icon = Icons.Rounded.Refresh,
                            onClick = onRefreshCronJobs
                        )
                        SettingsActionButton(
                            text = if (showCronLogs) uiLabel("Hide Logs") else uiLabel("Show Logs"),
                            icon = if (showCronLogs) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                            onClick = {
                                val next = !showCronLogs
                                showCronLogs = next
                                if (next) onRefreshCronLogs()
                            }
                        )
                    }
                ) {
                    if (state.settingsCronJobsLoading) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(uiLabel("Loading cron jobs..."), style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (state.settingsCronJobs.isEmpty()) {
                        Text(
                            text = uiLabel("No cron jobs yet."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        state.settingsCronJobs.forEach { job ->
                            Surface(
                                tonalElevation = 0.dp,
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = job.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.weight(1f)
                                        )
                                        PalmClawSwitch(
                                            checked = job.enabled,
                                            onCheckedChange = { enabled -> onSetCronJobEnabled(job.id, enabled) }
                                        )
                                    }
                                    SettingsInfoBlock(
                                        label = uiLabel("Schedule"),
                                        value = job.schedule,
                                        maxLines = 3
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        job.nextRunAt?.takeIf { it.isNotBlank() }?.let {
                                            SettingsInfoBlock(
                                                label = uiLabel("Next Run"),
                                                value = it,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 2
                                            )
                                        }
                                        job.lastStatus?.takeIf { it.isNotBlank() }?.let {
                                            SettingsInfoBlock(
                                                label = uiLabel("Last Status"),
                                                value = it,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 2
                                            )
                                        }
                                    }
                                    job.lastError?.takeIf { it.isNotBlank() }?.let {
                                        SettingsInfoBlock(
                                            label = uiLabel("Last Error"),
                                            value = it,
                                            valueColor = MaterialTheme.colorScheme.error,
                                            maxLines = 3
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        SettingsActionButton(
                                            text = uiLabel("Run"),
                                            icon = Icons.Rounded.PlayArrow,
                                            onClick = { onRunCronJobNow(job.id) }
                                        )
                                        SettingsActionButton(
                                            text = uiLabel("Remove"),
                                            icon = Icons.Outlined.DeleteOutline,
                                            onClick = { onRemoveCronJob(job.id) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (showCronLogs) {
                    ScrollableLogWindow(
                        title = uiLabel("Cron Logs"),
                        content = state.settingsCronLogs,
                        emptyText = uiLabel("No cron logs yet."),
                        actions = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SettingsActionButton(
                                    text = uiLabel("Refresh"),
                                    icon = Icons.Rounded.Refresh,
                                    onClick = onRefreshCronLogs
                                )
                                SettingsActionButton(
                                    text = uiLabel("Clear"),
                                    icon = Icons.Outlined.DeleteOutline,
                                    onClick = onClearCronLogs
                                )
                            }
                        }
                    )
                }
            }

            SettingsPanelPage.Heartbeat -> {
                SettingsSectionCard(
                    title = uiLabel("Heartbeat"),
                    subtitle = uiLabel("Periodic prompt driven by HEARTBEAT.md.")
                ) {
                    SettingsToggleRow(
                        title = uiLabel("Heartbeat"),
                        checked = state.settingsHeartbeatEnabled,
                        onCheckedChange = onHeartbeatEnabledChange
                    )
                    OutlinedTextField(
                        value = state.settingsHeartbeatIntervalSeconds,
                        onValueChange = onHeartbeatIntervalSecondsChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(uiLabel("Interval (sec)")) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                SettingsSectionCard(
                    title = uiLabel("Actions"),
                    subtitle = uiLabel("Run it now or edit the heartbeat doc.")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SettingsActionButton(
                            text = uiLabel("Trigger Now"),
                            icon = Icons.Rounded.PlayArrow,
                            onClick = onTriggerHeartbeatNow
                        )
                        SettingsActionButton(
                            text = uiLabel("Edit Doc"),
                            icon = Icons.Rounded.Description,
                            onClick = onOpenHeartbeatEditor
                        )
                    }
                }
            }

            SettingsPanelPage.Channels -> {
                val nonLocalSessions = state.sessions.filterNot { it.isLocal }
                val boundCount = state.settingsConnectedChannels.size
                val readyCount = state.settingsConnectedChannels.count {
                    it.status.startsWith("Ready", ignoreCase = true) ||
                        it.status.startsWith("Experimental", ignoreCase = true)
                }
                val issueCount = state.settingsConnectedChannels.count {
                    !it.status.startsWith("Ready", ignoreCase = true) &&
                        !it.status.startsWith("Experimental", ignoreCase = true)
                }
                val unboundCount = nonLocalSessions.count { session ->
                    val isTelegramPending =
                        session.boundChannel.equals("telegram", ignoreCase = true) &&
                            session.boundTelegramBotToken.isNotBlank() &&
                            session.boundChatId.isBlank()
                    val isFeishuPending =
                        session.boundChannel.equals("feishu", ignoreCase = true) &&
                            session.boundFeishuAppId.isNotBlank() &&
                            session.boundFeishuAppSecret.isNotBlank() &&
                            session.boundChatId.isBlank()
                    val isEmailPending =
                        session.boundChannel.equals("email", ignoreCase = true) &&
                            session.boundEmailConsentGranted &&
                            session.boundEmailImapHost.isNotBlank() &&
                            session.boundEmailImapUsername.isNotBlank() &&
                            session.boundEmailImapPassword.isNotBlank() &&
                            session.boundEmailSmtpHost.isNotBlank() &&
                            session.boundEmailSmtpUsername.isNotBlank() &&
                            session.boundEmailSmtpPassword.isNotBlank() &&
                            session.boundChatId.isBlank()
                    val isWeComPending =
                        session.boundChannel.equals("wecom", ignoreCase = true) &&
                            session.boundWeComBotId.isNotBlank() &&
                            session.boundWeComSecret.isNotBlank() &&
                            session.boundChatId.isBlank()
                    session.boundChannel.isBlank() || (
                        session.boundChatId.isBlank() &&
                            !isTelegramPending &&
                            !isFeishuPending &&
                            !isEmailPending &&
                            !isWeComPending
                        )
                }
                val telegramBound = state.settingsConnectedChannels.count { it.channel.equals("telegram", ignoreCase = true) }
                val discordBound = state.settingsConnectedChannels.count { it.channel.equals("discord", ignoreCase = true) }
                val slackBound = state.settingsConnectedChannels.count { it.channel.equals("slack", ignoreCase = true) }
                val feishuBound = state.settingsConnectedChannels.count { it.channel.equals("feishu", ignoreCase = true) }
                val emailBound = state.settingsConnectedChannels.count { it.channel.equals("email", ignoreCase = true) }
                val wecomBound = state.settingsConnectedChannels.count { it.channel.equals("wecom", ignoreCase = true) }
                Text(
                    text = tr("You can manage the session connections here.", ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (nonLocalSessions.isEmpty()) {
                    Text(
                        text = tr("No user-created sessions yet.", ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    nonLocalSessions.forEach { session ->
                        val isTelegramPending =
                            session.boundChannel.equals("telegram", ignoreCase = true) &&
                                session.boundTelegramBotToken.isNotBlank() &&
                                session.boundChatId.isBlank()
                        val isFeishuPending =
                            session.boundChannel.equals("feishu", ignoreCase = true) &&
                                session.boundFeishuAppId.isNotBlank() &&
                                session.boundFeishuAppSecret.isNotBlank() &&
                                session.boundChatId.isBlank()
                        val isEmailPending =
                            session.boundChannel.equals("email", ignoreCase = true) &&
                                session.boundEmailConsentGranted &&
                                session.boundEmailImapHost.isNotBlank() &&
                                session.boundEmailImapUsername.isNotBlank() &&
                                session.boundEmailImapPassword.isNotBlank() &&
                                session.boundEmailSmtpHost.isNotBlank() &&
                                session.boundEmailSmtpUsername.isNotBlank() &&
                                session.boundEmailSmtpPassword.isNotBlank() &&
                                session.boundChatId.isBlank()
                        val isWeComPending =
                            session.boundChannel.equals("wecom", ignoreCase = true) &&
                                session.boundWeComBotId.isNotBlank() &&
                                session.boundWeComSecret.isNotBlank() &&
                                session.boundChatId.isBlank()
                        val hasBinding = session.boundChannel.isNotBlank() &&
                            (
                                session.boundChatId.isNotBlank() ||
                                    isTelegramPending ||
                                    isFeishuPending ||
                                    isEmailPending ||
                                    isWeComPending
                                )
                        val route = if (hasBinding) {
                            val routeId = if (session.boundChatId.isNotBlank()) {
                                session.boundChatId
                            } else {
                                tr("Pending detection", "")
                            }
                            "${channelDisplayLabel(session.boundChannel)} ${uiLabel("Route")} $routeId"
                        } else {
                            tr("Not configured", "")
                        }
                        val status = state.settingsConnectedChannels
                            .firstOrNull { it.sessionId == session.id }
                            ?.status
                            ?: if (hasBinding) uiLabel("Configured") else uiLabel("Not configured")
                        Surface(
                            tonalElevation = 1.dp,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = session.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = route,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = uiLabel(status),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                PalmClawSwitch(
                                    checked = hasBinding && session.boundEnabled,
                                    onCheckedChange = { checked ->
                                        if (hasBinding) {
                                            onSetSessionChannelEnabled(session.id, checked)
                                        }
                                    },
                                    enabled = hasBinding
                                )
                            }
                        }
                    }
                }
                if (nonLocalSessions.isNotEmpty()) {
                    SettingsSectionCard(
                        title = uiLabel("Connection Diagnostics"),
                        subtitle = uiLabel("Session and route status.")
                    ) {
                        SettingsValueRow(uiLabel("Gateway"), uiLabel(if (state.settingsGatewayEnabled) "Enabled" else "Disabled"))
                        SettingsValueRow(uiLabel("Sessions"), nonLocalSessions.size.toString())
                        SettingsValueRow(uiLabel("Bound"), boundCount.toString())
                        SettingsValueRow(uiLabel("Ready"), readyCount.toString())
                        SettingsValueRow(uiLabel("Issues"), issueCount.toString())
                        SettingsValueRow(uiLabel("Unbound"), unboundCount.toString())
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        SettingsValueRow(uiLabel("Telegram"), telegramBound.toString())
                        SettingsValueRow(uiLabel("Discord"), discordBound.toString())
                        SettingsValueRow(uiLabel("Slack"), slackBound.toString())
                        SettingsValueRow(uiLabel("Feishu"), feishuBound.toString())
                        SettingsValueRow(uiLabel("Email"), emailBound.toString())
                        SettingsValueRow(uiLabel("WeCom"), wecomBound.toString())
                    }
                }
            }

            SettingsPanelPage.Mcp -> {
                SettingsSectionCard(
                    title = uiLabel("MCP Remote"),
                    subtitle = uiLabel("Connect remote MCP servers.")
                ) {
                    SettingsToggleRow(
                        title = uiLabel("Enable MCP Remote"),
                        checked = state.settingsMcpEnabled,
                        onCheckedChange = onMcpEnabledChange
                    )
                    SettingsActionButton(
                        text = if (revealApiKey) uiLabel("Hide Tokens") else uiLabel("Show Tokens"),
                        icon = if (revealApiKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        onClick = onRevealToggle
                    )
                }
                SettingsSectionCard(
                    title = uiLabel("Servers"),
                    subtitle = uiLabel("Add one only when you need MCP."),
                    actions = {
                        SettingsActionButton(
                            text = uiLabel("Add Server"),
                            icon = Icons.Rounded.Add,
                            onClick = onAddMcpServer
                        )
                    }
                ) {
                    if (state.settingsMcpServers.isEmpty()) {
                        Text(
                            text = uiLabel("No MCP servers configured."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    state.settingsMcpServers.forEachIndexed { index, server ->
                        val serverUsableLabel = uiLabel(if (server.usable) "Usable" else "Unavailable")
                        val serverStatusLabel = uiLabel(server.status)
                        Surface(
                            tonalElevation = 0.dp,
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Text(
                                            text = "${uiLabel("Server")} ${index + 1}",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "$serverUsableLabel 路 $serverStatusLabel",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = when (server.status.lowercase()) {
                                                "connected" -> if (server.usable) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.tertiary
                                                }
                                                "error" -> MaterialTheme.colorScheme.error
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    }
                                    SettingsActionButton(
                                        text = uiLabel("Remove"),
                                        icon = Icons.Outlined.DeleteOutline,
                                        onClick = { onRemoveMcpServer(server.id) }
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    SettingsInfoBlock(
                                        label = uiLabel("Status"),
                                        value = server.status,
                                        modifier = Modifier.weight(1f),
                                        valueColor = when (server.status.lowercase()) {
                                            "connected" -> if (server.usable) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.tertiary
                                            }
                                            "error" -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.onSurface
                                        },
                                        maxLines = 1
                                    )
                                    SettingsInfoBlock(
                                        label = uiLabel("Tools"),
                                        value = server.toolCount.toString(),
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1
                                    )
                                }
                                server.detail.takeIf { it.isNotBlank() }?.let {
                                    SettingsInfoBlock(
                                        label = uiLabel("Detail"),
                                        value = it,
                                        maxLines = 3
                                    )
                                }
                                OutlinedTextField(
                                    value = server.serverName,
                                    onValueChange = { value -> onMcpServerNameChange(server.id, value) },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(uiLabel("Server Name")) },
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = server.serverUrl,
                                    onValueChange = { value -> onMcpServerUrlChange(server.id, value) },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(uiLabel("Endpoint URL")) },
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = server.authToken,
                                    onValueChange = { value -> onMcpAuthTokenChange(server.id, value) },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(uiLabel("Auth Token")) },
                                    singleLine = true,
                                    visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation()
                                )
                                OutlinedTextField(
                                    value = server.toolTimeoutSeconds,
                                    onValueChange = { value -> onMcpToolTimeoutSecondsChange(server.id, value) },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(uiLabel("Tool Timeout (sec)")) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                            }
                        }
                    }
                }
            }

            SettingsPanelPage.Guide -> {
                UserGuideContent(
                    isChinese = state.settingsUseChinese,
                    selected = guideSection,
                    onSelect = { guideSectionName = it.name }
                )
            }

        }
    }
}

@Composable
private fun RuntimeSliderSetting(
    title: String,
    description: String,
    value: Int,
    min: Int,
    max: Int,
    stepSize: Int,
    onValueChange: (Int) -> Unit
) {
    val safeStep = stepSize.coerceAtLeast(1)
    val clamped = value.coerceIn(min, max)
    val points = ((max - min) / safeStep) + 1
    val sliderSteps = (points - 2).coerceAtLeast(0)
    val sliderValue = ((clamped - min) / safeStep.toFloat()).coerceIn(0f, (points - 1).toFloat())

    Surface(
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = uiLabel(title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(
                        text = clamped.toString(),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            if (description.isNotBlank()) {
                Text(
                    text = uiLabel(description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Slider(
                value = sliderValue,
                onValueChange = { raw ->
                    val snapped = raw.roundToInt().coerceIn(0, points - 1)
                    val mapped = (min + snapped * safeStep).coerceIn(min, max)
                    onValueChange(mapped)
                },
                valueRange = 0f..(points - 1).toFloat(),
                steps = sliderSteps
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = min.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = max.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private enum class UserGuideSection {
    Overview,
    Agent,
    Tools,
    Memory,
    Sessions,
    Channels,
    Skills,
    Cron,
    Heartbeat,
    AlwaysOn,
    Mcp;

    fun title(isChinese: Boolean): String = if (isChinese) {
        when (this) {
            Overview -> "概览"
            Agent -> "智能体"
            Tools -> "工具"
            Memory -> "记忆"
            Sessions -> "会话"
            Channels -> "通道"
            Skills -> "技能"
            Cron -> "Cron"
            Heartbeat -> "Heartbeat"
            AlwaysOn -> "常驻"
            Mcp -> "MCP"
        }
    } else {
        when (this) {
            Overview -> "Overview"
            Agent -> "Agent"
            Tools -> "Tools"
            Memory -> "Memory"
            Sessions -> "Sessions"
            Channels -> "Channels"
            Skills -> "Skills"
            Cron -> "Cron"
            Heartbeat -> "Heartbeat"
            AlwaysOn -> "Always-on"
            Mcp -> "MCP"
        }
    }

    fun content(isChinese: Boolean): List<String> = if (isChinese) {
        when (this) {
            Overview -> listOf(
                "PalmClaw 是一个按会话组织的本地 AI 助手。你可以在不同会话里运行智能体、连接通道、安排任务，并把重要信息保存到记忆中。",
                "日常使用建议先从本地会话开始。先确认模型提供方可用，再按需要启用通道、Cron、Heartbeat 或 MCP。",
                "如果你希望手机在后台持续处理远程消息，可以开启常驻模式。普通聊天和工具使用，常规模式通常已经足够。"
            )
            Agent -> listOf(
                "智能体是核心执行者。你发送消息后，它会结合当前会话上下文、可用工具和记忆来决定下一步动作。",
                "好的提示通常包含目标、约束和期望输出格式。请求越清晰，结果通常越稳定。",
                "如果结果看起来不对，先检查当前会话、提供方配置、可用工具和最近消息，不要一开始就重置所有设置。"
            )
            Tools -> listOf(
                "工具让智能体执行真实动作，例如读取文件、发送消息、检查状态，或控制自动化能力。",
                "不同工具的作用范围不同。有些只影响当前会话，有些会修改全局设置；使用前先确认目标。",
                "如果你不确定当前有哪些工具可用，可以先看工具说明，或让智能体先列出相关选项。"
            )
            Memory -> listOf(
                "记忆分为两层：共享记忆和会话历史。共享记忆适合长期事实，会话历史则保存某个会话里的过程和结论。",
                "如果某条信息只和一个会话相关，就留在该会话历史里；只有需要跨会话共享时，再写入共享记忆。",
                "随着对话变长，应用可以自动整理记忆，让长会话保持更轻，同时保留重要信息。"
            )
            Sessions -> listOf(
                "会话是组织工作的基本单位。每个会话都有自己的消息历史，也可以有独立的通道绑定，用来承载不同主题或联系人。",
                "如果你同时处理不同项目、不同人，或不同平台，建议拆成独立会话。这样上下文更干净，也能减少发错地方的风险。",
                "本地会话适合管理、诊断和控制；绑定远程通道的会话更适合通过 Telegram、邮件、企微等渠道进行真实对话。"
            )
            Channels -> listOf(
                "通道用于把会话连接到外部平台。通常分两步完成：先保存凭据，再检测目标并完成绑定。",
                "理想情况下，一个会话应尽量只对应一条清晰的外部通信路径，这样路由更容易理解，也能减少误操作。",
                "如果连接看起来不对，先看 Connection，再看 Configure。前者展示当前状态，后者负责修改配置。"
            )
            Skills -> listOf(
                "技能会给智能体增加额外的工作流和知识，适合封装可重复任务、固定流程，或特定领域的指导。",
                "如果你经常做同一类工作，例如结构化总结、固定 API 流程或标准审查，技能会让行为更稳定。",
                "大多数用户第一天并不需要技能。先把会话、工具和通道跑通，再在确实有帮助时加入技能。"
            )
            Cron -> listOf(
                "Cron 适合做定时任务，例如提醒、检查，或按会话触发的消息分发。",
                "配置时先确认全局调度器已经开启，再检查每个任务的计划、下次运行时间和最近状态。",
                "如果某个任务行为异常，先看任务本身是否启用、调度是否合理，以及目标会话或通道是否可用。"
            )
            Heartbeat -> listOf(
                "Heartbeat 会按固定间隔运行提示词，内容来自 HEARTBEAT.md，适合做例行检查、日报总结或自驱提醒。",
                "如果想快速验证效果，先手动触发一次，确认输出合适后再开启定时运行。",
                "Heartbeat 更适合轻量、可重复的任务；更强交互或更严格的流程，通常更适合放到普通会话或 Cron。"
            )
            AlwaysOn -> listOf(
                "常驻模式会尽可能让应用在后台持续工作，适合你需要更稳定远程回复的场景。",
                "它在充电、网络稳定且关闭电池优化时效果最好。",
                "即使开启常驻模式，手机端应用也仍然不同于云服务器。网络条件、系统限制和省电策略仍会影响长期稳定性。"
            )
            Mcp -> listOf(
                "MCP 用于接入外部服务端能力，让智能体可以调用远程工具。",
                "只有确实需要远程工具时再添加服务器。配置越精简，越容易排查问题。",
                "如果某个 MCP 服务器看起来不可用，先检查 URL、认证 Token 和工具超时，再确认服务器本身是否健康。"
            )
        }
    } else {
        when (this) {
            Overview -> listOf(
                "PalmClaw is a session-based local AI assistant. You can run the agent in different sessions, connect channels, schedule jobs, and keep important information in memory.",
                "For everyday use, start with the local session. Make sure your provider works first, then enable channels, cron, heartbeat, or MCP only when needed.",
                "Use Always-on when you want the phone to keep handling remote messages in background. For normal chat and tool use, regular mode is usually enough."
            )
            Agent -> listOf(
                "The agent is the core worker. When you send a message, it uses the current session context, available tools, and memory to decide what to do next.",
                "Good prompts usually include a goal, constraints, and the desired output format. The clearer the request, the more stable the result.",
                "If the result looks wrong, check the current session, provider setup, available tools, and recent messages before resetting everything."
            )
            Tools -> listOf(
                "Tools let the agent perform real actions such as reading files, sending messages, checking status, or controlling automation features.",
                "Different tools have different scopes. Some affect only the current session, while others change global settings. Confirm the target before using global-setting tools.",
                "If you are unsure what is available, check the tool descriptions or ask the agent to list the relevant options first."
            )
            Memory -> listOf(
                "Memory has two layers: shared memory and session history. Shared memory is for long-term facts, while session history keeps the process and conclusions of a specific session.",
                "If something matters only to one session, keep it in that session's history. Put information into shared memory only when it should be visible across sessions.",
                "As conversations grow, the app can consolidate memory automatically so long chats stay lighter while important information remains available."
            )
            Sessions -> listOf(
                "Sessions are the basic unit for organizing work. Each session has its own message history, may have its own channel binding, and can be used for different topics or contacts.",
                "If you work on different projects, people, or platforms, split them into separate sessions. This keeps context cleaner and reduces the chance of sending to the wrong place.",
                "The local session is good for admin, diagnostics, and control. Bound remote sessions are better for real conversations through Telegram, email, WeCom, and similar channels."
            )
            Channels -> listOf(
                "Channels connect a session to an external platform. Setup usually happens in two steps: save credentials first, then detect the target and finish binding.",
                "A session should ideally map to one clear external communication path. That keeps routing easier to understand and reduces mistakes.",
                "If a connection looks wrong, check Connection first and Configure second. Connection shows the current state, while Configure is where you change setup."
            )
            Skills -> listOf(
                "Skills extend the agent with extra workflows and knowledge. They are useful for packaging repeatable tasks, fixed procedures, or domain-specific guidance.",
                "If you repeatedly do the same kind of work, such as structured summaries, API routines, or standard reviews, skills can make behavior much more consistent.",
                "Most users do not need skills on day one. Get sessions, tools, and channels working first, then add skills only when they clearly help."
            )
            Cron -> listOf(
                "Cron is for scheduled work such as reminders, checks, or session-based message dispatches.",
                "When configuring it, first make sure the global scheduler is enabled, then verify each job's schedule, next run time, and latest status.",
                "If a job does not behave as expected, check whether the job itself is enabled, whether the schedule makes sense, and whether the target session or channel is available."
            )
            Heartbeat -> listOf(
                "Heartbeat runs a prompt on a fixed interval, using content from HEARTBEAT.md. It is useful for routine checks, daily summaries, or self-driven reminders.",
                "To validate behavior quickly, trigger it manually first and enable scheduling only after the output looks right.",
                "Heartbeat works best for lightweight and repeatable jobs. More interactive or strict workflows are usually better handled through regular sessions or cron."
            )
            AlwaysOn -> listOf(
                "Always-on keeps the app working in background as reliably as possible. It is useful when you need more stable remote replies.",
                "It works best while charging, on a stable network, and with battery optimization disabled.",
                "Even in Always-on mode, the app is still not the same as a cloud server. Network conditions, OS limits, and power-saving rules can still affect long-term reliability."
            )
            Mcp -> listOf(
                "MCP connects external server-side capabilities so the agent can use remote tools.",
                "Add servers only when you really need remote tools. The smaller the setup, the easier it is to troubleshoot.",
                "If an MCP server looks unavailable, first check the URL, auth token, and tool timeout, then confirm the server itself is healthy."
            )
        }
    }
}

@Composable
private fun UserGuideContent(
    isChinese: Boolean,
    selected: UserGuideSection,
    onSelect: (UserGuideSection) -> Unit
) {
    val sections = remember {
        listOf(
            UserGuideSection.Overview,
            UserGuideSection.Agent,
            UserGuideSection.Tools,
            UserGuideSection.Memory,
            UserGuideSection.Sessions,
            UserGuideSection.Channels,
            UserGuideSection.Skills,
            UserGuideSection.Cron,
            UserGuideSection.Heartbeat,
            UserGuideSection.AlwaysOn,
            UserGuideSection.Mcp
        )
    }

    Surface(
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.width(104.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                sections.forEach { section ->
                    val active = section == selected
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(section) },
                        tonalElevation = if (active) 1.dp else 0.dp,
                        shape = RoundedCornerShape(10.dp),
                        color = if (active) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    ) {
                        Text(
                            text = section.title(isChinese),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (active) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier.weight(1f),
                tonalElevation = 0.dp,
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = selected.title(isChinese),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    selected.content(isChinese).forEach { paragraph ->
                        Text(
                            text = paragraph,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 20.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownText(
    markdown: String,
    textStyle: TextStyle,
    inlineCodeBackground: Color,
    quoteBackground: Color,
    codeBlockBackground: Color,
    fillMaxWidth: Boolean = true,
    contentColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val context = LocalContext.current
    val plainTextColor = contentColor
    val plainTextCandidate = remember(markdown) {
        normalizeMarkdownForMobile(markdown)
    }
    val isPlainText = remember(plainTextCandidate) { isLikelyPlainText(plainTextCandidate) }
    if (isPlainText) {
        SelectionContainer {
            Text(
                text = plainTextCandidate,
                modifier = if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier,
                style = textStyle,
                color = plainTextColor
            )
        }
        return
    }

    val textColor = plainTextColor.toArgb()
    val normalizedMarkdown = remember(markdown) {
        normalizeMarkdownForMobile(markdown)
    }

    val markwon = remember(context) {
        Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .build()
    }

    AndroidView(
        modifier = if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                movementMethod = LinkMovementMethod.getInstance()
                setTextIsSelectable(true)
                linksClickable = true
                isClickable = true
                includeFontPadding = false
                setPadding(0, 0, 0, 0)
                setLineSpacing(0f, 1.02f)
            }
        },
        update = { textView ->
            textView.setTextColor(textColor)
            if (textStyle.fontSize != TextUnit.Unspecified) {
                textView.textSize = textStyle.fontSize.value
            }
            if (textView.tag != normalizedMarkdown) {
                textView.tag = normalizedMarkdown
                markwon.setMarkdown(textView, normalizedMarkdown)
            }
        }
    )
}

private fun normalizeMarkdownForMobile(markdown: String): String {
    return markdown
        .replace("\r\n", "\n")
        .replace("\\r\\n", "\n")
        .replace("\\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("(?m)^(\\t+)")) { match ->
            "    ".repeat(match.value.length)
        }
}

private fun isLikelyPlainText(text: String): Boolean {
    if (text.isBlank()) return true
    val markdownSignals = listOf(
        "```",
        "`",
        "|",
        "# ",
        "##",
        "> ",
        "[",
        "](",
        "**",
        "__",
        "~~",
        "- ",
        "* ",
        "1. "
    )
    return markdownSignals.none { signal -> text.contains(signal) }
}

@Composable
private fun ChatBubbleHeader(
    label: String,
    createdAt: Long,
    labelColor: Color = LocalChatBubbleColors.current.header,
    timeColor: Color = LocalChatBubbleColors.current.time,
    fillWidth: Boolean = true,
    topPadding: Dp = 0.dp,
    bottomPadding: Dp = 6.dp,
) {
    val timeLabel = remember(createdAt) {
        if (createdAt <= 0L) {
            ""
        } else {
            runCatching {
                val messageTime = Calendar.getInstance().apply { timeInMillis = createdAt }
                val now = Calendar.getInstance()
                val sameDay = messageTime.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                    messageTime.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
                val sameYear = messageTime.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                val pattern = when {
                    sameDay -> "HH:mm"
                    sameYear -> "MM-dd HH:mm"
                    else -> "yyyy-MM-dd HH:mm"
                }
                SimpleDateFormat(pattern, Locale.getDefault()).format(Date(createdAt))
            }.getOrDefault("")
        }
    }
    Row(
        modifier = if (fillWidth) {
            Modifier
                .fillMaxWidth()
                .padding(top = topPadding, bottom = bottomPadding)
        } else {
            Modifier.padding(top = topPadding, bottom = bottomPadding)
        },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = if (labelColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else labelColor,
            fontWeight = FontWeight.SemiBold
        )
        if (fillWidth) {
            Spacer(modifier = Modifier.weight(1f))
        } else if (timeLabel.isNotBlank()) {
            Spacer(modifier = Modifier.width(10.dp))
        }
        if (timeLabel.isNotBlank()) {
            Text(
                text = timeLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = if (timeColor == Color.Unspecified) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
                } else {
                    timeColor
                }
            )
        }
    }
}

private data class ChatBubbleColors(
    val container: Color,
    val content: Color,
    val header: Color,
    val time: Color
)

private val LocalChatBubbleColors = compositionLocalOf {
    ChatBubbleColors(
        container = Color.Unspecified,
        content = Color.Unspecified,
        header = Color.Unspecified,
        time = Color.Unspecified
    )
}

private const val HISTORY_ROUNDS_PAGE_SIZE = 10
private const val HISTORY_LOAD_TRIGGER_DELAY_MS = 450L
private const val HISTORY_LOADING_MIN_VISIBLE_MS = 650L
private val CHAT_INPUT_BAR_CLEARANCE = 80.dp
private val CHAT_TAIL_VISIBLE_GAP = 1.dp

private data class HistoryRestoreRequest(
    val anchorMessageId: Long,
    val anchorOffsetFromTop: Int
)

private data class ScrollIndicatorUi(
    val thumbFraction: Float,
    val progress: Float
)
