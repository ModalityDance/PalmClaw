package com.palmclaw.tools

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MutationFeedbackTest {
    @Test
    fun `committed unverified results carry a model-visible receipt without claiming failure`() {
        val result = MutationFeedback(
            ExecutionState.COMMITTED, VerificationState.UNAVAILABLE,
            buildJsonObject { put("raw_contact_id", 7) }, "Inspect the original record."
        ).result("contacts", "create_contact", "contact_unverified", "Batch committed; readback unavailable.")
        val body = Json.parseToJsonElement(result.content).jsonObject
        assertFalse(result.isError)
        assertEquals("unverified", body["status"]!!.jsonPrimitive.content)
        assertEquals(7, body["receipt"]!!.jsonObject["raw_contact_id"]!!.jsonPrimitive.int)
        assertFalse(body["retry_safe"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `BLE timeout and disconnection preserve the target without advising replay`() {
        for (code in listOf("write_timeout", "disconnected", "write_failed")) {
            val failure = BluetoothGatewayResult.Failure(code, "old message", "retry")
                .afterWriteDispatch("AA:BB:CC:DD:EE:FF", "service", "characteristic", 4)
            assertNull(failure.nextStep)
            assertEquals(ExecutionState.UNKNOWN, failure.feedback!!.executionState)
            assertEquals("characteristic", failure.feedback.receipt["characteristic_uuid"]!!.jsonPrimitive.content)
            assertFalse(failure.feedback.result("bluetooth", "ble_write", code, failure.message)
                .metadata!!["retry_safe"]!!.jsonPrimitive.boolean)
        }
    }

    @Test
    fun `media observation distinguishes unavailable empty absent and available`() {
        assertEquals(MediaOutputState.UNAVAILABLE, observeMediaOutput { error("query failed") })
        assertEquals(MediaOutputState.UNAVAILABLE, observeMediaOutput { -1L })
        assertEquals(MediaOutputState.ABSENT, observeMediaOutput { null })
        assertEquals(MediaOutputState.EMPTY, observeMediaOutput { 0L })
        assertEquals(MediaOutputState.AVAILABLE, observeMediaOutput { 1L })
    }
}
