package com.palmclaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.palmclaw.providers.ProviderCatalog
import java.util.Locale

internal fun providerApiPortalUrl(providerId: String): String? {
    return when (providerId.trim().lowercase(Locale.getDefault())) {
        "openai" -> "https://platform.openai.com/api-keys"
        "anthropic" -> "https://console.anthropic.com/settings/keys"
        "google" -> "https://aistudio.google.com/app/apikey"
        "openrouter" -> "https://openrouter.ai/keys"
        "deepseek" -> "https://platform.deepseek.com/api_keys"
        "groq" -> "https://console.groq.com/keys"
        "minimax" -> "https://www.minimax.io/platform/user-center/basic-information/interface-key"
        "dashscope" -> "https://dashscope.console.aliyun.com/apiKey"
        "moonshot" -> "https://platform.moonshot.cn/console/api-keys"
        "zhipu" -> "https://open.bigmodel.cn/usercenter/apikeys"
        "volcengine" -> "https://www.volcengine.com/docs/82379"
        "byteplus" -> "https://docs.byteplus.com/en/docs/ModelArk"
        "mistral" -> "https://console.mistral.ai/"
        else -> null
    }
}

internal fun providerPortalButtonText(
    useChinese: Boolean,
    providerTitle: String,
    enabled: Boolean
): String {
    return if (enabled) {
        if (useChinese) "前往 $providerTitle 官方 API 页面" else "Open $providerTitle API page"
    } else {
        if (useChinese) "$providerTitle 没有官方 API 页面" else "No official API page for $providerTitle"
    }
}

@Composable
internal fun providerDisplayTitle(providerId: String): String {
    val profile = ProviderCatalog.resolve(providerId)
    val useChinese = LocalUiLanguage.current == UiLanguage.Chinese

    fun withChineseNote(english: String, chinese: String): String {
        return if (useChinese) "$english（$chinese）" else english
    }

    return when (profile.id) {
        "deepseek" -> withChineseNote("DeepSeek", "深度求索")
        "minimax" -> withChineseNote("MiniMax", "稀宇极智")
        "dashscope" -> withChineseNote("Alibaba Cloud", "阿里云百炼")
        "moonshot" -> withChineseNote("Moonshot AI", "月之暗面")
        "zhipu" -> withChineseNote("Zhipu AI", "智谱 AI")
        "volcengine" -> withChineseNote("Volcengine", "火山引擎")
        "byteplus" -> withChineseNote("BytePlus", "字节跳动海外")
        "custom" -> if (useChinese) "自定义" else "Custom"
        else -> profile.title
    }
}

internal fun isCustomProvider(providerId: String): Boolean {
    return ProviderCatalog.resolve(providerId).id == "custom"
}

@Composable
internal fun ProviderDropdownText(
    providerId: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val markerColor = textColor.copy(alpha = 0.55f)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isCustomProvider(providerId)) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(
                        color = markerColor,
                        shape = CircleShape
                    )
            )
        }
        Text(
            text = providerDisplayTitle(providerId),
            style = style,
            color = textColor,
            maxLines = maxLines,
            overflow = overflow
        )
    }
}

@Composable
internal fun providerConfigServiceTitle(config: UiProviderConfig): String {
    val customName = config.customName.trim()
    return if (config.providerName == "custom" && customName.isNotBlank()) {
        customName
    } else {
        providerDisplayTitle(config.providerName)
    }
}

@Composable
internal fun providerConfigModelTitle(config: UiProviderConfig): String {
    return config.model.trim().ifBlank {
        localizedText(
            "No model",
            "未设置模型",
            useChinese = LocalUiLanguage.current == UiLanguage.Chinese
        )
    }
}

@Composable
internal fun providerModelPickerLabel(providerId: String, model: String): String {
    val useChinese = LocalUiLanguage.current == UiLanguage.Chinese
    val suggestedModels = ProviderCatalog.suggestedModels(providerId)
    val trimmedModel = model.trim()
    return when {
        trimmedModel.isBlank() -> localizedText("Choose a model", "选择模型", useChinese = useChinese)
        suggestedModels.contains(trimmedModel) -> trimmedModel
        else -> localizedText("Custom", "自定义", useChinese = useChinese)
    }
}

@Composable
internal fun providerModelHintText(providerId: String): String {
    val useChinese = LocalUiLanguage.current == UiLanguage.Chinese
    return when (providerId.trim().lowercase(Locale.getDefault())) {
        "volcengine", "byteplus" -> localizedText(
            "Choose preset or enter manually.",
            "选择预设或手动填写。",
            useChinese = useChinese
        )

        "openrouter" -> localizedText(
            "Choose preset or enter manually.",
            "选择预设或手动填写。",
            useChinese = useChinese
        )

        "custom" -> localizedText(
            "Enter model name",
            "请填写模型名",
            useChinese = useChinese
        )

        else -> localizedText(
            "Choose preset or enter manually",
            "选择预设或手动填写",
            useChinese = useChinese
        )
    }
}

@Composable
internal fun ProviderModelField(
    providerId: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val suggestedModels = remember(providerId) { ProviderCatalog.suggestedModels(providerId) }
    var modelMenuExpanded by rememberSaveable(providerId) { mutableStateOf(false) }
    val useChinese = LocalUiLanguage.current == UiLanguage.Chinese
    val modelLabel = localizedText("Model", "模型", useChinese = useChinese)

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(modelLabel) },
            supportingText = {
                Text(
                    text = providerModelHintText(providerId),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            trailingIcon = if (suggestedModels.isNotEmpty()) {
                {
                    IconButton(onClick = { modelMenuExpanded = !modelMenuExpanded }) {
                        Icon(
                            imageVector = if (modelMenuExpanded) {
                                Icons.Rounded.KeyboardArrowUp
                            } else {
                                Icons.Rounded.KeyboardArrowDown
                            },
                            contentDescription = providerModelPickerLabel(providerId, value)
                        )
                    }
                }
            } else {
                null
            },
            singleLine = true,
            shape = settingsTextFieldShape(),
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = settingsTextFieldColors()
        )

        if (suggestedModels.isNotEmpty()) {
            DropdownMenu(
                expanded = modelMenuExpanded,
                onDismissRequest = { modelMenuExpanded = false },
                shape = settingsTextFieldShape(),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                border = settingsDropdownMenuBorder()
            ) {
                suggestedModels.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            SettingsDropdownMenuText(
                                text = option,
                                localized = false
                            )
                        },
                        onClick = {
                            onValueChange(option)
                            modelMenuExpanded = false
                        }
                    )
                }
            }
        }
    }
}
