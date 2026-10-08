using ClimateRpiGateway.Configuration;
using ClimateRpiGateway.Models;
using MQTTnet;
using MQTTnet.Protocol;
using System.Buffers;
using System.Text.Json;

namespace ClimateRpiGateway.Services;

public class MqttService(GatewayOptions options, MeasurementStore store) : IDisposable
{
    private const string DataTopic = "climate/+/data";
    private const string CommandTopic = "climate/commands";

    private readonly IMqttClient _client = new MqttClientFactory().CreateMqttClient();
    private readonly JsonSerializerOptions _jsonOptions = new(JsonSerializerDefaults.Web);

    public bool IsConnected => _client.IsConnected;
    private int _subscriptionRequired = 1;

    public async Task RunAsync(CancellationToken ct)
    {
        _client.ApplicationMessageReceivedAsync += HandleMessageAsync;

        var clientOptions = new MqttClientOptionsBuilder()
            .WithClientId($"{options.GatewayId}-gateway")
            .WithTcpServer(options.MqttHost, options.MqttPort)
            .WithProtocolVersion(MQTTnet.Formatter.MqttProtocolVersion.V311)
            .Build();

        _client.ConnectedAsync += _ =>
        {
            Interlocked.Exchange(ref _subscriptionRequired, 1);
            return Task.CompletedTask;
        };

        while (!ct.IsCancellationRequested)
        {
            try
            {
                if (!_client.IsConnected)
                {
                    Console.WriteLine($"Connecting to MQTT at {options.MqttHost}:{options.MqttPort}...");
                    await _client.ConnectAsync(clientOptions, ct);
                }

                if (Volatile.Read(ref _subscriptionRequired) == 1)
                {
                    await SubscribeAsync(ct);
                    Interlocked.Exchange(ref _subscriptionRequired, 0);
                }
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                Console.WriteLine($"MQTT connection error: {ex.Message}");
            }

            await Task.Delay(TimeSpan.FromSeconds(1), ct);
        }
    }

    private async Task SubscribeAsync(CancellationToken cancellationToken)
    {
        var factory = new MqttClientFactory();

        var subscribeOptions = factory.CreateSubscribeOptionsBuilder()
            .WithTopicFilter(filter => filter
                .WithTopic(DataTopic)
                .WithQualityOfServiceLevel(MqttQualityOfServiceLevel.AtLeastOnce))
            .Build();

        var result = await _client.SubscribeAsync(subscribeOptions, cancellationToken);

        if (result.Items.Any(item => (int)item.ResultCode >= 128))
            throw new InvalidOperationException("MQTT subscription was rejected.");

        Console.WriteLine($"Subscribed to {DataTopic}.");
    }

    private Task HandleMessageAsync(MqttApplicationMessageReceivedEventArgs e)
    {
        try
        {
            var payload = e.ApplicationMessage.Payload.ToArray();
            var data = JsonSerializer.Deserialize<DeviceData>(payload, _jsonOptions);

            if (data is null || string.IsNullOrWhiteSpace(data.DeviceId) || data.Measurements is not { Length: > 0 })
            {
                Console.WriteLine("Ignored message with missing device ID or measurements.");
                return Task.CompletedTask;
            }

            store.Update(data);
        }
        catch (JsonException)
        {
            Console.WriteLine($"Ignored invalid Json on {e.ApplicationMessage.Topic}.");
        }

        return Task.CompletedTask;
    }

    public async Task SetSendIntervalAsync(int intervalSeconds, CancellationToken cancellationToken)
    {
        if (intervalSeconds is < 2 or > 3600)
            throw new ArgumentOutOfRangeException(nameof(intervalSeconds));

        if (!_client.IsConnected)
            throw new InvalidOperationException("The local MQTT broker is not connected.");

        var payload = JsonSerializer.Serialize(new
        {
            command = "setSendInterval",
            intervalSeconds
        });

        var message = new MqttApplicationMessageBuilder()
            .WithTopic(CommandTopic)
            .WithPayload(payload)
            .WithQualityOfServiceLevel(MqttQualityOfServiceLevel.AtLeastOnce)
            .WithRetainFlag(false)
            .Build();

        var result = await _client.PublishAsync(message, cancellationToken);

        if ((int)result.ReasonCode >= 128)
            throw new InvalidOperationException("The MQTT broker rejected the command.");

        Console.WriteLine($"Published send interval command: {intervalSeconds} seconds.");
    }

    public void Dispose()
    {
        _client.Dispose();
    }
}
