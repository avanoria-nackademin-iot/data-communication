namespace ClimateRpiGateway.Models;

public record Measurement
(
    string Type,
    decimal Value,
    string Unit
);
