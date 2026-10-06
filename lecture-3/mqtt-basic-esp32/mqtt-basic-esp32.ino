#include <Arduino.h>
#include <WiFi.h>
#include <DHT.h>
#include <ESP32MQTTClient.h>
#include <esp_idf_version.h>
#include <math.h>
#include <atomic>

namespace Sensor {
  constexpr uint8_t DHT_PIN = D2;
  constexpr uint8_t DHT_TYPE = DHT11;

  DHT dht(DHT_PIN, DHT_TYPE);

  float getTemperature() {
    return dht.readTemperature();
  } 

  float getHumidity() {
    return dht.readHumidity();
  } 

  void initSensor() {
    dht.begin();
    delay(1000);

    float temperature = getTemperature();
    float humidity = getHumidity();
  }
}

namespace Network {
  constexpr char WIFI_SSID[] = "";
  constexpr char WIFI_PASSWORD[] = "";

  void initWifi() {
    WiFi.mode(WIFI_STA);
    WiFi.setAutoReconnect(true);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

    while (WiFi.status() != WL_CONNECTED) {
      delay(500);
    }
  } 
}

namespace Mqtt {
  std::atomic<bool> sending{true};

  constexpr unsigned long PUBLISH_INTERVAL = 5000;
  unsigned long lastPublishTime = 0;

  constexpr char MQTT_HOST[] = "";
  constexpr uint16_t MQTT_PORT = 1883;
  constexpr char MQTT_USERNAME[] = "";
  constexpr char MQTT_PASSWORD[] = "";

  constexpr char MQTT_PUB_TOPIC[] = "";

  ESP32MQTTClient mqttClient;
 
  void initMqtt() {
    mqttClient.setURL(MQTT_HOST, MQTT_PORT, MQTT_USERNAME, MQTT_PASSWORD);
    mqttClient.setMqttClientName(MQTT_USERNAME);
    mqttClient.loopStart();
  }

  void publishMqttMessage() {
    if (!sending.load() || !mqttClient.isConnected())
      return;

    unsigned long currentTime = millis();

    if (currentTime - lastPublishTime < PUBLISH_INTERVAL)
      return;

    lastPublishTime = currentTime;

    float temperature = Sensor::getTemperature();
    float humidity = Sensor::getHumidity();

    if (isnan(temperature) || isnan(humidity))
      return;

    char payload[256];
    snprintf(payload, sizeof(payload), "{ \"deviceId\": \"%s\", \"temperature\": \":%.1f\", \"humidity\": \":%.1f\" }", MQTT_USERNAME, temperature, humidity);

    mqttClient.publish(MQTT_PUB_TOPIC, payload, 0, false);
  
  }
}

void onMqttConnect(esp_mqtt_client_handle_t client) {}

#if ESP_IDF_VERSION < ESP_IDF_VERSION_VAL(5, 0, 0)
  esp_err_t handleMQTT(esp_mqtt_event_handle_t event) {
    Mqtt::mqttClient.onEventCallback(event);
    return ESP_OK;
  }
#else
  void handleMQTT(void *handlerArgs, esp_event_base_t base, int32_t eventId, void *eventData) {
    auto event = static_cast<esp_mqtt_event_handle_t>(eventData);
    Mqtt::mqttClient.onEventCallback(event);
  }
#endif

void setup() {
  Serial.begin(115200);
  delay(2000);

  Sensor::initSensor();
  Network::initWifi();
  Mqtt::initMqtt();
}

void loop() {
  Mqtt::publishMqttMessage();
  delay(10);
}
