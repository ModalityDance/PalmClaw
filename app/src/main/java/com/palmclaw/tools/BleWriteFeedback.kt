package com.palmclaw.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Used only after dispatch was attempted, never for validation/permission rejection. */
internal fun BluetoothGatewayResult.Failure.afterWriteDispatch(
    address: String,
    serviceUuid: String,
    characteristicUuid: String,
    bytes: Int
): BluetoothGatewayResult.Failure = copy(
    message = "The GATT write has no reliable acknowledgement; the peripheral may have received it.",
    nextStep = null,
    feedback = MutationFeedback(
        ExecutionState.UNKNOWN, VerificationState.UNAVAILABLE,
        buildJsonObject {
            put("address", address)
            put("service_uuid", serviceUuid)
            put("characteristic_uuid", characteristicUuid)
            put("bytes_attempted", bytes)
            gattStatus?.let { put("gatt_status", it) }
        },
        "Reconnect if needed and inspect device state using its protocol. Do not resend a non-idempotent command merely because its acknowledgement is missing."
    )
)
