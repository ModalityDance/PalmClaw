package com.palmclaw.tools

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal class AndroidBleClientGateway(
    context: Context
) : BleClientGateway {
    private val appContext = context.applicationContext
    private val connectMutex = Mutex()
    private val activeSession = AtomicReference<GattSession?>(null)

    override fun status(): BluetoothGatewayResult<BluetoothStatus> {
        val adapter = adapter()
            ?: return BluetoothGatewayResult.Success(
                BluetoothStatus(
                    available = false,
                    enabled = false,
                    pairedCount = 0,
                    activeConnection = null
                )
            )
        return try {
            val enabled = adapter.isEnabled
            if (!enabled) closeActiveSession()
            val session = activeSession.get()?.takeIf(GattSession::isConnected)
            BluetoothGatewayResult.Success(
                BluetoothStatus(
                    available = true,
                    enabled = enabled,
                    pairedCount = adapter.bondedDevices.orEmpty().size,
                    activeConnection = session?.connectionInfo()
                )
            )
        } catch (_: SecurityException) {
            permissionFailure("status")
        } catch (failure: Throwable) {
            platformFailure("status", failure)
        }
    }

    override fun pairedDevices(): BluetoothGatewayResult<List<BluetoothDeviceInfo>> {
        val adapter = adapter() ?: return unavailable()
        return try {
            BluetoothGatewayResult.Success(
                adapter.bondedDevices.orEmpty()
                    .map { device ->
                        BluetoothDeviceInfo(
                            address = device.address,
                            name = device.name?.takeIf(String::isNotBlank),
                            type = device.type.toWireName(),
                            bonded = device.bondState == BluetoothDevice.BOND_BONDED
                        )
                    }
                    .sortedWith(
                        compareBy<BluetoothDeviceInfo> { it.name.orEmpty().lowercase(Locale.US) }
                            .thenBy(BluetoothDeviceInfo::address)
                    )
            )
        } catch (_: SecurityException) {
            permissionFailure("paired_list")
        } catch (failure: Throwable) {
            platformFailure("paired_list", failure)
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun trySetPower(
        enabled: Boolean
    ): BluetoothGatewayResult<BluetoothPowerResult> {
        val adapter = adapter() ?: return unavailable()
        return try {
            val before = adapter.isEnabled
            if (before == enabled) {
                if (!enabled) closeActiveSession()
                return BluetoothGatewayResult.Success(
                    BluetoothPowerResult(
                        enabled = before,
                        changedDirectly = false,
                        userActionRequired = false
                    )
                )
            }
            val started = if (enabled) adapter.enable() else adapter.disable()
            if (started) {
                repeat(5) {
                    if (adapter.isEnabled == enabled) {
                        if (!enabled) closeActiveSession()
                        return BluetoothGatewayResult.Success(
                            BluetoothPowerResult(
                                enabled = enabled,
                                changedDirectly = true,
                                userActionRequired = false
                            )
                        )
                    }
                    delay(100)
                }
            }
            val actualEnabled = adapter.isEnabled
            if (!actualEnabled) closeActiveSession()
            BluetoothGatewayResult.Success(
                BluetoothPowerResult(
                    enabled = actualEnabled,
                    changedDirectly = false,
                    userActionRequired = true
                )
            )
        } catch (_: SecurityException) {
            permissionFailure("set_power")
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            platformFailure("set_power", failure)
        }
    }

    override suspend fun scan(
        durationMs: Long,
        maxResults: Int
    ): BluetoothGatewayResult<List<BluetoothDeviceInfo>> {
        val adapter = adapter() ?: return unavailable()
        val scanner = try {
            adapter.bluetoothLeScanner
        } catch (_: SecurityException) {
            return permissionFailure("ble_scan")
        } catch (failure: Throwable) {
            return platformFailure("ble_scan", failure)
        } ?: return BluetoothGatewayResult.Failure(
            code = "scan_unavailable",
            message = "The BLE scanner is unavailable.",
            nextStep = "Enable Bluetooth and retry."
        )
        val devices = ConcurrentHashMap<String, BluetoothDeviceInfo>()
        val scanFailure = CompletableDeferred<Int>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result ?: return
                val device = result.device ?: return
                val address = runCatching { device.address }.getOrNull().orEmpty()
                if (address.isBlank()) return
                val name = runCatching {
                    device.name ?: result.scanRecord?.deviceName
                }.getOrNull()
                devices[address] = BluetoothDeviceInfo(
                    address = address,
                    name = name?.takeIf(String::isNotBlank),
                    rssi = result.rssi,
                    type = runCatching { device.type.toWireName() }.getOrNull(),
                    bonded = runCatching {
                        device.bondState == BluetoothDevice.BOND_BONDED
                    }.getOrDefault(false)
                )
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results.orEmpty().forEach { onScanResult(0, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                scanFailure.complete(errorCode)
            }
        }
        try {
            val started = try {
                scanner.startScan(callback)
                true
            } catch (_: SecurityException) {
                return permissionFailure("ble_scan")
            } catch (_: Throwable) {
                false
            }
            if (!started) {
                return BluetoothGatewayResult.Failure(
                    code = "scan_failed",
                    message = "BLE scan could not be started.",
                    nextStep = "Check Bluetooth permissions and retry."
                )
            }
            val failureCode = withTimeoutOrNull(durationMs.coerceAtLeast(1L)) {
                scanFailure.await()
            }
            if (failureCode != null) {
                return BluetoothGatewayResult.Failure(
                    code = "scan_failed",
                    message = "Android BLE scan failed with code $failureCode.",
                    nextStep = "Check Bluetooth state, permissions, and scan availability."
                )
            }
            return BluetoothGatewayResult.Success(
                devices.values
                    .sortedWith(
                        compareByDescending<BluetoothDeviceInfo> { it.rssi ?: Int.MIN_VALUE }
                            .thenBy(BluetoothDeviceInfo::address)
                    )
                    .take(maxResults.coerceIn(1, MAX_SCAN_RESULTS))
            )
        } catch (failure: CancellationException) {
            throw failure
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
    }

    override suspend fun connect(
        address: String,
        timeoutMs: Long
    ): BluetoothGatewayResult<BleConnectionInfo> = connectMutex.withLock {
        activeSession.get()?.let { current ->
            if (current.isConnected()) {
                return@withLock BluetoothGatewayResult.Failure(
                    code = "active_connection_exists",
                    message = "A BLE connection to ${current.address} is already active.",
                    nextStep = "Disconnect the active device before connecting another one."
                )
            }
            activeSession.compareAndSet(current, null)
            current.close()
        }
        val adapter = adapter() ?: return@withLock unavailable()
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            return@withLock BluetoothGatewayResult.Failure(
                code = "invalid_address",
                message = "Invalid Bluetooth address.",
                nextStep = "Use an address returned by ble_scan or paired_list."
            )
        }
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (_: SecurityException) {
            return@withLock permissionFailure("ble_connect")
        } catch (failure: Throwable) {
            return@withLock platformFailure("ble_connect", failure)
        } ?: return@withLock BluetoothGatewayResult.Failure(
                code = "connection_failed",
                message = "Android could not resolve the BLE device.",
                nextStep = "Scan again and retry with the returned address."
            )
        val session = GattSession(
            device = device,
            onDisconnected = { disconnected ->
                activeSession.compareAndSet(disconnected, null)
            }
        )
        val gatt = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(
                    appContext,
                    false,
                    session.callback,
                    BluetoothDevice.TRANSPORT_LE
                )
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(appContext, false, session.callback)
            }
        } catch (_: SecurityException) {
            return@withLock permissionFailure("ble_connect")
        } catch (failure: Throwable) {
            return@withLock BluetoothGatewayResult.Failure(
                code = "connection_failed",
                message = failure.message ?: "BLE connection could not be started.",
                nextStep = "Check that the device is nearby and accepts BLE connections."
            )
        }
        if (gatt == null) {
            return@withLock BluetoothGatewayResult.Failure(
                code = "connection_failed",
                message = "Android returned no GATT connection.",
                nextStep = "Check the device and retry."
            )
        }
        session.attach(gatt)
        val ready = try {
            session.awaitReady(timeoutMs)
        } catch (failure: CancellationException) {
            session.close()
            throw failure
        }
        if (ready is BluetoothGatewayResult.Success && session.isConnected()) {
            activeSession.set(session)
            return@withLock ready
        }
        if (ready is BluetoothGatewayResult.Success) {
            session.close()
            return@withLock BluetoothGatewayResult.Failure(
                code = "disconnected",
                message = "The BLE device disconnected while connection setup completed.",
                nextStep = "Reconnect the device and retry."
            )
        }
        session.close()
        ready
    }

    override suspend fun inspect(): BluetoothGatewayResult<BleGattProfile> =
        requireSession()?.inspect() ?: noConnection()

    override suspend fun read(
        serviceUuid: String,
        characteristicUuid: String,
        timeoutMs: Long
    ): BluetoothGatewayResult<ByteArray> =
        requireSession()?.read(serviceUuid, characteristicUuid, timeoutMs) ?: noConnection()

    override suspend fun write(
        serviceUuid: String,
        characteristicUuid: String,
        bytes: ByteArray,
        writeType: BleWriteType,
        timeoutMs: Long
    ): BluetoothGatewayResult<BleWriteResult> =
        requireSession()?.write(
            serviceUuid,
            characteristicUuid,
            bytes,
            writeType,
            timeoutMs
        ) ?: noConnection()

    override suspend fun disconnect(): BluetoothGatewayResult<Boolean> {
        return BluetoothGatewayResult.Success(closeActiveSession())
    }

    private fun requireSession(): GattSession? =
        activeSession.get()?.takeIf(GattSession::isConnected)

    private fun closeActiveSession(): Boolean {
        val session = activeSession.getAndSet(null) ?: return false
        session.close()
        return true
    }

    private fun adapter(): BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun <T> unavailable(): BluetoothGatewayResult<T> =
        BluetoothGatewayResult.Failure(
            code = "bluetooth_unavailable",
            message = "This device has no available Bluetooth adapter.",
            nextStep = "Use Bluetooth actions only on a device with Bluetooth support."
        )

    private fun <T> noConnection(): BluetoothGatewayResult<T> =
        BluetoothGatewayResult.Failure(
            code = "no_active_connection",
            message = "No BLE GATT connection is active.",
            nextStep = "Connect a BLE device first."
        )

    private fun permissionFailure(action: String): BluetoothGatewayResult.Failure =
        BluetoothGatewayResult.Failure(
            code = "permission_required",
            message = "Bluetooth permission is required for $action.",
            nextStep = "Grant the requested Bluetooth permission and retry.",
            gattStatus = null
        )

    private fun <T> platformFailure(
        action: String,
        failure: Throwable
    ): BluetoothGatewayResult<T> =
        BluetoothGatewayResult.Failure(
            code = "bluetooth_error",
            message = failure.message ?: "Android Bluetooth failed during $action.",
            nextStep = "Check Bluetooth state and retry."
        )

    private class GattSession(
        private val device: BluetoothDevice,
        private val onDisconnected: (GattSession) -> Unit
    ) {
        val address: String = runCatching { device.address }.getOrDefault("")
        private val ready = CompletableDeferred<BluetoothGatewayResult<BleConnectionInfo>>()
        private val operationMutex = Mutex()
        private val pending = AtomicReference<PendingOperation?>(null)
        private val closed = AtomicBoolean(false)
        private val connected = AtomicBoolean(false)
        private val gattRef = AtomicReference<BluetoothGatt?>(null)
        @Volatile
        private var negotiatedMtu: Int = DEFAULT_MTU

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failConnection("connection_failed", "GATT connection failed.", status)
                    return
                }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connected.set(true)
                        val discoveryStarted = runCatching { gatt.discoverServices() }
                            .getOrDefault(false)
                        if (!discoveryStarted) {
                            failConnection(
                                "service_discovery_failed",
                                "GATT service discovery could not be started.",
                                null
                            )
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        connected.set(false)
                        failPending(
                            BluetoothGatewayResult.Failure(
                                code = "disconnected",
                                message = "The BLE device disconnected.",
                                nextStep = "Reconnect the device and retry."
                            )
                        )
                        if (!ready.isCompleted) {
                            ready.complete(
                                BluetoothGatewayResult.Failure(
                                    code = "connection_failed",
                                    message = "The BLE device disconnected before setup completed.",
                                    nextStep = "Reconnect the device and retry."
                                )
                            )
                        }
                        closeAndReleaseOwner()
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failConnection(
                        "service_discovery_failed",
                        "GATT service discovery failed.",
                        status
                    )
                    return
                }
                runCatching { gatt.requestMtu(PREFERRED_MTU) }
                ready.complete(
                    BluetoothGatewayResult.Success(
                        BleConnectionInfo(
                            address = address,
                            name = runCatching { device.name }.getOrNull(),
                            servicesCount = gatt.services.orEmpty().size,
                            mtu = negotiatedMtu,
                            connected = true
                        )
                    )
                )
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && mtu >= DEFAULT_MTU) {
                    negotiatedMtu = mtu
                }
            }

            @Deprecated("Used on Android API levels below 33")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (Build.VERSION.SDK_INT < 33) {
                    @Suppress("DEPRECATION")
                    completeRead(characteristic.uuid, characteristic.value ?: ByteArray(0), status)
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                completeRead(characteristic.uuid, value, status)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                val current = pending.get() as? PendingOperation.Write ?: return
                if (current.characteristicUuid != characteristic.uuid) return
                if (!pending.compareAndSet(current, null)) return
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    current.result.complete(
                        BluetoothGatewayResult.Success(
                            BleWriteResult(
                                current.bytesWritten,
                                current.writeType,
                                deviceAcknowledged = true
                            )
                        )
                    )
                } else {
                    current.result.complete(gattFailure("write_failed", "GATT write failed.", status))
                }
            }
        }

        fun attach(gatt: BluetoothGatt) {
            gattRef.set(gatt)
        }

        suspend fun awaitReady(
            timeoutMs: Long
        ): BluetoothGatewayResult<BleConnectionInfo> {
            val result = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) { ready.await() }
                ?: BluetoothGatewayResult.Failure(
                    code = "connection_timeout",
                    message = "BLE connection timed out.",
                    nextStep = "Move closer to the device and retry."
                )
            if (result is BluetoothGatewayResult.Failure) close()
            return result
        }

        fun connectionInfo(): BleConnectionInfo = BleConnectionInfo(
            address = address,
            name = runCatching { device.name }.getOrNull(),
            servicesCount = gattRef.get()?.services.orEmpty().size,
            mtu = negotiatedMtu,
            connected = isConnected()
        )

        fun isConnected(): Boolean = connected.get() && !closed.get()

        fun inspect(): BluetoothGatewayResult<BleGattProfile> {
            val gatt = gattRef.get() ?: return noSession()
            if (!isConnected()) return noSession()
            val services = mutableListOf<BleServiceInfo>()
            var characteristicCount = 0
            var truncated = false
            for (service in gatt.services.orEmpty()) {
                if (services.size >= MAX_PROFILE_SERVICES) {
                    truncated = true
                    break
                }
                val characteristics = mutableListOf<BleCharacteristicInfo>()
                for (characteristic in service.characteristics.orEmpty()) {
                    if (characteristicCount >= MAX_PROFILE_CHARACTERISTICS) {
                        truncated = true
                        break
                    }
                    characteristics += BleCharacteristicInfo(
                        uuid = characteristic.uuid.toString().lowercase(Locale.US),
                        properties = characteristic.properties.toDomainProperties()
                    )
                    characteristicCount += 1
                }
                services += BleServiceInfo(
                    uuid = service.uuid.toString().lowercase(Locale.US),
                    primary = service.type == BluetoothGattService.SERVICE_TYPE_PRIMARY,
                    characteristics = characteristics
                )
                if (truncated) break
            }
            val allServices = gatt.services.orEmpty()
            return BluetoothGatewayResult.Success(
                BleGattProfile(
                    services = services,
                    totalServices = allServices.size,
                    totalCharacteristics = allServices.sumOf { it.characteristics.orEmpty().size },
                    truncated = truncated
                )
            )
        }

        suspend fun read(
            serviceUuid: String,
            characteristicUuid: String,
            timeoutMs: Long
        ): BluetoothGatewayResult<ByteArray> = operationMutex.withLock {
            if (!isConnected()) return@withLock noSession()
            val characteristic = findCharacteristic(serviceUuid, characteristicUuid)
                ?: return@withLock missingCharacteristic(serviceUuid, characteristicUuid)
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
                return@withLock BluetoothGatewayResult.Failure(
                    code = "read_not_supported",
                    message = "The characteristic does not support read.",
                    nextStep = "Use ble_inspect and choose a characteristic with the read property."
                )
            }
            val gatt = gattRef.get() ?: return@withLock noSession()
            val result = CompletableDeferred<BluetoothGatewayResult<ByteArray>>()
            val operation = PendingOperation.Read(characteristic.uuid, result)
            pending.set(operation)
            val started = try {
                gatt.readCharacteristic(characteristic)
            } catch (failure: SecurityException) {
                pending.compareAndSet(operation, null)
                return@withLock permissionFailure("ble_read")
            } catch (_: Throwable) {
                false
            }
            if (!started) {
                pending.compareAndSet(operation, null)
                return@withLock BluetoothGatewayResult.Failure(
                    code = "read_failed",
                    message = "Android could not start the GATT read.",
                    nextStep = "Check the connection and characteristic properties."
                )
            }
            awaitOperation(result, operation, timeoutMs, "read_timeout", "GATT read timed out.")
        }

        suspend fun write(
            serviceUuid: String,
            characteristicUuid: String,
            bytes: ByteArray,
            requestedWriteType: BleWriteType,
            timeoutMs: Long
        ): BluetoothGatewayResult<BleWriteResult> = operationMutex.withLock {
            if (!isConnected()) return@withLock noSession()
            val characteristic = findCharacteristic(serviceUuid, characteristicUuid)
                ?: return@withLock missingCharacteristic(serviceUuid, characteristicUuid)
            val resolvedWriteType = BleWritePolicy.resolve(
                requested = requestedWriteType,
                properties = characteristic.properties.toDomainProperties()
            )
                ?: return@withLock BluetoothGatewayResult.Failure(
                    code = "write_not_supported",
                    message = "The characteristic does not support the requested write type.",
                    nextStep = "Use ble_inspect and choose a compatible characteristic or write_type."
                )
            val payloadLimit = (negotiatedMtu - ATT_HEADER_BYTES).coerceAtLeast(0)
            if (bytes.size > payloadLimit) {
                return@withLock BluetoothGatewayResult.Failure(
                    code = "payload_too_large",
                    message = "The value is ${bytes.size} bytes but the current BLE payload " +
                        "limit is $payloadLimit bytes.",
                    nextStep = "Use a smaller command supported by the device protocol."
                )
            }
            val gatt = gattRef.get() ?: return@withLock noSession()
            val result = CompletableDeferred<BluetoothGatewayResult<BleWriteResult>>()
            val operation = PendingOperation.Write(
                characteristicUuid = characteristic.uuid,
                bytesWritten = bytes.size,
                writeType = resolvedWriteType,
                result = result
            )
            pending.set(operation)
            val platformWriteType = when (resolvedWriteType) {
                BleWriteType.WITH_RESPONSE -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                BleWriteType.WITHOUT_RESPONSE -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                BleWriteType.AUTO -> error("AUTO must be resolved before writing.")
            }
            val started = try {
                if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeCharacteristic(
                        characteristic,
                        bytes,
                        platformWriteType
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    startLegacyWrite(gatt, characteristic, bytes, platformWriteType)
                }
            } catch (failure: SecurityException) {
                pending.compareAndSet(operation, null)
                return@withLock permissionFailure("ble_write")
            } catch (_: Throwable) {
                false
            }
            if (!started) {
                pending.compareAndSet(operation, null)
                return@withLock BluetoothGatewayResult.Failure(
                    code = "write_failed",
                    message = "Android could not start the GATT write.",
                    nextStep = "Check the connection, payload size, and characteristic properties."
                )
            }
            if (resolvedWriteType == BleWriteType.WITHOUT_RESPONSE) {
                pending.compareAndSet(operation, null)
                return@withLock BluetoothGatewayResult.Success(
                    BleWriteResult(
                        bytesWritten = bytes.size,
                        writeType = resolvedWriteType,
                        deviceAcknowledged = false
                    )
                )
            }
            awaitOperation(result, operation, timeoutMs, "write_timeout", "GATT write timed out.")
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            connected.set(false)
            failPending(
                BluetoothGatewayResult.Failure(
                    code = "disconnected",
                    message = "The BLE connection was closed.",
                    nextStep = "Reconnect the device before another GATT operation."
                )
            )
            gattRef.getAndSet(null)?.let { gatt ->
                runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
            }
        }

        private fun completeRead(
            characteristicUuid: UUID,
            value: ByteArray,
            status: Int
        ) {
            val current = pending.get() as? PendingOperation.Read ?: return
            if (current.characteristicUuid != characteristicUuid) return
            if (!pending.compareAndSet(current, null)) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                current.result.complete(BluetoothGatewayResult.Success(value.copyOf()))
            } else {
                current.result.complete(gattFailure("read_failed", "GATT read failed.", status))
            }
        }

        private fun failConnection(code: String, message: String, status: Int?) {
            connected.set(false)
            if (!ready.isCompleted) {
                ready.complete(
                    BluetoothGatewayResult.Failure(
                        code = code,
                        message = message,
                        nextStep = "Check the device and retry.",
                        gattStatus = status
                    )
                )
            }
            closeAndReleaseOwner()
        }

        private fun failPending(failure: BluetoothGatewayResult.Failure) {
            when (val current = pending.getAndSet(null)) {
                is PendingOperation.Read -> current.result.complete(failure)
                is PendingOperation.Write -> current.result.complete(failure)
                null -> Unit
            }
        }

        private suspend fun <T> awaitOperation(
            result: CompletableDeferred<BluetoothGatewayResult<T>>,
            operation: PendingOperation,
            timeoutMs: Long,
            timeoutCode: String,
            timeoutMessage: String
        ): BluetoothGatewayResult<T> {
            return try {
                val completed = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
                    result.await()
                }
                if (completed != null) {
                    completed
                } else {
                    closeAndReleaseOwner()
                    BluetoothGatewayResult.Failure(
                        code = timeoutCode,
                        message = timeoutMessage,
                        nextStep = "Reconnect the device and retry."
                    )
                }
            } catch (failure: CancellationException) {
                closeAndReleaseOwner()
                throw failure
            } finally {
                pending.compareAndSet(operation, null)
            }
        }

        private fun closeAndReleaseOwner() {
            close()
            onDisconnected(this)
        }

        private fun findCharacteristic(
            serviceUuid: String,
            characteristicUuid: String
        ): BluetoothGattCharacteristic? {
            val serviceId = runCatching { UUID.fromString(serviceUuid) }.getOrNull() ?: return null
            val characteristicId = runCatching { UUID.fromString(characteristicUuid) }.getOrNull()
                ?: return null
            return gattRef.get()?.getService(serviceId)?.getCharacteristic(characteristicId)
        }

        private fun missingCharacteristic(
            serviceUuid: String,
            characteristicUuid: String
        ): BluetoothGatewayResult.Failure {
            val serviceId = runCatching { UUID.fromString(serviceUuid) }.getOrNull()
            val service = serviceId?.let { gattRef.get()?.getService(it) }
            return if (service == null) {
                BluetoothGatewayResult.Failure(
                    code = "service_not_found",
                    message = "The requested GATT service was not found.",
                    nextStep = "Use ble_inspect and select a returned service UUID."
                )
            } else {
                BluetoothGatewayResult.Failure(
                    code = "characteristic_not_found",
                    message = "The requested GATT characteristic was not found.",
                    nextStep = "Use ble_inspect and select a characteristic under the requested service."
                )
            }
        }

        @Suppress("DEPRECATION")
        private fun startLegacyWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            bytes: ByteArray,
            platformWriteType: Int
        ): Boolean {
            characteristic.writeType = platformWriteType
            characteristic.value = bytes
            return gatt.writeCharacteristic(characteristic)
        }

        private fun <T> noSession(): BluetoothGatewayResult<T> =
            BluetoothGatewayResult.Failure(
                code = "no_active_connection",
                message = "No BLE GATT connection is active.",
                nextStep = "Connect a BLE device first."
            )

        private fun <T> permissionFailure(action: String): BluetoothGatewayResult<T> =
            BluetoothGatewayResult.Failure(
                code = "permission_required",
                message = "Bluetooth permission is required for $action.",
                nextStep = "Grant Bluetooth permission and retry."
            )

        private fun gattFailure(
            code: String,
            message: String,
            status: Int
        ): BluetoothGatewayResult.Failure =
            BluetoothGatewayResult.Failure(
                code = code,
                message = message,
                nextStep = "Check the connection and retry.",
                gattStatus = status
            )

        private sealed interface PendingOperation {
            data class Read(
                val characteristicUuid: UUID,
                val result: CompletableDeferred<BluetoothGatewayResult<ByteArray>>
            ) : PendingOperation

            data class Write(
                val characteristicUuid: UUID,
                val bytesWritten: Int,
                val writeType: BleWriteType,
                val result: CompletableDeferred<BluetoothGatewayResult<BleWriteResult>>
            ) : PendingOperation
        }
    }

    private companion object {
        const val DEFAULT_MTU = 23
        const val PREFERRED_MTU = 247
        const val ATT_HEADER_BYTES = 3
        const val MAX_SCAN_RESULTS = 50
        const val MAX_PROFILE_SERVICES = 64
        const val MAX_PROFILE_CHARACTERISTICS = 256
    }
}

private fun Int.toWireName(): String = when (this) {
    BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
    BluetoothDevice.DEVICE_TYPE_LE -> "ble"
    BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
    else -> "unknown"
}

private fun Int.toDomainProperties(): Set<BleCharacteristicProperty> = buildSet {
    if (this@toDomainProperties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
        add(BleCharacteristicProperty.READ)
    }
    if (this@toDomainProperties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
        add(BleCharacteristicProperty.WRITE)
    }
    if (this@toDomainProperties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
        add(BleCharacteristicProperty.WRITE_WITHOUT_RESPONSE)
    }
    if (this@toDomainProperties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
        add(BleCharacteristicProperty.NOTIFY)
    }
    if (this@toDomainProperties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
        add(BleCharacteristicProperty.INDICATE)
    }
}
