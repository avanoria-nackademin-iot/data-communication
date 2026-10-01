#include <BLEDevice.h>

#define SERVICE_UUID "98e7d326-0000-4e78-946d-aef7eafa62d0"
#define CHARACTERISTIC_UUID "98e7d326-0001-4e78-946d-aef7eafa62d0"

BLEAdvertisedDevice *sensorDevice = nullptr;
BLERemoteCharacteristic *sensorCharacteristic = nullptr;

bool shouldConnect = false;
bool connected = false;

void onSensorData(BLERemoteCharacteristic *characteristic, uint8_t *data, size_t length, bool isNotify) {
  String sensorData;

  for (size_t i = 0; i < length; i++) {
    sensorData += (char)data[i];
  }

  int separatorIndex = sensorData.indexOf(';');

  if (separatorIndex == -1) {
    Serial.println("Invalid data package");
    return;
  }

  float temperature = sensorData.substring(0, separatorIndex).toFloat();
  float humidity = sensorData.substring(separatorIndex + 1).toFloat();

  Serial.println("-------------------------------------");
  Serial.print("Temperature: ");
  Serial.print(temperature);
  Serial.println(" °C");
  Serial.print("Humidity: ");
  Serial.print(humidity);
  Serial.println(" %");
}

class ScanCallbacks : public BLEAdvertisedDeviceCallbacks {
  
  void onResult(BLEAdvertisedDevice advertisedDevice) override {
    if (!advertisedDevice.haveServiceUUID())
      return;

    if (!advertisedDevice.isAdvertisingService(BLEUUID(SERVICE_UUID)))
      return;

    Serial.println("Avanoria-DHT11 device found.");

    BLEDevice::getScan()->stop();

    sensorDevice = new BLEAdvertisedDevice(advertisedDevice);

    shouldConnect = true;

  }
};

bool connectToSensor() {
  Serial.println("Connecting...");

  BLEClient *client = BLEDevice::createClient();

  if (!client->connect(sensorDevice)){
    Serial.println("Unable to connect to sensor device.");
    return false;
  }

  BLERemoteService *service = client->getService(SERVICE_UUID);

  if (service == nullptr) {
    Serial.println("Unable to find service.");
    client->disconnect();
    return false;
  }

  sensorCharacteristic = service->getCharacteristic(CHARACTERISTIC_UUID);

  if (sensorCharacteristic == nullptr) {
    Serial.println("Unable to find characteristic.");
    client->disconnect();
    return false;
  }

  if (sensorCharacteristic->canNotify()) {
    sensorCharacteristic->registerForNotify(onSensorData);
  }

  Serial.println("Connected to Avanoria-DHT11 sensor.");
  
  return true;
}


void setup() {
  Serial.begin(115200);
  delay(2000);

  BLEDevice::init("Avanoria-Receiver");
  
  BLEScan *scan = BLEDevice::getScan();
  scan->setAdvertisedDeviceCallbacks(new ScanCallbacks());
  scan->setActiveScan(true);

  Serial.println("Scanning for BLE devices");

  scan->start(0, false);
}

void loop() {
  if (shouldConnect) {
    connected = connectToSensor();
    shouldConnect = false;
  }

  delay(1000);
}
