#include "SettingsStorage.h"
#include <Preferences.h>

void SettingsStorage::load(DeviceSettings& settings) {
  Preferences preferences;
  preferences.begin("device-config", true);

  settings.deviceName = preferences.getString("name", "Arduino Nano ESP32");
  settings.deviceType = preferences.getString("deviceType", "");
  settings.deviceLocation = preferences.getString("deviceLocation", "");

  settings.wifiSSID = preferences.getString("ssid", "");
  settings.wifiPassword = preferences.getString("password", "");

  settings.apiUrl = preferences.getString("apiUrl", "https://");

  settings.ipMode = preferences.getString("ipMode", "dhcp");
  settings.staticIp = preferences.getString("staticIp", "");
  settings.gateway = preferences.getString("gateway", "");
  settings.subnet = preferences.getString("subnet", "255.255.255.0");
  settings.dns1 = preferences.getString("dns1", "8.8.8.8");
  settings.dns2 = preferences.getString("dns2", "8.8.4.4");

  preferences.end();
}

void SettingsStorage::save(const DeviceSettings& settings) {
  Preferences preferences;
  preferences.begin("device-config", false);

  preferences.putString("name", settings.deviceName);
  preferences.putString("deviceType", settings.deviceType);
  preferences.putString("deviceLocation", settings.deviceLocation);

  preferences.putString("ssid", settings.wifiSSID);
  preferences.putString("password", settings.wifiPassword);

  preferences.putString("apiUrl", settings.apiUrl);

  preferences.putString("ipMode", settings.ipMode);
  preferences.putString("staticIp", settings.staticIp);
  preferences.putString("gateway", settings.gateway);
  preferences.putString("subnet", settings.subnet);
  preferences.putString("dns1", settings.dns1);
  preferences.putString("dns2", settings.dns2);

  preferences.end();
}