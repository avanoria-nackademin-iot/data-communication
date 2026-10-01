#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <Preferences.h>

constexpr char DeviceName[] = "Avanoria-ESP";
constexpr char ServiceUuid[] = "7b1e0000-6a9d-4d5c-a2a1-1c5e9b000001";

constexpr char SaveCharacteristicUuid[] = "7b1e0001-6a9d-4d5c-a2a1-1c5e9b000001";
constexpr char DeviceIdCharacteristicUuid[] = "7b1e0002-6a9d-4d5c-a2a1-1c5e9b000001";


Preferences preferences;

BLECharacteristic* saveCharacteristic = nullptr;
BLECharacteristic* deviceIdCharacteristic = nullptr;

String currentDeviceId;

class ConfigurationCallbacks final : public BLECharacteristicCallbacks{
  void onWrite(BLECharacteristic* characteristic) override {

    const String uuid = characteristic->getUUID().toString().c_str();

    if (uuid == DeviceIdCharacteristicUuid) {
      currentDeviceId = characteristic->getValue().c_str();

      Serial.print("New device id received: ");
      Serial.println(currentDeviceId);
      return;
    }

    if (uuid == SaveCharacteristicUuid) {
      preferences.putString("deviceId", currentDeviceId);

      Serial.println("Configuration saved.");
    }

  }
};


void setup() {
  Serial.begin(115200);
  delay(2000);


  preferences.begin("device-config", false);

  currentDeviceId = preferences.getString("deviceId", "esp_001");

  BLEDevice::init(DeviceName);
  BLEServer* server = BLEDevice::createServer();
  BLEService* service = server->createService(ServiceUuid);

  auto* callbacks = new ConfigurationCallbacks();

  // Save
  saveCharacteristic = service->createCharacteristic(
    SaveCharacteristicUuid, 
    BLECharacteristic::PROPERTY_WRITE
  );
  saveCharacteristic->setCallbacks(callbacks);

  // DeviceId
  deviceIdCharacteristic = service->createCharacteristic(
    DeviceIdCharacteristicUuid, 
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE
  );  
  deviceIdCharacteristic->setValue(currentDeviceId.c_str());
  deviceIdCharacteristic->setCallbacks(callbacks);

  service->start();

  BLEAdvertising* advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(ServiceUuid);
  advertising->start();

  Serial.println("BLE unit advertising.");
  Serial.print("BLE Name: ");
  Serial.println(DeviceName);

}

void loop() {
  delay(100);
}
