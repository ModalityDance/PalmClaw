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


/**
 * First-run onboarding screen and its supporting components.
 */
internal enum class OnboardingStep {
    Language,
    Provider,
    Identity
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FirstRunOnboardingScreen(
    state: ChatUiState,
    step: OnboardingStep,
    onStepChange: (OnboardingStep) -> Unit,
    onLanguageSelected: (Boolean) -> Unit,
    onProviderChange: (String) -> Unit,
    onProviderCustomNameChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onUserDisplayNameChange: (String) -> Unit,
    onAgentDisplayNameChange: (String) -> Unit,
    onTestProvider: () -> Unit,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
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
                .imePadding()
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
                                OnboardingStep.Provider -> tr("Provider", "提供方")
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
                            val providerPortalUrl = providerApiPortalUrl(selectedProvider.id)
                            SettingsSectionCard(
                                title = tr("Provider", "提供方"),
                                subtitle = tr(
                                    "Choose the provider PalmClaw will use for chat and tools.",
                                    "选择 PalmClaw 用于聊天和工具的 Provider。"
                                )
                            ) {
                                ExposedDropdownMenuBox(
                                    expanded = providerMenuExpanded,
                                    onExpandedChange = { providerMenuExpanded = !providerMenuExpanded }
                                ) {
                                    SettingsSelectField(
                                        value = providerDisplayTitle(selectedProvider.id),
                                        modifier = Modifier
                                            .menuAnchor()
                                            .fillMaxWidth(),
                                        label = tr("Service", "服务"),
                                        trailingIcon = {
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenuExpanded)
                                        }
                                    )
                                    DropdownMenu(
                                        expanded = providerMenuExpanded,
                                        onDismissRequest = { providerMenuExpanded = false },
                                        shape = settingsTextFieldShape(),
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        tonalElevation = 0.dp,
                                        shadowElevation = 0.dp,
                                        border = settingsDropdownMenuBorder()
                                    ) {
                                        providerOptions.forEach { option ->
                                            DropdownMenuItem(
                                                text = {
                                                    ProviderDropdownText(providerId = option.id)
                                                },
                                                onClick = {
                                                    onProviderChange(option.id)
                                                    providerMenuExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                                OutlinedButton(
                                    onClick = {
                                        providerPortalUrl?.let { openExternalUrl(context, it) }
                                    },
                                    enabled = providerPortalUrl != null,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.20f),
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        disabledContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.12f),
                                        disabledContentColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.42f)
                                    )
                                ) {
                                    Text(
                                        text = providerPortalButtonText(
                                            useChinese = state.settingsUseChinese,
                                            providerTitle = providerDisplayTitle(selectedProvider.id),
                                            enabled = providerPortalUrl != null
                                        ),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                OutlinedTextField(
                                    value = state.settingsBaseUrl,
                                    onValueChange = onBaseUrlChange,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(tr("Endpoint URL", "接口地址")) },
                                    singleLine = true,
                                    shape = settingsTextFieldShape(),
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    colors = settingsTextFieldColors()
                                )
                                if (selectedProvider.id == "custom") {
                                    OutlinedTextField(
                                        value = state.settingsProviderCustomName,
                                        onValueChange = onProviderCustomNameChange,
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text(tr("Custom Name", "自定义名称")) },
                                        singleLine = true,
                                        shape = settingsTextFieldShape(),
                                        textStyle = MaterialTheme.typography.bodyMedium,
                                        colors = settingsTextFieldColors()
                                    )
                                }
                                ProviderModelField(
                                    providerId = selectedProvider.id,
                                    value = state.settingsModel,
                                    onValueChange = onModelChange,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = state.settingsApiKey,
                                    onValueChange = onApiKeyChange,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(tr("API Key", "API 密钥")) },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    shape = settingsTextFieldShape(),
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    colors = settingsTextFieldColors()
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
                                    placeholder = { Text(if (state.settingsUseChinese) "你" else "You") },
                                    singleLine = true,
                                    shape = settingsTextFieldShape(),
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    colors = settingsTextFieldColors()
                                )
                                OutlinedTextField(
                                    value = state.onboardingAgentDisplayName,
                                    onValueChange = onAgentDisplayNameChange,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(tr("What is the agent's name?", "")) },
                                    placeholder = { Text("PalmClaw") },
                                    singleLine = true,
                                    shape = settingsTextFieldShape(),
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    colors = settingsTextFieldColors()
                                )
                            }
                        }
                    }

                    state.settingsInfo?.takeIf { it.isNotBlank() }?.let { info ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.28f),
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ) {
                            Text(
                                text = localizedUiMessage(info, state.settingsUseChinese),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
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
internal fun OnboardingHeroCard(
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
internal fun OnboardingNavIconButton(
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
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.22f)
        },
        contentColor = if (filled) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
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
internal fun OnboardingStepChip(
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
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.22f)
        },
        contentColor = if (active) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
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
internal fun OnboardingChoiceCard(
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
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f)
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
