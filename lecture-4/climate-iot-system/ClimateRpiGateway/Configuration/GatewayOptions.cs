namespace ClimateRpiGateway.Configuration;

public record GatewayOptions
(
    string GatewayId,
    string MqttHost,
    int MqttPort,
    string AzureConnectionString,
    TimeSpan AzureSendInterval,
    TimeSpan StaleAfter
);

