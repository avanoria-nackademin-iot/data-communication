namespace ClimateRpiGateway.Models;

public record GatewayData
(
    string GatewayId,
    DateTimeOffset SentAt,
    DeviceSnapshot[] Devices
);