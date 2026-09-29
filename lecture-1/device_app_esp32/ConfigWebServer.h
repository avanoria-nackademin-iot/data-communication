#pragma once

#include "DeviceSettings.h"
#include "SettingsStorage.h"
#include "WifiConnection.h"
#include <WebServer.h>

class ConfigWebServer {
  public:
    ConfigWebServer(DeviceSettings& settings, SettingsStorage& storage, WifiConnection& wifi);

    void begin();
    void handleClient();

  private:
    WebServer server;
    DeviceSettings& settings;
    SettingsStorage& storage;
    WifiConnection& wifi;

    String buildPage();
    String htmlEscape(const String& value);

    void handleRoot();
    void handleSave();
    void handleNotFound();
}