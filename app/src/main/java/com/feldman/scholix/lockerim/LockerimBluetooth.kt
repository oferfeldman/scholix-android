package com.feldman.scholix.lockerim

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import com.feldman.scholix.api.platforms.LockerimPlatform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One user-initiated operation; its scan and GATT connection are always closed. */
@SuppressLint("MissingPermission")
internal class LockerimBluetooth(private val context: Context) {
    private val service = UUID.fromString("6e406060-b5a3-f393-e0a9-e50e24dcca9e")
    private val read = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    private val write = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    suspend fun operate(account: LockerimPlatform, locker: JSONObject, unlock: Boolean) = withTimeout(45_000) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("This device does not support Bluetooth.")
        if (!adapter.isEnabled) throw IOException("Turn on Bluetooth and try again.")
        val mac = locker.optString("macAddress")
        require(mac.isNotBlank()) { "This locker has no Bluetooth address." }
        val device = withTimeout(15_000) { findDevice(adapter, mac) }
        val connected = CompletableDeferred<Unit>()
        val services = CompletableDeferred<Unit>()
        val notifications = CompletableDeferred<Unit>()
        val received = Channel<ByteArray>(Channel.UNLIMITED)
        val writes = Channel<Int>(Channel.BUFFERED)
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) connected.complete(Unit)
                else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    val error = IOException("The locker disconnected ($status). Move closer and try again.")
                    connected.completeExceptionally(error)
                    services.completeExceptionally(error)
                    notifications.completeExceptionally(error)
                    received.close(error)
                    writes.close(error)
                }
            }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) services.complete(Unit)
                else services.completeExceptionally(IOException("Could not read the locker services ($status)."))
            }
            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) notifications.complete(Unit)
                else notifications.completeExceptionally(IOException("Could not listen to the locker ($status)."))
            }
            @Deprecated("Used on Android 11 and 12")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < 33) received.trySend(characteristic.value.copyOf())
            }
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                if (characteristic.uuid == read) received.trySend(value.copyOf())
            }
            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                writes.trySend(status)
            }
        }
        val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("Could not connect to the locker.")
        try {
            withTimeout(10_000) { connected.await() }
            check(gatt.discoverServices()) { "Could not discover the locker services." }
            withTimeout(5_000) { services.await() }
            val uart = gatt.getService(service) ?: throw IOException("Unsupported locker Bluetooth service.")
            val rx = uart.getCharacteristic(read) ?: throw IOException("Missing locker notification channel.")
            val tx = uart.getCharacteristic(write) ?: throw IOException("Missing locker command channel.")
            check(gatt.setCharacteristicNotification(rx, true)) { "Could not enable locker notifications." }
            val descriptor = rx.getDescriptor(cccd) ?: throw IOException("Missing locker notification descriptor.")
            if (Build.VERSION.SDK_INT >= 33) {
                check(gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                check(gatt.writeDescriptor(descriptor))
            }
            withTimeout(5_000) { notifications.await() }

            suspend fun send(packet: ByteArray) {
                for (chunk in packet.toList().chunked(20)) {
                    val bytes = chunk.toByteArray()
                    if (Build.VERSION.SDK_INT >= 33) {
                        check(gatt.writeCharacteristic(tx, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS)
                    } else {
                        @Suppress("DEPRECATION")
                        tx.value = bytes
                        tx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        @Suppress("DEPRECATION")
                        check(gatt.writeCharacteristic(tx))
                    }
                    val status = withTimeout(5_000) { writes.receive() }
                    if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("Could not send the locker command ($status).")
                }
            }

            val skda = locker.getString("skda")
            if (!locker.optBoolean("isRecognized")) {
                val ec = locker.getString("ec")
                send(LockerimProtocol.enrollment(ec))
                val report = JSONObject().put("subscriptionId", locker.get("subscriptionId"))
                    .put("macAddress", mac).put("ec", ec)
                withTimeout(5_000) {
                    while (true) {
                        val frame = received.receive()
                        LockerimProtocol.error(frame)?.let { throw IOException(it) }
                        if (frame.firstOrNull()?.toInt() == '^'.code) {
                            val plain = LockerimProtocol.decrypt(frame, skda)
                            report.put("pemType", plain.substring(6, 8)).put("KeypadPassword", plain.substring(8, 12))
                                .put("otpId", plain.substring(12, 18).toLong(16)).put("keypadOtpPassword", plain.substring(18, 22))
                                .put("fullRecognitionResponse", plain)
                        }
                        if (frame.firstOrNull()?.toInt() == '%'.code) {
                            report.put("remoteControlData", LockerimProtocol.decrypt(frame, skda))
                            break
                        }
                    }
                }
                account.request("Subscription/ECRecognition", report)
                locker.put("isRecognized", true)
            }
            val claims = JSONObject(String(Base64.getUrlDecoder().decode(account.token.split('.')[1]), Charsets.UTF_8))
            val userId = claims.getString("UserECId").toLong()
            val prefs = context.getSharedPreferences("lockerim_counters", Context.MODE_PRIVATE)
            val counterKey = "$skda-$userId"
            val counter = prefs.getInt(counterKey, 1)
            val command = LockerimProtocol.command(skda, userId, counter, unlock)
            // Reserve before transmission, including failed attempts, as the official client does.
            check(prefs.edit().putInt(counterKey, counter + 1).commit()) { "Could not save the locker counter." }
            send(command)
            withTimeout(5_000) {
                while (true) {
                    val response = received.receive()
                    val text = response.toString(Charsets.US_ASCII)
                    if (text.startsWith("<PIC") && response.size >= 6) {
                        val next = ((response[4].toInt() and 255) shl 8) + (response[5].toInt() and 255) + 1
                        prefs.edit().putInt(counterKey, next).commit()
                    }
                    LockerimProtocol.error(response)?.let { throw IOException(it) }
                    if (text == if (unlock) "<PUS>" else "<PLS>") break
                }
            }
        } finally {
            gatt.disconnect()
            gatt.close()
            received.cancel()
            writes.cancel()
        }
    }

    private suspend fun findDevice(adapter: BluetoothAdapter, mac: String): BluetoothDevice = suspendCancellableCoroutine { continuation ->
        val scanner = adapter.bluetoothLeScanner ?: throw IOException("Turn on Bluetooth and try again.")
        val expectedName = "PEM${mac.replace(":", "")}"
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device.address.equals(mac, true) || result.scanRecord?.deviceName.equals(expectedName, true)) {
                    scanner.stopScan(this)
                    if (continuation.isActive) continuation.resume(result.device)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                if (continuation.isActive) continuation.resumeWithException(IOException("Bluetooth scan failed ($errorCode)."))
            }
        }
        scanner.startScan(callback)
        continuation.invokeOnCancellation { scanner.stopScan(callback) }
    }
}
