#pragma once

#include "DeviceSettings.h"

class WifiConnection {
  public:
    bool connectToNetwork(const DeviceSettings& settings, unsigned long timeoutMs);
    bool startSetupAccessPoint();
    bool isSetupMode() const;

  private:
    bool setupMode = false;
};