#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <DHT.h>

#define DHT_PIN D2
#define DHT_TYPE DHT11

#define SERVICE_UUID "98e7d326-0000-4e78-946d-aef7eafa62d0"
#define CHARACTERISTIC_UUID "98e7d326-0001-4e78-946d-aef7eafa62d0"

DHT dht(DHT_PIN, DHT_TYPE);

BLECharacteristic *sensorCharacteristic;

void setup() {
  Serial.begin(115200);
  delay(2000);

  dht.begin();

  BLEDevice::init("Avanoria-DHT11");
  BLEServer *server = BLEDevice::createServer();

  BLEService *service = server->createService(SERVICE_UUID);

  sensorCharacteristic = service->createCharacteristic(
    CHARACTERISTIC_UUID,
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
  );

  sensorCharacteristic->addDescriptor(new BLE2902());
  service->start();

  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);
  BLEDevice::startAdvertising();

  Serial.println("BLE sensor is running.");
  Serial.println("Awaiting receiver...");
}

void loop() {
  float temperature = dht.readTemperature();
  float humidity = dht.readHumidity();

  if (isnan(temperature) || isnan(humidity)) {
    Serial.println("Unable to read data from DHT-11.");
    delay(2000);
    return;
  }

  String data = String(temperature, 2) + ";" + String(humidity, 2);
  
  sensorCharacteristic->setValue(data.c_str());
  sensorCharacteristic->notify();

  Serial.print("Sending: ");
  Serial.println(data);

  delay(5000);
}