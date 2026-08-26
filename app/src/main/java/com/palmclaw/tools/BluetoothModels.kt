package com.palmclaw.tools

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

internal enum class BleValueEncoding {
    HEX,
    UTF8;

    companion object {
        fun parse(raw: String?): BleValueEncoding? = when (raw?.trim()?.lowercase(Locale.US)) {
            "hex" -> HEX
            "utf8", "utf-8" -> UTF8
            else -> null
        }
    }
}

internal sealed interface BleValueDecodeResult {
    data class Success(val bytes: ByteArray) : BleValueDecodeResult

    data class Failure(
        val code: String,
        val message: String,
        val nextStep: String
    ) : BleValueDecodeResult
}

internal data class BleEncodedValue(
    val hex: String,
    val utf8: String?
)

internal object BluetoothValueCodec {
    fun decode(value: String, encoding: BleValueEncoding): BleValueDecodeResult {
        val bytes = when (encoding) {
            BleValueEncoding.UTF8 -> value.toByteArray(StandardCharsets.UTF_8)
            BleValueEncoding.HEX -> {
                val normalized = value.filterNot { it.isWhitespace() }
                if (normalized.length % 2 != 0 || !normalized.all { it.isHexDigit() }) {
                    return BleValueDecodeResult.Failure(
                        code = "invalid_value",
                        message = "Hex values must contain complete hexadecimal byte pairs.",
                        nextStep = "Use characters 0-9 and a-f with two characters per byte."
                    )
                }
                ByteArray(normalized.length / 2) { index ->
                    normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
                }
            }
        }
        if (bytes.size > MAX_VALUE_BYTES) {
            return BleValueDecodeResult.Failure(
                code = "payload_too_large",
                message = "BLE values are limited to $MAX_VALUE_BYTES bytes.",
                nextStep = "Use a smaller device command."
            )
        }
        return BleValueDecodeResult.Success(bytes)
    }

    fun encode(bytes: ByteArray): BleEncodedValue {
        val utf8 = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
        return BleEncodedValue(
            hex = bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) },
            utf8 = utf8
        )
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    const val MAX_VALUE_BYTES: Int = 512
}

internal data class BluetoothStatus(
    val available: Boolean,
    val enabled: Boolean,
    val pairedCount: Int,
    val activeConnection: BleConnectionInfo?
)

internal data class BluetoothDeviceInfo(
    val address: String,
    val name: String?,
    val rssi: Int? = null,
    val type: String? = null,
    val bonded: Boolean = false
)

internal data class BleConnectionInfo(
    val address: String,
    val name: String?,
    val servicesCount: Int,
    val mtu: Int,
    val connected: Boolean
)

internal enum class BleCharacteristicProperty(val wireName: String) {
    READ("read"),
    WRITE("write"),
    WRITE_WITHOUT_RESPONSE("write_without_response"),
    NOTIFY("notify"),
    INDICATE("indicate")
}

internal data class BleCharacteristicInfo(
    val uuid: String,
    val properties: Set<BleCharacteristicProperty>
)

internal data class BleServiceInfo(
    val uuid: String,
    val primary: Boolean,
    val characteristics: List<BleCharacteristicInfo>
)

internal data class BleGattProfile(
    val services: List<BleServiceInfo>,
    val totalServices: Int,
    val totalCharacteristics: Int,
    val truncated: Boolean
)

internal enum class BleWriteType(val wireName: String) {
    AUTO("auto"),
    WITH_RESPONSE("with_response"),
    WITHOUT_RESPONSE("without_response");

    companion object {
        fun parse(raw: String?): BleWriteType? = when (raw?.trim()?.lowercase(Locale.US)) {
            null, "", "auto" -> AUTO
            "with_response" -> WITH_RESPONSE
            "without_response" -> WITHOUT_RESPONSE
            else -> null
        }
    }
}

internal object BleWritePolicy {
    fun resolve(
        requested: BleWriteType,
        properties: Set<BleCharacteristicProperty>
    ): BleWriteType? {
        val supportsResponse = BleCharacteristicProperty.WRITE in properties
        val supportsNoResponse =
            BleCharacteristicProperty.WRITE_WITHOUT_RESPONSE in properties
        return when (requested) {
            BleWriteType.AUTO -> when {
                supportsResponse -> BleWriteType.WITH_RESPONSE
                supportsNoResponse -> BleWriteType.WITHOUT_RESPONSE
                else -> null
            }
            BleWriteType.WITH_RESPONSE ->
                BleWriteType.WITH_RESPONSE.takeIf { supportsResponse }
            BleWriteType.WITHOUT_RESPONSE ->
                BleWriteType.WITHOUT_RESPONSE.takeIf { supportsNoResponse }
        }
    }
}

internal data class BleWriteResult(
    val bytesWritten: Int,
    val writeType: BleWriteType,
    val deviceAcknowledged: Boolean
)

internal data class BluetoothPowerResult(
    val enabled: Boolean,
    val changedDirectly: Boolean,
    val userActionRequired: Boolean
)

internal sealed interface BluetoothGatewayResult<out T> {
    data class Success<T>(val value: T) : BluetoothGatewayResult<T>

    data class Failure(
        val code: String,
        val message: String,
        val nextStep: String? = null,
        val gattStatus: Int? = null
    ) : BluetoothGatewayResult<Nothing>
}

/**
 * Deep Bluetooth module interface used by the tool and its tests.
 *
 * The Android adapter owns Bluetooth callbacks, one active GATT connection, operation
 * serialization, timeouts, and resource cleanup.
 */
internal interface BleClientGateway {
    fun status(): BluetoothGatewayResult<BluetoothStatus>

    fun pairedDevices(): BluetoothGatewayResult<List<BluetoothDeviceInfo>>

    suspend fun trySetPower(enabled: Boolean): BluetoothGatewayResult<BluetoothPowerResult>

    suspend fun scan(durationMs: Long, maxResults: Int): BluetoothGatewayResult<List<BluetoothDeviceInfo>>

    suspend fun connect(address: String, timeoutMs: Long): BluetoothGatewayResult<BleConnectionInfo>

    suspend fun inspect(): BluetoothGatewayResult<BleGattProfile>

    suspend fun read(
        serviceUuid: String,
        characteristicUuid: String,
        timeoutMs: Long
    ): BluetoothGatewayResult<ByteArray>

    suspend fun write(
        serviceUuid: String,
        characteristicUuid: String,
        bytes: ByteArray,
        writeType: BleWriteType,
        timeoutMs: Long
    ): BluetoothGatewayResult<BleWriteResult>

    suspend fun disconnect(): BluetoothGatewayResult<Boolean>
}
