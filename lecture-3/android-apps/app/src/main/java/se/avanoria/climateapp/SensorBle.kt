package se.avanoria.climateapp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ktx.suspend
import java.util.UUID

object SensorProfile {
    val service: UUID =
        UUID.fromString("7b1e0001-6b4a-4a9d-8f71-9e6700000001")

    val deviceId: UUID = characteristic("0002")
    val wifiSsid: UUID = characteristic("0003")
    val mqttHost: UUID = characteristic("0004")
    val mqttUsername: UUID = characteristic("0005")
    val mqttPassword: UUID = characteristic("0006")
    val command: UUID = characteristic("0007")
    val result: UUID = characteristic("0008")
    val wifiPassword: UUID = characteristic("000a")

    val all = listOf(
        deviceId,
        wifiSsid,
        wifiPassword,
        mqttHost,
        mqttUsername,
        mqttPassword,
        command,
        result
    )

    private fun characteristic(suffix: String): UUID =
        UUID.fromString("7b1e0001-6b4a-4a9d-8f71-9e670000$suffix")
}

@SuppressLint("MissingPermission")
class SensorBle(context: Context) : BleManager(context) {
    private val characteristics =
        mutableMapOf<UUID, BluetoothGattCharacteristic>()

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        characteristics.clear()

        val service = gatt.getService(SensorProfile.service)
            ?: return false

        SensorProfile.all.forEach { uuid ->
            val characteristic = service.getCharacteristic(uuid)
                ?: return false

            characteristics[uuid] = characteristic
        }

        return true
    }

    override fun initialize() {
        requestMtu(517).enqueue()
    }

    override fun onServicesInvalidated() {
        characteristics.clear()
    }

    suspend fun connectAndRead(device: BluetoothDevice): SensorSettings {
        connect(device)
            .useAutoConnect(false)
            .retry(2, 400)
            .timeout(20_000)
            .suspend()

        // Android visar parkopplingsdialogen när det behövs.
        withTimeout(120_000) {
            createBond().suspend()
        }

        return withTimeout(30_000) {
            SensorSettings(
                deviceId = readText(SensorProfile.deviceId),
                wifiSsid = readText(SensorProfile.wifiSsid),
                wifiPassword = readText(SensorProfile.wifiPassword),
                mqttHost = readText(SensorProfile.mqttHost),
                mqttUsername = readText(SensorProfile.mqttUsername),
                mqttPassword = readText(SensorProfile.mqttPassword)
            )
        }
    }

    private suspend fun readText(uuid: UUID): String {
        val characteristic = checkNotNull(characteristics[uuid]) {
            "Bluetooth-anslutningen är inte tillgänglig."
        }

        val data = readCharacteristic(characteristic).suspend()
        return data.value?.toString(Charsets.UTF_8) ?: ""
    }

    private suspend fun writeText(uuid: UUID, value: String) {
        val characteristic = checkNotNull(characteristics[uuid]) {
            "Bluetooth-anslutningen är inte tillgänglig."
        }

        writeCharacteristic(
            characteristic,
            value.toByteArray(Charsets.UTF_8),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ).suspend()
    }

    private fun validateText(label: String, value: String, maximumBytes: Int) {
        val size = value.toByteArray(Charsets.UTF_8).size

        require('\u0000' !in value && size <= maximumBytes) {
            "$label får innehålla högst $maximumBytes UTF-8-byte."
        }

        require(size <= getMtu() - 3) {
            "$label är för långt för den förhandlade BLE-anslutningen."
        }
    }

    suspend fun saveAndApply(settings: SensorSettings) {
        require(settings.wifiSsid.isNotEmpty()) {
            "Ange sensorns Wi-Fi SSID."
        }

        require(
            settings.mqttHost.isNotBlank() &&
                    settings.mqttHost.none { it.isWhitespace() || it == ':' || it == '/' }
        ) {
            "Sensorns MQTT-adress ska vara ett IP- eller DNS-namn utan port."
        }

        validateText("SSID", settings.wifiSsid, 32)
        validateText("Wi-Fi-lösenord", settings.wifiPassword, 64)
        validateText("MQTT-adress", settings.mqttHost, 253)
        validateText("MQTT-användarnamn", settings.mqttUsername, 128)
        validateText("MQTT-lösenord", settings.mqttPassword, 128)

        withTimeout(30_000) {
            writeText(SensorProfile.wifiSsid, settings.wifiSsid)
            writeText(SensorProfile.wifiPassword, settings.wifiPassword)
            writeText(SensorProfile.mqttHost, settings.mqttHost)
            writeText(SensorProfile.mqttUsername, settings.mqttUsername)
            writeText(SensorProfile.mqttPassword, settings.mqttPassword)

            writeText(SensorProfile.command, "save")

            awaitSaved()

            writeText(SensorProfile.command, "apply")
        }

        // Sensorn startar om 1,5 sekunder efter apply.
        delay(300)
    }

    private suspend fun awaitSaved() {
        withTimeout(10_000) {
            while (true) {
                val response = readText(SensorProfile.result)

                if (response == "saved") {
                    return@withTimeout
                }

                if (response.startsWith("error:")) {
                    error("Sensorn svarade: $response")
                }

                delay(200)
            }
        }
    }
}