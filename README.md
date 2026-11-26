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

Active calls use a foreground microphone service and can continue when you switch apps. Tap the ongoing notification to return to the call. Incoming call notifications include **Accept** and **Decline** actions. Accept opens the call screen and answers after microphone permission is granted, if needed.

## Background incoming calls

**Settings → Application → Background calls** controls reception after closing the app and startup after a phone restart. Both options are on by default once an account is saved. A persistent notification keeps the SIP connection available when the activity is closed or swiped from Recents. After a reboot, Sippy reconnects after the phone is unlocked; app updates also restore background reception. Idle availability does not use the microphone. Ending the last call returns the service to listening for new calls instead of stopping it.

For calls while the phone sleeps, use **Background battery settings** to allow unrestricted battery use. Samsung may also require keeping Sippy out of **Sleeping apps**. Android Doze, network availability, and manufacturer restrictions can still affect direct SIP reception; guaranteed push wake-up requires provider-side push integration. Android **Force stop** blocks reception until the user opens Sippy again. The system's foreground-app **Stop** control also stops the current listener.

## Ringtones and settings backups

Use the **Settings page** dropdown to choose Account, Network, Codecs, Phonebook, Sounds, Application, or LDAP. In **Settings → Sounds → Incoming ringtone**, keep the default ringtone, choose an Android ringtone, or select a WAV/MP3 file (up to 20 MB). Custom audio is checked and copied into Sippy, so moving the original file does not break ringing. Selected tones use the phone's ringtone volume and silent/vibrate mode. Return to **Default ringtone** to restore the original sound. Use **Play preview** and **Pause preview** to listen to the selected ringtone. Playback pauses and resumes from the same position; leaving Sounds or receiving a call stops it. The **Dialpad tones** toggle is also on the Sounds page. Enable **Vibrate on incoming calls** on this page to repeat a vibration pattern while a call is ringing. It is off by default. Sound follows ringtone volume and normal/vibrate mode; vibration respects silent mode and Do Not Disturb. Both alerts stop when the incoming call is answered, declined, or cancelled.

In-call DTMF keys also play a short, quiet local tone. **Settings → Sounds → Dialpad tones** controls this feedback as well as the dialer's tones; turning it off still sends DTMF to the remote party.

**Export SIP settings** saves the stored account, advanced account settings, network/media settings, and codec order to a versioned JSON file. Save any edits before exporting. Password export is optional and off by default; when included, the JSON contains the readable password. **Import SIP settings** validates the file and asks before replacing the saved settings and reconnecting. An export without a password requires entering the password again after import. Ringtones, contacts, history, and event logs are not part of the SIP settings backup.

In **Settings → Application → Appearance**, choose **Light**, **Dark**, or **Follow system** (the default). The choice is saved and applied immediately. Follow system updates the app when Android's dark theme changes. Changing the appearance keeps the SIP service and current calls connected.

## Registration troubleshooting

Tap the **Registration failed** banner to see the response code and reason reported by Linphone. For providers that supply both a SIP username and a separate authorization ID, enter the authorization ID in **Account → Advanced account settings → Authentication ID**. Use **Registrar override** for an explicit remote SIP port and **Outbound proxy** for the provider's separate proxy host and port. When set, the outbound proxy receives REGISTER and outbound signaling. The account identity still uses the SIP domain. The local SIP port is in **Network → Local ports**.

## Phonebooks

Contacts can come from the Android address book, manually added local entries, a file import, and a synced CSV URL. Use the source and sort menus to filter or sort each phonebook. Name search is case-insensitive and number search ignores common number formatting. On-device additions do not write to the Android address book or to the remote CSV.

Import a UTF-8 file from **Settings → Phonebook → Import a CSV file**. Each file replaces the imported directory when validated successfully. Use **Export local contacts to CSV** on the same settings page to save only contacts created in Sippy's Local phonebook, with name and number columns. Phone, imported, and synced contacts are excluded. A hosted CSV can use HTTP or HTTPS. Use HTTPS for private directories. Android can defer automatic sync based on network and battery constraints. The last successful directory remains available if a sync attempt fails.

