package com.palmclaw.ui.settings

import android.net.Uri
import com.palmclaw.config.AppConfig
import com.palmclaw.config.SkillUserState
import com.palmclaw.skills.SkillCompatibilityStatus
import com.palmclaw.skills.SkillSource
import com.palmclaw.ui.ChatStateStore
import com.palmclaw.ui.domain.SkillRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

internal class SkillSettingsCoordinator(
    private val scope: CoroutineScope,
    private val stateStore: ChatStateStore,
    private val skillRepository: SkillRepository,
    private val actions: Actions
) {
    data class Actions(
        val saveSkillSettings: (Boolean, Boolean) -> Unit,
        val getConfig: () -> AppConfig,
        val saveConfig: (AppConfig) -> Unit,
        val refreshGatewayRuntimeConfig: () -> Unit,
        val refreshSkillCatalog: suspend (Boolean) -> Unit
    )

    fun onSkillEnabledChanged(skillName: String, enabled: Boolean) {
        val normalizedName = skillName.trim()
        if (normalizedName.isBlank()) return
        stateStore.updateSkillsState { state ->
            state.copy(
                installedSkills = state.installedSkills.map { skill ->
                    if (skill.name == normalizedName) skill.copy(enabled = enabled) else skill
                },
                selectedSkillDetail = state.selectedSkillDetail?.let { detail ->
                    if (detail.name == normalizedName) detail.copy(enabled = enabled) else detail
                }
            )
        }
    }

    fun onSkillAllowIncompatibleChanged(skillName: String, allowIncompatible: Boolean) {
        val normalizedName = skillName.trim()
        if (normalizedName.isBlank()) return
        stateStore.updateSkillsState { state ->
            state.copy(
                installedSkills = state.installedSkills.map { skill ->
                    if (skill.name == normalizedName) skill.copy(allowIncompatible = allowIncompatible) else skill
                },
                selectedSkillDetail = state.selectedSkillDetail?.let { detail ->
                    if (detail.name == normalizedName) detail.copy(allowIncompatible = allowIncompatible) else detail
                }
            )
        }
    }

    fun selectInstalledSkill(skillName: String) {
        val normalizedName = skillName.trim()
        stateStore.updateSkillsState { state ->
            state.copy(selectedSkillName = normalizedName)
        }
    }

    fun clearInstalledSkillSelection() {
        stateStore.updateSkillsState {
            it.copy(
                selectedSkillName = "",
                selectedSkillDetail = null
            )
        }
    }

    fun canEnableDirectly(skill: UiSkillConfig): Boolean {
        return skill.compatibilityStatus == SkillCompatibilityStatus.Compatible ||
            skill.compatibilityStatus == SkillCompatibilityStatus.LikelyCompatible ||
            skill.allowIncompatible
    }

    fun saveSkillSettings(showSuccessMessage: Boolean, showErrorMessage: Boolean) =
        actions.saveSkillSettings(showSuccessMessage, showErrorMessage)

    fun stageClawHubSkillInstall(detailUrl: String) {
        val normalizedUrl = detailUrl.trim()
        if (normalizedUrl.isBlank()) return
        val skillTitle = clawHubSkillTitleFor(normalizedUrl)
        scope.launch {
            stateStore.updateSkillsState {
                it.copy(
                    skillActionInFlight = true,
                    selectedClawHubDetail = null,
                    downloadStatus = UiSkillDownloadStatus(
                        key = normalizedUrl,
                        title = skillTitle,
                        detailUrl = normalizedUrl,
                        status = "Downloading...",
                        inProgress = true
                    )
                )
            }
            stateStore.updateSettingsShellState { it.copy(info = "Downloading skill: $skillTitle") }
            runCatching {
                val detail = withContext(Dispatchers.IO) {
                    skillRepository.fetchSkillDetail(normalizedUrl)
                }
                withContext(Dispatchers.IO) {
                    skillRepository.stageClawHubSkill(detail)
                }
            }.onSuccess { review ->
                stateStore.updateSkillsState {
                    it.copy(
                        skillActionInFlight = false,
                        stagedSkillReview = SkillSettingsMapper.toUiStagedSkillReview(review),
                        selectedClawHubDetail = null,
                        downloadStatus = UiSkillDownloadStatus(
                            key = normalizedUrl,
                            title = review.detail.title.ifBlank { skillTitle },
                            detailUrl = normalizedUrl,
                            status = "Ready for review",
                            inProgress = false
                        )
                    )
                }
                stateStore.updateSettingsShellState {
                    it.copy(info = "Skill downloaded. Review before installing.")
                }
            }.onFailure { t ->
                val message = t.message ?: t.javaClass.simpleName
                stateStore.updateSkillsState {
                    it.copy(
                        skillActionInFlight = false,
                        selectedClawHubDetail = null,
                        downloadStatus = UiSkillDownloadStatus(
                            key = normalizedUrl,
                            title = skillTitle,
                            detailUrl = normalizedUrl,
                            status = "Download failed",
                            inProgress = false,
                            error = message
                        )
                    )
                }
                stateStore.updateSettingsShellState {
                    it.copy(info = "ClawHub install failed: $message")
                }
            }
        }
    }

    fun stageLocalSkillImport(uriString: String) {
        val normalizedUri = uriString.trim()
        if (normalizedUri.isBlank()) return
        scope.launch {
            stateStore.updateSkillsState {
                it.copy(
                    skillActionInFlight = true,
                    downloadStatus = null
                )
            }
            stateStore.updateSettingsShellState { it.copy(info = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    skillRepository.stageLocalSkillPackage(Uri.parse(normalizedUri))
                }
            }.onSuccess { review ->
                stateStore.updateSkillsState {
                    it.copy(
                        skillActionInFlight = false,
                        stagedSkillReview = SkillSettingsMapper.toUiStagedSkillReview(review),
                        selectedClawHubDetail = null
                    )
                }
            }.onFailure { t ->
                stateStore.updateSkillsState {
                    it.copy(skillActionInFlight = false)
                }
                stateStore.updateSettingsShellState {
                    it.copy(info = "Local skill import failed: ${t.message ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    fun dismissStagedSkillReview() {
        val review = stateStore.skillsDiscoveryState.value.stagedSkillReview
        stateStore.updateSkillsState {
            it.copy(
                stagedSkillReview = null,
                downloadStatus = null
            )
        }
        review?.stagingId?.let { stagingId ->
            scope.launch(Dispatchers.IO) {
                skillRepository.cleanupStaging(stagingId)
            }
        }
    }

    fun confirmStagedSkillInstall() {
        val review = stateStore.skillsDiscoveryState.value.stagedSkillReview ?: return
        val requiresForceEnable = review.compatibilityStatus in setOf(
            SkillCompatibilityStatus.Unknown,
            SkillCompatibilityStatus.DesktopRequired
        )
        val enableOnInstall = !requiresForceEnable
        scope.launch {
            stateStore.updateSkillsState {
                it.copy(skillActionInFlight = true)
            }
            stateStore.updateSettingsShellState { it.copy(info = "Installing skill...") }
            runCatching {
                withContext(Dispatchers.IO) {
                    skillRepository.installStagedSkill(
                        review = SkillSettingsMapper.toStagedSkillReview(review),
                        enable = enableOnInstall,
                        allowIncompatible = false
                    )
                }
                val currentConfig = actions.getConfig()
                actions.saveConfig(
                    currentConfig.copy(
                        skillStates = currentConfig.skillStates + (
                            review.suggestedName to SkillUserState(
                                enabled = enableOnInstall,
                                allowIncompatible = false
                            )
                        )
                    )
                )
                actions.refreshGatewayRuntimeConfig()
                actions.refreshSkillCatalog(false)
            }.onSuccess {
                stateStore.updateSkillsState {
                    it.copy(
                        skillActionInFlight = false,
                        stagedSkillReview = null,
                        downloadStatus = null
                    )
                }
                stateStore.updateSettingsShellState {
                    it.copy(
                        info = if (enableOnInstall) {
                            "Skill installed and enabled."
                        } else {
                            "Skill installed. Review compatibility before force enabling it."
                        }
                    )
                }
            }.onFailure { t ->
                stateStore.updateSkillsState {
                    it.copy(skillActionInFlight = false)
                }
                stateStore.updateSettingsShellState {
                    it.copy(info = "Install failed: ${t.message ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    fun deleteInstalledSkill(skillName: String) {
        val normalizedName = skillName.trim()
        if (normalizedName.isBlank()) return
        val entry = skillRepository.getSkill(normalizedName)
        if (entry?.source == SkillSource.Builtin || normalizedName.lowercase(Locale.US) in PROTECTED_SKILL_NAMES) {
            stateStore.updateSettingsShellState {
                it.copy(info = "Local skills cannot be deleted.")
            }
            return
        }
        scope.launch {
            stateStore.updateSkillsState {
                it.copy(skillActionInFlight = true)
            }
            stateStore.updateSettingsShellState { it.copy(info = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    skillRepository.deleteInstalledSkill(normalizedName)
                }
                val current = actions.getConfig()
                actions.saveConfig(
                    current.copy(
                        skillStates = current.skillStates - normalizedName
                    )
                )
                actions.refreshGatewayRuntimeConfig()
            }.onSuccess {
                actions.refreshSkillCatalog(false)
                stateStore.updateSkillsState {
                    it.copy(skillActionInFlight = false)
                }
                stateStore.updateSettingsShellState { it.copy(info = "Skill deleted.") }
            }.onFailure { t ->
                stateStore.updateSkillsState {
                    it.copy(skillActionInFlight = false)
                }
                stateStore.updateSettingsShellState {
                    it.copy(info = "Delete failed: ${t.message ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    private fun clawHubSkillTitleFor(detailUrl: String): String {
        val state = stateStore.skillsDiscoveryState.value
        return state.selectedClawHubDetail
            ?.takeIf { it.detailUrl == detailUrl }
            ?.title
            ?: state.clawHubSearchResults.firstOrNull { it.detailUrl == detailUrl }?.title
            ?: state.clawHubStaffPicks.firstOrNull { it.detailUrl == detailUrl }?.title
            ?: state.clawHubPopular.firstOrNull { it.detailUrl == detailUrl }?.title
            ?: detailUrl
    }

    private companion object {
        val PROTECTED_SKILL_NAMES = setOf("channels")
    }
}
