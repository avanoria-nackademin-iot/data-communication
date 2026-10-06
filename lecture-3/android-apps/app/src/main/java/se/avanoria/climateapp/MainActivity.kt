package se.avanoria.climateapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import java.util.Locale

private val Background = Color(0xFF0D151D)
private val CardBackground = Color(0xFF111D27)
private val CardBorder = Color(0xFF293B49)
private val Accent = Color(0xFF32D5BD)
private val PrimaryText = Color(0xFFF4F7FA)
private val SecondaryText = Color(0xFF92A9BF)
private val StopColor = Color(0xFFFF6B58)

class MainActivity : ComponentActivity() {
    private lateinit var model: ClimateViewModel

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasBluetoothPermissions()) {
            startScanOrEnableBluetooth()
        } else {
            model.notify("Godkänn Bluetooth-behörigheten för att söka efter sensorer.")
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) {
            model.startScan()
        } else {
            model.notify("Bluetooth måste vara aktiverat.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        model = ViewModelProvider(this)[ClimateViewModel::class.java]

        enableEdgeToEdge()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    onPrimary = Background,
                    background = Background,
                    onBackground = PrimaryText,
                    surface = CardBackground,
                    onSurface = PrimaryText,
                    onSurfaceVariant = SecondaryText
                )
            ) {
                ClimateApp(
                    model = model,
                    onScan = { requestScan() }
                )
            }
        }
    }

    private fun requiredBluetoothPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasBluetoothPermissions(): Boolean =
        requiredBluetoothPermissions().all {
            ContextCompat.checkSelfPermission(
                this,
                it
            ) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestScan() {
        if (hasBluetoothPermissions()) {
            startScanOrEnableBluetooth()
        } else {
            permissionsLauncher.launch(requiredBluetoothPermissions())
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanOrEnableBluetooth() {
        try {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter

            if (adapter == null) {
                model.notify("Telefonen saknar Bluetooth.")
            } else if (!adapter.isEnabled) {
                enableBluetoothLauncher.launch(
                    Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                )
            } else {
                model.startScan()
            }
        } catch (_: SecurityException) {
            model.notify("Bluetooth-behörighet saknas.")
        }
    }

    override fun onStop() {
        model.stopScan()
        super.onStop()
    }
}

@Composable
private fun ClimateApp(model: ClimateViewModel, onScan: () -> Unit) {
    var showResetDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = model.page != AppPage.Welcome) {
        model.back()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (model.page != AppPage.Welcome) {
                    TextButton(
                        onClick = { model.back() },
                        enabled = !model.busy
                    ) {
                        Text("←", fontSize = 26.sp)
                    }
                }

                Text(
                    text = when (model.page) {
                        AppPage.Welcome -> "Climate"
                        AppPage.Broker -> "Appens MQTT"
                        AppPage.SelectSensor -> "Välj sensor"
                        AppPage.ConfigureSensor -> "Konfigurera"
                        AppPage.Settings -> "Inställningar"
                        AppPage.Dashboard -> "Klimatsensor"
                    },
                    modifier = Modifier.weight(1f),
                    fontSize = 25.sp,
                    fontWeight = FontWeight.SemiBold
                )

                if (model.page == AppPage.Dashboard) {
                    TextButton(
                        onClick = { model.showSettings() },
                        enabled = !model.busy
                    ) {
                        Text("⚙", fontSize = 28.sp)
                    }
                }
            }

            if (model.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (model.message.isNotEmpty()) {
                Text(
                    text = model.message,
                    color = SecondaryText,
                    fontSize = 14.sp
                )
            }

            when (model.page) {
                AppPage.Welcome -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("°C", color = Accent, fontSize = 80.sp)

                        Text(
                            text = "Ditt klimat, i realtid",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(Modifier.height(16.dp))

                        Text(
                            text = "Konfigurera din sensor och följ temperatur och luftfuktighet.",
                            color = SecondaryText
                        )

                        Spacer(Modifier.height(32.dp))

                        ActionButton("Get Started", onClick = { model.getStarted() })
                    }
                }

                AppPage.Broker -> {
                    BrokerForm(
                        initial = model.appSettings,
                        busy = model.busy,
                        onSave = { model.saveAppSettings(it) }
                    )
                }

                AppPage.SelectSensor -> {
                    Text(
                        "Sök efter klimatsensorer i närheten.",
                        color = SecondaryText
                    )

                    ActionButton(
                        text = if (model.scanning) "Söker…" else "Sök sensorer",
                        enabled = !model.scanning && !model.busy,
                        onClick = onScan
                    )

                    if (model.scanning) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(model.devices, key = { it.address }) { device ->
                            OutlinedButton(
                                onClick = { model.selectSensor(device) },
                                enabled = !model.busy,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(20.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        device.name,
                                        color = PrimaryText,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    Text(
                                        device.address,
                                        color = SecondaryText,
                                        fontSize = 12.sp
                                    )

                                    Text(
                                        "${device.rssi} dBm",
                                        color = Accent
                                    )
                                }
                            }
                        }
                    }
                }

                AppPage.ConfigureSensor -> {
                    val sensor = model.sensorSettings

                    if (sensor != null) {
                        SensorForm(
                            initial = sensor,
                            busy = model.busy,
                            onSave = { model.saveSensor(it) }
                        )
                    } else {
                        Text(
                            "PIN-koden visas i sensorns Serial Monitor. Ange den när Android frågar.",
                            color = SecondaryText
                        )
                    }
                }

                AppPage.Settings -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Text(
                            model.sensorSettings?.deviceId ?: "Ingen sensor vald",
                            color = Accent,
                            fontSize = 22.sp
                        )

                        Text(
                            "Appens broker: ${model.appSettings.address}",
                            color = SecondaryText
                        )

                        Text(
                            if (model.connected) "MQTT: Ansluten" else "MQTT: Frånkopplad"
                        )

                        Text(
                            "Sensor: ${onlineText(model.sensorOnline)}",
                            color = SecondaryText
                        )

                        ActionButton(
                            text = if (model.connected) "Disconnect" else "Connect",
                            enabled = !model.busy && model.sensorSettings != null,
                            onClick = {
                                if (model.connected) {
                                    model.disconnect()
                                } else {
                                    model.connectMqtt()
                                }
                            }
                        )

                        if (model.connected) {
                            ActionButton(
                                text = "Visa mätvärden",
                                enabled = !model.busy,
                                onClick = { model.showDashboard() }
                            )
                        }

                        OutlinedButton(
                            onClick = { model.showBrokerSettings() },
                            enabled = !model.busy && !model.connected,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Ändra appens MQTT-inställningar")
                        }

                        OutlinedButton(
                            onClick = { model.disconnect() },
                            enabled = !model.busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Välj sensor igen")
                        }

                        TextButton(
                            onClick = { showResetDialog = true },
                            enabled = !model.busy
                        ) {
                            Text("Återställ appen", color = StopColor)
                        }
                    }
                }

                AppPage.Dashboard -> {
                    Dashboard(model)
                }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Återställ appen?") },
            text = {
                Text(
                    "Appens MQTT-inställningar tas bort och wizarden börjar om. Sensorns sparade inställningar behålls."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        model.reset()
                    }
                ) {
                    Text("Återställ", color = StopColor)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Avbryt")
                }
            }
        )
    }
}

