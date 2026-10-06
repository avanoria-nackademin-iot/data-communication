#include <Arduino.h>
#include <WiFi.h>
#include <DHT.h>
#include <ESP32MQTTClient.h>
#include <NimBLEDevice.h>
#include <Preferences.h>
#include <esp_idf_version.h>
#include <esp_system.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include <atomic>
#include <cmath>
#include <cstring>

constexpr char DEVICE_ID[] = "";

namespace Configuration {
  constexpr uint16_t MQTT_PORT = 1883;

  struct Settings {
    uint32_t version = 1;
    char wifiSsid[33] = "";
    char wifiPassword[65] = "";
    char mqttHost[254] = "";
    char mqttUsername[129] = "";
    char mqttPassword[129] = "";
  };

  // Aktiva inställningar ändras först efter omstart.
  Settings active;

  template <size_t N>
  bool hasTerminator(const char (&value)[N]) {
    return memchr(value, '\0', N) != nullptr;
  }

  bool isValid(const Settings& settings) {
    if (settings.version != 1 ||
        !hasTerminator(settings.wifiSsid) ||
        !hasTerminator(settings.wifiPassword) ||
        !hasTerminator(settings.mqttHost) ||
        !hasTerminator(settings.mqttUsername) ||
        !hasTerminator(settings.mqttPassword)) {
      return false;
    }

    if (settings.wifiSsid[0] == '\0' ||
        settings.mqttHost[0] == '\0') {
      return false;
    }

    if (strpbrk(settings.mqttHost, ":/ \t\r\n") != nullptr) {
      return false;
    }

    return true;
  }

  void load() {
    Preferences preferences;

    if (!preferences.begin("climate-config", true)) {
      Serial.println("Using default settings.");
      return;
    }

    Settings saved;

    bool loaded =
      preferences.getBytesLength("settings") == sizeof(saved) &&
      preferences.getBytes("settings", &saved, sizeof(saved)) == sizeof(saved);

    preferences.end();

    if (loaded && isValid(saved)) {
      active = saved;
      Serial.println("Saved settings loaded.");
    } else {
      Serial.println("Using default settings.");
    }
  }

  bool save(const Settings& settings) {
    if (!isValid(settings)) {
      return false;
    }

    Preferences preferences;

    if (!preferences.begin("climate-config", false)) {
      return false;
    }

    size_t written = preferences.putBytes("settings", &settings, sizeof(settings));
    preferences.end();

    return written == sizeof(settings);
  }
}

namespace Sensor {
  constexpr uint8_t DHT_PIN = D2;
  constexpr uint8_t DHT_TYPE = DHT11;

  DHT dht(DHT_PIN, DHT_TYPE);

  void initSensor() {
    dht.begin();
  }

  float readTemperature() {
    return dht.readTemperature();
  }

  float readHumidity() {
    return dht.readHumidity();
  }
}

namespace Network {
  constexpr unsigned long RETRY_INTERVAL = 30000;

  unsigned long lastRetryTime = 0;
  bool wasConnected = false;

  void initWifi() {
    WiFi.mode(WIFI_STA);
    WiFi.setAutoReconnect(true);
    WiFi.begin(Configuration::active.wifiSsid, Configuration::active.wifiPassword);

    lastRetryTime = millis();
    Serial.println("Connecting to Wi-Fi...");
  }

  void update() {
    bool connected = WiFi.status() == WL_CONNECTED;

    if (connected && !wasConnected) {
      Serial.print("Wi-Fi connected. IP: ");
      Serial.println(WiFi.localIP());
    } else if (!connected && wasConnected) {
      Serial.println("Wi-Fi disconnected.");
    }

    wasConnected = connected;

    if (!connected && millis() - lastRetryTime >= RETRY_INTERVAL) {
      lastRetryTime = millis();
      WiFi.reconnect();
    }
  }
}

namespace Mqtt {
  std::atomic<bool> sending{true};

  constexpr unsigned long PUBLISH_INTERVAL = 5000;
  constexpr char MQTT_PUB_TOPIC[] = "climate/temperature";

  unsigned long lastPublishTime = 0;
  bool initialized = false;

  char statusTopic[96];
  char sendingTopic[96];
  char commandTopic[96];

  ESP32MQTTClient mqttClient;

  void initMqtt() {
    snprintf(statusTopic, sizeof(statusTopic), "devices/%s/status", DEVICE_ID);
    snprintf(sendingTopic, sizeof(sendingTopic), "devices/%s/sending", DEVICE_ID);
    snprintf(commandTopic, sizeof(commandTopic), "devices/%s/commands", DEVICE_ID);

    mqttClient.setURL(
      Configuration::active.mqttHost,
      Configuration::MQTT_PORT,
      Configuration::active.mqttUsername,
      Configuration::active.mqttPassword
    );

    mqttClient.setMqttClientName(DEVICE_ID);
    mqttClient.enableLastWillMessage(statusTopic, "offline", true, 0);
    mqttClient.loopStart();

    initialized = true;
    Serial.println("MQTT client started.");
  }

