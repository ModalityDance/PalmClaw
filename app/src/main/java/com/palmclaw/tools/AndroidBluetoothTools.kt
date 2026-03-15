package com.palmclaw.tools

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

fun createAndroidBluetoothToolSet(context: Context): List<Tool> {
    return listOf(
        BluetoothControlTool(
            context = context,
            state = BluetoothConnectionState()
        )
    )
}

private class BluetoothControlTool(
    private val context: Context,
    private val state: BluetoothConnectionState
) : Tool, TimedTool {
    override val name: String = "bluetooth"
    override val description: String =
        "Unified Bluetooth tool. action=status|set_power|open_settings|paired_list|ble_scan|ble_connect|ble_disconnect"
    override val timeoutMs: Long = 300_000L
    override val jsonSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("required", Json.parseToJsonElement("[\"action\"]"))
        put(
            "properties",
            Json.parseToJsonElement(
                """
                {
                  "action":{"type":"string","enum":["status","set_power","open_settings","paired_list","ble_scan","ble_connect","ble_disconnect"]},
                  "enabled":{"type":"boolean"},
                  "seconds":{"type":"integer","minimum":1,"maximum":20},
                  "max_results":{"type":"integer","minimum":1,"maximum":50},
                  "address":{"type":"string"},
                  "timeout_sec":{"type":"integer","minimum":3,"maximum":60},
                  "discover_services":{"type":"boolean"},
                  "auto_reconnect":{"type":"boolean"},
                  "all":{"type":"boolean"},
                  "open_settings_if_failed":{"type":"boolean"},
                  "wait_user_confirmation":{"type":"boolean"},
                  "allow_manual_success":{"type":"boolean"},
                  "open_settings_if_needed":{"type":"boolean"}
                }
                """.trimIndent()
            )
        )
    }

    override suspend fun run(argumentsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val args = Json.decodeFromString<Args>(argumentsJson)
        return@withContext when (args.action.trim().lowercase(Locale.US)) {
            "status" -> actionStatus()
            "set_power" -> actionSetPower(args)
            "open_settings" -> actionOpenSettings()
            "paired_list" -> actionPairedList(args)
            "ble_scan" -> actionBleScan(args)
            "ble_connect" -> actionBleConnect(args)
            "ble_disconnect" -> actionBleDisconnect(args)
            else -> ToolResult(
                toolCallId = "",
                content = "bluetooth failed: unsupported action '${args.action}'",
                isError = true
            )
        }
    }

    private suspend fun actionStatus(): ToolResult {
        val adapter = getAdapterOrNull()
            ?: return ToolResult(
                toolCallId = "",
                content = "bluetooth failed: bluetooth adapter unavailable",
                isError = true
            )
        val pairedCount = runCatching { adapter.bondedDevices?.size ?: 0 }.getOrDefault(0)
        val active = state.activeAddresses()
        return ToolResult(
            toolCallId = "",
            content = buildString {
                appendLine("bluetooth_enabled=${adapter.isEnabled}")
                appendLine("paired_devices=$pairedCount")
                appendLine("active_ble_connections=${active.size}")
                if (active.isNotEmpty()) {
                    appendLine("active_addresses=${active.joinToString(",")}")
                }
            }.trimEnd(),
            isError = false,
            metadata = buildJsonObject {
                put("enabled", adapter.isEnabled)
                put("paired_count", pairedCount)
                put("active_ble_connections", active.size)
            }
        )
    }

    private suspend fun actionSetPower(args: Args): ToolResult {
        val targetEnabled = args.enabled
            ?: return ToolResult(
                toolCallId = "",
                content = "bluetooth failed: action=set_power requires 'enabled' boolean",
                isError = true
            )
        val permissionsError = ensurePermissions(
            requiredPermissions = requiredConnectPermissions(),
            action = "set_power",
            openSettingsIfFailed = args.openSettingsIfFailed ?: true,
            waitUserConfirmation = args.waitUserConfirmation ?: true
        )
        if (permissionsError != null) return permissionsError
        val adapter = getAdapterOrNull()
            ?: return ToolResult(
                toolCallId = "",
                content = "bluetooth failed: bluetooth adapter unavailable",
                isError = true
            )

        if (targetEnabled) {
            val enableError = ensureBluetoothEnabled(
                action = "set_power",
                adapter = adapter,
                openSettingsIfNeeded = args.openSettingsIfNeeded ?: true,
                waitUserConfirmation = args.waitUserConfirmation ?: true
            )
            if (enableError != null) return enableError
            return ToolResult(toolCallId = "", content = "Bluetooth is enabled.", isError = false)
        }

        if (!adapter.isEnabled) {
            return ToolResult(toolCallId = "", content = "Bluetooth is already disabled.", isError = false)
        }

        val directDisabled = runCatching { adapter.disable() }.getOrDefault(false)
        if (directDisabled) {
            delay(500)
            if (!adapter.isEnabled) {
                state.disconnectAll()
                return ToolResult(toolCallId = "", content = "Bluetooth disabled.", isError = false)
            }
        }

        val openSettingsIfNeeded = args.openSettingsIfNeeded ?: true
        if (!openSettingsIfNeeded) {
            return ToolResult(
                toolCallId = "",
                content = "bluetooth failed: cannot disable directly; set open_settings_if_needed=true for manual flow",
                isError = true
            )
        }

        val opened = AndroidUserActionBridge.openBluetoothSettings()
        if (opened != true) {
            return ToolResult(
                toolCallId = "",
                content = "bluetooth failed: could not open Bluetooth settings for manual disable",
                isError = true
            )
        }

        val waitUserConfirm = args.waitUserConfirmation ?: true
        if (waitUserConfirm) {
            val confirmed = AndroidUserActionBridge.requestUserConfirmation(
                title = "Bluetooth Power",
                message = "Please turn Bluetooth OFF in system settings, then return and tap Continue.",
                confirmLabel = "Continue",
                cancelLabel = "Cancel"
            )
            if (confirmed != true) {
                return ToolResult(
                    toolCallId = "",
                    content = "bluetooth cancelled by user during manual disable flow",
                    isError = true
                )
            }
        }

        if (adapter.isEnabled) {
            return ToolResult(
                toolCallId = "",
                content = "bluetooth failed: bluetooth is still enabled after manual flow",
                isError = true
            )
        }

        state.disconnectAll()
        return ToolResult(toolCallId = "", content = "Bluetooth disabled (manual flow).", isError = false)
    }

    private suspend fun actionOpenSettings(): ToolResult {
        return when (AndroidUserActionBridge.openBluetoothSettings()) {
            true -> ToolResult(toolCallId = "", content = "Bluetooth settings opened.", isError = false)
            false -> ToolResult(
                toolCallId = "",
                content = "bluetooth failed: could not open Bluetooth settings",
                isError = true
            )

            null -> ToolResult(
                toolCallId = "",
                content = "bluetooth failed: UI bridge unavailable for opening Bluetooth settings",
                isError = true
            )
        }
    }

    private suspend fun actionPairedList(args: Args): ToolResult {
        val permissionsError = ensurePermissions(
            requiredPermissions = requiredConnectPermissions(),
            action = "paired_list",
            openSettingsIfFailed = args.openSettingsIfFailed ?: true,
            waitUserConfirmation = args.waitUserConfirmation ?: true
        )
        if (permissionsError != null) return permissionsError
        val adapter = getAdapterOrNull()
            ?: return ToolResult(
                toolCallId = "",
                content = "bluetooth failed: bluetooth adapter unavailable",
                isError = true
            )
        val devices = adapter.bondedDevices
            .orEmpty()
            .sortedWith(
                compareBy<BluetoothDevice> { it.name.orEmpty().lowercase(Locale.US) }
                    .thenBy { it.address.orEmpty() }
            )
        if (devices.isEmpty()) {
            return ToolResult(
                toolCallId = "",
                content = "No paired Bluetooth devices found.",
                isError = false,
                metadata = buildJsonObject { put("count", 0) }
            )
        }
        val lines = devices.mapIndexed { index, d ->
            val type = when (d.type) {
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
                BluetoothDevice.DEVICE_TYPE_LE -> "ble"
                BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
                else -> "unknown"
            }
            "${index + 1}. name=${d.name.orEmpty().ifBlank { "(unknown)" }}, address=${d.address}, type=$type"
        }
        return ToolResult(
            toolCallId = "",
            content = lines.joinToString("\n"),
            isError = false,
            metadata = buildJsonObject { put("count", lines.size) }
        )
    }

    private suspend fun actionBleScan(args: Args): ToolResult {
        val permissionsError = ensurePermissions(
            requiredPermissions = requiredScanPermissions(),
            action = "ble_scan",
            openSettingsIfFailed = args.openSettingsIfFailed ?: true,
            waitUserConfirmation = args.waitUserConfirmation ?: true
        )
        if (permissionsError != null) return permissionsError
        val adapter = getAdapterOrNull()
            ?: return ToolResult(
                toolCallId = "",
                content = "bluetooth failed: bluetooth adapter unavailable",
                isError = true
            )
        val enableError = ensureBluetoothEnabled(
            action = "ble_scan",
            adapter = adapter,
            openSettingsIfNeeded = args.openSettingsIfFailed ?: true,
            waitUserConfirmation = args.waitUserConfirmation ?: true
        )
        if (enableError != null) return enableError
        val scanner = adapter.bluetoothLeScanner ?: return ToolResult(
            toolCallId = "",
            content = "bluetooth failed: BLE scanner unavailable",
            isError = true
        )

        val timeoutSec = (args.seconds ?: 5).coerceIn(1, 20)
        val maxResults = (args.maxResults ?: 20).coerceIn(1, 50)
        val found = ConcurrentHashMap<String, DeviceHit>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                if (result == null) return
                val address = result.device?.address.orEmpty()
                if (address.isBlank()) return
                val name = result.device?.name ?: result.scanRecord?.deviceName ?: ""
                found[address] = DeviceHit(address = address, name = name, rssi = result.rssi)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results.orEmpty().forEach { onScanResult(0, it) }
            }
        }

        return runCatching {
            scanner.startScan(callback)
            delay(timeoutSec * 1000L)
            scanner.stopScan(callback)

            val lines = found.values
                .sortedByDescending { it.rssi }
                .take(maxResults)
                .mapIndexed { index, d ->
                    "${index + 1}. name=${d.name.ifBlank { "(unknown)" }}, address=${d.address}, rssi=${d.rssi}"
                }
            ToolResult(
                toolCallId = "",
                content = if (lines.isEmpty()) "No BLE devices found." else lines.joinToString("\n"),
                isError = false,
                metadata = buildJsonObject {
                    put("count", lines.size)
                    put("duration_sec", timeoutSec)
                }
            )
        }.getOrElse { t ->
            runCatching { scanner.stopScan(callback) }
            ToolResult(
                toolCallId = "",
                content = "bluetooth failed: ble_scan error: ${t.message ?: t.javaClass.simpleName}",
                isError = true
            )
        }
    }

    private suspend fun actionBleConnect(args: Args): ToolResult {
        val address = args.address?.trim().orEmpty()
        if (address.isBlank()) {
            return ToolResult(
                toolCallId = "",
                content = "bluetooth failed: action=ble_connect requires 'address'",
                isError = true
            )
        }
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            return ToolResult(
                toolCallId = "",
                content = "bluetooth failed: invalid bluetooth address '$address'",
                isError = true
            )
        }

        val permissionsError = ensurePermissions(
            requiredPermissions = requiredConnectPermissions(),
            action = "ble_connect",
            openSettingsIfFailed = args.openSettingsIfFailed ?: true,
            waitUserConfirmation = args.waitUserConfirmation ?: true
        )
        if (permissionsError != null) return permissionsError
        val adapter = getAdapterOrNull()
            ?: return ToolResult(
                toolCallId = "",
                content = "bluetooth failed: bluetooth adapter unavailable",
                isError = true
            )
        val enableError = ensureBluetoothEnabled(
            action = "ble_connect",
            adapter = adapter,
            openSettingsIfNeeded = args.openSettingsIfFailed ?: true,
            waitUserConfirmation = args.waitUserConfirmation ?: true
        )
        if (enableError != null) return enableError

        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
            ?: return ToolResult(
                toolCallId = "",
                content = "bluetooth failed: unable to get device for address '$address'",
                isError = true
            )

        val timeoutSec = (args.timeoutSec ?: 20).coerceIn(3, 60)
        val discoverServices = args.discoverServices ?: true
        val autoReconnect = args.autoReconnect ?: false
        val openSettingsIfFailed = args.openSettingsIfFailed ?: true
        val waitUserConfirm = args.waitUserConfirmation ?: true
        val allowManualSuccess = args.allowManualSuccess ?: true

        val first = attemptBleConnect(
            context = context,
            device = device,
            timeoutMs = timeoutSec * 1000L,
            discoverServices = discoverServices,
            autoReconnect = autoReconnect
        )
        if (first is BleConnectAttempt.Connected) {
            state.put(device.address, first.gatt)
            return ToolResult(
                toolCallId = "",
                content = "BLE connected: ${device.address}, services=${first.servicesCount}",
                isError = false,
                metadata = buildJsonObject {
                    put("address", device.address)
                    put("services_count", first.servicesCount)
                    put("retried_with_settings", false)
                }
            )
        }

        if (!openSettingsIfFailed) {
            return ToolResult(
                toolCallId = "",
                content = "bluetooth failed: ble_connect: ${(first as BleConnectAttempt.Failed).reason}",
                isError = true
            )
        }

        when (AndroidUserActionBridge.openBluetoothSettings()) {
            true -> Unit
            false -> {
                return ToolResult(
                    toolCallId = "",
                    content = "bluetooth failed: could not open Bluetooth settings",
                    isError = true
                )
            }

            null -> {
                return ToolResult(
                    toolCallId = "",
                    content = "bluetooth failed: UI bridge unavailable for opening Bluetooth settings",
                    isError = true
                )
            }
        }

        if (waitUserConfirm) {
            when (AndroidUserActionBridge.requestUserConfirmation(
                title = "Bluetooth Setup",
                message = "Please pair/connect device $address in system Bluetooth settings, then return and tap Continue.",
                confirmLabel = "Continue",
                cancelLabel = "Cancel"
            )) {
                true -> Unit
                false -> {
                    return ToolResult(
                        toolCallId = "",
                        content = "bluetooth failed: user cancelled manual bluetooth setup",
                        isError = true
                    )
                }

                null -> {
                    return ToolResult(
                        toolCallId = "",
                        content = "bluetooth failed: confirmation UI unavailable",
                        isError = true
                    )
                }
            }
        }

        val second = attemptBleConnect(
            context = context,
            device = device,
            timeoutMs = timeoutSec * 1000L,
            discoverServices = discoverServices,
            autoReconnect = autoReconnect
        )
        if (second is BleConnectAttempt.Connected) {
            state.put(device.address, second.gatt)
            return ToolResult(
                toolCallId = "",
                content = "BLE connected after manual setup: ${device.address}, services=${second.servicesCount}",
                isError = false,
                metadata = buildJsonObject {
                    put("address", device.address)
                    put("services_count", second.servicesCount)
                    put("retried_with_settings", true)
                }
            )
        }

        if (allowManualSuccess && waitUserConfirm) {
            return ToolResult(
                toolCallId = "",
                content = "Manual Bluetooth setup confirmed by user. BLE connect validation failed, but proceeding. reason=${(second as BleConnectAttempt.Failed).reason}",
                isError = false,
                metadata = buildJsonObject {
                    put("address", device.address)
                    put("manual_confirmed", true)
                    put("ble_connected", false)
                }
            )
        }

        return ToolResult(
            toolCallId = "",
            content = "bluetooth failed: ble_connect still failed after manual setup: ${(second as BleConnectAttempt.Failed).reason}",
            isError = true
        )
    }

    private suspend fun actionBleDisconnect(args: Args): ToolResult {
        if (args.all == true) {
            val count = state.disconnectAll()
            return ToolResult(
                toolCallId = "",
                content = "Disconnected $count BLE connection(s).",
                isError = false,
                metadata = buildJsonObject { put("count", count) }
            )
        }

        val address = args.address?.trim().orEmpty()
        if (address.isBlank()) {
            return ToolResult(
                toolCallId = "",
                content = "bluetooth failed: action=ble_disconnect requires address or all=true",
                isError = true
            )
        }

        val disconnected = state.disconnect(address)
        if (!disconnected) {
            return ToolResult(
                toolCallId = "",
                content = "No active BLE connection found for $address.",
                isError = false
            )
        }

        return ToolResult(
            toolCallId = "",
            content = "BLE disconnected: $address",
            isError = false
        )
    }

    private suspend fun ensurePermissions(
        requiredPermissions: List<String>,
        action: String,
        openSettingsIfFailed: Boolean,
        waitUserConfirmation: Boolean
    ): ToolResult? {
        var missing = missingPermissions(context, requiredPermissions)
        if (missing.isEmpty()) return null
        when (AndroidUserActionBridge.requestPermissions(missing)) {
            true -> {
                missing = missingPermissions(context, requiredPermissions)
                if (missing.isEmpty()) return null
                if (!openSettingsIfFailed) {
                    return ToolResult(
                        toolCallId = "",
                        content = "$name/$action failed: missing permissions: ${missing.joinToString(", ")}. Next: grant permissions and retry.",
                        isError = true
                    )
                }
            }

            false -> {
                if (!openSettingsIfFailed) {
                    return ToolResult(
                        toolCallId = "",
                        content = "$name failed: user denied permissions for action=$action: ${missing.joinToString(", ")}. Next: grant permissions and retry.",
                        isError = true
                    )
                }
            }

            null -> {
                if (!openSettingsIfFailed) {
                    return ToolResult(
                        toolCallId = "",
                        content = "$name failed: missing permissions for action=$action (${missing.joinToString(", ")}), but UI permission prompt unavailable. Next: grant permissions in app settings and retry.",
                        isError = true
                    )
                }
            }
        }

        val openResult = openAppSettings()
        if (openResult.isError) {
            return ToolResult(
                toolCallId = "",
                content = "$name/$action failed: ${openResult.content}. Next: open app settings manually, grant permissions, then retry.",
                isError = true
            )
        }

        if (waitUserConfirmation) {
            when (AndroidUserActionBridge.requestUserConfirmation(
                title = "Permission Required",
                message = "Grant required Bluetooth permission(s) in app settings, then return and tap Continue.",
                confirmLabel = "Continue",
                cancelLabel = "Cancel"
            )) {
                true -> Unit
                false -> {
                    return ToolResult(
                        toolCallId = "",
                        content = "$name/$action failed: user cancelled permission flow. Next: grant permissions and retry.",
                        isError = true
                    )
                }

                null -> {
                    return ToolResult(
                        toolCallId = "",
                        content = "$name/$action failed: confirmation UI unavailable. Next: grant permissions manually, then retry.",
                        isError = true
                    )
                }
            }
        }

        missing = missingPermissions(context, requiredPermissions)
        if (missing.isNotEmpty()) {
            return ToolResult(
                toolCallId = "",
                content = "$name/$action failed: permissions still missing: ${missing.joinToString(", ")}. Next: grant in app settings, then retry.",
                isError = true
            )
        }
        return null
    }

    private fun openAppSettings(): ToolResult {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        return launchIntent(context, intent)
    }

    private fun getAdapterOrNull(): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return manager.adapter
    }

    private suspend fun ensureBluetoothEnabled(
        action: String,
        adapter: BluetoothAdapter,
        openSettingsIfNeeded: Boolean = true,
        waitUserConfirmation: Boolean = true
    ): ToolResult? {
        if (adapter.isEnabled) return null
        val enableDialogResult = AndroidUserActionBridge.requestEnableBluetooth()
        if (enableDialogResult == true) {
            delay(300)
            if (adapter.isEnabled) return null
        }

        if (!openSettingsIfNeeded) {
            return ToolResult(
                toolCallId = "",
                content = "$name failed: bluetooth is disabled for action=$action and enable flow did not complete. Next: set open_settings_if_failed=true/open_settings_if_needed=true to continue with manual settings flow.",
                isError = true
            )
        }

        val opened = AndroidUserActionBridge.openBluetoothSettings()
        if (opened != true) {
            return ToolResult(
                toolCallId = "",
                content = "$name failed: bluetooth enable dialog did not complete (action=$action), and opening Bluetooth settings failed.",
                isError = true
            )
        }

        if (waitUserConfirmation) {
            val confirmed = AndroidUserActionBridge.requestUserConfirmation(
                title = "Bluetooth Setup",
                message = "Please enable Bluetooth in system settings, then return and tap Continue.",
                confirmLabel = "Continue",
                cancelLabel = "Cancel"
            )
            if (confirmed != true) {
                return ToolResult(
                    toolCallId = "",
                    content = "$name failed: user cancelled manual bluetooth enable flow (action=$action)",
                    isError = true
                )
            }
        }

        delay(300)
        return if (adapter.isEnabled) {
            null
        } else {
            ToolResult(
                toolCallId = "",
                content = "$name failed: bluetooth is still disabled after manual settings flow (action=$action)",
                isError = true
            )
        }
    }

    @Serializable
    private data class Args(
        val action: String,
        val enabled: Boolean? = null,
        val seconds: Int? = null,
        val max_results: Int? = null,
        val address: String? = null,
        val timeout_sec: Int? = null,
        val discover_services: Boolean? = null,
        val auto_reconnect: Boolean? = null,
        val all: Boolean? = null,
        val open_settings_if_failed: Boolean? = null,
        val wait_user_confirmation: Boolean? = null,
        val allow_manual_success: Boolean? = null,
        val open_settings_if_needed: Boolean? = null
    ) {
        val maxResults: Int? get() = max_results
        val timeoutSec: Int? get() = timeout_sec
        val discoverServices: Boolean? get() = discover_services
        val autoReconnect: Boolean? get() = auto_reconnect
        val openSettingsIfFailed: Boolean? get() = open_settings_if_failed
        val waitUserConfirmation: Boolean? get() = wait_user_confirmation
        val allowManualSuccess: Boolean? get() = allow_manual_success
        val openSettingsIfNeeded: Boolean? get() = open_settings_if_needed
    }

    private data class DeviceHit(
        val address: String,
        val name: String,
        val rssi: Int
    )
}

