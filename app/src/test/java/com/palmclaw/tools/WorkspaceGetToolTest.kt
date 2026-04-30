package com.palmclaw.tools

import com.palmclaw.workspace.SessionWorkspaceManager
import com.palmclaw.workspace.WorkspacePathResolver
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkspaceGetToolTest {
    private lateinit var rootDir: File
    private lateinit var workspaceManager: SessionWorkspaceManager

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("workspace-get-tool-test").toFile()
        workspaceManager = SessionWorkspaceManager(rootDir)
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    @Test
    fun `workspace_get returns current workspace snapshot and supported schemes`() = runBlocking {
        val snapshot = workspaceManager.ensureWorkspace("session:42", "Meaning Of Life")
        val tool = WorkspaceGetTool(
            workspaceManager = workspaceManager,
            currentSessionIdProvider = { "session:42" }
        )

        val result = tool.run("{}")
        val payload = Json.parseToJsonElement(result.content).jsonObject

        assertFalse(result.isError)
        assertEquals(snapshot.sessionId, payload.getValue("session_id").jsonPrimitive.content)
        assertEquals("Meaning Of Life", payload.getValue("session_title").jsonPrimitive.content)
        assertEquals(snapshot.workspaceRoot, payload.getValue("workspace_root").jsonPrimitive.content)
        assertEquals(
            WorkspacePathResolver.SESSION_SCHEME,
            payload.getValue("session_scheme").jsonPrimitive.content
        )
        assertEquals(
            WorkspacePathResolver.SHARED_SCHEME,
            payload.getValue("shared_scheme").jsonPrimitive.content
        )
        assertEquals(
            listOf("relative", WorkspacePathResolver.SESSION_SCHEME, WorkspacePathResolver.SHARED_SCHEME),
            payload.getValue("supported_path_schemes").jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals("session:42", result.metadata!!.getValue("session_id").jsonPrimitive.content)
        assertEquals(snapshot.workspaceRoot, result.metadata!!.getValue("workspace_root").jsonPrimitive.content)
    }
}