@Composable
private fun BrokerForm(
    initial: AppMqttSettings,
    busy: Boolean,
    onSave: (AppMqttSettings) -> Unit
) {
    var draft by remember(initial) { mutableStateOf(initial) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Dessa uppgifter används av telefonen för att ansluta till MQTT-brokern.",
            color = SecondaryText
        )

        FormField(
            label = "MQTT-adress",
            value = draft.address,
            enabled = !busy,
            onChange = { draft = draft.copy(address = it) }
        )

        Text(
            "Exempel: mqtt://192.168.1.171:1883",
            color = SecondaryText,
            fontSize = 12.sp
        )

        FormField(
            label = "MQTT-användarnamn",
            value = draft.username,
            enabled = !busy,
            onChange = { draft = draft.copy(username = it) }
        )

        FormField(
            label = "MQTT-lösenord",
            value = draft.password,
            password = true,
            enabled = !busy,
            onChange = { draft = draft.copy(password = it) }
        )

        ActionButton(
            text = "Spara och fortsätt",
            enabled = !busy && draft.address.isNotBlank(),
            onClick = { onSave(draft) }
        )
    }
}

@Composable
private fun SensorForm(
    initial: SensorSettings,
    busy: Boolean,
    onSave: (SensorSettings) -> Unit
) {
    var draft by remember(initial) { mutableStateOf(initial) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            initial.deviceId,
            color = Accent,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold
        )

        Text("Sensorns Wi-Fi", color = Accent)

        FormField(
            label = "SSID",
            value = draft.wifiSsid,
            enabled = !busy,
            onChange = { draft = draft.copy(wifiSsid = it) }
        )

        FormField(
            label = "Wi-Fi-lösenord",
            value = draft.wifiPassword,
            password = true,
            enabled = !busy,
            onChange = { draft = draft.copy(wifiPassword = it) }
        )

        Text("Sensorns MQTT", color = Accent)

        FormField(
            label = "MQTT-värd",
            value = draft.mqttHost,
            enabled = !busy,
            onChange = { draft = draft.copy(mqttHost = it) }
        )

        Text(
            "IP- eller DNS-namn utan mqtt://. Sensorn använder port 1883.",
            color = SecondaryText,
            fontSize = 12.sp
        )

        FormField(
            label = "MQTT-användarnamn",
            value = draft.mqttUsername,
            enabled = !busy,
            onChange = { draft = draft.copy(mqttUsername = it) }
        )

        FormField(
            label = "MQTT-lösenord",
            value = draft.mqttPassword,
            password = true,
            enabled = !busy,
            onChange = { draft = draft.copy(mqttPassword = it) }
        )

        ActionButton(
            text = "Spara på sensorn",
            enabled = !busy,
            onClick = { onSave(draft) }
        )
    }
}

