using ClimateRpiGateway.Configuration;
using ClimateRpiGateway.Models;
using Microsoft.Azure.Devices.Client;
using System.Text.Json;

namespace ClimateRpiGateway.Services;

public class AzureIotHubService(GatewayOptions options, MqttService mqttService, MeasurementStore measurementStore) : IDisposable
{
    private readonly DeviceClient _client = DeviceClient.CreateFromConnectionString( options.AzureConnectionString, TransportType.Mqtt_WebSocket_Only);
    private readonly JsonSerializerOptions _jsonOptions = new(JsonSerializerDefaults.Web);

    private CancellationToken _shutdownToken;


    public async Task RunAsync(CancellationToken cancellationToken)
    {
        _shutdownToken = cancellationToken;

        await _client.OpenAsync(cancellationToken);

        await _client.SetMethodHandlerAsync("SetSendInterval", HandleSetSendIntervalAsync, null, cancellationToken);

        Console.WriteLine($"Azure IoT Hub connected as {options.GatewayId}.");

        using var timer = new PeriodicTimer(options.AzureSendInterval);

        while (await timer.WaitForNextTickAsync(cancellationToken))
            await SendSnapshotAsync(cancellationToken);
    }

    private async Task SendSnapshotAsync(CancellationToken cancellationToken)
    {
        var now = DateTimeOffset.UtcNow;
        var devices = measurementStore.GetSnapshot(now, options.StaleAfter);

        if (devices.Length == 0)
        {
            Console.WriteLine("No measurements to send.");
            return;
        }

        var snapshot = new GatewayData(options.GatewayId, now, devices);
        var payload = JsonSerializer.SerializeToUtf8Bytes(snapshot, _jsonOptions);

        // Leave room for message properties below IoT Hub's message size limit.
        if (payload.Length > 240 * 1024)
        {
            Console.WriteLine("Snapshot is too large. Split it into smaller messages.");
            return;
        }

        using var message = new Message(payload)
        {
            ContentType = "application/json",
            ContentEncoding = "utf-8"
        };

        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(30));

        try
        {
            await _client.SendEventAsync(message, timeout.Token);
            Console.WriteLine($"Sent {devices.Length} device readings to Azure.");
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception ex)
        {
            // Keep the readings; the next interval sends a new snapshot.
            Console.WriteLine($"Azure send failed: {ex.Message}");
        }
    }


    private async Task<MethodResponse> HandleSetSendIntervalAsync(MethodRequest request, object context)
    {
        try
        {
            using var document = JsonDocument.Parse(request.DataAsJson);

            if (!document.RootElement.TryGetProperty("intervalSeconds", out var property) || !property.TryGetInt32(out var interval) || interval is < 2 or > 3600)
            {
                return CreateResponse(400, new
                {
                    error = "intervalSeconds must be an integer between 2 and 3600."
                });
            }

            if (!mqttService.IsConnected)
            {
                return CreateResponse(503, new
                {
                    error = "The local MQTT broker is not connected."
                });
            }

            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(_shutdownToken);
            timeout.CancelAfter(TimeSpan.FromSeconds(10));

            await mqttService.SetSendIntervalAsync(interval, timeout.Token);

            return CreateResponse(200, new
            {
                status = "publishedToBroker",
                intervalSeconds = interval,
                topic = "climate/commands"
            });
        }
        catch (JsonException)
        {
            return CreateResponse(400, new { error = "Invalid JSON payload." });
        }
        catch (Exception ex)
        {
            Console.WriteLine($"Command failed: {ex.Message}");
            return CreateResponse(503, new { error = "Failed to publish the command." });
        }
    }

    private MethodResponse CreateResponse(int status, object body)
    {
        return new MethodResponse(JsonSerializer.SerializeToUtf8Bytes(body, _jsonOptions), status);
    }

    public async Task CloseAsync()
    {
        try
        {
            await _client.CloseAsync();
        }
        catch (Exception ex)
        {
            Console.WriteLine($"Azure close failed: {ex.Message}");
        }
    }


    public void Dispose()
    {
        _client.Dispose();
    }
}
