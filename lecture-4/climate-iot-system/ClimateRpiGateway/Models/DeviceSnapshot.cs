namespace ClimateRpiGateway.Models;

public record DeviceSnapshot
(
    string DeviceId,
    string Location,
    DateTimeOffset ReceivedAt,
    bool IsStale,
    Measurement[] Measurements
);