  void publishMqttMessage() {
    if (!initialized || !sending.load() || !mqttClient.isConnected()) {
      return;
    }

    unsigned long currentTime = millis();

    if (currentTime - lastPublishTime < PUBLISH_INTERVAL) {
      return;
    }

    lastPublishTime = currentTime;

    float temperature = Sensor::readTemperature();
    float humidity = Sensor::readHumidity();

    if (isnan(temperature) || isnan(humidity)) {
      Serial.println("Could not read DHT11.");
      return;
    }

    char payload[320];

    snprintf(
      payload,
      sizeof(payload),
      "{\"deviceId\":\"%s\",\"measurements\":["
      "{\"type\":\"temperature\",\"value\":%.1f,\"unit\":\"°C\"},"
      "{\"type\":\"humidity\",\"value\":%.1f,\"unit\":\"%%\"}]}",
      DEVICE_ID,
      temperature,
      humidity
    );

    mqttClient.publish(MQTT_PUB_TOPIC, payload, 0, false);
  }
}

namespace Bluetooth {
  constexpr char SERVICE_UUID[] = "7b1e0001-6b4a-4a9d-8f71-9e6700000001";
  constexpr char DEVICE_ID_UUID[] = "7b1e0001-6b4a-4a9d-8f71-9e6700000002";
  constexpr char WIFI_SSID_UUID[] = "7b1e0001-6b4a-4a9d-8f71-9e6700000003";
  constexpr char WIFI_PASSWORD_UUID[] = "7b1e0001-6b4a-4a9d-8f71-9e670000000a";
  constexpr char MQTT_HOST_UUID[] = "7b1e0001-6b4a-4a9d-8f71-9e6700000004";
  constexpr char MQTT_USERNAME_UUID[] = "7b1e0001-6b4a-4a9d-8f71-9e6700000005";
  constexpr char MQTT_PASSWORD_UUID[] = "7b1e0001-6b4a-4a9d-8f71-9e6700000006";
  constexpr char COMMAND_UUID[] = "7b1e0001-6b4a-4a9d-8f71-9e6700000007";
  constexpr char RESULT_UUID[] = "7b1e0001-6b4a-4a9d-8f71-9e6700000008";

  enum class Action : uint8_t {
    Save,
    Apply
  };

  struct Request {
    Action action;
    Configuration::Settings settings;
  };

  QueueHandle_t requests = nullptr;

  NimBLECharacteristic* wifiSsid = nullptr;
  NimBLECharacteristic* wifiPassword = nullptr;
  NimBLECharacteristic* mqttHost = nullptr;
  NimBLECharacteristic* mqttUsername = nullptr;
  NimBLECharacteristic* mqttPassword = nullptr;
  NimBLECharacteristic* result = nullptr;

  bool savedSuccessfully = false;
  bool restartPending = false;
  unsigned long restartRequestedAt = 0;

  void setResult(const char* message) {
    result->setValue(message);
  }

  template <size_t N>
  bool readField(NimBLECharacteristic* characteristic, char (&destination)[N]) {
    auto value = characteristic->getValue();

    if (value.size() >= N ||
        memchr(value.data(), '\0', value.size()) != nullptr) {
      return false;
    }

    memset(destination, 0, N);
    memcpy(destination, value.data(), value.size());

    return true;
  }

