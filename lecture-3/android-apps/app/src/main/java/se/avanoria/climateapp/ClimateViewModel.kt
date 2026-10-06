package se.avanoria.climateapp

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@SuppressLint("MissingPermission")
class ClimateViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SettingsStore(application)
    private val handler = Handler(Looper.getMainLooper())

    var page by mutableStateOf(AppPage.Welcome)
        private set

    var appSettings by mutableStateOf(AppMqttSettings())
        private set

    private var appConfigured = false

    var sensorSettings by mutableStateOf<SensorSettings?>(null)
        private set

    var devices by mutableStateOf<List<SensorDevice>>(emptyList())
        private set

    var scanning by mutableStateOf(false)
        private set

    var busy by mutableStateOf(false)
        private set

    var connected by mutableStateOf(false)
        private set

    var sensorOnline by mutableStateOf<Boolean?>(null)
        private set

    var sending by mutableStateOf<Boolean?>(null)
        private set

    var reading by mutableStateOf<ClimateReading?>(null)
        private set

    var message by mutableStateOf("")
        private set

    private var scanner: BluetoothLeScanner? = null
    private var scanJob: Job? = null
    private var ble: SensorBle? = null

    private val mqtt = MqttReceiver(
        onReading = { reading = it },
        onSending = { sending = it },
        onOnline = { sensorOnline = it },
        onDisconnected = {
            connected = false
            sensorOnline = null
            sending = null
            message = "MQTT-anslutningen bröts: $it"
        },
        onError = { message = it }
    )

    init {
        try {
            val saved = store.load()

            if (saved != null) {
                appSettings = saved
                appConfigured = true
            }
        } catch (_: Exception) {
            message = "Sparade appinställningar kunde inte läsas. Ange dem igen."
        }
    }

    fun getStarted() {
        page = if (appConfigured) {
            AppPage.SelectSensor
        } else {
            AppPage.Broker
        }
    }

    fun saveAppSettings(settings: AppMqttSettings) {
        perform {
            parseMqttEndpoint(settings.address)

            withContext(Dispatchers.IO) {
                store.save(settings)
            }

            appSettings = settings
            appConfigured = true
            page = AppPage.SelectSensor
            message = "Appens MQTT-inställningar är sparade."
        }
    }

    fun showBrokerSettings() {
        if (busy || connected) return
        page = AppPage.Broker
        message = ""
    }

    fun showSettings() {
        if (!busy) {
            page = AppPage.Settings
        }
    }

    fun showDashboard() {
        if (connected && !busy) {
            page = AppPage.Dashboard
        }
    }

    fun notify(message: String) {
        this.message = message
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post {
                if (!scanning) return@post

                try {
                    val found = SensorDevice(
                        name = result.scanRecord?.deviceName
                            ?: result.device.name
                            ?: "Klimatsensor",
                        address = result.device.address,
                        rssi = result.rssi
                    )

                    devices = (
                            devices.filterNot { it.address == found.address } + found
                            ).sortedBy { it.name }
                } catch (_: SecurityException) {
                    stopScan()
                    message = "Bluetooth-behörighet saknas."
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                stopScan()
                message = "Bluetooth-sökningen misslyckades. Felkod: $errorCode"
            }
        }
    }

    fun startScan() {
        if (busy || scanning) return

        try {
            val adapter = getApplication<Application>()
                .getSystemService(BluetoothManager::class.java)
                ?.adapter

            val next = adapter?.bluetoothLeScanner

            check(next != null && adapter.isEnabled) {
                "Bluetooth måste vara aktiverat."
            }

            devices = emptyList()
            scanner = next
            scanning = true
            message = "Söker efter klimatsensorer…"

            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SensorProfile.service))
                .build()

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            next.startScan(listOf(filter), settings, scanCallback)

            scanJob = viewModelScope.launch {
                delay(10_000)
                stopScan()

                message = if (devices.isEmpty()) {
                    "Ingen sensor hittades. Kontrollera att sensorn är igång."
                } else {
                    "Välj sensorn du vill konfigurera."
                }
            }
        } catch (error: Exception) {
            stopScan()
            message = error.message ?: "Kunde inte starta Bluetooth-sökningen."
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        scanning = false

        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {
            // Bluetooth kan ha stängts av eller behörigheten återkallats.
        }

        scanner = null
    }

    fun selectSensor(sensor: SensorDevice) {
        if (busy) return

        stopScan()
        closeBle()
        sensorSettings = null
        page = AppPage.ConfigureSensor

        perform(
            onFailure = {
                closeBle()
                page = AppPage.SelectSensor
            }
        ) {
            message = "Ansluter till ${sensor.name}. Godkänn parkopplingen."

            val manager = SensorBle(getApplication())
            ble = manager

            val adapter = getApplication<Application>()
                .getSystemService(BluetoothManager::class.java)
                ?.adapter

            checkNotNull(adapter) {
                "Bluetooth är inte tillgängligt."
            }

            sensorSettings = manager.connectAndRead(
                adapter.getRemoteDevice(sensor.address)
            )

            message = "Sensorns inställningar är hämtade."
        }
    }

    fun saveSensor(settings: SensorSettings) {
        perform(
            onFailure = {
                closeBle()
                page = AppPage.SelectSensor
            }
        ) {
            val manager = checkNotNull(ble) {
                "Välj sensorn igen."
            }

            message = "Sparar inställningar på sensorn…"
            manager.saveAndApply(settings)

            sensorSettings = settings
            closeBle()

            page = AppPage.Settings
            message = "Inställningarna är sparade. Sensorn startar om."
        }
    }

    fun connectMqtt() {
        if (connected) return

        perform(onFailure = { mqtt.disconnect() }) {
            val sensor = checkNotNull(sensorSettings) {
                "Välj och konfigurera en sensor först."
            }

            reading = null
            sensorOnline = null
            sending = null
            message = "Ansluter appen till MQTT…"

            withTimeout(25_000) {
                mqtt.connect(appSettings, sensor.deviceId)
            }

            connected = true
            message = "Ansluten till MQTT."
        }
    }

    fun toggleSending() {
        if (!connected || sensorOnline != true) return
        val current = sending ?: return

        perform {
            withTimeout(10_000) {
                mqtt.setSending(!current)
            }

            // Visad status uppdateras först när sensorn bekräftar via MQTT.
            message = "Kommandot skickat till sensorn."
        }
    }

    fun disconnect() {
        if (busy) return

        mqtt.disconnect()
        closeBle()
        stopScan()

        connected = false
        sensorOnline = null
        sending = null
        reading = null
        sensorSettings = null
        devices = emptyList()

        page = AppPage.SelectSensor
        message = "Välj en sensor."
    }

    fun reset() {
        perform {
            mqtt.disconnect()
            closeBle()
            stopScan()

            withContext(Dispatchers.IO) {
                store.clear()
            }

            connected = false
            appConfigured = false
            appSettings = AppMqttSettings()
            sensorSettings = null
            sensorOnline = null
            sending = null
            reading = null
            devices = emptyList()

            page = AppPage.Welcome
            message = ""
        }
    }

    fun back() {
        if (busy) return

        when (page) {
            AppPage.Dashboard -> page = AppPage.Settings

            AppPage.Settings -> disconnect()

            AppPage.ConfigureSensor -> {
                closeBle()
                sensorSettings = null
                page = AppPage.SelectSensor
                message = ""
            }

            AppPage.SelectSensor -> {
                stopScan()
                page = AppPage.Welcome
                message = ""
            }

            AppPage.Broker -> page = AppPage.Welcome

            AppPage.Welcome -> Unit
        }
    }

    private fun closeBle() {
        ble?.close()
        ble = null
    }

    private fun perform(
        onFailure: () -> Unit = {},
        action: suspend () -> Unit
    ) {
        if (busy) return

        busy = true

        viewModelScope.launch {
            try {
                action()
            } catch (_: TimeoutCancellationException) {
                onFailure()
                message = "Åtgärden tog för lång tid. Försök igen."
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onFailure()
                message = error.message ?: "Åtgärden misslyckades."
            } finally {
                busy = false
            }
        }
    }

    override fun onCleared() {
        stopScan()
        closeBle()
        mqtt.disconnect()
        super.onCleared()
    }
}