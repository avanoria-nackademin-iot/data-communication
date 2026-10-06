using CommunityToolkit.Mvvm.ComponentModel;

namespace MeasurementApp.Models;

public partial class DeviceItem(string deviceId) : ObservableObject
{
    public string DeviceId { get; } = deviceId;

    [ObservableProperty]
    public partial string LastMessage { get; set; } = "No value";

    [ObservableProperty]
    public partial string SendingStatus { get; set; } = "Unknown";

    [ObservableProperty]
    public partial string Status { get; set; } = "Unknown";
}