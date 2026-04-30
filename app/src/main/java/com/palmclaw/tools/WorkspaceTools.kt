package com.palmclaw.tools

import com.palmclaw.config.AppSession
import com.palmclaw.workspace.SessionWorkspaceManager
import com.palmclaw.workspace.WorkspacePathResolver
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.add
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class WorkspaceGetTool(
    private val workspaceManager: SessionWorkspaceManager,
    private val currentSessionIdProvider: () -> String
) : Tool {
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    override val name: String = "workspace_get"

    override val description: String =
        "Get the current session workspace paths, standard directories, and supported path schemes."

    override val jsonSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("properties", buildJsonObject {})
    }

    override suspend fun run(argumentsJson: String): ToolResult {
        val sessionId = currentSessionIdProvider().trim().ifBlank { AppSession.LOCAL_SESSION_ID }
        val snapshot = workspaceManager.getSnapshot(sessionId)
            ?: workspaceManager.ensureWorkspace(
                sessionId,
                if (sessionId == AppSession.LOCAL_SESSION_ID) AppSession.LOCAL_SESSION_TITLE else sessionId
            )
        val payload = buildJsonObject {
            put("version", snapshot.version)
            put("session_id", snapshot.sessionId)
            put("session_title", snapshot.sessionTitle)
            put("workspace_root", snapshot.workspaceRoot)
            put("docs_dir", snapshot.docsDir)
            put("scratch_dir", snapshot.scratchDir)
            put("artifacts_dir", snapshot.artifactsDir)
            put("created_at_ms", snapshot.createdAtMs)
            put("updated_at_ms", snapshot.updatedAtMs)
            put("shared_workspace_root", workspaceManager.sharedWorkspaceRoot().absolutePath)
            put("relative_path_default", "current_session_workspace")
            put("session_scheme", WorkspacePathResolver.SESSION_SCHEME)
            put("shared_scheme", WorkspacePathResolver.SHARED_SCHEME)
            putJsonArray("supported_path_schemes") {
                add("relative")
                add(WorkspacePathResolver.SESSION_SCHEME)
                add(WorkspacePathResolver.SHARED_SCHEME)
            }
        }
        return ToolResult(
            toolCallId = "",
            content = json.encodeToString(JsonObject.serializer(), payload),
            isError = false,
            metadata = buildJsonObject {
                put("session_id", snapshot.sessionId)
                put("workspace_root", snapshot.workspaceRoot)
            }
        )
    }
}
