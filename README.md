# Sippy

![Sippy logo](logo.png)

Sippy is a Kotlin Android SIP softphone powered by Linphone. It supports one SIP account and up to four simultaneous call lines.

## Build and run

Open this directory in Android Studio, use JDK 21 and Android SDK 36, select the `app` run configuration, and run it against a device or emulator. From a shell with the same tools, run `./gradlew :app:assembleDebug`.

For a smaller device-specific APK, use `./gradlew :app:assembleDebug -PtargetAbi=armeabi-v7a` for older ARM32 based phones or `./gradlew :app:assembleDebug -PtargetAbi=arm64-v8a` for modern ARM64 phones. Without `targetAbi`, the APK includes all SDK-supported architectures.

Sippy supports Android 9 and later through its minimum SDK of 28. It targets Android 16 (API 36) and was tested on an Android 16 emulator. 

## Connect and call

1. In **Settings → Account**, enter your extension, domain, and password, then tap **Save and connect**. Expand **Advanced account settings** for a separate authentication ID, registrar, outbound proxy, display name, authentication realm, and registration interval.
2. Use **Network** to set UDP, TCP or TLS, media encryption, DTMF signaling, STUN, ICE, IPv6, echo cancellation, and local SIP/RTP ports.
3. Dial an extension or SIP URI, or select a contact and confirm the call. Grant microphone access for calls and notifications for a tap-to-return call alert. Sippy requests microphone, contacts, and supported notification permissions on first launch. Use **Settings → Application → Check permissions** to request missing permissions again or open Android settings when required.
4. During a call, use **Hold / Resume**, **Keypad**, **Mute**, **Speaker**, **New call**, or **Transfer**. A new call pauses the active line. Tap a held line to switch to it. **Direct transfer** sends a SIP REFER, and **Ask first** calls the destination before you tap **Complete**. The PBX must support the selected DTMF method and SIP transfer (including REFER with Replaces for attended transfer).

Active calls use a foreground microphone service and can continue when you switch apps. Tap the ongoing notification to return to the call. Incoming call notifications include **Accept** and **Decline** actions. Accept opens the call screen and answers after microphone permission is granted, if needed. Idle incoming calls can ring while Sippy's SIP service is running. Incoming push and wake-up after the app has been stopped require separate provider-side integration.

## Ringtones and settings backups

Use the **Settings page** dropdown to choose Account, Network, Codecs, Phonebook, Sounds, or Application. In **Settings → Sounds → Incoming ringtone**, keep the default ringtone, choose an Android ringtone, or select a WAV/MP3 file (up to 20 MB). Custom audio is checked and copied into Sippy, so moving the original file does not break ringing. Selected tones use the phone's ringtone volume and silent/vibrate mode. Return to **Default ringtone** to restore the original sound. Use **Play preview** and **Pause preview** to listen to the selected ringtone. Playback pauses and resumes from the same position; leaving Sounds or receiving a call stops it. The **Dialpad tones** toggle is also on the Sounds page. Enable **Vibrate on incoming calls** on this page to repeat a vibration pattern while a call is ringing. It is off by default. Sound follows ringtone volume and normal/vibrate mode; vibration respects silent mode and Do Not Disturb. Both alerts stop when the incoming call is answered, declined, or cancelled.

**Export SIP settings** saves the stored account, advanced account settings, network/media settings, and codec order to a versioned JSON file. Save any edits before exporting. Password export is optional and off by default; when included, the JSON contains the readable password. **Import SIP settings** validates the file and asks before replacing the saved settings and reconnecting. An export without a password requires entering the password again after import. Ringtones, contacts, history, and event logs are not part of the SIP settings backup.

In **Settings → Application → Appearance**, choose **Light**, **Dark**, or **Follow system** (the default). The choice is saved and applied immediately. Follow system updates the app when Android's dark theme changes. Changing the appearance keeps the SIP service and current calls connected.

## Registration troubleshooting

Tap the **Registration failed** banner to see the response code and reason reported by Linphone. For providers that supply both a SIP username and a separate authorization ID, enter the authorization ID in **Account → Advanced account settings → Authentication ID**. Use **Registrar override** for an explicit remote SIP port and **Outbound proxy** for the provider's separate proxy host and port. When set, the outbound proxy receives REGISTER and outbound signaling. The account identity still uses the SIP domain. The local SIP port is in **Network → Local ports**.

## Phonebooks

Contacts can come from the Android address book, manually added local entries, a file import, and a synced CSV URL. Use the source and sort menus to filter or sort each phonebook. Name search is case-insensitive and number search ignores common number formatting. On-device additions do not write to the Android address book or to the remote CSV.

Import an UTF-8 file from **Contacts** or **Settings → Phonebook**. Each file replaces the imported directory when validated successfully. A hosted CSV can use HTTP or HTTPS. Use HTTPS for private directories. Android can defer automatic sync based on network and battery constraints. The last successful directory remains available if a sync attempt fails.

```csv
name,number
Reception,100
"Care team, East",201
IT support,302
```

An optional header is supported. Use `name` and `number` for explicit columns. Comma, semicolon, and tab delimiters are accepted. Numbers may be extensions, phone numbers, or SIP addresses. A number-only directory is valid.

## Codecs

The standard Linphone dependency supplies Opus, G.722, PCMA (G.711 A-law), and PCMU (G.711 μ-law). **Settings → Codecs** controls the enabled codecs and offer order. Availability is read from the installed SDK.

G.729 (including G.729A interoperability), AMR, and AMR-WB require a compatible Linphone Android SDK build compiled with `ENABLE_G729`, `ENABLE_AMR`, and `ENABLE_AMRWB`, respectively. To package such a build, use:

```sh
./gradlew :app:assembleDebug -PsipSdkAar=/absolute/path/to/linphone-sdk-android.aar
```

See the [Linphone codec documentation](https://wiki.linphone.org/xwiki/wiki/public/view/Lib/Features/Codecs/) for codec build and licensing details.

## Event log and call history

Open **Settings → Event log** to filter Info, Warning, Error, and SIP signaling entries. Tap a row for details. Sippy keeps events for 3 days by default, with a configurable limit of 1 to 30 days. SIP records include the request or response line and selected headers. Authentication headers and message bodies are excluded from the saved log.

Open **History** for call direction, outcome, start time, and duration. Use **Redial** to confirm a new call to the saved number or SIP address. Call history is stored separately from the event log retention setting.
