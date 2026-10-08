using Microsoft.Azure.Devices.Client;
using System.Text;
using System.Text.Json;

var connectionString = "";

using var client = DeviceClient.CreateFromConnectionString(connectionString, TransportType.Mqtt);

await client.OpenAsync();

try
{
    var measasurement = new
    {
        deviceId = "test-device-01",
        temperature = 22.5,
        unit = "°C",
        measuredAt = DateTime.Now
    };

    var json = JsonSerializer.Serialize(measasurement);

    using var message = new Message(Encoding.UTF8.GetBytes(json))
    {
        ContentType = "application/json",
        ContentEncoding = "utf-8"
    };

    await client.SendEventAsync(message);
    Console.WriteLine($"Message sent: {json}");

}
catch (Exception ex)
{
    Console.WriteLine($"{ex.Message}");
}