@Composable
private fun ColumnScope.Dashboard(model: ClimateViewModel) {
    val temperature = model.reading?.find("temperature")
    val humidity = model.reading?.find("humidity")

    Text(
        text = model.sensorSettings?.deviceId ?: "",
        color = SecondaryText
    )

    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        MeasurementCard(
            title = "Temperatur",
            measurement = temperature,
            defaultUnit = "°C"
        )

        MeasurementCard(
            title = "Luftfuktighet",
            measurement = humidity,
            defaultUnit = "%"
        )
    }

    Text(
        text = "Sensor: ${onlineText(model.sensorOnline)}",
        color = if (model.sensorOnline == true) Accent else SecondaryText
    )

    Text(
        text = when (model.sending) {
            true -> "Sensorn skickar data"
            false -> "Sensorns sändning är stoppad"
            null -> "Väntar på sensorns sändningsstatus"
        },
        color = SecondaryText
    )

    Button(
        onClick = { model.toggleSending() },
        enabled = model.connected &&
                model.sensorOnline == true &&
                model.sending != null &&
                !model.busy,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (model.sending == true) StopColor else Accent,
            contentColor = Background
        )
    ) {
        Text(
            text = when (model.sending) {
                true -> "Stoppa sändning"
                false -> "Starta sändning"
                null -> "Väntar på status…"
            },
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun onlineText(online: Boolean?): String =
    when (online) {
        true -> "Online"
        false -> "Offline"
        null -> "Okänd status"
    }

@Composable
private fun MeasurementCard(
    title: String,
    measurement: Measurement?,
    defaultUnit: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CardBackground,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, color = SecondaryText, fontSize = 18.sp)

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = measurement?.let {
                        String.format(
                            Locale.forLanguageTag("sv-SE"),
                            "%.1f",
                            it.value
                        )
                    } ?: "—",
                    fontSize = 58.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = measurement?.unit ?: defaultUnit,
                    modifier = Modifier.padding(bottom = 8.dp),
                    color = SecondaryText,
                    fontSize = 28.sp
                )
            }
        }
    }
}

@Composable
private fun FormField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean = true,
    password: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        visualTransformation = if (password) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        }
    )
}

@Composable
private fun ActionButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            text,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}