private suspend fun attemptBleConnect(
    context: Context,
    device: BluetoothDevice,
    timeoutMs: Long,
    discoverServices: Boolean,
    autoReconnect: Boolean
): BleConnectAttempt {
    var gattRef: BluetoothGatt? = null
    val outcome = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<BleConnectAttempt> { cont ->
            val completed = AtomicBoolean(false)
            fun finish(result: BleConnectAttempt) {
                if (completed.compareAndSet(false, true) && cont.isActive) {
                    cont.resume(result)
                }
            }

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        safelyCloseGatt(gatt)
                        finish(BleConnectAttempt.Failed("gatt status=$status"))
                        return
                    }
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            if (!discoverServices) {
                                finish(BleConnectAttempt.Connected(gatt, 0))
                            } else {
                                val started = runCatching { gatt.discoverServices() }.getOrDefault(false)
                                if (!started) {
                                    safelyCloseGatt(gatt)
                                    finish(BleConnectAttempt.Failed("discoverServices() returned false"))
                                }
                            }
                        }

                        BluetoothProfile.STATE_DISCONNECTED -> {
                            safelyCloseGatt(gatt)
                            finish(BleConnectAttempt.Failed("disconnected"))
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        safelyCloseGatt(gatt)
                        finish(BleConnectAttempt.Failed("services discovery failed, status=$status"))
                        return
                    }
                    finish(BleConnectAttempt.Connected(gatt, gatt.services?.size ?: 0))
                }
            }

            val gatt = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(context, autoReconnect, callback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(context, autoReconnect, callback)
                }
            }.getOrNull()

            if (gatt == null) {
                finish(BleConnectAttempt.Failed("connectGatt returned null"))
                return@suspendCancellableCoroutine
            }

            gattRef = gatt
            cont.invokeOnCancellation {
                safelyCloseGatt(gatt)
            }
        }
    }

    if (outcome == null) {
        gattRef?.let { safelyCloseGatt(it) }
        return BleConnectAttempt.Failed("timeout after ${timeoutMs}ms")
    }
    return outcome
}

