#include "WifiConnection.h"
#include <WiFi.h>

namespace {
  constexpr char SetupSSID[] = "Avanoria-NanoESP32-Setup";
  constexpr char SetupPASS[] = "P@ssword123!";
}

bool WifiConnection::connectToNetwork(const DeviceSettings &settings, unsigned long timeoutMs) {
  setupMode = false;
  WiFi.mode(WIFI_STA);

  if (settings.ipMode == "static") {
    IPAddress localIp;
    IPAddress gateway;
    IPAddress subnet;
    IPAddress dns1;
    IPAddress dns2;

    if (!localIp.fromString(settings.staticIp) || !gateway.fromString(settings.gateway) || !subnet.fromString(settings.subnet)) {
      Serial.println("Invalid static ip, gateway or subnet mask.");
    }

    if (settings.dns1.isEmpty()) {
      dns1 = gateway;
    } else if (!dns1.fromString(settings.dns1)) {
      Serial.println("Invalid primary DNS.");
      return false;
    }

    if (!settings.dns2.isEmpty() && !dns2.fromString(settings.dns2)) {
      Serial.println("Invalid secondary DNS.");
      return false;
    }

    if (!WiFi.config(localIp, gateway, subnet, dns1, dns2)) {
      Serial.println("Unable to configure static IP.");
      return false;
    }
  }

  WiFi.begin(settings.wifiSSID.c_str(), settings.wifiPassword.c_str());

  Serial.print("Connecting to Wi-Fi: ");
  Serial.println(settings.wifiSSID);
  
  Serial.print("IP-mode: ");
  Serial.println(settings.ipMode);

  const unsigned long startTime = millis();

  while (WiFi.status() != WL_CONNECTED && millis() - startTime < timeoutMs) {
    delay(500);
    Serial.print(".");
  }

  Serial.println();

  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("Unable to connect to WiFi.");
    return false;
  }

  Serial.println("WiFi connected.");
  Serial.print("IP-Address: ");
  Serial.println(WiFi.localIP());
  Serial.print("Gateway: ");
  Serial.println(WiFi.gatewayIP());
  Serial.print("Subnet: ");
  Serial.println(WiFi.subnetMask());
  Serial.print("DNS: ");
  Serial.println(WiFi.dnsIP());

  return true;
}

bool WifiConnection::startSetupAccessPoint() {
  setupMode = true;
  WiFi.mode(WIFI_AP);

  const bool started = WiFi.softAP(SetupSSID, SetupPass)

  if (started) {
    Serial.println("Configuration Wizard Started.");
    Serial.print("SSID: ");
    Serial.println(SetupSSID);
    Serial.println();
    Serial.print("Open http://");
    Serial.println(WiFi.softAPIP());
  } else {
    serial.println("Unable to start configuration wizard.");
  }

  return started;
}

bool WifiConnection::isSetupMode() const {
  return setupMode;
}