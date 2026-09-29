#include "DeviceSettings.h"
#include "SettingsStorage.h"
#include "WifiConnection.h"
#include "ConfigWebServer.h"

DeviceSettings settings;
SettingsStorage storage;
WifiConnection wifiConnection;
ConfigWebServer configWebServer(settings, storage, wifiConnection);

void setup() {
  Serial.begin(115200);
  delay(2000);

  storage.load(settings);

  bool connected = false;

  if (!settings.wifiSSID.isEmpty()) {
    connected = wifiConnection.connectToNetwork(settings, 15000);
  }

  if (!connected) {
    wifiConnection.startSetupAccessPoint();
  }

  configWebServer.begin();

}

void loop() {
  configWebServer.handleClient();
}
