package se.avanoria.climateapp

import java.net.URI

enum class AppPage {
    Welcome,
    Broker,
    SelectSensor,
    ConfigureSensor,
    Settings,
    Dashboard
}

data class AppMqttSettings(
    val address: String = "",
    val username: String = "",
    val password: String = ""
)

data class SensorSettings(
    val deviceId: String = "",
    val wifiSsid: String = "",
    val wifiPassword: String = "",
    val mqttHost: String = "",
    val mqttUsername: String = "",
    val mqttPassword: String = ""
)

data class SensorDevice(
    val name: String,
    val address: String,
    val rssi: Int
)

data class Measurement(
    val type: String,
    val value: Double,
    val unit: String
)

data class ClimateReading(val deviceId: String, val measurements: List<Measurement>) {
    fun find(type: String): Measurement? = measurements.firstOrNull { it.type == type }
}

data class MqttEndpoint(
    val host: String,
    val port: Int,
    val tls: Boolean
)

fun parseMqttEndpoint(address: String): MqttEndpoint {
    val input = address.trim()

    require(input.isNotEmpty()) {
        "Ange MQTT-adressen."
    }

    val uri = URI(
        if (input.contains("://")) input else "mqtt://$input"
    )

    require(uri.scheme in listOf("mqtt", "mqtts")) {
        "Använd mqtt:// eller mqtts://."
    }

    val host = uri.host

    require(!host.isNullOrBlank()) {
        "MQTT-adressen saknar ett giltigt värdnamn."
    }

    require(
        uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                uri.rawPath.isNullOrEmpty()
    ) {
        "Ange bara MQTT-värd och eventuell port."
    }

    val tls = uri.scheme == "mqtts"
    val port = if (uri.port == -1) {
        if (tls) 8883 else 1883
    } else {
        uri.port
    }

    require(port in 1..65535) {
        "MQTT-porten måste vara mellan 1 och 65535."
    }

    return MqttEndpoint(host, port, tls)
}