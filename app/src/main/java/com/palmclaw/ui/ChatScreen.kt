package com.palmclaw.ui

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.DownloadManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.view.WindowManager
import android.widget.Toast
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Translate
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import com.palmclaw.tools.hasAllFilesAccess
import com.palmclaw.tools.hasPermission
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
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
            onProviderCustomNameChange = vm::onSettingsProviderCustomNameChanged,
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
    val dismissKeyboard = {
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
    }
    val hostActivity = context as? ComponentActivity
    var pendingPermissionResult by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var pendingBluetoothEnableResult by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var pendingUserConfirmResult by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var permissionsRefreshNonce by rememberSaveable { mutableStateOf(0) }
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
        val callback = pendingPermissionResult
        pendingPermissionResult = null
        callback?.invoke(grantedAll)
        permissionsRefreshNonce += 1
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
    var bindingFeishuResponseModeDraft by rememberSaveable { mutableStateOf("mention") }
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
    var closeAfterDetectedBindingSave by rememberSaveable { mutableStateOf(false) }
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
    val permissionsDashboard = remember(permissionsRefreshNonce, context.applicationContext) {
        readPermissionsDashboardState(context.applicationContext)
    }
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
        closeAfterDetectedBindingSave = false
        telegramAdvancedExpanded = false
        discordAdvancedExpanded = false
        slackAdvancedExpanded = false
        feishuAdvancedExpanded = false
        emailAdvancedExpanded = false
        weComAdvancedExpanded = false
        sessionSettingsSessionId = null
        sessionSettingsPageName = SessionSettingsPage.Menu.name
    }
    val openSessionSettingsForSession: (UiSessionSummary) -> Unit = { session ->
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
        bindingFeishuResponseModeDraft = draft.feishuResponseMode
        bindingEmailConsentGrantedDraft = true
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
                uiScope.launch {
                    mainSurfaceName = MainSurface.Chat.name
                    runCatching { drawerState.snapTo(DrawerValue.Open) }
                        .onFailure { drawerState.open() }
                }
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

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionsRefreshNonce += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
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

    fun launchRuntimePermissionRequest(
        permissions: Array<String>,
        onResult: (Boolean) -> Unit = {}
    ) {
        val normalized = permissions.map { it.trim() }.filter { it.isNotBlank() }.distinct().toTypedArray()
        if (normalized.isEmpty()) {
            onResult(true)
            return
        }
        if (hostActivity == null) {
            onResult(false)
            return
        }
        if (pendingPermissionResult != null) {
            onResult(false)
            return
        }
        pendingPermissionResult = onResult
        runCatching { requestPermissionsLauncher.launch(normalized) }
            .onFailure {
                pendingPermissionResult = null
                permissionsRefreshNonce += 1
                onResult(false)
            }
    }

    val actionRequester = remember(hostActivity) {
        object : AndroidUserActionRequester {
            override fun requestPermissions(
                permissions: Array<String>,
                onResult: (grantedAll: Boolean) -> Unit
            ) {
                launchRuntimePermissionRequest(permissions, onResult)
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
        PendingUserConfirmationDialog(
            title = pendingUserConfirmTitle,
            message = pendingUserConfirmMessage,
            confirmLabel = pendingUserConfirmLabel,
            cancelLabel = pendingUserCancelLabel,
            onConfirm = {
                val cb = pendingUserConfirmResult
                pendingUserConfirmResult = null
                cb?.invoke(true)
            },
            onCancel = {
                val cb = pendingUserConfirmResult
                pendingUserConfirmResult = null
                cb?.invoke(false)
            }
        )
    }

    if (showCreateSessionDialog) {
        CreateSessionDialog(
            sessionName = createSessionName,
            onSessionNameChange = { createSessionName = it },
            onCreate = {
                vm.createSession(createSessionName)
                createSessionName = ""
                showCreateSessionDialog = false
                uiScope.launch { drawerState.close() }
            },
            onDismiss = {
                createSessionName = ""
                showCreateSessionDialog = false
            }
        )
    }

    pendingRenameSessionId?.let { sessionId ->
        val item = state.sessions.firstOrNull { it.id == sessionId && !it.isLocal }
        if (item != null) {
            RenameSessionDialog(
                sessionName = renameSessionName,
                onSessionNameChange = { renameSessionName = it },
                onSave = {
                    vm.renameSession(sessionId, renameSessionName)
                    pendingRenameSessionId = null
                    renameSessionName = ""
                },
                onDismiss = {
                    pendingRenameSessionId = null
                    renameSessionName = ""
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
            DeleteSessionDialog(
                title = uiLabel("Delete Session"),
                message = irreversibleConfirmMessage(
                    prompt = uiLabel("Delete session '%s'?").format(item.title),
                    useChinese = isChinese
                ),
                onDelete = {
                    vm.deleteSession(sessionId)
                    pendingDeleteSessionId = null
                },
                onDismiss = { pendingDeleteSessionId = null }
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
            val selectedTargetDisplay = when (normalizedChannel) {
                "telegram" -> state.sessionBindingTelegramCandidates
                    .firstOrNull { it.chatId.trim() == bindingChatIdDraft.trim() }
                    ?.let { candidate ->
                        if (candidate.title.isBlank() || candidate.title == candidate.chatId) {
                            candidate.chatId
                        } else {
                            "${candidate.title} · ${candidate.chatId}"
                        }
                    }
                "feishu" -> state.sessionBindingFeishuCandidates
                    .firstOrNull { it.chatId.trim() == bindingChatIdDraft.trim() }
                    ?.let { candidate ->
                        if (candidate.title.isBlank() || candidate.title == candidate.chatId) {
                            candidate.chatId
                        } else {
                            "${candidate.title} · ${candidate.chatId}"
                        }
                    }
                "email" -> state.sessionBindingEmailCandidates
                    .firstOrNull { it.email.trim().equals(bindingChatIdDraft.trim(), ignoreCase = true) }
                    ?.email
                "wecom" -> state.sessionBindingWeComCandidates
                    .firstOrNull { it.chatId.trim() == bindingChatIdDraft.trim() }
                    ?.let { candidate ->
                        if (candidate.title.isBlank() || candidate.title == candidate.chatId) {
                            candidate.chatId
                        } else {
                            "${candidate.title} · ${candidate.chatId}"
                        }
                    }
                else -> null
            }
            val hasPendingDetection = when (normalizedChannel) {
                "feishu" -> bindingFeishuAppIdDraft.isNotBlank() && bindingFeishuAppSecretDraft.isNotBlank() && bindingChatIdDraft.isBlank()
                "email" -> bindingEmailImapHostDraft.isNotBlank() &&
                    bindingEmailImapUsernameDraft.isNotBlank() &&
                    bindingEmailSmtpHostDraft.isNotBlank() &&
                    bindingEmailSmtpUsernameDraft.isNotBlank() &&
                    bindingChatIdDraft.isBlank()
                "wecom" -> bindingWeComBotIdDraft.isNotBlank() && bindingWeComSecretDraft.isNotBlank() && bindingChatIdDraft.isBlank()
                else -> false
            }
            val targetLabel = when {
                bindingChannelDraft.isBlank() -> uiLabel("This session stays local.")
                selectedTargetDisplay != null -> selectedTargetDisplay
                bindingChatIdDraft.isNotBlank() -> bindingChatIdDraft.trim()
                hasPendingDetection -> uiLabel("Waiting for detection")
                else -> tr("Not set", "")
            }
            val activeChannel = item.boundChannel.trim().lowercase()
            val activeChannelLabel = when (activeChannel) {
                "telegram" -> uiLabel("Telegram")
                "discord" -> uiLabel("Discord")
                "slack" -> uiLabel("Slack")
                "feishu" -> uiLabel("Feishu")
                "email" -> uiLabel("Email")
                "wecom" -> uiLabel("WeCom")
                else -> uiLabel("None")
            }
            val activeTargetLabel = when {
                activeChannel.isBlank() -> uiLabel("This session stays local.")
                connected?.chatId?.trim()?.isNotBlank() == true -> connected.chatId.trim()
                item.boundChatId.trim().isNotBlank() -> item.boundChatId.trim()
                else -> tr("Not set", "")
            }
            val activeEnabledLabel = when {
                activeChannel.isBlank() -> tr("Local only", "")
                item.boundEnabled -> tr("On", "")
                else -> tr("Off", "")
            }
            val activeConnectedLabel = when {
                activeChannel.isBlank() -> tr("Local only", "")
                connected?.status?.equals("Connected", ignoreCase = true) == true -> tr("Yes", "")
                else -> tr("No", "")
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
                            SessionSettingsPage.Configure -> tr("Channels", "")
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
                                            onClick = vm::refreshSessionConnectionStatus,
                                            containerSize = 30.dp,
                                            iconSize = 12.dp
                                        )
                                    }
                                ) {
                                    SettingsValueRow(tr("Channel", ""), activeChannelLabel.ifBlank { tr("Not selected", "") })
                                    SettingsValueRow(tr("Target", ""), activeTargetLabel)
                                    SettingsValueRow(tr("Enabled", ""), activeEnabledLabel)
                                    SettingsValueRow(tr("Connected", ""), activeConnectedLabel)
                                }
                                SettingsSectionCard(
                                    title = tr("Configure", ""),
                                    subtitle = tr("Channel settings.", ""),
                                    actions = {
                                        SettingsSectionIconButton(
                                            icon = Icons.Rounded.KeyboardArrowUp,
                                            contentDescription = tr("Open channel settings", ""),
                                            onClick = { sessionSettingsPageName = SessionSettingsPage.Configure.name },
                                            rotateZ = 90f,
                                            containerSize = 30.dp,
                                            iconSize = 12.dp
                                        )
                                    }
                                ) {
                                    SettingsValueRow(tr("Channel", ""), channelLabel.ifBlank { tr("Not selected", "") })
                                }
                            } else if (sessionSettingsPage == SessionSettingsPage.Diagnostics) {
                                SettingsSectionCard(
                                    title = tr("Connection", ""),
                                    subtitle = tr("Current routing and status.", ""),
                                    actions = {
                                        SettingsSectionIconButton(
                                            icon = Icons.Rounded.Refresh,
                                            contentDescription = tr("Refresh connection status", ""),
                                            onClick = vm::refreshSessionConnectionStatus,
                                            containerSize = 30.dp,
                                            iconSize = 12.dp
                                        )
                                    }
                                ) {
                                    SettingsValueRow(tr("Channel", ""), activeChannelLabel.ifBlank { tr("Not selected", "") })
                                    SettingsValueRow(tr("Target", ""), activeTargetLabel)
                                    SettingsValueRow(tr("Enabled", ""), activeEnabledLabel)
                                    SettingsValueRow(tr("Connected", ""), activeConnectedLabel)
                                }
                            } else {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
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
                                            targetLabel != tr("Not set", "") &&
                                                targetLabel != uiLabel("Waiting for detection") ->
                                                "$channelLabel: $targetLabel"
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
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                ExposedDropdownMenuBox(
                                    expanded = bindingChannelMenuExpanded,
                                    onExpandedChange = { bindingChannelMenuExpanded = it }
                                ) {
                                    SettingsSelectField(
                                        value = channelLabel,
                                        modifier = Modifier
                                            .menuAnchor()
                                            .fillMaxWidth(),
                                        label = "Select Channel",
                                        trailingIcon = {
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = bindingChannelMenuExpanded)
                                        }
                                    )
                                    ExposedDropdownMenu(
                                        expanded = bindingChannelMenuExpanded,
                                        onDismissRequest = { bindingChannelMenuExpanded = false },
                                        shape = settingsTextFieldShape(),
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        tonalElevation = 0.dp,
                                        shadowElevation = 0.dp,
                                        border = settingsDropdownMenuBorder()
                                    ) {
                                        DropdownMenuItem(
                                            text = {
                                                SettingsDropdownMenuText(text = "None")
                                            },
                                            onClick = {
                                                closeAfterDetectedBindingSave = false
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
                                                bindingFeishuResponseModeDraft = "mention"
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
                                            text = {
                                                SettingsDropdownMenuText(text = "Telegram")
                                            },
                                            onClick = {
                                                closeAfterDetectedBindingSave = false
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
                                            text = {
                                                SettingsDropdownMenuText(text = "Discord")
                                            },
                                            onClick = {
                                                closeAfterDetectedBindingSave = false
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
                                            text = {
                                                SettingsDropdownMenuText(text = "Slack")
                                            },
                                            onClick = {
                                                closeAfterDetectedBindingSave = false
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
                                            text = {
                                                SettingsDropdownMenuText(text = "Feishu")
                                            },
                                            onClick = {
                                                closeAfterDetectedBindingSave = false
                                                bindingChannelDraft = "feishu"
                                                if (bindingFeishuResponseModeDraft.isBlank()) {
                                                    bindingFeishuResponseModeDraft = "mention"
                                                }
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
                                            text = {
                                                SettingsDropdownMenuText(text = "Email")
                                            },
                                            onClick = {
                                                closeAfterDetectedBindingSave = false
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
                                            text = {
                                                SettingsDropdownMenuText(text = "WeCom")
                                            },
                                            onClick = {
                                                closeAfterDetectedBindingSave = false
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
                            ) {
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
                                SettingsTextField(
                                    value = bindingTelegramBotTokenDraft,
                                    onValueChange = { bindingTelegramBotTokenDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "Telegram Bot Token",
                                    visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation()
                                )
                            }
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
                            ) {
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
                                        val isSelected = bindingChatIdDraft.trim() == candidate.chatId
                                        SessionSetupSelectableItemCard(
                                            selected = isSelected,
                                            title = candidate.title,
                                            subtitle = "${candidate.kind}: ${candidate.chatId}",
                                            onClick = {
                                                bindingChatIdDraft = candidate.chatId
                                                bindingTelegramAllowedChatIdDraft = candidate.chatId
                                                closeAfterDetectedBindingSave = true
                                                vm.showSettingsInfo("Telegram chat selected. Tap Save again to finish binding.")
                                            }
                                        )
                                    }
                                }
                                SessionSetupFeedbackText(
                                    message = state.sessionBindingTelegramInfo,
                                    visible = state.sessionBindingTelegramDiscoveryAttempted,
                                    useChinese = state.settingsUseChinese
                                )
                            }
                            SettingsAdvancedSection(
                                expanded = telegramAdvancedExpanded,
                                onToggle = { telegramAdvancedExpanded = !telegramAdvancedExpanded }
                            ) {
                                SettingsAdvancedOptionCard(
                                    title = "Telegram Chat ID",
                                    description = "Manual target override. Usually filled by Detect Chats."
                                ) {
                                    SettingsTextField(
                                        value = bindingChatIdDraft,
                                        onValueChange = { bindingChatIdDraft = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = "Telegram Chat ID",
                                        placeholder = "Filled automatically after Detect Chats"
                                    )
                                }
                                SettingsAdvancedOptionCard(
                                    title = "Allowed Chat ID",
                                    description = "Restricts replies to one chat. Usually the same as Telegram Chat ID."
                                ) {
                                    SettingsTextField(
                                        value = bindingTelegramAllowedChatIdDraft,
                                        onValueChange = { bindingTelegramAllowedChatIdDraft = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = "Allowed Chat ID",
                                        placeholder = "Usually same as chat ID"
                                    )
                                }
                            }
                                } else if (sessionSettingsPage == SessionSettingsPage.Configure && normalizedChannel == "discord") {
                            SessionSetupStepCard(
                                step = 1,
                                text = uiLabel("Open the Discord Developer Portal, create an application, then open Bot and add a bot.")
                            ) {
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
                                SettingsTextField(
                                    value = bindingDiscordBotTokenDraft,
                                    onValueChange = { bindingDiscordBotTokenDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "Discord Bot Token",
                                    visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation()
                                )
                            }
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
                            ) {
                                SettingsInfoBlock(
                                    label = uiLabel("User ID example"),
                                    value = "123456789012345678"
                                )
                                SettingsTextField(
                                    value = bindingChatIdDraft,
                                    onValueChange = { bindingChatIdDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "Target Channel ID",
                                    placeholder = "Example: 123456789012345678"
                                )
                            }
                            SessionSetupStepCard(
                                step = 5,
                                text = uiLabel("Choose how the bot should respond in this channel.")
                            ) {
                                ExposedDropdownMenuBox(
                                    expanded = bindingDiscordResponseModeMenuExpanded,
                                    onExpandedChange = { bindingDiscordResponseModeMenuExpanded = it }
                                ) {
                                    SettingsSelectField(
                                        value = uiLabel(bindingDiscordResponseModeDraft.ifBlank { "mention" }),
                                        modifier = Modifier
                                            .menuAnchor()
                                            .fillMaxWidth(),
                                        label = "Response Mode",
                                        trailingIcon = {
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = bindingDiscordResponseModeMenuExpanded)
                                        }
                                    )
                                    ExposedDropdownMenu(
                                        expanded = bindingDiscordResponseModeMenuExpanded,
                                        onDismissRequest = { bindingDiscordResponseModeMenuExpanded = false },
                                        shape = settingsTextFieldShape(),
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        tonalElevation = 0.dp,
                                        shadowElevation = 0.dp,
                                        border = settingsDropdownMenuBorder()
                                    ) {
                                        DropdownMenuItem(
                                            text = {
                                                SettingsDropdownMenuText(text = "mention")
                                            },
                                            onClick = {
                                                bindingDiscordResponseModeDraft = "mention"
                                                bindingDiscordResponseModeMenuExpanded = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                SettingsDropdownMenuText(text = "open")
                                            },
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
                            }
                            SessionSetupStepCard(
                                step = 6,
                                text = uiLabel("After filling the fields, tap Save at the bottom to start the Discord connection.")
                            )
                            SettingsAdvancedSection(
                                expanded = discordAdvancedExpanded,
                                onToggle = { discordAdvancedExpanded = !discordAdvancedExpanded }
                            ) {
                                SettingsAdvancedOptionCard(
                                    title = "Allowed User IDs",
                                    description = "Leave blank to allow anyone in the channel to trigger replies."
                                ) {
                                    SettingsTextField(
                                        value = bindingDiscordAllowedUserIdsDraft,
                                        onValueChange = { bindingDiscordAllowedUserIdsDraft = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 2,
                                        maxLines = 4,
                                        label = "Allowed User IDs",
                                        placeholder = "One ID per line or comma-separated"
                                    )
                                }
                            }
                                } else if (sessionSettingsPage == SessionSettingsPage.Configure && normalizedChannel == "slack") {
                            SessionSetupStepCard(
                                step = 1,
                                text = uiLabel("Create a Slack app from scratch, then enable Socket Mode.")
                            ) {
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
                            }
                            SessionSetupStepCard(
                                step = 2,
                                text = uiLabel("Turn on Socket Mode, then create an app-level token with connections:write.")
                            ) {
                                SettingsInfoBlock(
                                    label = uiLabel("App token example"),
                                    value = "xapp-1-A1234567890-1234567890-abcdefghijklmnopqrstuvwxyz"
                                )
                                SettingsTextField(
                                    value = bindingSlackAppTokenDraft,
                                    onValueChange = { bindingSlackAppTokenDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "Slack App Token (xapp)",
                                    visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation()
                                )
                            }
                            SessionSetupStepCard(
                                step = 3,
                                text = uiLabel("Add bot scopes chat:write, reactions:write, app_mentions:read, then enable Event Subscriptions for message and app mention events.")
                            )
                            SessionSetupStepCard(
                                step = 4,
                                text = uiLabel("Install the app to your workspace, then copy the bot token.")
                            ) {
                                SettingsInfoBlock(
                                    label = uiLabel("Bot token example"),
                                    value = "xoxb-123456789012-123456789012-abcdefghijklmnopqrstuvwxyz"
                                )
                                SettingsTextField(
                                    value = bindingSlackBotTokenDraft,
                                    onValueChange = { bindingSlackBotTokenDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "Slack Bot Token (xoxb)",
                                    visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation()
                                )
                            }
                            SessionSetupStepCard(
                                step = 5,
                                text = uiLabel("Enter the target conversation ID. Slack channel, group, and DM IDs usually start with C, G, or D.")
                            ) {
                                SettingsTextField(
                                    value = bindingChatIdDraft,
                                    onValueChange = { bindingChatIdDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "Target Channel ID",
                                    placeholder = "Example: C123ABC45 or D123ABC45"
                                )
                            }
                            SessionSetupStepCard(
                                step = 6,
                                text = uiLabel("Choose how the bot should respond in this conversation.")
                            ) {
                                ExposedDropdownMenuBox(
                                    expanded = bindingSlackResponseModeMenuExpanded,
                                    onExpandedChange = { bindingSlackResponseModeMenuExpanded = it }
                                ) {
                                    SettingsSelectField(
                                        value = uiLabel(bindingSlackResponseModeDraft.ifBlank { "mention" }),
                                        modifier = Modifier
                                            .menuAnchor()
                                            .fillMaxWidth(),
                                        label = "Response Mode",
                                        trailingIcon = {
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = bindingSlackResponseModeMenuExpanded)
                                        }
                                    )
                                    ExposedDropdownMenu(
                                        expanded = bindingSlackResponseModeMenuExpanded,
                                        onDismissRequest = { bindingSlackResponseModeMenuExpanded = false },
                                        shape = settingsTextFieldShape(),
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        tonalElevation = 0.dp,
                                        shadowElevation = 0.dp,
                                        border = settingsDropdownMenuBorder()
                                    ) {
                                        DropdownMenuItem(
                                            text = {
                                                SettingsDropdownMenuText(text = "mention")
                                            },
                                            onClick = {
                                                bindingSlackResponseModeDraft = "mention"
                                                bindingSlackResponseModeMenuExpanded = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                SettingsDropdownMenuText(text = "open")
                                            },
                                            onClick = {
                                                bindingSlackResponseModeDraft = "open"
                                                bindingSlackResponseModeMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            SessionSetupStepCard(
                                step = 7,
                                text = uiLabel("After filling the fields, tap Save at the bottom to start the Slack connection.")
                            )
                            SettingsAdvancedSection(
                                expanded = slackAdvancedExpanded,
                                onToggle = { slackAdvancedExpanded = !slackAdvancedExpanded }
                            ) {
                                SettingsAdvancedOptionCard(
                                    title = "Allowed User IDs",
                                    description = "Leave blank to allow anyone in this conversation to trigger replies."
                                ) {
                                    SettingsTextField(
                                        value = bindingSlackAllowedUserIdsDraft,
                                        onValueChange = { bindingSlackAllowedUserIdsDraft = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 2,
                                        maxLines = 4,
                                        label = "Allowed User IDs",
                                        placeholder = "One ID per line or comma-separated"
                                    )
                                }
                            }
                                } else if (sessionSettingsPage == SessionSettingsPage.Configure && normalizedChannel == "feishu") {
                            SessionSetupStepCard(
                                step = 1,
                                text = uiLabel("Create a Feishu app in Feishu Open Platform, enable Bot capability, then copy App ID and App Secret.")
                            ) {
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
                                SettingsTextField(
                                    value = bindingFeishuAppIdDraft,
                                    onValueChange = { bindingFeishuAppIdDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "Feishu App ID"
                                )
                                SettingsTextField(
                                    value = bindingFeishuAppSecretDraft,
                                    onValueChange = { bindingFeishuAppSecretDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "Feishu App Secret",
                                    visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation()
                                )
                            }
                            SessionSetupStepCard(
                                step = 2,
                                text = uiLabel("After filling App ID and App Secret, tap Save once at the bottom so PalmClaw starts Long Connection.")
                            )
                            SessionSetupStepCard(
                                step = 3,
                                text = uiLabel("In Events & Callbacks, select Long Connection, then add im.message.receive_v1.")
                            )
                            SessionSetupStepCard(
                                step = 4,
                                text = uiLabel("In Permission Management, add im:message and im:message.p2p_msg:readonly. If you test with @ in a group, also add im:message.group_at_msg:readonly.")
                            )
                            SessionSetupStepCard(
                                step = 5,
                                text = uiLabel("Publish the app, open it in Feishu, and confirm Long Connection while PalmClaw is running.")
                            )
                            SessionSetupStepCard(
                                step = 6,
                                text = uiLabel("In Feishu, send one message that @mentions the bot. Private chats and group chats both require @ to trigger replies.")
                            )
                            SessionSetupStepCard(
                                step = 7,
                                text = uiLabel("Tap Detect Chats, choose the conversation to bind, then tap Save again.")
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SettingsActionButton(
                                        text = uiLabel("Detect Chats"),
                                        icon = Icons.Rounded.Refresh,
                                        onClick = {
                                            vm.discoverFeishuChatsForBinding(
                                                appId = bindingFeishuAppIdDraft,
                                                appSecret = bindingFeishuAppSecretDraft,
                                                encryptKey = bindingFeishuEncryptKeyDraft,
                                                verificationToken = bindingFeishuVerificationTokenDraft
                                            )
                                        },
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
                                        val isSelected = bindingChatIdDraft.trim() == candidate.chatId
                                        SessionSetupSelectableItemCard(
                                            selected = isSelected,
                                            title = candidate.title,
                                            subtitle = "${candidate.kind}: ${candidate.chatId}",
                                            note = candidate.note.takeIf { it.isNotBlank() }?.let {
                                                localizedUiMessage(it, state.settingsUseChinese)
                                            }.orEmpty(),
                                            onClick = {
                                                bindingChatIdDraft = candidate.chatId
                                                bindingFeishuResponseModeDraft = "mention"
                                                closeAfterDetectedBindingSave = true
                                                vm.showSettingsInfo("Feishu chat selected. Tap Save again to finish binding.")
                                            }
                                        )
                                    }
                                }
                                SessionSetupFeedbackText(
                                    message = state.sessionBindingFeishuInfo,
                                    visible = state.sessionBindingFeishuDiscoveryAttempted,
                                    useChinese = state.settingsUseChinese
                                )
                            }
                            SettingsAdvancedSection(
                                expanded = feishuAdvancedExpanded,
                                onToggle = { feishuAdvancedExpanded = !feishuAdvancedExpanded }
                            ) {
                                SettingsAdvancedOptionCard(
                                    title = "Encrypt Key",
                                    description = "Only fill this if your Feishu app requires encrypted events."
                                ) {
                                    SettingsTextField(
                                        value = bindingFeishuEncryptKeyDraft,
                                        onValueChange = { bindingFeishuEncryptKeyDraft = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = "Encrypt Key"
                                    )
                                }
                                SettingsAdvancedOptionCard(
                                    title = "Verification Token",
                                    description = "Only fill this if your Feishu app has a verification token configured."
                                ) {
                                    SettingsTextField(
                                        value = bindingFeishuVerificationTokenDraft,
                                        onValueChange = { bindingFeishuVerificationTokenDraft = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = "Verification Token"
                                    )
                                }
                                SettingsAdvancedOptionCard(
                                    title = "Target ID",
                                    description = "Manual target override. Usually filled automatically after Detect Chats."
                                ) {
                                    SettingsTextField(
                                        value = bindingChatIdDraft,
                                        onValueChange = { bindingChatIdDraft = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = "Target ID",
                                        placeholder = "Private: ou_xxx, Group: oc_xxx"
                                    )
                                }
                                SettingsAdvancedOptionCard(
                                    title = "Allowed Open IDs",
                                    description = "Restricts which senders can trigger replies for this binding."
                                ) {
                                    SettingsTextField(
                                        value = bindingFeishuAllowedOpenIdsDraft,
                                        onValueChange = { bindingFeishuAllowedOpenIdsDraft = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 2,
                                        maxLines = 4,
                                        label = "Allowed Open IDs",
                                        placeholder = "One open_id per line, or * to allow all"
                                    )
                                }
                            }
                                } else if (sessionSettingsPage == SessionSettingsPage.Configure && normalizedChannel == "email") {
                            SessionSetupStepCard(
                                step = 1,
                                text = uiLabel("Prepare a mailbox for the bot. IMAP is used to read mail and SMTP is used to send replies.")
                            )
                            SessionSetupStepCard(
                                step = 2,
                                text = uiLabel("Enter IMAP settings for receiving mail.")
                            ) {
                                SettingsTextField(
                                    value = bindingEmailImapHostDraft,
                                    onValueChange = { bindingEmailImapHostDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "IMAP Host"
                                )
                                SettingsTextField(
                                    value = bindingEmailImapPortDraft,
                                    onValueChange = { bindingEmailImapPortDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "IMAP Port",
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                                SettingsTextField(
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
                                    label = "IMAP Username"
                                )
                                SettingsTextField(
                                    value = bindingEmailImapPasswordDraft,
                                    onValueChange = { bindingEmailImapPasswordDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "IMAP Password / App Password",
                                    visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation()
                                )
                            }
                            SessionSetupStepCard(
                                step = 3,
                                text = uiLabel("Enter SMTP settings for replies.")
                            ) {
                                SettingsTextField(
                                    value = bindingEmailSmtpHostDraft,
                                    onValueChange = { bindingEmailSmtpHostDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "SMTP Host"
                                )
                                SettingsTextField(
                                    value = bindingEmailSmtpPortDraft,
                                    onValueChange = { bindingEmailSmtpPortDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "SMTP Port",
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                                SettingsTextField(
                                    value = bindingEmailSmtpUsernameDraft,
                                    onValueChange = { bindingEmailSmtpUsernameDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "SMTP Username"
                                )
                                SettingsTextField(
                                    value = bindingEmailSmtpPasswordDraft,
                                    onValueChange = { bindingEmailSmtpPasswordDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "SMTP Password / App Password",
                                    visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation()
                                )
                                SettingsTextField(
                                    value = bindingEmailFromAddressDraft,
                                    onValueChange = { bindingEmailFromAddressDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "From Address"
                                )
                            }
                            SessionSetupStepCard(
                                step = 4,
                                text = uiLabel("Tap Save once to start mailbox polling, then send one email to this account.")
                            )
                            SessionSetupStepCard(
                                step = 5,
                                text = uiLabel("Tap Detect Senders, choose the sender address to bind, then tap Save again.")
                            ) {
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
                                                consentGranted = true,
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
                                        enabled = bindingEmailImapHostDraft.isNotBlank() &&
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
                                        val isSelected = bindingChatIdDraft.trim().equals(candidate.email, ignoreCase = true)
                                        SessionSetupSelectableItemCard(
                                            selected = isSelected,
                                            title = candidate.email,
                                            subtitle = candidate.subject.takeIf { it.isNotBlank() }?.let {
                                                "${tr("Last subject", "")}: $it"
                                            } ?: candidate.email,
                                            note = candidate.note.takeIf { it.isNotBlank() }?.let {
                                                localizedUiMessage(it, state.settingsUseChinese)
                                            }.orEmpty(),
                                            onClick = {
                                                bindingChatIdDraft = candidate.email
                                                closeAfterDetectedBindingSave = true
                                                vm.showSettingsInfo("Email sender selected. Tap Save again to finish binding.")
                                            }
                                        )
                                    }
                                }
                                SessionSetupFeedbackText(
                                    message = state.sessionBindingEmailInfo,
                                    visible = state.sessionBindingEmailDiscoveryAttempted,
                                    useChinese = state.settingsUseChinese
                                )
                            }
                            SettingsAdvancedSection(
                                expanded = emailAdvancedExpanded,
                                onToggle = { emailAdvancedExpanded = !emailAdvancedExpanded }
                            ) {
                                SettingsAdvancedOptionCard(
                                    title = "Auto reply",
                                    description = "Turn this off if you only want detection and manual replies."
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = uiLabel("Auto reply"),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        PalmClawSwitch(
                                            checked = bindingEmailAutoReplyEnabledDraft,
                                            onCheckedChange = { bindingEmailAutoReplyEnabledDraft = it }
                                        )
                                    }
                                }
                                SettingsAdvancedOptionCard(
                                    title = "Sender Email Address",
                                    description = "Manual sender override. Usually chosen from Detect Senders."
                                ) {
                                    SettingsTextField(
                                        value = bindingChatIdDraft,
                                        onValueChange = { bindingChatIdDraft = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = "Sender Email Address",
                                        placeholder = "someone@example.com"
                                    )
                                }
                            }
                                } else if (sessionSettingsPage == SessionSettingsPage.Configure && normalizedChannel == "wecom") {
                        SessionSetupStepCard(
                            step = 1,
                            text = uiLabel("In WeCom Admin, go to Security & Management > Management Tools, then create an AI Bot.")
                        )
                        SessionSetupStepCard(
                            step = 2,
                            text = uiLabel("Choose Manual Create, then choose API mode with long connection. Copy the Bot ID and Secret.")
                        )
                        SessionSetupStepCard(
                            step = 3,
                            text = uiLabel("Fill WeCom Bot ID and Secret below, then tap Save once to start the long connection.")
                        ) {
                            SettingsTextField(
                                value = bindingWeComBotIdDraft,
                                onValueChange = { bindingWeComBotIdDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                    label = "WeCom Bot ID"
                                )
                                SettingsTextField(
                                    value = bindingWeComSecretDraft,
                                    onValueChange = { bindingWeComSecretDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "WeCom Secret",
                                    visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation()
                                )
                            }
                            SessionSetupStepCard(
                                step = 4,
                                text = uiLabel("After Save, go to Available Permissions and grant the message permission for the bot.")
                            )
                            SessionSetupStepCard(
                                step = 5,
                                text = uiLabel("Open the bot in WeCom and send one message so the app can detect the conversation.")
                            )
                            SessionSetupStepCard(
                                step = 6,
                                text = uiLabel("Tap Detect Chats, choose the conversation to bind, then tap Save again.")
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SettingsActionButton(
                                        text = uiLabel("Detect Chats"),
                                        icon = Icons.Rounded.Refresh,
                                        onClick = {
                                            vm.discoverWeComChatsForBinding(
                                                botId = bindingWeComBotIdDraft,
                                                secret = bindingWeComSecretDraft
                                            )
                                        },
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
                                        val isSelected = bindingChatIdDraft.trim() == candidate.chatId
                                        SessionSetupSelectableItemCard(
                                            selected = isSelected,
                                            title = candidate.title,
                                            subtitle = "${candidate.kind}: ${candidate.chatId}",
                                            note = candidate.note.takeIf { it.isNotBlank() }?.let {
                                                localizedUiMessage(it, state.settingsUseChinese)
                                            }.orEmpty(),
                                            onClick = {
                                                bindingChatIdDraft = candidate.chatId
                                                closeAfterDetectedBindingSave = true
                                                vm.showSettingsInfo("WeCom chat selected. Tap Save again to finish binding.")
                                            }
                                        )
                                    }
                                }
                                SessionSetupFeedbackText(
                                    message = state.sessionBindingWeComInfo,
                                    visible = state.sessionBindingWeComDiscoveryAttempted,
                                    useChinese = state.settingsUseChinese
                                )
                            }
                            SettingsAdvancedSection(
                                expanded = weComAdvancedExpanded,
                                onToggle = { weComAdvancedExpanded = !weComAdvancedExpanded }
                            ) {
                                SettingsAdvancedOptionCard(
                                    title = "Target ID",
                                    description = "Manual target override. Use a detected chatId or a specific userId."
                                ) {
                                    SettingsTextField(
                                        value = bindingChatIdDraft,
                                        onValueChange = { bindingChatIdDraft = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = "Target ID",
                                        placeholder = "userId or detected chatId"
                                    )
                                }
                                SettingsAdvancedOptionCard(
                                    title = "Allowed User IDs",
                                    description = "Restricts which users can trigger replies. Use * to allow all."
                                ) {
                                    SettingsTextField(
                                        value = bindingWeComAllowedUserIdsDraft,
                                        onValueChange = { bindingWeComAllowedUserIdsDraft = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 2,
                                        maxLines = 4,
                                        label = "Allowed User IDs",
                                        placeholder = "One user ID per line, or * to allow all"
                                    )
                                }
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
                                        targetDisplayName = selectedTargetDisplay ?: bindingChatIdDraft.trim(),
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
                                        feishuResponseMode = bindingFeishuResponseModeDraft,
                                        feishuAllowedOpenIds = bindingFeishuAllowedOpenIdsDraft,
                                        emailConsentGranted = true,
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
                val longDistance = abs(listState.firstVisibleItemIndex - tailIndex) > 20
                if (animated && !longDistance) {
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
    val submitChatMessage: () -> Unit = {
        followLatest = true
        scrollToLatestAfterSend = true
        vm.sendMessage()
        keyboardController?.hide()
        Unit
    }
    val scrollToLatestAction = {
        if (tailIndex >= 0) {
            followLatest = true
            uiScope.launch { moveToLatest(animated = true) }
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

    LaunchedEffect(
        state.isGenerating,
        state.messages.lastOrNull()?.id,
        state.messages.lastOrNull()?.role
    ) {
        if (!state.isGenerating) {
            generationAnchorMessageId = null
            return@LaunchedEffect
        }
        val lastMessage = state.messages.lastOrNull()
        val latestUserLikeMessageId = state.messages
            .lastOrNull { message ->
                message.role != "assistant" && message.role != "tool"
            }
            ?.id
        when {
            generationAnchorMessageId == null -> {
                generationAnchorMessageId = latestUserLikeMessageId ?: lastMessage?.id
            }
            lastMessage != null && lastMessage.role != "assistant" && lastMessage.role != "tool" -> {
                generationAnchorMessageId = lastMessage.id
            }
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

    LaunchedEffect(state.currentSessionId) {
        // Session switch should snap to latest quickly without expensive animation.
        hasInitialJumpToBottom = false
        followLatest = true
        scrollToLatestAfterSend = false
        pendingHistoryRestore = null
        isLoadingOlderHistory = false
        olderHistoryLoadingStartedAtMs = 0L
        visibleHistoryRounds = HISTORY_ROUNDS_PAGE_SIZE
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
    LaunchedEffect(state.settingsInfo, mainSurface, sessionSettingsSessionId) {
        val info = state.settingsInfo?.trim().orEmpty()
        val canShowSettingsSnackbar =
            mainSurface == MainSurface.Settings || sessionSettingsSessionId != null
        if (info.isBlank() || !canShowSettingsSnackbar) return@LaunchedEffect
        val isBoundSuccess = info.startsWith("Bound to ", ignoreCase = true)
        if (closeAfterDetectedBindingSave && isBoundSuccess) {
            closeAfterDetectedBindingSave = false
            dismissSessionSettings()
        } else if (closeAfterDetectedBindingSave) {
            closeAfterDetectedBindingSave = false
        }
        settingsSnackbarHostState.currentSnackbarData?.dismiss()
        val isError = info.contains("failed", ignoreCase = true) ||
            info.contains("error", ignoreCase = true)
        val localizedMessage = localizedUiMessage(info, state.settingsUseChinese)
        if (sessionSettingsSessionId != null) {
            Toast.makeText(
                context.applicationContext,
                localizedMessage,
                if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            ).show()
        } else {
            settingsSnackbarHostState.showSnackbar(
                message = localizedMessage,
                withDismissAction = true,
                duration = if (isError) SnackbarDuration.Long else SnackbarDuration.Short
            )
        }
        vm.clearSettingsInfo()
    }

    if (showHeartbeatEditor) {
        LaunchedEffect(state.settingsHeartbeatDoc) {
            delay(650)
            vm.saveHeartbeatDocument(showSuccessMessage = false, showErrorMessage = false)
        }
        HeartbeatEditorSheet(
            heartbeatDoc = state.settingsHeartbeatDoc,
            saving = state.settingsSaving,
            onHeartbeatDocChange = vm::onSettingsHeartbeatDocChanged,
            onClose = { showHeartbeatEditor = false }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = mainSurface == MainSurface.Chat,
        drawerContent = {
            SessionDrawerContent(
                state = state,
                onCreateSessionRequest = { showCreateSessionDialog = true },
                onSelectSession = { sessionId ->
                    vm.selectSession(sessionId)
                    mainSurfaceName = MainSurface.Chat.name
                    uiScope.launch { drawerState.close() }
                },
                onRenameSession = { session ->
                    renameSessionName = session.title
                    pendingRenameSessionId = session.id
                },
                onConfigureSession = openSessionSettingsForSession,
                onDeleteSession = { sessionId -> pendingDeleteSessionId = sessionId },
                onOpenSettings = {
                    vm.openSettings()
                    settingsPageName = SettingsPanelPage.Home.name
                    mainSurfaceName = MainSurface.Settings.name
                    uiScope.launch { drawerState.close() }
                }
            )
        }
    ) {
        Scaffold(
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            topBar = {
                when (mainSurface) {
                    MainSurface.Chat -> TopAppBar(
                        modifier = Modifier.height(84.dp),
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
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .padding(start = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Menu,
                                    contentDescription = tr("Open menu", ""),
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clickable {
                                            dismissKeyboard()
                                            uiScope.launch { drawerState.open() }
                                        },
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.92f)
                                )
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
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.palmclaw_mark),
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Column {
                                    Text(
                                        text = settingsPageTitle,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    settingsPageSubtitle.takeIf { it.isNotBlank() }?.let { subtitle ->
                                        Text(
                                            text = subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = {
                                    if (settingsPage == SettingsPanelPage.Home) {
                                        uiScope.launch {
                                            mainSurfaceName = MainSurface.Chat.name
                                            runCatching { drawerState.snapTo(DrawerValue.Open) }
                                                .onFailure { drawerState.open() }
                                        }
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
                            val isDarkTheme = isSystemInDarkTheme()
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
                                    container = if (isDarkTheme) {
                                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.52f)
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
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
                            val displayContent = if (
                                state.settingsUseChinese &&
                                (message.role == "assistant" || isSystem) &&
                                shouldLocalizeUiMessage(visibleContent)
                            ) {
                                localizedUiMessage(visibleContent, useChinese = true)
                            } else {
                                visibleContent
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
                                                    markdown = displayContent,
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
                                                            markdown = displayContent,
                                                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                                            inlineCodeBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                                                            quoteBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.56f),
                                                            codeBlockBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f),
                                                            contentColor = bubbleColors.content
                                                        )
                                                    }
                                                } else {
                                                    MarkdownText(
                                                        markdown = displayContent,
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
                                                    markdown = displayContent,
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

                    ChatScrollOverlay(
                        listState = listState,
                        scrollIndicator = scrollIndicator,
                        showScrollToLatestButton = showScrollToLatestButton,
                        chatInputBarClearance = chatInputBarClearance,
                        onScrollToLatest = scrollToLatestAction
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .imePadding()
                    ) {
                        ChatComposerBar(
                            state = state,
                            onInputHeightChange = { inputBarSurfaceHeightPx = it },
                            onInputChanged = vm::onInputChanged,
                            onSendMessage = submitChatMessage,
                            onStopGeneration = vm::stopGeneration
                        )
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
                            permissionsDashboard = permissionsDashboard,
                            onNavigate = { target -> settingsPageName = target.name },
                            onCreateSessionRequest = { showCreateSessionDialog = true },
                            onRequestPermissions = { permissions ->
                                launchRuntimePermissionRequest(permissions)
                            },
                            onRefreshPermissionsStatus = { permissionsRefreshNonce += 1 },
                            revealApiKey = revealApiKey,
                            onRevealToggle = { revealApiKey = !revealApiKey },
                            onStartNewProviderDraft = vm::startNewProviderDraft,
                            onSelectProviderConfig = vm::selectProviderConfigForEditing,
                            onDeleteProviderConfig = vm::deleteProviderConfig,
                            onSetActiveProviderConfig = vm::setActiveProviderConfig,
                            onProviderChange = vm::onSettingsProviderChanged,
                            onProviderCustomNameChange = vm::onSettingsProviderCustomNameChanged,
                            onModelChange = vm::onSettingsModelChanged,
                            onApiKeyChange = vm::onSettingsApiKeyChanged,
                            onBaseUrlChange = vm::onSettingsBaseUrlChanged,
                            onTestProvider = vm::testProviderSettings,
                            onSaveProviderDraft = vm::saveProviderSettings,
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
                            onCheckUpdate = vm::checkAppUpdate,
                            onNotifyUpdateDownloadStarted = vm::notifyAppUpdateDownloadStarted,
                            onNotifyUpdateDownloadFallback = vm::notifyAppUpdateDownloadFallback,
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
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(start = 32.dp, end = 32.dp, top = 10.dp, bottom = 200.dp),
                        snackbar = { data ->
                            val rawMessage = data.visuals.message.trim()
                            val isError = rawMessage.contains("failed", ignoreCase = true) ||
                                rawMessage.contains("error", ignoreCase = true)
                            val isStructured = rawMessage.contains('\n') ||
                                rawMessage.length > 120 ||
                                Regex("\\w+:\\s+").containsMatchIn(rawMessage)
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = if (isError) {
                                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.96f)
                                    } else {
                                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.96f)
                                    },
                                    contentColor = if (isError) {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    },
                                    tonalElevation = 6.dp,
                                    shadowElevation = 10.dp,
                                    modifier = Modifier
                                        .widthIn(max = 560.dp)
                                        .fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(if (isStructured) 6.dp else 2.dp)
                                        ) {
                                            if (isError || isStructured) {
                                                Text(
                                                    text = if (isError) uiLabel("Error") else uiLabel("Notice"),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                            SelectionContainer {
                                                Text(
                                                    text = rawMessage,
                                                    style = if (isStructured) {
                                                        MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                                    } else {
                                                        MaterialTheme.typography.bodyMedium
                                                    },
                                                    maxLines = if (isStructured) Int.MAX_VALUE else 4,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                        MinimalActionIconButton(onClick = { data.dismiss() }) {
                                            Icon(
                                                imageVector = Icons.Rounded.Close,
                                                contentDescription = uiLabel("Close"),
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }

        AppUpdateDialogs(
            context = context,
            state = state,
            useChinese = isChinese,
            onDismissPrompt = vm::dismissAppUpdatePrompt,
            onDismissNotice = vm::dismissAppUpdateNotice,
            onDownloadStarted = vm::notifyAppUpdateDownloadStarted,
            onDownloadFallback = vm::notifyAppUpdateDownloadFallback
        )
    }
}

private enum class MainSurface {
    Chat,
    Settings
}

