#include <WiFi.h>

constexpr char WIFI_SSID[] = "Avanoria_M_Network";
constexpr char WIFI_PASS[] = "BytMig123!";

bool wasConnected = false;
unsigned long lastStatusMessage = 0;

void setup() {
  Serial.begin(115200);
  delay(2000);

  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.begin(WIFI_SSID, WIFI_PASS);

  Serial.print("Connecting to ");
  Serial.println(WIFI_SSID);
}

void loop() {
  const bool isConnected = WiFi.status() == WL_CONNECTED;

  if (isConnected && !wasConnected) {
    Serial.println("WIFI connected!");

    Serial.print("IPv4 Address: ");
    Serial.println(WiFi.localIP());

    Serial.print("Signal Strength: ");
    Serial.print(WiFi.RSSI());
    Serial.println(" dBm");
  }

  if (!isConnected && wasConnected) {
    Serial.println("Reconnecting to WiFi...");
  }

  if (!isConnected && millis() - lastStatusMessage >= 5000) {
    lastStatusMessage = millis();
    Serial.println("Awaiting WiFi connection...");
  }

  wasConnected = isConnected;

  delay(100);
}

















