using ClimateRpiGateway.Models;
using System.Collections.Concurrent;

namespace ClimateRpiGateway.Services;

public class MeasurementStore
{
    private readonly ConcurrentDictionary<string, DeviceSnapshot> _readings = new();

    public void Update(DeviceData data)
    {
        _readings[data.DeviceId] = new DeviceSnapshot
            (
                data.DeviceId,
                data.Location ?? "unssigned",
                DateTimeOffset.UtcNow,
                false,
                data.Measurements
            );
    }

    public DeviceSnapshot[] GetSnapshots(DateTimeOffset now, TimeSpan staleAfter)
    {
        return [.. _readings.Values
            .Select(device => device with {
                IsStale = now - device.ReceivedAt > staleAfter
            })
            .OrderBy(device => device.DeviceId)];
    }
}
