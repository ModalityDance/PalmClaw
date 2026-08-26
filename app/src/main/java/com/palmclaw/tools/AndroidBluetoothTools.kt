package com.palmclaw.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

fun createAndroidBluetoothToolSet(context: Context): List<Tool> {
    val appContext = context.applicationContext
    return listOf(
        BluetoothControlTool(
            gateway = AndroidBluetoothRuntime.gateway(appContext),
            userInteraction = AndroidBluetoothUserInteraction(appContext)
        )
    )
}

/**
 * Process-wide owner for the phone's single generic BLE client connection.
 *
 * PalmClaw can construct more than one tool registry while runtime ownership is changing. Sharing
 * this gateway prevents those registries from creating independent GATT sessions.
 */
private object AndroidBluetoothRuntime {
    @Volatile
    private var sharedGateway: BleClientGateway? = null

    fun gateway(context: Context): BleClientGateway =
        sharedGateway ?: synchronized(this) {
            sharedGateway ?: AndroidBleClientGateway(context).also { sharedGateway = it }
        }
}

/**
 * Owns Android permission prompts and system UI transitions for the Bluetooth tool.
 *
 * The tool remains independent from Android UI APIs, while the BLE gateway remains independent
 * from permission prompting. This keeps both boundaries replaceable in unit tests.
 */
private class AndroidBluetoothUserInteraction(
    private val context: Context
) : BluetoothUserInteraction {
    override suspend fun ensurePermissions(
        action: String,
        scopes: Set<BluetoothPermissionScope>
    ): BluetoothGatewayResult<Unit> {
        val required = requiredPermissions(scopes)
        var missing = missingPermissions(context, required)
        if (missing.isEmpty()) return BluetoothGatewayResult.Success(Unit)

        when (AndroidUserActionBridge.requestPermissions(missing)) {
            true -> {
                missing = missingPermissions(context, required)
                if (missing.isEmpty()) return BluetoothGatewayResult.Success(Unit)
            }
            false, null -> Unit
        }

        val settingsResult = launchIntent(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        )
        if (settingsResult.isError) {
            return BluetoothGatewayResult.Failure(
                code = "settings_unavailable",
                message = "Bluetooth permissions are missing and app settings could not be opened.",
                nextStep = "Open PalmClaw app settings, grant Bluetooth permissions, and retry."
            )
        }

        val confirmed = AndroidUserActionBridge.requestUserConfirmation(
            title = "Bluetooth Permission",
            message = "Grant the required Bluetooth permission(s), then return and tap Continue.",
            confirmLabel = "Continue",
            cancelLabel = "Cancel"
        )
        if (confirmed != true) {
            return BluetoothGatewayResult.Failure(
                code = if (confirmed == false) "user_cancelled" else "confirmation_unavailable",
                message = if (confirmed == false) {
                    "Bluetooth permission setup was cancelled."
                } else {
                    "Bluetooth permission confirmation is unavailable."
                },
                nextStep = "Grant Bluetooth permissions in app settings and retry."
            )
        }

        missing = missingPermissions(context, required)
        return if (missing.isEmpty()) {
            BluetoothGatewayResult.Success(Unit)
        } else {
            BluetoothGatewayResult.Failure(
                code = "permission_required",
                message = "Bluetooth permission is still required for $action.",
                nextStep = "Grant ${missing.joinToString()} in PalmClaw app settings and retry."
            )
        }
    }

    override suspend fun requestEnableBluetooth(): Boolean? =
        AndroidUserActionBridge.requestEnableBluetooth()

    override suspend fun openBluetoothSettings(): Boolean? =
        AndroidUserActionBridge.openBluetoothSettings()

    override suspend fun confirm(
        title: String,
        message: String,
        confirmLabel: String,
        cancelLabel: String
    ): Boolean? =
        AndroidUserActionBridge.requestUserConfirmation(
            title = title,
            message = message,
            confirmLabel = confirmLabel,
            cancelLabel = cancelLabel
        )

    @Suppress("DEPRECATION")
    private fun requiredPermissions(
        scopes: Set<BluetoothPermissionScope>
    ): List<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return buildList {
                if (BluetoothPermissionScope.SCAN in scopes) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                }
                if (BluetoothPermissionScope.CONNECT in scopes) {
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
        }
        return buildList {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
            if (BluetoothPermissionScope.SCAN in scopes) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }
}
