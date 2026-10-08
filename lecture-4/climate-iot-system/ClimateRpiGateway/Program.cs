using ClimateRpiGateway.Configuration;
using ClimateRpiGateway.Services;

var connectionString = Environment.GetEnvironmentVariable("IOTHUB_DEVICE_CONNECTION_STRING");

if (string.IsNullOrWhiteSpace(connectionString))
{
    Console.WriteLine("Set IOTHUB_DEVICE_CONNECTION_STRING before starting.");
    return;
}

var options = new GatewayOptions(
    GatewayId: "RPI-01",
    MqttHost: Environment.GetEnvironmentVariable("MQTT_HOST") ?? "localhost",
    MqttPort: 1883,
    AzureConnectionString: connectionString,
    AzureSendInterval: TimeSpan.FromSeconds(60),
    StaleAfter: TimeSpan.FromMinutes(5));

using var shutdown = new CancellationTokenSource();

Console.CancelKeyPress += (_, e) =>
{
    e.Cancel = true;
    shutdown.Cancel();
};

var measurementStore = new MeasurementStore();

using var mqttService = new MqttService(options, measurementStore);
using var azureService = new AzureIotHubService(options, mqttService, measurementStore);

Console.WriteLine("Starting gateway. Press Ctrl+C to stop.");

var mqttTask = mqttService.RunAsync(shutdown.Token);
var azureTask = azureService.RunAsync(shutdown.Token);

try
{
    // Stop the other service if either service exits or fails.
    await Task.WhenAny(mqttTask, azureTask);
    shutdown.Cancel();

    await Task.WhenAll(mqttTask, azureTask);
}
catch (OperationCanceledException) when (shutdown.IsCancellationRequested)
{
    Console.WriteLine("Gateway stopped.");
}
catch (Exception ex)
{
    Console.WriteLine($"Gateway failed: {ex.Message}");
}
finally
{
    await azureService.CloseAsync();
}