```csv
name,number
Reception,100
"Care team, East",201
IT support,302
```

An optional header is supported. Use `name` and `number` for explicit columns. Comma, semicolon, and tab delimiters are accepted. Numbers may be extensions, phone numbers, or SIP addresses. A number-only directory is valid.

### LDAP directory

**Settings → LDAP → Enable LDAP directory** turns LDAP on or off and is off by default. When off, Contacts shows your personal phonebooks without the Personal phonebooks / LDAP directory selector. Saved LDAP settings and the client-certificate selection are kept, and connection testing is disabled until LDAP is enabled again.

Configure a company directory in Settings → LDAP: server, port, LDAPS (usually 636) or LDAP with StartTLS (usually 389), bind DN, password and search base. Save the settings, or test them before saving. Server certificates and hostnames are verified; passwords are stored encrypted on the device. LDAP settings are separate from SIP JSON backups.

In Contacts, select **LDAP directory** from the phonebook selector. Search by name or number, or leave the search blank to browse. LDAP results stay separate from personal phonebooks and are queried online rather than imported. Tap a result to confirm a call. Each search is limited to 200 directory entries; narrow the search if the limit is reached. Name and number attributes and the base search filter can be adjusted for your directory schema. Defaults include `displayName`, `cn`, `givenName`, `sn` and common telephone attributes. For mutual TLS, use **Choose client certificate** to select a certificate and private key from Android's credential store; use **Install client certificate** to open Android's installer for your provider's `.p12`/`.pfx` if needed, then choose the installed certificate. The choice is saved immediately. **Clear client certificate** restores **None** for password-only access. Only the alias is saved in Sippy; the private key stays in Android's credential store. A missing, expired or revoked key requires selecting it again. Clearing the selection does not delete the certificate from Android. Both LDAPS and StartTLS support client certificates, while server certificates and hostnames remain verified.

A TLS `certificate_required` response occurs before LDAP authentication and is not an invalid-password result. Confirm the exact hostname, port, access policy and acceptable client-certificate issuer with your provider. Sippy sends the configured server name during TLS negotiation (SNI). A server-requested certificate must come from an issuer that server accepts; an arbitrary or self-signed certificate will not normally work.

## Codecs

The standard Linphone dependency supplies Opus, G.722, PCMA (G.711 A-law), and PCMU (G.711 μ-law). **Settings → Codecs** controls the enabled codecs and offer order. Availability is read from the installed SDK.

G.729 (including G.729A interoperability), AMR, and AMR-WB require a compatible Linphone Android SDK build compiled with `ENABLE_G729`, `ENABLE_AMR`, and `ENABLE_AMRWB`, respectively. To package such a build, use:

```sh
./gradlew :app:assembleDebug -PsipSdkAar=/absolute/path/to/linphone-sdk-android.aar
```

See the [Linphone codec documentation](https://wiki.linphone.org/xwiki/wiki/public/view/Lib/Features/Codecs/) for codec build and licensing details.

## Event log and call history

Open **Settings → Event log** to filter Info, Warning, Error, and SIP signaling entries. Tap a row for details. Sippy keeps events for 3 days by default, with a configurable limit of 1 to 30 days. SIP records include the request or response line and selected headers. Authentication headers and message bodies are excluded from the saved log.

Open **History** for call direction, outcome, start time, and duration. Use **Redial** to confirm a new call to the saved number or SIP address. **Clear history**, next to the title, asks for confirmation before permanently removing the saved history. Cleared records stay cleared when the SIP engine reconnects. Event logs and contacts are unaffected.

During earpiece calls, supported phones use their proximity sensor to turn the display off and block accidental face touches. Moving the phone away restores the display. Speakerphone and headset routes do not enable this protection; ending the call releases it. No camera permission is required.

Version 1.1.7 supports Android 9 and later, including the Galaxy A6+ on Android 10, with a separate ARM64 build for modern phones.
