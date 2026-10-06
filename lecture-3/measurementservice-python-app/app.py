import json
import math
import queue
import tkinter as tk
from tkinter import ttk
from uuid import uuid4

import paho.mqtt.client as mqtt


MQTT_HOST = ""
MQTT_PORT = 1883
MQTT_USERNAME = ""
MQTT_PASSWORD = ""

# + betyder ett valfritt segment, exempelvis c309 eller c308.
MQTT_MEASUREMENT_TOPIC = ""


class MeasurementApp:
    def __init__(self, root):
        self.root = root
        self.root.title("Measurement Service")
        self.root.geometry("900x450")

        # MQTT-callbacks lägger händelser i kön.
        # Gränssnittet behandlar dem på sin egen tråd.
        self.events = queue.Queue()

        self.connection_text = tk.StringVar(value="Ansluter till MQTT...")
        self.command_text = tk.StringVar(value="Markera en enhet för att skicka kommandon.")

        self.build_ui()

        # Eget klient-ID så appen inte kopplar bort ESP32-klienten.
        self.client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id=f"measurement-app-{uuid4().hex}",
            protocol=mqtt.MQTTv311,
        )

        self.client.username_pw_set(MQTT_USERNAME, MQTT_PASSWORD)
        self.client.reconnect_delay_set(min_delay=1, max_delay=10)

        self.client.on_connect = self.on_connect
        self.client.on_connect_fail = self.on_connect_fail
        self.client.on_disconnect = self.on_disconnect
        self.client.on_subscribe = self.on_subscribe
        self.client.on_message = self.on_message

        # Anslut utan att blockera gränssnittet.
        self.client.connect_async(MQTT_HOST, MQTT_PORT, keepalive=60)

        # Starta MQTT-kommunikationen i en bakgrundstråd.
        self.client.loop_start()

        self.root.after(100, self.process_events)
        self.root.protocol("WM_DELETE_WINDOW", self.close)

    def build_ui(self):
        frame = ttk.Frame(self.root, padding=20)
        frame.pack(fill=tk.BOTH, expand=True)

        ttk.Label(frame, textvariable=self.connection_text).pack(anchor=tk.W, pady=(0, 15))

        self.device_list = ttk.Treeview(
            frame,
            columns=("device_id", "value", "unit"),
            show="headings",
            selectmode="browse",
        )

        self.device_list.heading("device_id", text="Device Id")
        self.device_list.heading("value", text="Senaste värde")
        self.device_list.heading("unit", text="Enhet")

        self.device_list.column("device_id", width=400)
        self.device_list.column("value", width=150, anchor=tk.CENTER)
        self.device_list.column("unit", width=100, anchor=tk.CENTER)

        self.device_list.pack(fill=tk.BOTH, expand=True)

        button_frame = ttk.Frame(frame)
        button_frame.pack(fill=tk.X, pady=15)

        ttk.Button(
            button_frame,
            text="START",
            command=lambda: self.send_command("start"),
        ).pack(side=tk.LEFT, padx=(0, 10))

        ttk.Button(
            button_frame,
            text="STOP",
            command=lambda: self.send_command("stop"),
        ).pack(side=tk.LEFT)

        ttk.Label(frame, textvariable=self.command_text).pack(anchor=tk.W)

    def on_connect(self, client, userdata, flags, reason_code, properties):
        if reason_code.is_failure:
            self.events.put(("connection", f"Anslutningen nekades: {reason_code}"))
            return

        # Prenumerera även efter en återanslutning.
        client.subscribe(MQTT_MEASUREMENT_TOPIC, qos=0)
        self.events.put(("connection", "MQTT anslutet"))

    def on_connect_fail(self, client, userdata):
        self.events.put(("connection", "Kunde inte ansluta. Försöker igen..."))

    def on_disconnect(self, client, userdata, disconnect_flags, reason_code, properties):
        self.events.put(("connection", "MQTT frånkopplat. Försöker återansluta..."))

    def on_subscribe(self, client, userdata, mid, reason_codes, properties):
        if any(code.is_failure for code in reason_codes):
            self.events.put(("connection", "Brokern nekade prenumerationen."))

    def on_message(self, client, userdata, message):
        # Uppdatera inte Tkinter direkt från MQTT:s bakgrundstråd.
        self.events.put(("measurement", message.payload))

    def process_events(self):
        # Behandla högst 100 händelser per omgång så UI kan fortsätta svara.
        for _ in range(100):
            try:
                event_type, data = self.events.get_nowait()
            except queue.Empty:
                break

            if event_type == "connection":
                self.connection_text.set(data)
            elif event_type == "measurement":
                self.update_measurement(data)

        self.root.after(100, self.process_events)

    def update_measurement(self, payload):
        try:
            # Gör JSON-meddelandet till en Python-dictionary.
            measurement = json.loads(payload.decode("utf-8"))

            if not isinstance(measurement, dict):
                raise ValueError("Meddelandet måste vara ett JSON-objekt.")

            device_id = measurement["deviceId"]
            value = measurement["value"]
            unit = measurement["unit"]

            if not isinstance(device_id, str) or not device_id.strip():
                raise ValueError("Device Id saknas.")

            # Device Id ska kunna användas som ett segment i kommando-topic.
            if any(character in device_id for character in "/+#\0"):
                raise ValueError("Device Id innehåller otillåtna tecken.")

            if isinstance(value, bool) or not isinstance(value, (int, float)):
                raise ValueError("Mätvärdet måste vara numeriskt.")

            if not math.isfinite(value):
                raise ValueError("Mätvärdet måste vara ett ändligt tal.")

            if not isinstance(unit, str) or not unit.strip():
                raise ValueError("Enhet saknas.")

            values = (device_id, f"{value:.1f}", unit)

            if self.device_list.exists(device_id):
                # Uppdatera den befintliga enhetens senaste mätvärde.
                self.device_list.item(device_id, values=values)
            else:
                # Lägg till en ny enhet i listan.
                self.device_list.insert("", tk.END, iid=device_id, values=values)

        except (UnicodeDecodeError, ValueError, KeyError, OverflowError) as error:
            self.command_text.set(f"Felaktigt mätmeddelande: {error}")

    def send_command(self, command):
        selected = self.device_list.selection()

        if not selected:
            self.command_text.set("Markera en enhet först.")
            return

        if not self.client.is_connected():
            self.command_text.set("Appen är inte ansluten till MQTT.")
            return

        device_id = selected[0]
        topic = f"devices/{device_id}/commands"

        # Kommandon sparas inte som retained-meddelanden.
        result = self.client.publish(topic, payload=command, qos=0, retain=False)

        if result.rc == mqtt.MQTT_ERR_SUCCESS:
            self.command_text.set(f"Kommandot '{command}' lämnat för sändning till {topic}.")
        else:
            self.command_text.set(f"Kunde inte skicka: {mqtt.error_string(result.rc)}")

    def close(self):
        self.client.disconnect()
        self.client.loop_stop()
        self.root.destroy()


if __name__ == "__main__":
    root = tk.Tk()
    app = MeasurementApp(root)
    root.mainloop()