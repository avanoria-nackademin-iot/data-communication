#include "ConfigWebServer.h"
#include <WiFi.h>
#include <ESP.h>

ConfigWebServer::ConfigWebServer(DeviceSettings& settings, SettingsStorage& storage, WifiConnection& wifi) 
: server(80), settings(settings), storage(storage), wifi(wifi) {

}

void ConfigWebServer::begin() {
  server.on("/", HTTP_GET, [this]() {
    handleRoot();
  });

  server.on("/save", HTTP_POST, [this]() {
    handleSave();
  });

  server.onNotFound([this]() {
    handleNotFound();
  });

  server.begin();
  Serial.println("Configuration Wizard started.");
}

void ConfigWebServer::handleClient() {
  server.handleClient();
}

String ConfigWebServer::htmlEscape(const String& value) {
  String result;
  result.reserve(value.length());

  for (unsigned int i = 0; i < value.length(); i++) {
    switch (value[i]) {
      case '&':  result += "&amp;";  break;
      case '<':  result += "&lt;";   break;
      case '>':  result += "&gt;";   break;
      case '"':  result += "&quot;"; break;
      case '\'': result += "&#39;";  break;
      default:   result += value[i]; break;     
    }
  }

  return result;
}

String ConfigWebServer::buildPage() {
  const bool connected = WiFi.status() == WL_CONNECTED;
  
  const String status = connected
      ? "Connected"
      : wifi.isSetupMode() ? "Configuration mode" : "Not connected";

  const String ipAddress = connected
      ? WiFi.localIP().toString()
      : wifi.isSetupMode()
          ? WiFi.softAPIP().toString()
          : "No IP address";

  const String signalStrength = connected
      ? String(WiFi.RSSI()) + " dBm"
      : "Not available";

  const String dhcpSelected =
      settings.ipMode == "dhcp" ? "selected" : "";

  const String staticSelected =
      settings.ipMode == "static" ? "selected" : "";

  const String staticFieldsStyle =
      settings.ipMode == "static" ? "" : "display:none;";


  String page = R"rawliteral(
    <!DOCTYPE html>
    <html lang="en">
    <head>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <title>Configuration Wizard</title>
      <style>
        body {
          font-family: Arial, sans-serif;
          max-width: 680px;
          margin: 30px auto;
          padding: 0 16px;
          color: #1f2937;
          background: #f3f4f6;
        }

        main {
          background: white;
          padding: 24px;
          border-radius: 12px;
          box-shadow: 0 4px 18px #0001;
        }

        h1 { margin-top: 0; }

        label {
          display: block;
          margin-top: 16px;
          font-weight: bold;
        }

        input, select {
          box-sizing: border-box;
          width: 100%;
          padding: 10px;
          margin-top: 6px;
          border: 1px solid #cbd5e1;
          border-radius: 6px;
          font-size: 16px;
        }

        button {
          margin-top: 22px;
          padding: 11px 16px;
          border: 0;
          border-radius: 6px;
          color: white;
          background: #2563eb;
          font-size: 16px;
          cursor: pointer;
        }

        .status {
          padding: 12px;
          background: #eff6ff;
          border-radius: 6px;
          line-height: 1.7;
        }

        .hint {
          color: #64748b;
          font-size: 14px;
          margin-top: 6px;
        }
      </style>
    </head>
    <body>
      <main>
        <h1>Configuration Wizard</h1>

        <div class="status">
          <strong>Status:</strong> STATUS<br>
          <strong>Current IP address:</strong> CURRENT_IP<br>
          <strong>Wi-Fi:</strong> WIFI_NAME<br>
          <strong>Signal:</strong> SIGNAL
        </div>

        <form method="POST" action="/save">
          <label for="name">Device Name</label>
          <input id="name" name="name" value="DEVICE_NAME" required>

          <label for="type">Device Type</label>
          <input id="type" name="type" value="DEVICE_TYPE" required>

          <label for="location">Device Location</label>
          <input id="location" name="location" value="DEVICE_LOCATION" required>

          <label for="ssid">WiFI-network (SSID)</label>
          <input id="ssid" name="ssid" value="WIFI_NAME" required>

          <label for="password">WIFI-password</label>
          <input id="password" name="password" type="password"
                placeholder="">
          <div class="hint">The password is hidden.</div>

          <label for="ipMode">IP address mode</label>
          <select id="ipMode" name="ipMode" onchange="updateIpFields()">
            <option value="dhcp" DHCP_SELECTED>Dyanmic (DHCP)</option>
            <option value="static" STATIC_SELECTED>Static IP address</option>
          </select>

          <div id="staticFields" style="STATIC_FIELDS_STYLE">
            <label for="staticIp">IP address</label>
            <input id="staticIp" name="staticIp" value="STATIC_IP"
                  placeholder="eg: 192.168.1.50">

            <label for="gateway">Gateway/router</label>
            <input id="gateway" name="gateway" value="GATEWAY"
                  placeholder="eg: 192.168.1.1">

            <label for="subnet">Subnet mask</label>
            <input id="subnet" name="subnet" value="SUBNET"
                  placeholder="eg: 255.255.255.0">

            <label for="dns1">Primary DNS</label>
            <input id="dns1" name="dns1" value="DNS1"
                  placeholder="Exempel: 8.8.8.8">

            <label for="dns2">Secondary DNS</label>
            <input id="dns2" name="dns2" value="DNS2"
                  placeholder="eg: 8.8.4.4">

            <div class="hint">
              Please check that the static ip address is not in used by another device on the network.
            </div>
          </div>

          <label for="apiUrl">API address</label>
          <input id="apiUrl" name="apiUrl" value="API_URL" required>

          <button type="submit">Save Settings</button>
        </form>
      </main>

      <script>
        function updateIpFields() {
          const isStatic = document.getElementById("ipMode").value === "static";
          const fields = document.getElementById("staticFields");

          fields.style.display = isStatic ? "block" : "none";

          ["staticIp", "gateway", "subnet"].forEach(id => {
            document.getElementById(id).required = isStatic;
          });
        }

        updateIpFields();
      </script>
    </body>
    </html>
  )rawliteral";

  page.replace("STATUS", htmlEscape(status));
  page.replace("CURRENT_IP", htmlEscape(ipAddress));
  page.replace("DEVICE_NAME", htmlEscape(settings.deviceName));
  page.replace("DEVICE_TYPE", htmlEscape(settings.deviceType));
  page.replace("DEVICE_LOCATION", htmlEscape(settings.deviceLocation));
  page.replace("WIFI_NAME", htmlEscape(settings.wifiSsid));
  page.replace("SIGNAL", htmlEscape(signalStrength));
  page.replace("API_URL", htmlEscape(settings.apiUrl));

  page.replace("DHCP_SELECTED", dhcpSelected);
  page.replace("STATIC_SELECTED", staticSelected);
  page.replace("STATIC_FIELDS_STYLE", staticFieldsStyle);

  page.replace("STATIC_IP", htmlEscape(settings.staticIp));
  page.replace("GATEWAY", htmlEscape(settings.gateway));
  page.replace("SUBNET", htmlEscape(settings.subnet));
  page.replace("DNS1", htmlEscape(settings.dns1));
  page.replace("DNS2", htmlEscape(settings.dns2));

  return page;

}






























