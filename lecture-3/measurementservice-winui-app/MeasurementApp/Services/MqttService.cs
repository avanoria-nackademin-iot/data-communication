using MQTTnet;
using MQTTnet.Formatter;
using MQTTnet.Protocol;
using System;
using System.Threading;
using System.Threading.Tasks;

namespace MeasurementApp.Services;

public sealed class MqttService
{
    private readonly MqttClientFactory _factory = new();
    private readonly IMqttClient _client;

    public event Action<string, string>? MessageReceived;
    public event Action<string>? ConnectionChanged;

    public MqttService()
    {
        _client = _factory.CreateMqttClient();

        _client.ApplicationMessageReceivedAsync += e =>
        {
            string topic = e.ApplicationMessage.Topic;
            string payload = e.ApplicationMessage.ConvertPayloadToString();

            MessageReceived?.Invoke(topic, payload);

            return Task.CompletedTask;
        };
    }

    public async Task RunAsync(CancellationToken cancellationToken)
    {
        var options = new MqttClientOptionsBuilder()
            .WithTcpServer("172.17.233.188", 1883)
            .WithClientId("climateapp")
            .WithCredentials("climateapp", "BytMig123!")
            .WithProtocolVersion(MqttProtocolVersion.V500)
            .Build();

        var subscriptions = _factory.CreateSubscribeOptionsBuilder()
            .WithTopicFilter(t => t.WithTopic("climate/temperature").WithAtMostOnceQoS())
            .WithTopicFilter(t => t.WithTopic("devices/+/status").WithAtMostOnceQoS())
            .WithTopicFilter(t => t.WithTopic("devices/+/sending").WithAtMostOnceQoS())
            .Build();

        try
        {
            while (!cancellationToken.IsCancellationRequested)
            {
                try
                {
                    ConnectionChanged?.Invoke("Connecting to MQTT-broker…");

                    await _client.ConnectAsync(options, cancellationToken);
                    var result = await _client.SubscribeAsync(subscriptions, cancellationToken);

                    foreach (var item in result.Items)
                    {
                        if ((int)item.ResultCode >= 128)
                            throw new InvalidOperationException("MQTT-Broker denied the subscription.");
                    }

                    ConnectionChanged?.Invoke("MQTT-broker connected.");

                    while (_client.IsConnected)
                    {
                        await Task.Delay(1000, cancellationToken);
                    }
                }
                catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
                {
                    break;
                }
                catch (Exception ex)
                {
                    ConnectionChanged?.Invoke($"MQTT: {ex.Message}");

                    // Börja nästa försök med en ny anslutning.
                    if (_client.IsConnected)
                    {
                        try
                        {
                            await _client.DisconnectAsync(cancellationToken: cancellationToken);
                        }
                        catch
                        {
                            // Nästa anslutningsförsök hanterar återhämtningen.
                        }
                    }
                }

                await Task.Delay(5000, cancellationToken);
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            // Appen stängdes.
        }
        finally
        {
            _client.Dispose();
        }
    }

    public async Task SendCommandAsync(string deviceId, string command)
    {
        if (!_client.IsConnected)
            throw new InvalidOperationException("The app is not connected to a MQTT-broker.");

        var message = new MqttApplicationMessageBuilder()
            .WithTopic($"devices/{deviceId}/commands")
            .WithPayload(command)
            .WithQualityOfServiceLevel(MqttQualityOfServiceLevel.AtMostOnce)
            .WithRetainFlag(false)
            .Build();

        await _client.PublishAsync(message);
    }
}
