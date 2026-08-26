package com.palmclaw.ui

import com.palmclaw.ui.domain.RuntimeApplicationGateway
import com.palmclaw.ui.domain.RuntimeExecutionGateway
import com.palmclaw.ui.domain.RuntimeRefreshGateway
import com.palmclaw.ui.domain.RuntimeStatusSource
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeGatewayContractTest {
    @Test
    fun `runtime application gateway implements each narrow ui contract`() {
        assertTrue(
            RuntimeStatusSource::class.java.isAssignableFrom(RuntimeApplicationGateway::class.java)
        )
        assertTrue(
            RuntimeExecutionGateway::class.java.isAssignableFrom(RuntimeApplicationGateway::class.java)
        )
        assertTrue(
            RuntimeRefreshGateway::class.java.isAssignableFrom(RuntimeApplicationGateway::class.java)
        )
    }

    @Test
    fun `runtime user message execution leaves the ui dispatcher`() {
        val sourceFile = listOf(
            File("src/main/java/com/palmclaw/ui/domain/UiDomainServices.kt"),
            File("app/src/main/java/com/palmclaw/ui/domain/UiDomainServices.kt")
        ).first { it.exists() }
        val source = sourceFile.readText()
        val gatewayStart = source.indexOf("class RuntimeApplicationGateway")
        val runStart = source.indexOf("override suspend fun runUserMessage", gatewayStart)
        val nextMethod = source.indexOf("override suspend fun triggerHeartbeatNow", runStart)
        val runSource = source.substring(runStart, nextMethod)

        assertTrue(runSource.contains("withContext(Dispatchers.Default)"))
    }
}
