package com.palmclaw.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothValueCodecTest {

    @Test
    fun `hex and utf8 values round trip through the public value model`() {
        val hex = BluetoothValueCodec.decode("00 ff 2a", BleValueEncoding.HEX)
        val utf8 = BluetoothValueCodec.decode("温度 23", BleValueEncoding.UTF8)

        assertTrue(hex is BleValueDecodeResult.Success)
        assertEquals(listOf(0x00, 0xff, 0x2a), (hex as BleValueDecodeResult.Success).bytes.map { it.toInt() and 0xff })
        assertTrue(utf8 is BleValueDecodeResult.Success)
        assertEquals(
            "温度 23",
            BluetoothValueCodec.encode((utf8 as BleValueDecodeResult.Success).bytes).utf8
        )
    }

    @Test
    fun `invalid or oversized values fail before reaching a device`() {
        val oddHex = BluetoothValueCodec.decode("abc", BleValueEncoding.HEX)
        val invalidHex = BluetoothValueCodec.decode("zz", BleValueEncoding.HEX)
        val oversized = BluetoothValueCodec.decode("aa".repeat(513), BleValueEncoding.HEX)

        assertEquals("invalid_value", (oddHex as BleValueDecodeResult.Failure).code)
        assertEquals("invalid_value", (invalidHex as BleValueDecodeResult.Failure).code)
        assertEquals("payload_too_large", (oversized as BleValueDecodeResult.Failure).code)
    }

    @Test
    fun `binary read results expose hex without inventing utf8`() {
        val encoded = BluetoothValueCodec.encode(byteArrayOf(0xc3.toByte(), 0x28))

        assertEquals("c328", encoded.hex)
        assertNull(encoded.utf8)
    }

    @Test
    fun `automatic writes prefer acknowledgement and reject unsupported types`() {
        val both = setOf(
            BleCharacteristicProperty.WRITE,
            BleCharacteristicProperty.WRITE_WITHOUT_RESPONSE
        )
        val readOnly = setOf(BleCharacteristicProperty.READ)

        assertEquals(BleWriteType.WITH_RESPONSE, BleWritePolicy.resolve(BleWriteType.AUTO, both))
        assertNull(
            BleWritePolicy.resolve(
                BleWriteType.WITHOUT_RESPONSE,
                setOf(BleCharacteristicProperty.WRITE)
            )
        )
        assertNull(BleWritePolicy.resolve(BleWriteType.AUTO, readOnly))
    }
}
