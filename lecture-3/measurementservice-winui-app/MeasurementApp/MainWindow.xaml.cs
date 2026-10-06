using MeasurementApp.Models;
using MeasurementApp.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using System;
using System.Collections.ObjectModel;
using System.Linq;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace MeasurementApp;

public sealed partial class MainWindow : Window
{
    private readonly MqttService _mqttService = new();
    private readonly CancellationTokenSource _shutdown = new();
    private readonly JsonSerializerOptions _jsonOptions = new(JsonSerializerDefaults.Web);

    private Task? _mqttTask;

    public ObservableCollection<DeviceItem> Devices { get; } = [];

    public MainWindow()
    {
        InitializeComponent();

        _mqttService.MessageReceived += (topic, payload) =>
        {
            DispatcherQueue.TryEnqueue(() => HandleMessage(topic, payload));
        };

        _mqttService.ConnectionChanged += message =>
        {
            DispatcherQueue.TryEnqueue(() => ConnectionText.Text = message);
        };

        Closed += (_, _) => _shutdown.Cancel();
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        _mqttTask ??= _mqttService.RunAsync(_shutdown.Token);
    }

    private void HandleMessage(string topic, string payload)
    {
        if (topic.StartsWith("rooms/", StringComparison.Ordinal))
        {
            try
            {
                var measurement = JsonSerializer.Deserialize<MeasurementMessage>(payload, _jsonOptions);

                if (measurement is null ||
                    string.IsNullOrWhiteSpace(measurement.DeviceId) ||
                    string.IsNullOrWhiteSpace(measurement.Unit))
                {
                    return;
                }

                var device = GetOrCreateDevice(measurement.DeviceId);

                device.LastMessage = $"{measurement.Value:F1} {measurement.Unit}";
            }
            catch (JsonException)
            {
                ConnectionText.Text = "Ett felaktigt JSON-meddelande ignorerades.";
            }

            return;
        }

        // Exempel: devices/s-c309-temp-01/status
        string[] parts = topic.Split('/');

        if (parts.Length != 3 || parts[0] != "devices")
            return;

        var deviceItem = GetOrCreateDevice(parts[1]);

        if (parts[2] == "status")
        {
            deviceItem.Status = payload;
        }
        else if (parts[2] == "sending")
        {
            deviceItem.SendingStatus = payload switch
            {
                "started" => "Started",
                "stopped" => "Stopped",
                _ => "Okänt"
            };
        }
    }

    private DeviceItem GetOrCreateDevice(string deviceId)
    {
        var device = Devices.FirstOrDefault(x => x.DeviceId == deviceId);

        if (device is not null)
            return device;

        device = new DeviceItem(deviceId);
        Devices.Add(device);

        return device;
    }

    private async void Start_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: string deviceId })
            await SendCommandAsync(deviceId, "start");
    }

    private async void Stop_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: string deviceId })
            await SendCommandAsync(deviceId, "stop");
    }

    private async Task SendCommandAsync(string deviceId, string command)
    {
        try
        {
            await _mqttService.SendCommandAsync(deviceId, command);
            ConnectionText.Text = $"Kommandot {command} skickades till {deviceId}.";
        }
        catch (Exception ex)
        {
            ConnectionText.Text = ex.Message;
        }
    }
}
