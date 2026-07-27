package com.palmclaw.tools

import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class BluetoothPermissionScope {
    SCAN,
    CONNECT
}

internal interface BluetoothUserInteraction {
    suspend fun ensurePermissions(
        action: String,
        scopes: Set<BluetoothPermissionScope>
    ): BluetoothGatewayResult<Unit>

    suspend fun requestEnableBluetooth(): Boolean?

    suspend fun openBluetoothSettings(): Boolean?

    suspend fun confirm(
        title: String,
        message: String,
        confirmLabel: String,
        cancelLabel: String
    ): Boolean?
}

internal class BluetoothControlTool(
    private val gateway: BleClientGateway,
    private val userInteraction: BluetoothUserInteraction
) : Tool, TimedTool {
    override val name: String = "bluetooth"
    override val description: String =
        "Inspect Bluetooth state and perform bounded BLE tasks. " +
            "Use action=status|set_power|open_settings|paired_list|ble_scan|ble_connect|" +
            "ble_inspect|ble_read|ble_write|ble_disconnect. " +
            "BLE writes require explicit UUIDs, value encoding, and user confirmation."
    override val timeoutMs: Long = 300_000L
    override val jsonSchema: JsonObject = bluetoothToolSchema()

    override suspend fun run(argumentsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val args = try {
            BLUETOOTH_JSON.decodeFromString<Args>(argumentsJson)
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            return@withContext error(
                action = "unknown",
                code = "invalid_arguments",
                message = failure.message ?: "Invalid Bluetooth arguments.",
                nextStep = "Use the fields declared in the bluetooth tool schema."
            )
        }
        val action = args.action.trim().lowercase(Locale.US)
        try {
            when (action) {
                "status" -> status(action)
                "set_power" -> setPower(action, args)
                "open_settings" -> openSettings(action)
                "paired_list" -> pairedList(action)
                "ble_scan" -> scan(action, args)
                "ble_connect" -> connect(action, args)
                "ble_inspect" -> inspect(action)
                "ble_read" -> read(action, args)
                "ble_write" -> write(action, args)
                "ble_disconnect" -> disconnect(action)
                else -> error(
                    action,
                    "unsupported_action",
                    "Unsupported Bluetooth action '${args.action}'.",
                    "Use one of the actions declared in the bluetooth tool schema."
                )
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            error(
                action,
                "bluetooth_error",
                failure.message ?: failure.javaClass.simpleName,
                "Inspect Bluetooth state and retry."
            )
        }
    }

    private suspend fun status(action: String): ToolResult {
        permission(action, setOf(BluetoothPermissionScope.CONNECT))?.let { return it }
        return when (val result = gateway.status()) {
            is BluetoothGatewayResult.Success -> ok(action, "Bluetooth status loaded.") {
                putStatus(result.value)
            }
            is BluetoothGatewayResult.Failure -> gatewayError(action, result)
        }
    }

    private suspend fun setPower(action: String, args: Args): ToolResult {
        permission(action, setOf(BluetoothPermissionScope.CONNECT))?.let { return it }
        val enabled = args.enabled ?: return error(
            action,
            "invalid_arguments",
            "set_power requires enabled.",
            "Set enabled to true or false."
        )
        return when (val result = gateway.trySetPower(enabled)) {
            is BluetoothGatewayResult.Failure -> gatewayError(action, result)
            is BluetoothGatewayResult.Success -> {
                val power = result.value
                when {
                    power.enabled == enabled -> ok(action, "Bluetooth power state verified.") {
                        put("enabled", power.enabled)
                        put("changed_directly", power.changedDirectly)
                        put("user_action_required", false)
                    }
                    !power.userActionRequired -> error(
                        action,
                        "power_change_failed",
                        "Bluetooth did not reach the requested power state.",
                        "Open Bluetooth settings and change it manually."
                    )
                    else -> completeManualPower(action, enabled)
                }
            }
        }
    }

    private suspend fun completeManualPower(action: String, enabled: Boolean): ToolResult {
        if (enabled && userInteraction.requestEnableBluetooth() == true) {
            verifiedEnabled()?.let { verified ->
                if (verified) {
                    return ok(action, "Bluetooth enabled through the system dialog.") {
                        put("enabled", true)
                        put("changed_directly", false)
                        put("user_action_required", true)
                    }
                }
            }
        }
        if (userInteraction.openBluetoothSettings() != true) {
            return error(
                action,
                "settings_unavailable",
                "Bluetooth settings could not be opened.",
                "Open system Bluetooth settings manually."
            )
        }
        val confirmed = userInteraction.confirm(
            title = "Bluetooth Power",
            message = "Set Bluetooth ${if (enabled) "ON" else "OFF"} in system settings, then return.",
            confirmLabel = "Continue",
            cancelLabel = "Cancel"
        )
        if (confirmed != true) {
            return error(
                action,
                if (confirmed == false) "user_cancelled" else "confirmation_unavailable",
                if (confirmed == false) {
                    "Bluetooth power change was cancelled."
                } else {
                    "Bluetooth power confirmation is unavailable."
                },
                "Change Bluetooth in system settings and retry."
            )
        }
        val actual = verifiedEnabled()
            ?: return error(
                action,
                "bluetooth_unavailable",
                "Bluetooth status is unavailable after the system flow.",
                "Check whether this device supports Bluetooth."
            )
        return if (actual == enabled) {
            ok(action, "Bluetooth power state verified after system settings.") {
                put("enabled", actual)
                put("changed_directly", false)
                put("user_action_required", true)
            }
        } else {
            error(
                action,
                "power_change_not_verified",
                "Bluetooth did not reach the requested power state.",
                "Change Bluetooth in system settings and retry.",
                details = { put("enabled", actual) }
            )
        }
    }

    private suspend fun openSettings(action: String): ToolResult =
        if (userInteraction.openBluetoothSettings() == true) {
            ok(action, "Bluetooth settings opened.") { put("opened", true) }
        } else {
            error(
                action,
                "settings_unavailable",
                "Bluetooth settings could not be opened.",
                "Open system Bluetooth settings manually."
            )
        }

    private suspend fun pairedList(action: String): ToolResult {
        permission(action, setOf(BluetoothPermissionScope.CONNECT))?.let { return it }
        return when (val result = gateway.pairedDevices()) {
            is BluetoothGatewayResult.Failure -> gatewayError(action, result)
            is BluetoothGatewayResult.Success -> ok(action, "Paired Bluetooth devices loaded.") {
                put("count", result.value.size)
                put("devices", buildJsonArray { result.value.forEach { add(it.toJson()) } })
            }
        }
    }

    private suspend fun scan(action: String, args: Args): ToolResult {
        permission(
            action,
            setOf(BluetoothPermissionScope.SCAN, BluetoothPermissionScope.CONNECT)
        )?.let { return it }
        ensureEnabled(action)?.let { return it }
        val durationSec = (args.seconds ?: DEFAULT_SCAN_SECONDS).coerceIn(1, MAX_SCAN_SECONDS)
        val maxResults = (args.maxResults ?: DEFAULT_SCAN_RESULTS).coerceIn(1, MAX_SCAN_RESULTS)
        return when (val result = gateway.scan(durationSec * 1_000L, maxResults)) {
            is BluetoothGatewayResult.Failure -> gatewayError(action, result)
            is BluetoothGatewayResult.Success -> ok(action, "BLE scan completed.") {
                put("duration_sec", durationSec)
                put("count", result.value.size)
                put("devices", buildJsonArray { result.value.forEach { add(it.toJson()) } })
            }
        }
    }

    private suspend fun connect(action: String, args: Args): ToolResult {
        permission(action, setOf(BluetoothPermissionScope.CONNECT))?.let { return it }
        ensureEnabled(action)?.let { return it }
        val address = normalizeAddress(args.address) ?: return error(
            action,
            "invalid_address",
            "ble_connect requires a valid Bluetooth MAC address.",
            "Use an address returned by ble_scan or paired_list."
        )
        val timeoutMs = (args.timeoutSec ?: DEFAULT_CONNECT_TIMEOUT_SECONDS)
            .coerceIn(MIN_CONNECT_TIMEOUT_SECONDS, MAX_CONNECT_TIMEOUT_SECONDS) * 1_000L
        val first = gateway.connect(address, timeoutMs)
        if (first is BluetoothGatewayResult.Success) {
            return connectionSuccess(action, first.value, retriedWithSettings = false)
        }
        val firstFailure = first as BluetoothGatewayResult.Failure
        if (firstFailure.code in NON_RECOVERABLE_CONNECT_ERRORS) {
            return gatewayError(action, firstFailure)
        }
        if (userInteraction.openBluetoothSettings() != true) {
            return gatewayError(action, firstFailure)
        }
        val confirmed = userInteraction.confirm(
            title = "Bluetooth Setup",
            message = "Pair or prepare $address in system Bluetooth settings, then return.",
            confirmLabel = "Continue",
            cancelLabel = "Cancel"
        )
        if (confirmed != true) {
            return error(
                action,
                if (confirmed == false) "user_cancelled" else "confirmation_unavailable",
                if (confirmed == false) {
                    "Bluetooth setup was cancelled."
                } else {
                    "Bluetooth setup confirmation is unavailable."
                },
                "Complete setup in system Bluetooth settings and retry."
            )
        }
        val second = gateway.connect(address, timeoutMs)
        return when (second) {
            is BluetoothGatewayResult.Success ->
                connectionSuccess(action, second.value, retriedWithSettings = true)
            is BluetoothGatewayResult.Failure -> error(
                action,
                "connection_not_verified",
                "System setup completed, but the BLE GATT connection was not verified.",
                second.nextStep ?: "Check that the device is nearby and accepts BLE connections.",
                second.gattStatus
            ) {
                put("setup_completed", true)
                put("connected", false)
                put("connection_error", second.code)
            }
        }
    }

    private suspend fun inspect(action: String): ToolResult {
        permission(action, setOf(BluetoothPermissionScope.CONNECT))?.let { return it }
        return when (val result = gateway.inspect()) {
            is BluetoothGatewayResult.Failure -> gatewayError(action, result)
            is BluetoothGatewayResult.Success -> ok(action, "BLE GATT profile loaded.") {
                put("services", buildJsonArray { result.value.services.forEach { add(it.toJson()) } })
                put("service_count", result.value.totalServices)
                put("characteristic_count", result.value.totalCharacteristics)
                put("truncated", result.value.truncated)
            }
        }
    }

    private suspend fun read(action: String, args: Args): ToolResult {
        permission(action, setOf(BluetoothPermissionScope.CONNECT))?.let { return it }
        val identifiers = parseGattIdentifiers(args) ?: return invalidUuidError(action)
        val timeoutMs = operationTimeoutMs(args)
        return when (
            val result = gateway.read(
                identifiers.first,
                identifiers.second,
                timeoutMs
            )
        ) {
            is BluetoothGatewayResult.Failure -> gatewayError(action, result)
            is BluetoothGatewayResult.Success -> {
                val encoded = BluetoothValueCodec.encode(result.value)
                ok(action, "BLE characteristic read.") {
                    put("service_uuid", identifiers.first)
                    put("characteristic_uuid", identifiers.second)
                    put("size_bytes", result.value.size)
                    put("value_hex", encoded.hex)
                    encoded.utf8?.let { put("value_utf8", it) }
                }
            }
        }
    }

    private suspend fun write(action: String, args: Args): ToolResult {
        permission(action, setOf(BluetoothPermissionScope.CONNECT))?.let { return it }
        val identifiers = parseGattIdentifiers(args) ?: return invalidUuidError(action)
        val rawValue = args.value ?: return error(
            action,
            "invalid_arguments",
            "ble_write requires value.",
            "Provide the device command as hex or UTF-8 text."
        )
        val encoding = BleValueEncoding.parse(args.encoding) ?: return error(
            action,
            "invalid_arguments",
            "ble_write encoding must be hex or utf8.",
            "Choose one supported value encoding."
        )
        val decoded = BluetoothValueCodec.decode(rawValue, encoding)
        if (decoded is BleValueDecodeResult.Failure) {
            return error(action, decoded.code, decoded.message, decoded.nextStep)
        }
        val bytes = (decoded as BleValueDecodeResult.Success).bytes
        val writeType = BleWriteType.parse(args.writeType) ?: return error(
            action,
            "invalid_arguments",
            "write_type must be auto, with_response, or without_response.",
            "Choose one supported write type."
        )
        val preview = BluetoothValueCodec.encode(bytes).hex.take(WRITE_PREVIEW_HEX_CHARS)
        val confirmed = userInteraction.confirm(
            title = "Write to BLE device?",
            message = buildString {
                append("Service: ${identifiers.first}\n")
                append("Characteristic: ${identifiers.second}\n")
                append("Value ($encoding, ${bytes.size} bytes): $preview")
                if (preview.length < bytes.size * 2) append("…")
            },
            confirmLabel = "Write",
            cancelLabel = "Cancel"
        )
        if (confirmed != true) {
            return error(
                action,
                if (confirmed == false) "user_cancelled" else "confirmation_unavailable",
                if (confirmed == false) "BLE write was cancelled." else "BLE write confirmation is unavailable.",
                "No value was written."
            )
        }
        return when (
            val result = gateway.write(
                identifiers.first,
                identifiers.second,
                bytes,
                writeType,
                operationTimeoutMs(args)
            )
        ) {
            is BluetoothGatewayResult.Failure -> gatewayError(action, result)
            is BluetoothGatewayResult.Success -> ok(action, "BLE characteristic written.") {
                put("service_uuid", identifiers.first)
                put("characteristic_uuid", identifiers.second)
                put("bytes_written", result.value.bytesWritten)
                put("write_type", result.value.writeType.wireName)
                put("device_acknowledged", result.value.deviceAcknowledged)
                put("value_hex", BluetoothValueCodec.encode(bytes).hex)
            }
        }
    }

    private suspend fun disconnect(action: String): ToolResult {
        permission(action, setOf(BluetoothPermissionScope.CONNECT))?.let { return it }
        return when (val result = gateway.disconnect()) {
            is BluetoothGatewayResult.Failure -> gatewayError(action, result)
            is BluetoothGatewayResult.Success -> ok(action, "BLE connection closed.") {
                put("disconnected", result.value)
            }
        }
    }

    private suspend fun ensureEnabled(action: String): ToolResult? {
        val enabled = verifiedEnabled() ?: return error(
            action,
            "bluetooth_unavailable",
            "Bluetooth is unavailable.",
            "Check whether this device supports Bluetooth."
        )
        if (enabled) return null
        if (userInteraction.requestEnableBluetooth() == true && verifiedEnabled() == true) return null
        if (userInteraction.openBluetoothSettings() != true) {
            return error(
                action,
                "bluetooth_disabled",
                "Bluetooth is disabled and the system settings flow is unavailable.",
                "Enable Bluetooth manually and retry."
            )
        }
        val confirmed = userInteraction.confirm(
            title = "Enable Bluetooth",
            message = "Enable Bluetooth in system settings, then return.",
            confirmLabel = "Continue",
            cancelLabel = "Cancel"
        )
        if (confirmed != true) {
            return error(
                action,
                if (confirmed == false) "user_cancelled" else "confirmation_unavailable",
                if (confirmed == false) "Bluetooth enable was cancelled." else "Bluetooth confirmation is unavailable.",
                "Enable Bluetooth and retry."
            )
        }
        return if (verifiedEnabled() == true) {
            null
        } else {
            error(
                action,
                "bluetooth_disabled",
                "Bluetooth is still disabled after the system flow.",
                "Enable Bluetooth and retry."
            )
        }
    }

    private fun verifiedEnabled(): Boolean? =
        when (val status = gateway.status()) {
            is BluetoothGatewayResult.Success -> status.value.enabled.takeIf { status.value.available }
            is BluetoothGatewayResult.Failure -> null
        }

    private suspend fun permission(
        action: String,
        scopes: Set<BluetoothPermissionScope>
    ): ToolResult? =
        when (val result = userInteraction.ensurePermissions(action, scopes)) {
            is BluetoothGatewayResult.Success -> null
            is BluetoothGatewayResult.Failure -> gatewayError(action, result)
        }

    private fun parseGattIdentifiers(args: Args): Pair<String, String>? {
        val service = normalizeUuid(args.serviceUuid) ?: return null
        val characteristic = normalizeUuid(args.characteristicUuid) ?: return null
        return service to characteristic
    }

    private fun invalidUuidError(action: String): ToolResult =
        error(
            action,
            "invalid_uuid",
            "$action requires valid service_uuid and characteristic_uuid values.",
            "Use UUIDs returned by ble_inspect."
        )

    private fun normalizeUuid(raw: String?): String? =
        raw?.trim()?.takeIf(String::isNotBlank)?.let { value ->
            runCatching { UUID.fromString(value).toString().lowercase(Locale.US) }.getOrNull()
        }

    private fun normalizeAddress(raw: String?): String? {
        val normalized = raw?.trim()?.uppercase(Locale.US).orEmpty()
        return normalized.takeIf { BLUETOOTH_ADDRESS.matches(it) }
    }

    private fun operationTimeoutMs(args: Args): Long =
        (args.timeoutSec ?: DEFAULT_OPERATION_TIMEOUT_SECONDS)
            .coerceIn(MIN_OPERATION_TIMEOUT_SECONDS, MAX_OPERATION_TIMEOUT_SECONDS) * 1_000L

    private fun connectionSuccess(
        action: String,
        connection: BleConnectionInfo,
        retriedWithSettings: Boolean
    ): ToolResult = ok(action, "BLE GATT connection verified.") {
        put("connected", true)
        put("address", connection.address)
        connection.name?.let { put("name", it) }
        put("services_count", connection.servicesCount)
        put("mtu", connection.mtu)
        put("retried_with_settings", retriedWithSettings)
    }

    private fun gatewayError(
        action: String,
        failure: BluetoothGatewayResult.Failure
    ): ToolResult =
        error(
            action,
            failure.code,
            failure.message,
            failure.nextStep,
            failure.gattStatus
        )

    private fun ok(
        action: String,
        message: String,
        details: JsonObjectBuilder.() -> Unit = {}
    ): ToolResult {
        val body = buildJsonObject {
            put("status", "ok")
            put("tool", name)
            put("action", action)
            put("message", message)
            details()
        }
        return ToolResult(
            toolCallId = "",
            content = body.toString(),
            isError = false,
            metadata = body
        )
    }

    private fun error(
        action: String,
        code: String,
        message: String,
        nextStep: String? = null,
        gattStatus: Int? = null,
        details: JsonObjectBuilder.() -> Unit = {}
    ): ToolResult {
        val body = buildJsonObject {
            put("status", "error")
            put("tool", name)
            put("action", action)
            put("code", code)
            put("message", message)
            nextStep?.let { put("next_step", it) }
            gattStatus?.let { put("gatt_status", it) }
            details()
        }
        return ToolResult(
            toolCallId = "",
            content = body.toString(),
            isError = true,
            metadata = JsonObject(body + mapOf("error" to JsonPrimitive(code)))
        )
    }

    private fun JsonObjectBuilder.putStatus(status: BluetoothStatus) {
        put("available", status.available)
        put("enabled", status.enabled)
        put("paired_count", status.pairedCount)
        status.activeConnection?.let { connection ->
            put(
                "active_connection",
                buildJsonObject {
                    put("address", connection.address)
                    connection.name?.let { put("name", it) }
                    put("services_count", connection.servicesCount)
                    put("mtu", connection.mtu)
                    put("connected", connection.connected)
                }
            )
        }
    }

    private fun BluetoothDeviceInfo.toJson(): JsonObject = buildJsonObject {
        put("address", address)
        name?.let { put("name", it) }
        rssi?.let { put("rssi", it) }
        type?.let { put("type", it) }
        put("bonded", bonded)
    }

    private fun BleServiceInfo.toJson(): JsonObject = buildJsonObject {
        put("uuid", uuid)
        put("primary", primary)
        put(
            "characteristics",
            buildJsonArray {
                characteristics.forEach { characteristic ->
                    add(buildJsonObject {
                        put("uuid", characteristic.uuid)
                        put(
                            "properties",
                            buildJsonArray {
                                characteristic.properties
                                    .sortedBy(BleCharacteristicProperty::wireName)
                                    .forEach { add(it.wireName) }
                            }
                        )
                    })
                }
            }
        )
    }

    @Serializable
    private data class Args(
        val action: String,
        val enabled: Boolean? = null,
        val seconds: Int? = null,
        @SerialName("max_results")
        val maxResults: Int? = null,
        val address: String? = null,
        @SerialName("timeout_sec")
        val timeoutSec: Int? = null,
        @SerialName("service_uuid")
        val serviceUuid: String? = null,
        @SerialName("characteristic_uuid")
        val characteristicUuid: String? = null,
        val value: String? = null,
        val encoding: String? = null,
        @SerialName("write_type")
        val writeType: String? = null
    )

    private companion object {
        val BLUETOOTH_ADDRESS = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")
        val NON_RECOVERABLE_CONNECT_ERRORS = setOf(
            "active_connection_exists",
            "invalid_address",
            "bluetooth_unavailable",
            "permission_required"
        )
        const val DEFAULT_SCAN_SECONDS = 5
        const val MAX_SCAN_SECONDS = 20
        const val DEFAULT_SCAN_RESULTS = 20
        const val MAX_SCAN_RESULTS = 50
        const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 20
        const val MIN_CONNECT_TIMEOUT_SECONDS = 3
        const val MAX_CONNECT_TIMEOUT_SECONDS = 60
        const val DEFAULT_OPERATION_TIMEOUT_SECONDS = 15
        const val MIN_OPERATION_TIMEOUT_SECONDS = 3
        const val MAX_OPERATION_TIMEOUT_SECONDS = 60
        const val WRITE_PREVIEW_HEX_CHARS = 64
    }
}

internal fun bluetoothToolSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    put("required", buildJsonArray { add("action") })
    put(
        "properties",
        BLUETOOTH_JSON.parseToJsonElement(
            """
            {
              "action":{"type":"string","enum":["status","set_power","open_settings","paired_list","ble_scan","ble_connect","ble_inspect","ble_read","ble_write","ble_disconnect"]},
              "enabled":{"type":"boolean"},
              "seconds":{"type":"integer","minimum":1,"maximum":20},
              "max_results":{"type":"integer","minimum":1,"maximum":50},
              "address":{"type":"string","pattern":"^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$"},
              "timeout_sec":{"type":"integer","minimum":3,"maximum":60},
              "service_uuid":{"type":"string"},
              "characteristic_uuid":{"type":"string"},
              "value":{"type":"string","maxLength":1024},
              "encoding":{"type":"string","enum":["hex","utf8"]},
              "write_type":{"type":"string","enum":["auto","with_response","without_response"]}
            }
            """.trimIndent()
        )
    )
}

private val BLUETOOTH_JSON = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
}
