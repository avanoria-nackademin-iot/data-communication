using Microsoft.UI.Xaml;
using System;
using System.Globalization;
using System.Linq;
using System.Threading.Tasks;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Storage.Streams;


namespace BluetoothReceiver;

public sealed partial class MainWindow : Window
{
    private static readonly Guid ServiceUuid = Guid.Parse("98e7d326-0000-4e78-946d-aef7eafa62d0");
    private static readonly Guid CharacteristicUuid = Guid.Parse("98e7d326-0001-4e78-946d-aef7eafa62d0");

    private BluetoothLEAdvertisementWatcher? _watcher;
    private BluetoothLEDevice? _device;
    private GattDeviceService? _service;
    private GattCharacteristic? _characteristic;
    private bool _connecting;


    public MainWindow()
    {
        InitializeComponent();
    }

    private void ConnectButton_Click(object sender, RoutedEventArgs e)
    {
        StartScanning();
    }

    private void StartScanning()
    {
        StatusText.Text = "Seaching for device...";
        ConnectButton.IsEnabled = false;

        _watcher = new BluetoothLEAdvertisementWatcher
        {
            ScanningMode = BluetoothLEScanningMode.Active
        };

        _watcher.Received += Watcher_Received;
        _watcher.Start();
    }

    private void Watcher_Received(BluetoothLEAdvertisementWatcher sender, BluetoothLEAdvertisementReceivedEventArgs args)
    {
        if (_connecting)
            return;

        if (args.Advertisement.LocalName != "Avanoria-DHT11")
            return;

        _connecting = true;

        sender.Stop();

        DispatcherQueue.TryEnqueue(async () =>
        {
            await ConnectToDeviceAsync(args.BluetoothAddress);
        }); 
    }

    private async Task ConnectToDeviceAsync(ulong bluetoothAddress)
    {
        StatusText.Text = "Device found. Connecting...";

        _device = await BluetoothLEDevice.FromBluetoothAddressAsync(bluetoothAddress);

        if (_device is null)
        {
            ConnectionFailed("Unable to connect to bluetooth device.");
            return;
        }

        _device.ConnectionStatusChanged += Device_ConnectionStatusChanged;

        var serviceResult = await _device.GetGattServicesForUuidAsync(ServiceUuid, BluetoothCacheMode.Uncached);

        if (serviceResult.Status != GattCommunicationStatus.Success)
        {
            ConnectionFailed("Unable to find sensor service");
            return;
        }


        _service = serviceResult.Services.FirstOrDefault();

        if (_service is null)
        {
            ConnectionFailed("BLE Service is missing.");
            return;
        }

        var characteristicResult = await _service.GetCharacteristicsForUuidAsync(CharacteristicUuid, BluetoothCacheMode.Uncached);

        if (characteristicResult.Status != GattCommunicationStatus.Success)
        {
            ConnectionFailed("Unable to find sensor characteristic");
            return;
        }

        _characteristic = characteristicResult.Characteristics.FirstOrDefault();

        if (_characteristic is null)
        {
            ConnectionFailed("BLE-characteristic is missing");
            return;
        }

        _characteristic.ValueChanged += OnSensorData;

        var notificationResult = await _characteristic.WriteClientCharacteristicConfigurationDescriptorAsync(GattClientCharacteristicConfigurationDescriptorValue.Notify);

        if (notificationResult != GattCommunicationStatus.Success)
        {
            ConnectionFailed("Unable to activate BLE-notifications.");
            return;
        }

        StatusText.Text = $"Connected to {_device.Name}";
        ConnectButton.Content = "Connected";

    }

    private void OnSensorData(GattCharacteristic sender, GattValueChangedEventArgs args)
    {
        using var reader = DataReader.FromBuffer(args.CharacteristicValue);
        reader.UnicodeEncoding = UnicodeEncoding.Utf8;

        var rawData = reader.ReadString(reader.UnconsumedBufferLength);
        var parts = rawData.Split(";");

        if (parts.Length != 2)
            return;

        if (!double.TryParse(parts[0], System.Globalization.NumberStyles.Float, CultureInfo.InvariantCulture, out var temperature))
            return;

        if (!double.TryParse(parts[1], System.Globalization.NumberStyles.Float, CultureInfo.InvariantCulture, out var humidity))
            return;

        DispatcherQueue.TryEnqueue(() =>
        {
            TemperatureText.Text = $"{temperature:F1} °C";
            HumidityText.Text = $"{humidity:F1} %";
        });
    }

    private void Device_ConnectionStatusChanged(BluetoothLEDevice sender, object args)
    {
        if (sender.ConnectionStatus != BluetoothConnectionStatus.Disconnected)
            return;

        DispatcherQueue.TryEnqueue(() =>
        {
            StatusText.Text = "Connection aborted.";
            ConnectButton.IsEnabled = true;
            _connecting = false;
        });
    }

    private void ConnectionFailed(string message)
    {
        StatusText.Text = message;
        ConnectButton.IsEnabled = true;

        _connecting = false;
    }
}