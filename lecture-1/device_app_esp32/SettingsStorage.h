#pragma once

#include "DeviceSettings.h"

class SettingsStorage {
  public:
    void load(DeviceSettings& settings);
    void save(const DeviceSettings& settings);
}