private fun requiredScanPermissions(): List<String> {
    return if (Build.VERSION.SDK_INT >= 31) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        listOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }
}

private fun requiredConnectPermissions(): List<String> {
    return if (Build.VERSION.SDK_INT >= 31) {
        listOf(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN
        )
    }
}

private fun safelyCloseGatt(gatt: BluetoothGatt) {
    runCatching { gatt.disconnect() }
    runCatching { gatt.close() }
}

private class BluetoothConnectionState {
    private val gattByAddress = ConcurrentHashMap<String, BluetoothGatt>()

    fun put(address: String, gatt: BluetoothGatt) {
        val key = address.trim().uppercase(Locale.US)
        gattByAddress.put(key, gatt)?.let { safelyCloseGatt(it) }
    }

    fun disconnect(address: String): Boolean {
        val key = address.trim().uppercase(Locale.US)
        val gatt = gattByAddress.remove(key) ?: return false
        safelyCloseGatt(gatt)
        return true
    }

    fun disconnectAll(): Int {
        val all = gattByAddress.values.toList()
        gattByAddress.clear()
        all.forEach { safelyCloseGatt(it) }
        return all.size
    }

    fun activeAddresses(): List<String> {
        return gattByAddress.keys.toList().sorted()
    }
}

private sealed class BleConnectAttempt {
    data class Connected(val gatt: BluetoothGatt, val servicesCount: Int) : BleConnectAttempt()
    data class Failed(val reason: String) : BleConnectAttempt()
}


