package com.palmclaw.ui.settings

import com.palmclaw.skills.SkillCompatibilityStatus
import com.palmclaw.ui.ChatStateStore

internal class SkillSettingsCoordinator(
    private val stateStore: ChatStateStore,
    private val actions: Actions
) {
    data class Actions(
        val saveSkillSettings: (Boolean, Boolean) -> Unit
    )

    fun onSkillEnabledChanged(skillName: String, enabled: Boolean) {
        val normalizedName = skillName.trim()
        if (normalizedName.isBlank()) return
        stateStore.updateToolSettings { state ->
            state.copy(
                settingsInstalledSkills = state.settingsInstalledSkills.map { skill ->
                    if (skill.name == normalizedName) skill.copy(enabled = enabled) else skill
                },
                settingsSelectedSkillDetail = state.settingsSelectedSkillDetail?.let { detail ->
                    if (detail.name == normalizedName) detail.copy(enabled = enabled) else detail
                }
            )
        }
    }

    fun onSkillAllowIncompatibleChanged(skillName: String, allowIncompatible: Boolean) {
        val normalizedName = skillName.trim()
        if (normalizedName.isBlank()) return
        stateStore.updateToolSettings { state ->
            state.copy(
                settingsInstalledSkills = state.settingsInstalledSkills.map { skill ->
                    if (skill.name == normalizedName) skill.copy(allowIncompatible = allowIncompatible) else skill
                },
                settingsSelectedSkillDetail = state.settingsSelectedSkillDetail?.let { detail ->
                    if (detail.name == normalizedName) detail.copy(allowIncompatible = allowIncompatible) else detail
                }
            )
        }
    }

    fun selectInstalledSkill(skillName: String) {
        val normalizedName = skillName.trim()
        stateStore.updateToolSettings { state ->
            state.copy(settingsSelectedSkillName = normalizedName)
        }
    }

    fun clearInstalledSkillSelection() {
        stateStore.updateToolSettings {
            it.copy(
                settingsSelectedSkillName = "",
                settingsSelectedSkillDetail = null
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
}
