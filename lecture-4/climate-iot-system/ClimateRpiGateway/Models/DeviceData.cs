namespace ClimateRpiGateway.Models;

public record DeviceData
(
    string DeviceId,
    string Location,
    Measurement[] Measurements
);
