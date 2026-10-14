package io.github.pgodlews.heymuse

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import heymuse.Heymuse
import heymuse.Peripheral
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The Muse setup service on the Portal's own Bluetooth, for the Muse app to pair over: what the
 * Linux SDK serves through BlueZ. The pairing conversation itself is the Go core's
 * (heymuse.App.startPairing); this only carries its packets.
 *
 * The advertisement holds the service UUID and the "not paired" manufacturer data, and the scan
 * response the name. The name is the adapter's, so it is set for the pairing and put back after.
 */
class BlePeripheral(
    private val context: Context,
    /** The phone's writes to RX, whole, in order. */
    private val onWrite: (ByteArray) -> Unit,
    /** The phone dropping. */
    private val onDisconnect: () -> Unit,
) : Peripheral {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager?.adapter

    private var server: BluetoothGattServer? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var savedName: String? = null
    private val phone = AtomicReference<BluetoothDevice?>()
    @Volatile private var mtu = Heymuse.AssumedMTU.toInt()
    @Volatile private var sent: CountDownLatch? = null

    private val advertising = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "advertising")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "advertising failed: $errorCode")
            advertiseError = errorCode
            advertiseResult.countDown()
        }
    }
    @Volatile private var advertiseError = 0
    private var advertiseResult = CountDownLatch(1)

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "a phone connected")
                phone.set(device)
                mtu = Heymuse.AssumedMTU.toInt()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (phone.compareAndSet(device, null)) {
                    Log.i(TAG, "the phone left")
                    sent?.countDown()
                    onDisconnect()
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, newMtu: Int) {
            Log.i(TAG, "MTU $newMtu")
            mtu = newMtu
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val ours = characteristic.uuid == RX
            if (responseNeeded) {
                server?.sendResponse(
                    device, requestId,
                    if (ours) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_WRITE_NOT_PERMITTED, 0, null
                )
            }
            if (ours && value != null) {
                phone.compareAndSet(null, device)
                onWrite(value)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value ?: ByteArray(0)
            val part = if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, part)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (descriptor.uuid == CCCD) descriptor.value = value
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor,
        ) {
            server?.sendResponse(
                device, requestId, BluetoothGatt.GATT_SUCCESS, 0,
                descriptor.value ?: BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            )
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            sent?.countDown()
        }
    }

    override fun open(name: String) {
        val adapter = adapter ?: throw IllegalStateException("this Portal has no Bluetooth")
        if (!adapter.isEnabled) {
            Log.i(TAG, "turning Bluetooth on")
            @Suppress("DEPRECATION")
            adapter.enable()
            val until = System.currentTimeMillis() + 10_000
            while (!adapter.isEnabled && System.currentTimeMillis() < until) Thread.sleep(200)
            if (!adapter.isEnabled) throw IllegalStateException("Bluetooth is off; turn it on in the Portal's settings")
        }
        val advertiser = adapter.bluetoothLeAdvertiser
            ?: throw IllegalStateException("this Portal's Bluetooth cannot advertise")

        val gatt = manager.openGattServer(context, callback)
            ?: throw IllegalStateException("could not open a GATT server")
        val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(
            RX,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val txc = BluetoothGattCharacteristic(
            TX,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        txc.addDescriptor(
            BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
        )
        service.addCharacteristic(rx)
        service.addCharacteristic(txc)
        if (!gatt.addService(service)) {
            gatt.close()
            throw IllegalStateException("could not add the setup service")
        }
        server = gatt
        tx = txc

        savedName = adapter.name
        adapter.name = name

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE))
            .addManufacturerData(Heymuse.ManufacturerID.toInt(), byteArrayOf(Heymuse.ManufacturerData.toByte()))
            .setIncludeDeviceName(false)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        advertiseResult = CountDownLatch(1)
        advertiseError = 0
        advertiser.startAdvertising(settings, data, scanResponse, advertising)
        // A failure is reported at once; success says nothing more that matters here.
        if (advertiseResult.await(1500, TimeUnit.MILLISECONDS) && advertiseError != 0) {
            close()
            throw IllegalStateException("Bluetooth refused to advertise (error $advertiseError)")
        }
        Log.i(TAG, "on the air as $name")
    }

    override fun sendPacket(packet: ByteArray) {
        val device = phone.get() ?: throw IllegalStateException("no phone connected")
        val gatt = server ?: throw IllegalStateException("not open")
        val c = tx ?: throw IllegalStateException("not open")
        val latch = CountDownLatch(1)
        sent = latch
        c.value = packet
        if (!gatt.notifyCharacteristicChanged(device, c, false)) {
            sent = null
            throw IllegalStateException("the notification was refused")
        }
        // The next packet waits for the stack to have sent this one.
        if (!latch.await(2, TimeUnit.SECONDS)) Log.w(TAG, "a notification was not confirmed")
        sent = null
    }

    override fun mtu(): Long = mtu.toLong()

    override fun disconnect() {
        val device = phone.getAndSet(null) ?: return
        server?.cancelConnection(device)
    }

    override fun close() {
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertising) }
        phone.getAndSet(null)?.let { d -> runCatching { server?.cancelConnection(d) } }
        runCatching { server?.close() }
        server = null
        tx = null
        savedName?.let { n -> runCatching { adapter?.name = n } }
        savedName = null
        Log.i(TAG, "off the air")
    }

    companion object {
        private const val TAG = "HeyMuseBle"
        val SERVICE: UUID = UUID.fromString(Heymuse.ServiceUUID)
        val RX: UUID = UUID.fromString(Heymuse.RXUUID)
        val TX: UUID = UUID.fromString(Heymuse.TXUUID)
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
