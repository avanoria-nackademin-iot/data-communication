package se.avanoria.climateapp

import android.os.Handler
import android.os.Looper
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.subscribe.suback.Mqtt3SubAckReturnCode
import kotlinx.coroutines.future.await
import org.json.JSONObject
import java.util.UUID

class MqttReceiver(
    private val onReading: (ClimateReading) -> Unit,
    private val onSending: (Boolean) -> Unit,
    private val onOnline: (Boolean) -> Unit,
    private val onDisconnected: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var client: Mqtt3AsyncClient? = null

    private var selectedDeviceId = ""

    suspend fun connect(settings: AppMqttSettings, deviceId: String) {
        disconnect()

        val endpoint = parseMqttEndpoint(settings.address)
        selectedDeviceId = deviceId

        lateinit var next: Mqtt3AsyncClient

        val builder = MqttClient.builder()
            .useMqttVersion3()
            .identifier("climate-app-${UUID.randomUUID()}")
            .serverHost(endpoint.host)
            .serverPort(endpoint.port)
            .addDisconnectedListener { context ->
                handler.post {
                    if (client === next) {
                        client = null
                        onDisconnected(
                            context.cause.message ?: "MQTT-anslutningen bröts."
                        )
                    }
                }
            }

        if (endpoint.tls) {
            builder.sslWithDefaultConfig()
        }

        next = builder.buildAsync()
        client = next

        val request = next.connectWith()
            .cleanSession(true)
            .keepAlive(30)

        if (settings.username.isNotEmpty()) {
            request.simpleAuth()
                .username(settings.username)
                .password(settings.password.toByteArray(Charsets.UTF_8))
                .applySimpleAuth()
        }

        val connection = request.send()

        // Stäng även en anslutning som blir klar efter att användaren avbrutit.
        connection.whenComplete { _, error ->
            if (error == null && client !== next) {
                next.disconnect()
            }
        }

        connection.await()

        check(client === next) {
            "MQTT-anslutningen avbröts."
        }

        subscribe(next, "climate/temperature") { payload ->
            val json = JSONObject(payload)

            if (json.optString("deviceId") == deviceId) {
                onReading(parseReading(json))
            }
        }

        subscribe(next, "devices/$deviceId/sending") { payload ->
            when (payload.trim()) {
                "started" -> onSending(true)
                "stopped" -> onSending(false)
            }
        }

        subscribe(next, "devices/$deviceId/status") { payload ->
            when (payload.trim()) {
                "online" -> onOnline(true)
                "offline" -> onOnline(false)
            }
        }

        check(client === next && next.state.isConnected) {
            "MQTT-anslutningen bröts."
        }
    }

    private suspend fun subscribe(
        mqtt: Mqtt3AsyncClient,
        topic: String,
        receive: (String) -> Unit
    ) {
        val acknowledgement = mqtt.subscribeWith()
            .topicFilter(topic)
            .qos(MqttQos.AT_LEAST_ONCE)
            .callback { publish ->
                val payload = publish.payloadAsBytes.toString(Charsets.UTF_8)

                handler.post {
                    if (client === mqtt) {
                        try {
                            receive(payload)
                        } catch (error: Exception) {
                            onError("Felaktigt MQTT-meddelande: ${error.message}")
                        }
                    }
                }
            }
            .send()
            .await()

        check(
            acknowledgement.returnCodes.none {
                it == Mqtt3SubAckReturnCode.FAILURE
            }
        ) {
            "Brokern nekade prenumeration på $topic."
        }
    }

    suspend fun setSending(sending: Boolean) {
        val mqtt = checkNotNull(client) {
            "Appen är inte ansluten till MQTT."
        }

        check(mqtt.state.isConnected) {
            "MQTT-anslutningen är inte aktiv."
        }

        mqtt.publishWith()
            .topic("devices/$selectedDeviceId/commands")
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(false)
            .payload(
                (if (sending) "start" else "stop")
                    .toByteArray(Charsets.UTF_8)
            )
            .send()
            .await()
    }

    fun disconnect() {
        val previous = client
        client = null

        if (previous?.state?.isConnected == true) {
            previous.disconnect()
        }
    }

    private fun parseReading(json: JSONObject): ClimateReading {
        val items = json.getJSONArray("measurements")

        val measurements = List(items.length()) { index ->
            val item = items.getJSONObject(index)
            val value = item.getDouble("value")

            require(value.isFinite()) {
                "Mätvärdet måste vara ett ändligt tal."
            }

            Measurement(
                type = item.getString("type"),
                value = value,
                unit = item.getString("unit")
            )
        }

        return ClimateReading(
            deviceId = json.getString("deviceId"),
            measurements = measurements
        )
    }
}