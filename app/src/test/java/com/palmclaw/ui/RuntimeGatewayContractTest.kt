package com.palmclaw.ui

import com.palmclaw.ui.domain.RuntimeApplicationGateway
import com.palmclaw.ui.domain.RuntimeExecutionGateway
import com.palmclaw.ui.domain.RuntimeRefreshGateway
import com.palmclaw.ui.domain.RuntimeStatusSource
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
}
