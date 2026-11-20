package com.palmclaw.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Facts about a side effect, independent of whether its follow-up observation succeeded. */
internal enum class ExecutionState(val wireName: String) {
    COMMITTED("committed"), SUBMITTED("submitted"), UNKNOWN("unknown")
}

internal enum class VerificationState(val wireName: String) {
    VERIFIED("verified"), UNAVAILABLE("unavailable"), MISMATCH("mismatch"), NOT_PERFORMED("not_performed")
}

internal data class MutationFeedback(
    val executionState: ExecutionState,
    val verification: VerificationState,
    val receipt: JsonObject,
    val nextStep: String
) {
    fun result(tool: String, action: String, code: String, message: String): ToolResult {
        val body = buildJsonObject {
            put("tool", tool)
            put("action", action)
            put("status", if (executionState == ExecutionState.UNKNOWN) "outcome_unknown" else "unverified")
            put("code", code)
            put("message", message)
            put("execution_state", executionState.wireName)
            put("verification", verification.wireName)
            if (receipt.isNotEmpty()) put("receipt", receipt)
            // Never replay an uncertain mutation merely to recover its response.
            put("retry_safe", false)
            put("next_step", nextStep)
        }
        return ToolResult("", body.toString(), executionState == ExecutionState.UNKNOWN, body)
    }
}
