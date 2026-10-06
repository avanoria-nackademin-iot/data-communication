namespace MeasurementApp.Models;

public sealed class MeasurementMessage
{
    public required string DeviceId { get; init; }
    public required decimal Value { get; init; }
    public required string Unit { get; init; }
}
