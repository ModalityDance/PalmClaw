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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val chatContentState by vm.chatContentState.collectAsStateWithLifecycle()
    val onboardingUiState by vm.onboardingUiState.collectAsStateWithLifecycle()
    val settingsShellState by vm.settingsShellState.collectAsStateWithLifecycle()
    val identityDisplayState by vm.identityDisplayState.collectAsStateWithLifecycle()
    val channelsSettingsState by vm.channelsSettingsState.collectAsStateWithLifecycle()
    val automationSettingsState by vm.automationSettingsState.collectAsStateWithLifecycle()
    val alwaysOnSettingsState by vm.alwaysOnSettingsState.collectAsStateWithLifecycle()
    val updateSettingsState by vm.updateSettingsState.collectAsStateWithLifecycle()
    val sessionBindingState by vm.sessionBindingState.collectAsStateWithLifecycle()
    val isChinese = settingsShellState.useChinese
    if (!onboardingUiState.completed) {
        var onboardingStepName by rememberSaveable { mutableStateOf(OnboardingStep.Language.name) }
        val onboardingStep = runCatching { OnboardingStep.valueOf(onboardingStepName) }
            .getOrDefault(OnboardingStep.Language)
        FirstRunOnboardingScreen(
            state = onboardingUiState,
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
    val pickAttachmentsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            vm.importComposerAttachments(uris.map { it.toString() })
        }
    }
    val importSkillLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { vm.stageLocalSkillImport(it.toString()) }
    }
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
    val sessionSettingsDraft = rememberSessionSettingsDraftState()
    var settingsPageName by rememberSaveable { mutableStateOf(SettingsPanelPage.Home.name) }
    var previewAudioPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var previewAudioRef by rememberSaveable { mutableStateOf<String?>(null) }
    var previewAudioDurationMs by rememberSaveable { mutableStateOf(0) }
    var previewAudioPositionMs by rememberSaveable { mutableStateOf(0) }
    var inputBarSurfaceHeightPx by remember { mutableStateOf(0) }
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
        sessionSettingsDraft.bindingChannelMenuExpanded = false
        sessionSettingsDraft.bindingDiscordResponseModeMenuExpanded = false
        sessionSettingsDraft.bindingSlackResponseModeMenuExpanded = false
        sessionSettingsDraft.bindingFeishuResponseModeMenuExpanded = false
        vm.clearTelegramChatDiscovery()
        vm.clearFeishuChatDiscovery()
        vm.clearEmailSenderDiscovery()
        vm.clearWeComChatDiscovery()
        sessionSettingsDraft.closeAfterDetectedBindingSave = false
        sessionSettingsDraft.telegramAdvancedExpanded = false
        sessionSettingsDraft.discordAdvancedExpanded = false
        sessionSettingsDraft.slackAdvancedExpanded = false
        sessionSettingsDraft.feishuAdvancedExpanded = false
        sessionSettingsDraft.emailAdvancedExpanded = false
        sessionSettingsDraft.weComAdvancedExpanded = false
        sessionSettingsSessionId = null
        sessionSettingsPageName = SessionSettingsPage.Menu.name
    }
    val openSessionSettingsForSession: (UiSessionSummary) -> Unit = { session ->
        val draft = vm.getSessionChannelDraft(session.id)
        sessionSettingsDraft.loadFrom(draft)
        vm.clearTelegramChatDiscovery()
        vm.clearFeishuChatDiscovery()
        vm.clearEmailSenderDiscovery()
        vm.clearWeComChatDiscovery()
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
                sessionSettingsDraft.bindingChannelMenuExpanded = false
                sessionSettingsDraft.bindingDiscordResponseModeMenuExpanded = false
                sessionSettingsDraft.bindingSlackResponseModeMenuExpanded = false
                sessionSettingsDraft.bindingFeishuResponseModeMenuExpanded = false
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

    DisposableEffect(hostActivity, alwaysOnSettingsState.enabled, alwaysOnSettingsState.keepScreenAwake) {
        val activity = hostActivity
        if (activity != null) {
            if (alwaysOnSettingsState.enabled && alwaysOnSettingsState.keepScreenAwake) {
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
        val item = chatContentState.sessions.firstOrNull { it.id == sessionId && !it.isLocal }
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
        val item = chatContentState.sessions.firstOrNull { it.id == sessionId }
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

    SessionSettingsSheet(
        sessionId = sessionSettingsSessionId,
        chatContentState = chatContentState,
        channelsSettingsState = channelsSettingsState,
        sessionBindingState = sessionBindingState,
        settingsShellState = settingsShellState,
        sessionSettingsPage = sessionSettingsPage,
        sessionSettingsDraft = sessionSettingsDraft,
        revealApiKey = revealApiKey,
        vm = vm,
        dismissSessionSettings = dismissSessionSettings,
        onPageChange = { page -> sessionSettingsPageName = page.name },
        onCloseSessionSettings = dismissSessionSettings
    )

    val openAttachmentChooserTitle = uiLabel("Open attachment")
    val openAttachment: (UiAttachment) -> Unit = { attachment ->
        AttachmentOpenResolver.toUri(context, attachment.localWorkspacePath ?: attachment.reference)?.let { uri ->
            val mime = AttachmentOpenResolver.resolveMimeType(attachment)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { context.startActivity(Intent.createChooser(intent, openAttachmentChooserTitle)) }
        }
    }
    val toggleAudioPreview: (UiAttachment) -> Unit = { attachment ->
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
                val raw = (attachment.localWorkspacePath ?: attachment.reference).trim()
                val uri = AttachmentOpenResolver.toUri(context, raw)
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
        vm.sendMessage()
        Unit
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

    LaunchedEffect(mainSurface, settingsPage) {
        if (mainSurface != MainSurface.Settings) return@LaunchedEffect
        when (settingsPage) {
            SettingsPanelPage.Cron -> vm.refreshCronJobs()
            SettingsPanelPage.Runtime -> vm.refreshAgentLogs()
            SettingsPanelPage.Skills -> vm.refreshSkillCatalog()
            else -> Unit
        }
    }
    LaunchedEffect(settingsShellState.info, mainSurface, sessionSettingsSessionId) {
        val info = settingsShellState.info?.trim().orEmpty()
        val canShowSettingsSnackbar =
            mainSurface == MainSurface.Settings || sessionSettingsSessionId != null
        if (info.isBlank() || !canShowSettingsSnackbar) return@LaunchedEffect
        val isBoundSuccess = info.startsWith("Bound to ", ignoreCase = true)
        if (sessionSettingsDraft.closeAfterDetectedBindingSave && isBoundSuccess) {
            sessionSettingsDraft.closeAfterDetectedBindingSave = false
            dismissSessionSettings()
        } else if (sessionSettingsDraft.closeAfterDetectedBindingSave) {
            sessionSettingsDraft.closeAfterDetectedBindingSave = false
        }
        settingsSnackbarHostState.currentSnackbarData?.dismiss()
        val isError = info.contains("failed", ignoreCase = true) ||
            info.contains("error", ignoreCase = true)
        val localizedMessage = localizedUiMessage(info, settingsShellState.useChinese)
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
        LaunchedEffect(automationSettingsState.heartbeatDoc) {
            delay(650)
            vm.saveHeartbeatDocument(showSuccessMessage = false, showErrorMessage = false)
        }
        HeartbeatEditorSheet(
            heartbeatDoc = automationSettingsState.heartbeatDoc,
            saving = settingsShellState.saving,
            onHeartbeatDocChange = vm::onSettingsHeartbeatDocChanged,
            onClose = { showHeartbeatEditor = false }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = mainSurface == MainSurface.Chat,
        drawerContent = {
            SessionDrawerContent(
                state = chatContentState,
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
                                        text = if (chatContentState.currentSessionId == AppSession.LOCAL_SESSION_ID) {
                                            tr("LOCAL", "")
                                        } else {
                                            chatContentState.currentSessionTitle
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
                                        tint = if (settingsShellState.useChinese) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                                IconButton(onClick = vm::toggleUiTheme) {
                                    Icon(
                                        imageVector = if (settingsShellState.darkTheme) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                                        contentDescription = tr("Toggle theme", ""),
                                        tint = if (settingsShellState.darkTheme) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                            if (settingsShellState.saving) {
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
                        ChatMessageListPane(
                            state = chatContentState,
                            identity = identityDisplayState,
                            useChinese = settingsShellState.useChinese,
                            inputBarSurfaceHeightPx = inputBarSurfaceHeightPx,
                            previewAudioRef = previewAudioRef,
                            previewAudioDurationMs = previewAudioDurationMs,
                            previewAudioPositionMs = previewAudioPositionMs,
                            onOpenAttachment = openAttachment,
                            onToggleAudioPreview = toggleAudioPreview,
                            onLoadOlderMessages = vm::loadOlderMessages,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .imePadding()
                        ) {
                            ChatComposerBar(
                                state = chatContentState,
                                onInputHeightChange = { measuredHeight ->
                                    if (abs(inputBarSurfaceHeightPx - measuredHeight) > 2) {
                                        inputBarSurfaceHeightPx = measuredHeight
                                    }
                                },
                                onInputChanged = vm::onInputChanged,
                                onPickAttachments = { pickAttachmentsLauncher.launch(arrayOf("*/*")) },
                                onRemoveAttachment = vm::removeComposerAttachment,
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
                        val providerSettingsState by vm.providerSettingsState.collectAsStateWithLifecycle()
                        val skillsDiscoveryState by vm.skillsDiscoveryState.collectAsStateWithLifecycle()
                        val toolSettingsState by vm.toolSettingsState.collectAsStateWithLifecycle()
                        val mcpSettingsState by vm.mcpSettingsState.collectAsStateWithLifecycle()
                        SettingsContent(
                            settingsShellState = settingsShellState,
                            providerSettingsState = providerSettingsState,
                            channelsSettingsState = channelsSettingsState,
                            skillsDiscoveryState = skillsDiscoveryState,
                            toolSettingsState = toolSettingsState,
                            automationSettingsState = automationSettingsState,
                            alwaysOnSettingsState = alwaysOnSettingsState,
                            mcpSettingsState = mcpSettingsState,
                            updateSettingsState = updateSettingsState,
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
                            onToolEnabledChange = vm::onToolEnabledChanged,
                            onSearchProviderChange = vm::onSearchProviderChanged,
                            onSearchBraveApiKeyChange = vm::onSearchBraveApiKeyChanged,
                            onSearchTavilyApiKeyChange = vm::onSearchTavilyApiKeyChanged,
                            onSearchJinaApiKeyChange = vm::onSearchJinaApiKeyChanged,
                            onSearchKagiApiKeyChange = vm::onSearchKagiApiKeyChanged,
                            onSkillEnabledChange = vm::onSkillEnabledChanged,
                            onSkillAllowIncompatibleChange = vm::onSkillAllowIncompatibleChanged,
                            onSelectInstalledSkill = vm::selectInstalledSkill,
                            onClearInstalledSkillSelection = vm::clearInstalledSkillSelection,
                            onRefreshSkills = vm::refreshSkillCatalog,
                            onImportLocalSkill = {
                                importSkillLauncher.launch(
                                    arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")
                                )
                            },
                            onRefreshClawHub = vm::refreshClawHubBrowse,
                            onClawHubSearchQueryChange = vm::onClawHubSearchQueryChanged,
                            onSearchClawHub = vm::searchClawHubSkills,
                            onOpenClawHubSkillDetail = vm::openClawHubSkillDetail,
                            onClearClawHubSkillDetail = vm::clearClawHubSkillDetail,
                            onStageClawHubSkillInstall = vm::stageClawHubSkillInstall,
                            onConfirmStagedSkillInstall = vm::confirmStagedSkillInstall,
                            onDismissStagedSkillReview = vm::dismissStagedSkillReview,
                            onDeleteInstalledSkill = vm::deleteInstalledSkill,
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
                                    SettingsPanelPage.Tools -> vm.saveToolSettings(showSuccessMessage = false, showErrorMessage = false)
                                    SettingsPanelPage.Skills -> vm.saveSkillSettings(showSuccessMessage = false, showErrorMessage = false)
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
            state = updateSettingsState,
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
