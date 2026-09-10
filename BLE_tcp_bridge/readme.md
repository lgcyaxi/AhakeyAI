# Windows BLE backend

Studio launches the packaged backend with `--headless --parent-pid <studio-pid>`.
This mode creates no application window or tray icon. The owner process is
checked every second; when it exits, the listener, GATT session and device
watcher are released. Studio may also terminate only its own child process.
No-argument launch retains the optional standalone diagnostics window;
`--minimized` retains its historical tray behavior.

The backend listens only on `127.0.0.1`, port 9000 by default. `--port <port>`
overrides the listener without changing saved configuration. Device preferences
remain under LocalApplicationData/AhaKey Studio/ble-driver/config_server.json.
Only a successfully connected AhaKey device is persisted. Saved-device
reconnection matches its address and tolerates delayed Windows name updates.

## Studio control protocol

All messages use `[type:u8][length:u16 little-endian][payload:length bytes]`.
Types 0x01 through 0x04 and responses 0x81 through 0x83 remain compatible.
0x82 marks the target ready only after the GATT notification subscription has
succeeded and Windows reports a connected link. An absent 0x83 payload means
that no current device status, including battery level, has been received.

| Request | Payload | Response |
| --- | --- | --- |
| 0x05 list | empty | 0x84 UTF-8 JSON device snapshot |
| 0x06 select/connect | UTF-8 opaque device id from list | 0x85 acknowledgement |
| 0x07 rescan | empty | 0x85 acknowledgement |
| 0x08 disconnect | empty | 0x85 acknowledgement |

Device snapshot: `{"devices":[{"id":"...","name":"...","mac":"..."}],"state":"scanning","error":"","saved":false}`.
State is `scanning`, `connecting`, `ready`, `disconnected`, or `error`.
Acknowledgement: `{"ok":true,"message":"connecting"}`. An accepted selection
starts an asynchronous connection; it does not mean that the device is ready.
Poll 0x03 and 0x05 for completion. Disconnect suspends automatic reconnect until
an explicit selection or process restart. Scan refreshes discovery but does not
silently undo a deliberate disconnect. The list is bounded to 100 entries and
60,000 UTF-8 bytes. Do not treat native device identifiers as command text.

Failures expose a stage and status, not raw native exception messages. Opening
a BluetoothLEDevice object alone is not evidence of a physical connection.
Service discovery and notification registration must both succeed. Attempts
have a 30-second timeout, with at least eight seconds before automatic retry;
stale callbacks are ignored and old GATT resources are disposed.

## Offline diagnostics

`--headless --no-bluetooth --port <test-port> --parent-pid <test-parent-pid>`
starts the protocol host without scanning, connecting, reading or migrating
real user preferences. List/status queries and disconnect remain available;
scan/select report that Bluetooth is disabled. Use an isolated ephemeral test
port. This verifies IPC/lifecycle behavior, not real BLE radio or battery data.