  class CommandCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* characteristic, NimBLEConnInfo& connInfo) override {
      auto value = characteristic->getValue();
      std::string command(value.c_str(), value.size());

      Request request{};

      if (command == "save") {
        request.action = Action::Save;

        bool valid =
          readField(wifiSsid, request.settings.wifiSsid) &&
          readField(wifiPassword, request.settings.wifiPassword) &&
          readField(mqttHost, request.settings.mqttHost) &&
          readField(mqttUsername, request.settings.mqttUsername) &&
          readField(mqttPassword, request.settings.mqttPassword) &&
          Configuration::isValid(request.settings);

        if (!valid) {
          setResult("error:invalid_settings");
          return;
        }
      } else if (command == "apply") {
        request.action = Action::Apply;
      } else {
        setResult("error:unknown_command");
        return;
      }

      setResult(command == "save" ? "saving" : "applying");

      if (xQueueSend(requests, &request, 0) != pdTRUE) {
        setResult("error:busy");
      }
    }
  };

  CommandCallbacks commandCallbacks;

  NimBLECharacteristic* createSetting(NimBLEService* service, const char* uuid, const char* value) {
    uint32_t properties =
      NIMBLE_PROPERTY::READ |
      NIMBLE_PROPERTY::WRITE |
      NIMBLE_PROPERTY::READ_AUTHEN |
      NIMBLE_PROPERTY::WRITE_AUTHEN;

    auto characteristic = service->createCharacteristic(uuid, properties);
    characteristic->setValue(value);

    return characteristic;
  }

  void initBluetooth() {
    requests = xQueueCreate(2, sizeof(Request));

    if (requests == nullptr) {
      Serial.println("Could not create BLE request queue.");
      return;
    }

    NimBLEDevice::init(DEVICE_ID);

    uint32_t passkey = 100000 + esp_random() % 900000;

    NimBLEDevice::setSecurityAuth(true, true, true);
    NimBLEDevice::setSecurityIOCap(BLE_HS_IO_DISPLAY_ONLY);
    NimBLEDevice::setSecurityPasskey(passkey);

    Serial.printf("BLE pairing PIN: %06lu\n", static_cast<unsigned long>(passkey));

    auto server = NimBLEDevice::createServer();
    server->advertiseOnDisconnect(true);

    auto service = server->createService(SERVICE_UUID);

    auto deviceId = service->createCharacteristic(
      DEVICE_ID_UUID,
      NIMBLE_PROPERTY::READ
    );

    deviceId->setValue(DEVICE_ID);

    wifiSsid = createSetting(service, WIFI_SSID_UUID, Configuration::active.wifiSsid);
    wifiPassword = createSetting(service, WIFI_PASSWORD_UUID, Configuration::active.wifiPassword);
    mqttHost = createSetting(service, MQTT_HOST_UUID, Configuration::active.mqttHost);
    mqttUsername = createSetting(service, MQTT_USERNAME_UUID, Configuration::active.mqttUsername);
    mqttPassword = createSetting(service, MQTT_PASSWORD_UUID, Configuration::active.mqttPassword);

    auto command = service->createCharacteristic(
      COMMAND_UUID,
      NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_AUTHEN
    );

    command->setCallbacks(&commandCallbacks);

    result = service->createCharacteristic(
      RESULT_UUID,
      NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::READ_AUTHEN
    );

    setResult("ready");
    service->start();

    NimBLEAdvertisementData advertisementData;
    advertisementData.setFlags(0x06);
    advertisementData.addServiceUUID(SERVICE_UUID);

    NimBLEAdvertisementData scanResponseData;
    scanResponseData.setName(DEVICE_ID);

    auto advertising = NimBLEDevice::getAdvertising();
    advertising->setAdvertisementData(advertisementData);
    advertising->setScanResponseData(scanResponseData);
    advertising->enableScanResponse(true);
    advertising->start();

    Serial.printf("BLE available as %s\n", DEVICE_ID);
  }

  void update() {
    if (requests == nullptr) {
      return;
    }

    if (restartPending) {
      if (millis() - restartRequestedAt >= 1500) {
        ESP.restart();
      }

      return;
    }

    Request request;

    if (xQueueReceive(requests, &request, 0) != pdTRUE) {
      return;
    }

    if (request.action == Action::Save) {
      setResult("saving");
      savedSuccessfully = Configuration::save(request.settings);

      if (savedSuccessfully) {
        setResult("saved");
        Serial.println("Settings saved. Waiting for apply.");
      } else {
        setResult("error:save_failed");
        Serial.println("Could not save settings.");
      }
    } else if (request.action == Action::Apply) {
      if (!savedSuccessfully) {
        setResult("error:save_first");
        return;
      }

      setResult("restarting");
      restartRequestedAt = millis();
      restartPending = true;

      Serial.println("Restarting with saved settings...");
    }
  }
}

void onMqttConnect(esp_mqtt_client_handle_t client) {
  if (!Mqtt::mqttClient.isMyTurn(client)) {
    return;
  }

  Serial.println("MQTT connected.");

  Mqtt::mqttClient.publish(Mqtt::statusTopic, "online", 0, true);

  Mqtt::mqttClient.publish(Mqtt::sendingTopic, Mqtt::sending.load() ? "started" : "stopped", 0, true);

  Mqtt::mqttClient.subscribe(Mqtt::commandTopic, [](const std::string& payload) {
    if (payload == "start") {
      Mqtt::sending.store(true);
      Mqtt::mqttClient.publish(Mqtt::sendingTopic, "started", 0, true);
      Serial.println("Start sending.");
    
    } else if (payload == "stop") {
      Mqtt::sending.store(false);
      Mqtt::mqttClient.publish(Mqtt::sendingTopic, "stopped", 0, true);
      Serial.println("Stopped sending.");
    
    } else {
      Serial.printf("Unknown command: %s\n", payload.c_str());
    }
  }, 0);
}

#if ESP_IDF_VERSION < ESP_IDF_VERSION_VAL(5, 0, 0)
esp_err_t handleMQTT(esp_mqtt_event_handle_t event) {
  Mqtt::mqttClient.onEventCallback(event);
  return ESP_OK;
}
#else
void handleMQTT(void* handlerArgs, esp_event_base_t base, int32_t eventId, void* eventData) {
  auto event = static_cast<esp_mqtt_event_handle_t>(eventData);
  Mqtt::mqttClient.onEventCallback(event);
}
#endif

void setup() {
  Serial.begin(115200);
  delay(1000);

  Configuration::load();
  Sensor::initSensor();
  Bluetooth::initBluetooth();
  Network::initWifi();
}

void loop() {
  Bluetooth::update();
  Network::update();

  if (!Mqtt::initialized && WiFi.status() == WL_CONNECTED) {
    Mqtt::initMqtt();
  }

  Mqtt::publishMqttMessage();
  delay(10);
}