# Hera Android Debug

Android debug companion for the Hera/JL7016 earphone firmware. The app connects
to the earphone over BLE, subscribes to the AE04 notification characteristic,
captures the OPUS stream, and can play either side of the call audio for
verification.

<p>
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Android-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white">
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white">
  <img alt="BLE" src="https://img.shields.io/badge/Transport-BLE%20GATT-0082FC?style=for-the-badge&logo=bluetooth&logoColor=white">
  <img alt="OPUS" src="https://img.shields.io/badge/Audio-OPUS%2016k-00A67E?style=for-the-badge">
  <img alt="Architecture" src="https://img.shields.io/badge/Architecture-Single%20Activity%20Compose-263238?style=for-the-badge">
</p>

## What It Does

This app is built for firmware bring-up and audio-path verification. It helps
answer two concrete questions:

- Is the earphone sending AE04 BLE notify packets?
- Are the first and second OPUS payloads carrying the expected audio source?

The current firmware packet layout is:

```text
byte 0      : VAD flag
byte 1-40   : mic OPUS frame, local/user side
byte 41-80  : peer OPUS frame, remote/HFP side during a call
byte 81     : sequence number
byte 82-83  : reserved / optional length bytes
```

## Playback Modes

The main screen includes two playback source buttons:

```text
Play Mic 1-40
Play Peer 41-80
```

Use `Play Mic 1-40` to listen to the local microphone side. Use
`Play Peer 41-80` to listen to the remote party audio that the firmware captures
from the HFP/eSCO playback path.

Expected behavior:

| Scenario | `Play Mic 1-40` | `Play Peer 41-80` |
| --- | --- | --- |
| Not in a phone call | Local mic audio | Usually silent / empty |
| During a phone call | Local/user side audio | Remote caller audio |

When switching playback source, the app clears the decode queue and recreates
the OPUS decoder so frames from the two streams do not share decoder state.

## BLE Target

The app looks for the Hera AE04 service and characteristic:

```text
Service UUID        0000ae00-0000-1000-8000-00805f9b34fb
Notify Char UUID    0000ae04-0000-1000-8000-00805f9b34fb
```

The device name filter is currently:

```text
Hera
```

## Typical Test Flow

1. Flash the matching Hera/JL7016 firmware.
2. Install and open this Android app.
3. Tap `Grant Perms`.
4. Tap `Start Scan`, then `Connect`.
5. Tap `Subscribe`.
6. Tap `Start Decode`.
7. Select `Play Mic 1-40` or `Play Peer 41-80`.
8. Make a phone call and compare both playback modes.

During a call, the firmware should log `src:mic+peer`, and this app should show
non-zero counts for both MIC and DEC/peer frames when both streams are present.

## Capture Files

The app can save raw packet and OPUS data under the app external files directory:

```text
captures/ae04_packets_yyyyMMdd_HHmmss.bin
captures/ae04_mic_yyyyMMdd_HHmmss.opus
```

`ae04_packets_*.bin` contains the raw BLE notify packets. The mic OPUS file is
kept for quick local-side inspection; for full dual-stream analysis, use the raw
packet capture and split bytes `1-40` and `41-80`.

## Build

From the project root:

```powershell
.\gradlew.bat assembleDebug
```

Debug APK output:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Architecture Notes

| Layer | Implementation |
| --- | --- |
| UI | Jetpack Compose + Material 3 |
| BLE | Android `BluetoothGattCallback` notification flow |
| Decode | Concentus OPUS decoder at 16 kHz mono |
| Playback | `AudioTrack` streaming PCM16 mono |
| Capture | Raw packet and OPUS frame files via `FileOutputStream` |
| App shape | Single-activity debug utility |

## Troubleshooting

If packets arrive but audio is silent:

- Check whether `MIC` or `DEC` non-zero counters are increasing.
- Verify that the selected playback mode matches the stream being tested.
- During calls, confirm the firmware sends 84-byte packets with both OPUS halves.
- If `Play Peer 41-80` is silent, check the firmware HFP/eSCO peer path first.

If no packets arrive:

- Confirm Android permissions are granted.
- Confirm the app is subscribed to AE04 notify.
- Confirm the firmware enables AE04 CCC and logs `ae04 notify ok`.
