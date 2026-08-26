package com.palmclaw.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothControlToolTest {

    @Test
    fun `schema exposes a bounded agent interface without manual success flags`() {
        val tool = BluetoothControlTool(InMemoryBleGateway(), FakeBluetoothUserInteraction())
        val actions = tool.jsonSchema["properties"]!!
            .jsonObject["action"]!!
            .jsonObject["enum"] as JsonArray

        assertEquals(
            listOf(
                "status",
                "set_power",
                "open_settings",
                "paired_list",
                "ble_scan",
                "ble_connect",
                "ble_inspect",
                "ble_read",
                "ble_write",
                "ble_disconnect"
            ),
            actions.map { it.jsonPrimitive.content }
        )
        val properties = tool.jsonSchema["properties"]!!.jsonObject
        assertFalse(properties.containsKey("allow_manual_success"))
        assertFalse(properties.containsKey("discover_services"))
        assertFalse(properties.containsKey("auto_reconnect"))
    }

    @Test
    fun `legacy control flags are rejected instead of silently ignored`() = runBlocking {
        val tool = BluetoothControlTool(InMemoryBleGateway(), FakeBluetoothUserInteraction())

        val result = tool.run("""{"action":"status","allow_manual_success":true}""")

        assertTrue(result.isError)
        assertEquals("invalid_arguments", result.body()["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `manual setup never turns a failed GATT connection into success`() = runBlocking {
        val gateway = InMemoryBleGateway(connectFailure = true)
        val interaction = FakeBluetoothUserInteraction(confirmations = ArrayDeque(listOf(true)))
        val tool = BluetoothControlTool(gateway, interaction)

        val result = tool.run(
            """{"action":"ble_connect","address":"AA:BB:CC:DD:EE:FF"}"""
        )
        val body = result.body()

        assertTrue(result.isError)
        assertEquals("connection_not_verified", body["code"]!!.jsonPrimitive.content)
        assertTrue(body["setup_completed"]!!.jsonPrimitive.boolean)
        assertFalse(body["connected"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `agent can connect inspect and read through structured results`() = runBlocking {
        val tool = BluetoothControlTool(InMemoryBleGateway(), FakeBluetoothUserInteraction())

        val connected = tool.run(
            """{"action":"ble_connect","address":"AA:BB:CC:DD:EE:FF"}"""
        )
        val profile = tool.run("""{"action":"ble_inspect"}""")
        val read = tool.run(
            """{
              "action":"ble_read",
              "service_uuid":"0000180f-0000-1000-8000-00805f9b34fb",
              "characteristic_uuid":"00002a19-0000-1000-8000-00805f9b34fb"
            }""".trimIndent()
        )

        assertFalse(connected.content, connected.isError)
        assertTrue(connected.body()["connected"]!!.jsonPrimitive.boolean)
        assertFalse(profile.content, profile.isError)
        assertEquals(
            "00002a19-0000-1000-8000-00805f9b34fb",
            (profile.body()["services"]!! as JsonArray)[0]
                .jsonObject["characteristics"]!!
                .let { it as JsonArray }[0]
                .jsonObject["uuid"]!!
                .jsonPrimitive.content
        )
        assertFalse(read.content, read.isError)
        assertEquals("32", read.body()["value_hex"]!!.jsonPrimitive.content)
        assertEquals("2", read.body()["value_utf8"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a second connection is rejected until the active connection is closed`() = runBlocking {
        val tool = BluetoothControlTool(InMemoryBleGateway(), FakeBluetoothUserInteraction())
        val first = tool.run(
            """{"action":"ble_connect","address":"AA:BB:CC:DD:EE:FF"}"""
        )
        val second = tool.run(
            """{"action":"ble_connect","address":"11:22:33:44:55:66"}"""
        )

        assertFalse(first.content, first.isError)
        assertTrue(second.isError)
        assertEquals("active_connection_exists", second.body()["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `cancelled write leaves the device value unchanged`() = runBlocking {
        val gateway = InMemoryBleGateway()
        val interaction = FakeBluetoothUserInteraction(confirmations = ArrayDeque(listOf(false)))
        val tool = BluetoothControlTool(gateway, interaction)
        tool.run("""{"action":"ble_connect","address":"AA:BB:CC:DD:EE:FF"}""")

        val write = tool.run(
            """{
              "action":"ble_write",
              "service_uuid":"0000180f-0000-1000-8000-00805f9b34fb",
              "characteristic_uuid":"00002a19-0000-1000-8000-00805f9b34fb",
              "value":"64",
              "encoding":"hex"
            }""".trimIndent()
        )
        val read = tool.run(
            """{
              "action":"ble_read",
              "service_uuid":"0000180f-0000-1000-8000-00805f9b34fb",
              "characteristic_uuid":"00002a19-0000-1000-8000-00805f9b34fb"
            }""".trimIndent()
        )

        assertTrue(write.isError)
        assertEquals("user_cancelled", write.body()["code"]!!.jsonPrimitive.content)
        assertEquals("32", read.body()["value_hex"]!!.jsonPrimitive.content)
    }

    @Test
    fun `confirmed write updates a readable characteristic`() = runBlocking {
        val gateway = InMemoryBleGateway()
        val interaction = FakeBluetoothUserInteraction(confirmations = ArrayDeque(listOf(true)))
        val tool = BluetoothControlTool(gateway, interaction)
        tool.run("""{"action":"ble_connect","address":"AA:BB:CC:DD:EE:FF"}""")

        val write = tool.run(
            """{
              "action":"ble_write",
              "service_uuid":"0000180f-0000-1000-8000-00805f9b34fb",
              "characteristic_uuid":"00002a19-0000-1000-8000-00805f9b34fb",
              "value":"64",
              "encoding":"hex"
            }""".trimIndent()
        )
        val read = tool.run(
            """{
              "action":"ble_read",
              "service_uuid":"0000180f-0000-1000-8000-00805f9b34fb",
              "characteristic_uuid":"00002a19-0000-1000-8000-00805f9b34fb"
            }""".trimIndent()
        )

        assertFalse(write.content, write.isError)
        assertEquals(1, write.body()["bytes_written"]!!.jsonPrimitive.content.toInt())
        assertEquals("64", read.body()["value_hex"]!!.jsonPrimitive.content)
    }

    @Test
    fun `write without response does not claim device acknowledgement`() = runBlocking {
        val tool = BluetoothControlTool(
            InMemoryBleGateway(),
            FakeBluetoothUserInteraction(confirmations = ArrayDeque(listOf(true)))
        )
        tool.run("""{"action":"ble_connect","address":"AA:BB:CC:DD:EE:FF"}""")

        val write = tool.run(
            """{
              "action":"ble_write",
              "service_uuid":"0000180f-0000-1000-8000-00805f9b34fb",
              "characteristic_uuid":"00002a19-0000-1000-8000-00805f9b34fb",
              "value":"64",
              "encoding":"hex",
              "write_type":"without_response"
            }""".trimIndent()
        )

        assertFalse(write.content, write.isError)
        assertFalse(write.body()["device_acknowledged"]!!.jsonPrimitive.boolean)
        assertEquals(
            "without_response",
            write.body()["write_type"]!!.jsonPrimitive.content
        )
    }

    private fun ToolResult.body(): JsonObject =
        Json.parseToJsonElement(content).jsonObject
}

private class FakeBluetoothUserInteraction(
    private val confirmations: ArrayDeque<Boolean> = ArrayDeque()
) : BluetoothUserInteraction {
    override suspend fun ensurePermissions(
        action: String,
        scopes: Set<BluetoothPermissionScope>
    ): BluetoothGatewayResult<Unit> = BluetoothGatewayResult.Success(Unit)

    override suspend fun requestEnableBluetooth(): Boolean? = true

    override suspend fun openBluetoothSettings(): Boolean? = true

    override suspend fun confirm(
        title: String,
        message: String,
        confirmLabel: String,
        cancelLabel: String
    ): Boolean? = if (confirmations.isEmpty()) true else confirmations.removeFirst()
}

private class InMemoryBleGateway(
    private val connectFailure: Boolean = false
) : BleClientGateway {
    private val serviceUuid = "0000180f-0000-1000-8000-00805f9b34fb"
    private val characteristicUuid = "00002a19-0000-1000-8000-00805f9b34fb"
    private var enabled = true
    private var connection: BleConnectionInfo? = null
    private var value = byteArrayOf('2'.code.toByte())

    override fun status(): BluetoothGatewayResult<BluetoothStatus> =
        BluetoothGatewayResult.Success(
            BluetoothStatus(
                available = true,
                enabled = enabled,
                pairedCount = 1,
                activeConnection = connection
            )
        )

    override fun pairedDevices(): BluetoothGatewayResult<List<BluetoothDeviceInfo>> =
        BluetoothGatewayResult.Success(
            listOf(BluetoothDeviceInfo("AA:BB:CC:DD:EE:FF", "Fixture", type = "ble", bonded = true))
        )

    override suspend fun trySetPower(enabled: Boolean): BluetoothGatewayResult<BluetoothPowerResult> {
        this.enabled = enabled
        return BluetoothGatewayResult.Success(
            BluetoothPowerResult(enabled, changedDirectly = true, userActionRequired = false)
        )
    }

    override suspend fun scan(
        durationMs: Long,
        maxResults: Int
    ): BluetoothGatewayResult<List<BluetoothDeviceInfo>> =
        BluetoothGatewayResult.Success(
            listOf(BluetoothDeviceInfo("AA:BB:CC:DD:EE:FF", "Fixture", rssi = -42, type = "ble"))
        )

    override suspend fun connect(
        address: String,
        timeoutMs: Long
    ): BluetoothGatewayResult<BleConnectionInfo> {
        if (connectFailure) {
            return BluetoothGatewayResult.Failure(
                code = "connection_failed",
                message = "Fixture connection failed."
            )
        }
        if (connection != null) {
            return BluetoothGatewayResult.Failure(
                code = "active_connection_exists",
                message = "A BLE connection is already active."
            )
        }
        val connected = BleConnectionInfo(address, "Fixture", 1, 247, connected = true)
        connection = connected
        return BluetoothGatewayResult.Success(connected)
    }

    override suspend fun inspect(): BluetoothGatewayResult<BleGattProfile> {
        if (connection == null) return noConnection()
        return BluetoothGatewayResult.Success(
            BleGattProfile(
                services = listOf(
                    BleServiceInfo(
                        uuid = serviceUuid,
                        primary = true,
                        characteristics = listOf(
                            BleCharacteristicInfo(
                                uuid = characteristicUuid,
                                properties = setOf(
                                    BleCharacteristicProperty.READ,
                                    BleCharacteristicProperty.WRITE,
                                    BleCharacteristicProperty.WRITE_WITHOUT_RESPONSE
                                )
                            )
                        )
                    )
                ),
                totalServices = 1,
                totalCharacteristics = 1,
                truncated = false
            )
        )
    }

    override suspend fun read(
        serviceUuid: String,
        characteristicUuid: String,
        timeoutMs: Long
    ): BluetoothGatewayResult<ByteArray> {
        if (connection == null) return noConnection()
        if (serviceUuid != this.serviceUuid || characteristicUuid != this.characteristicUuid) {
            return BluetoothGatewayResult.Failure("characteristic_not_found", "Characteristic not found.")
        }
        return BluetoothGatewayResult.Success(value.copyOf())
    }

    override suspend fun write(
        serviceUuid: String,
        characteristicUuid: String,
        bytes: ByteArray,
        writeType: BleWriteType,
        timeoutMs: Long
    ): BluetoothGatewayResult<BleWriteResult> {
        if (connection == null) return noConnection()
        if (serviceUuid != this.serviceUuid || characteristicUuid != this.characteristicUuid) {
            return BluetoothGatewayResult.Failure("characteristic_not_found", "Characteristic not found.")
        }
        value = bytes.copyOf()
        val resolvedType = if (writeType == BleWriteType.AUTO) {
            BleWriteType.WITH_RESPONSE
        } else {
            writeType
        }
        return BluetoothGatewayResult.Success(
            BleWriteResult(
                bytes.size,
                resolvedType,
                deviceAcknowledged = resolvedType == BleWriteType.WITH_RESPONSE
            )
        )
    }

    override suspend fun disconnect(): BluetoothGatewayResult<Boolean> {
        val disconnected = connection != null
        connection = null
        return BluetoothGatewayResult.Success(disconnected)
    }

    private fun <T> noConnection(): BluetoothGatewayResult<T> =
        BluetoothGatewayResult.Failure("no_active_connection", "No BLE connection is active.")
}
