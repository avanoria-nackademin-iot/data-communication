using Azure.Messaging.EventHubs.Consumer;

var connectionString = "Endpoint=sb://iothub-ns-avanoria-i-68584365-6987ca168f.servicebus.windows.net/;SharedAccessKeyName=iothubowner;SharedAccessKey=SVF/JCaeL2+X1cKz38Gqdr//4OeXBee3sAIoTPb1uzg=;EntityPath=avanoria-iot-hub";

await using var consumer = new EventHubConsumerClient(
    EventHubConsumerClient.DefaultConsumerGroupName,
    connectionString);

try
{
    await foreach (var receivedEvent in consumer.ReadEventsAsync(false))
    {
        var json = receivedEvent.Data.EventBody.ToString();
        
        Console.WriteLine($"Received: {json}");
    }
}
catch (Exception ex)
{
    Console.WriteLine(ex.Message);
}