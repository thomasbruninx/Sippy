# Usage
Basic usage instructions and feature description for Sippy.

## Connect and call

1. In **Settings → Account(s)**, enter your extension, domain, and password, then tap **Save and connect**. Expand **Advanced account settings** for a separate authentication ID, registrar, outbound proxy, display name, authentication realm, and registration interval.
2. Use **Network** to set UDP, TCP or TLS, media encryption, DTMF signaling, STUN, ICE, IPv6, echo cancellation, and local SIP/RTP ports.
3. Dial an extension or SIP URI, or select a contact and confirm the call. Grant microphone access for calls and notifications for a tap-to-return call alert. Sippy requests microphone, contacts, and supported notification permissions on first launch. Use **Settings → Application → Check permissions** to request missing permissions again or open Android settings when required.
4. During a call, use **Hold / Resume**, **Keypad**, **Mute**, **Speaker**, **New call**, or **Transfer**. A new call pauses the active line. Tap a held line to switch to it. **Direct transfer** sends a SIP REFER, and **Ask first** calls the destination before you tap **Complete**. The PBX must support the selected DTMF method and SIP transfer (including REFER with Replaces for attended transfer).

Active calls use a foreground microphone service and can continue when you switch apps. Tap the ongoing notification to return to the call. Incoming call notifications include **Accept** and **Decline** actions. Accept opens the call screen and answers after microphone permission is granted, if needed.

Starting a call from the dialer clears the number field. Press **Call** with an empty field to restore the last dialed number without placing a call; press **Call** again to dial it. The last dialed number is remembered across app restarts.

## SIP accounts and Do Not Disturb

Use **Settings → Account(s)** to add, rename, delete or select named SIP accounts. The existing account is migrated to **Default account** when upgrading. All configured accounts stay registered simultaneously, with a maximum of four simultaneous call lines in total. Accounts using the same SIP identity must use the same credentials; use different SIP usernames or domains for independent credentials. A managed account takes precedence over a personal copy of the same identity while the policy is active; the personal copy remains stored and returns when the policy is removed. The **Outgoing account** selector on the dialer chooses which account places the next call; switching it keeps registrations and current calls intact. An attended transfer uses the original call’s account. Each call shows its account name, and the persistent notification identifies the selected account and how many accounts are available.

Identity, credentials, registration, transport, media encryption, STUN and ICE belong to each account. Codecs, ringtones and transfer mode also belong to each account. DTMF signaling method, device audio settings, IPv6 and local listening ports remain shared device settings. Account edits and imports require ending current calls. Managed accounts are marked **Managed** and cannot be renamed or deleted. Fields supplied by MDM are locked; other fields remain editable, and users may select the account. For server templates provisioned with **Enter extension credentials in app**, enter the username/extension, authentication ID and password separately for each account; managed domain, registrar and transport settings remain locked. Removing a managed policy restores personal accounts without copying managed passwords into local storage.

Tap the **eye icon** beside a password field to show or hide the password. Passwords start hidden whenever the settings page is opened.

The **Do Not Disturb** toggle at the bottom of the dialer controls the selected account and declines its incoming calls. It keeps accounts registered, allows outgoing calls, and does not affect connected calls or Android’s system-wide DND setting. The persistent notification lists accounts with DND enabled. **Settings → DND behaviour** provides per-account visibility, optional Reason headers, server synchronization and an emergency-priority exception. See the [DND guide](DND.md) for defaults and protocol details.

## Background incoming calls

**Settings → Application → Background calls** controls reception after closing the app and startup after a phone restart. Both options are on by default once an account is saved. A persistent notification keeps the SIP connection available when the activity is closed or swiped from Recents. After a reboot, Sippy reconnects after the phone is unlocked; app updates also restore background reception. Idle availability does not use the microphone. Ending the last call returns the service to listening for new calls instead of stopping it.

For calls while the phone sleeps, use **Background battery settings** to allow unrestricted battery use. Samsung may also require keeping Sippy out of **Sleeping apps**. Android Doze, network availability, and manufacturer restrictions can still affect direct SIP reception; guaranteed push wake-up requires provider-side push integration. Android **Force stop** blocks reception until the user opens Sippy again. The system's foreground-app **Stop** control also stops the current listener.

## Troubleshooting and factory reset

Open **Settings → Application → Troubleshooting** to export the event log or reset Sippy.

**Export event log (CSV)** saves every retained event, regardless of the event screen’s current filter or display limit. Columns are `timestamp_utc` (ISO 8601), `level`, and `message`, ordered oldest first. The file uses UTF-8 and quotes commas, quotes and multiline messages. Existing log redaction is preserved; spreadsheet-formula prefixes in messages are escaped. An empty log exports the column headings. The result reports how many events were exported or explains a file-writing failure. Logs may contain phone numbers and server addresses, so share them with the intended troubleshooting recipient.

**Factory reset** asks for confirmation and requires ending all calls and pending file operations. It removes personal SIP accounts, all local settings and credentials (including locally entered extension details for managed templates), personal ringtone files, event logs, call history, and contacts created/imported in Sippy. Synced contact caches and temporary files are cleared too. It resets appearance, audio and other editable options to their defaults. Background sync results from before the reset cannot repopulate the cleared cache.

MDM policy stays active: managed accounts, locked values, inline managed contacts and files required by managed ringtones are retained. A managed CSV URL can download its directory again after reset. Android contacts, permissions, installed certificates, system sound settings and backup/export files saved outside Sippy are unchanged. Sippy clears its SDK call logs so old calls are not restored on reconnect. New events can appear immediately as the app starts and managed accounts reconnect. A reset cannot be undone; export settings or local contacts first if you need to keep them.

## Ringtones and settings backups

Use the **Settings page** dropdown to choose Application, Account(s), Network, DND behaviour, Codecs, Sounds, Phonebook, or LDAP. In **Settings → Sounds → Incoming ringtone**, keep the default ringtone, choose an Android ringtone, or select a WAV/MP3 file (up to 20 MB). Custom audio is checked and copied into Sippy, so moving the original file does not break ringing. Selected tones use the phone's ringtone volume and silent/vibrate mode. Return to **Default ringtone** to restore the original sound. Use **Play preview** and **Pause preview** to listen to the selected ringtone. Playback pauses and resumes from the same position; leaving Sounds or receiving a call stops it. The **Device sounds** section contains global controls for all accounts. Use the account selector in **Account ringtone** to choose which account's ringtone you are editing. The **Dialpad tones** toggle is also on the Sounds page and applies to the device. Enable **Vibrate on incoming calls** on this page to repeat a vibration pattern while a call is ringing. It is off by default. Use **Phone sound settings** to check Android's call vibration controls. If needed, enable vibration for incoming calls there. Sound follows ringtone volume and normal/vibrate mode; vibration respects silent mode and Do Not Disturb. Changing Sippy's vibration toggle during ringing applies immediately. Both alerts stop when the incoming call is answered, declined, or cancelled.

In-call DTMF keys also play a short, quiet local tone. **Settings → Sounds → Dialpad tones** controls this feedback as well as the dialer's tones; turning it off still sends DTMF to the remote party.

**Settings → Application → Settings backup** exports and imports all supported configuration categories. Both actions show checkboxes for accounts, codecs, ringtones, transfer modes, DND, device network/media, sounds, Application, phonebook sync, LDAP and optional passwords. Import skips MDM-managed fields and shows a detailed result. MDM-supplied SIP passwords are never exported. Custom ringtone audio can be included; contacts, history and logs are not part of the settings backup. Older SIP-only JSON files are still supported. See the [backup and upgrade guide](BACKUPS.md) for details.

In **Settings → Account(s) → Advanced account settings → Transfer mode**, choose **Direct only**, **Attended only**, or **Both** (the default) for the selected account. Direct only and Attended only open the destination picker immediately. Both asks you to choose Direct transfer or Ask first each time. A transfer always follows the original call's account, even if a different outgoing account is selected. The choice is saved automatically.

In **Settings → Application → Appearance**, choose **Light**, **Dark**, or **Follow system** (the default). The choice is saved and applied immediately. Follow system updates the app when Android's dark theme changes. Changing the appearance keeps the SIP service and current calls connected.

## Registration troubleshooting

Tap the **Registration failed** banner to see the response code and reason reported by Linphone. For providers that supply both a SIP username and a separate authorization ID, enter the authorization ID in **Account(s) → Advanced account settings → Authentication ID**. Use **Registrar override** for an explicit remote SIP port and **Outbound proxy** for the provider's separate proxy host and port. When set, the outbound proxy receives REGISTER and outbound signaling. The account identity still uses the SIP domain. The local SIP port is in **Network → Local ports**.

## Phonebooks

Contacts can come from the Android address book, manually added local entries, a file import, a synced CSV URL, and an inline CSV supplied by MDM. Inline managed CSV replaces the **Imported** phonebook, shown as **Imported · Managed**, and disables file imports while managed. Updates replace the entries; old imports do not return after the policy is removed. Local, Phone and Synced contacts are kept. Use the source and sort menus to filter or sort each phonebook. Name search is case-insensitive and number search ignores common number formatting. On-device additions do not write to the Android address book or to the remote CSV.

Import a UTF-8 file from **Settings → Phonebook → Import a CSV file**. Each file replaces the imported directory when validated successfully. Use **Export local contacts to CSV** on the same settings page to save only contacts created in Sippy's Local phonebook, with name and number columns. Phone, imported, and synced contacts are excluded. The separate **CSV sync** section contains the hosted URL, sync interval and sync actions. A hosted CSV can use HTTP or HTTPS. Use HTTPS for private directories. Android can defer automatic sync based on network and battery constraints. The last successful directory remains available if a sync attempt fails.

```csv
name,number
Reception,100
"Care team, East",201
IT support,302
```

An optional header is supported. Use `name` and `number` for explicit columns. Comma, semicolon, and tab delimiters are accepted. Numbers may be extensions, phone numbers, or SIP addresses. A number-only directory is valid.

### LDAP directory

**Settings → LDAP → Enable LDAP directory** turns LDAP on or off and is off by default. When off, Contacts shows your personal phonebooks without the Personal phonebooks / LDAP directory selector. Saved LDAP settings and the client-certificate selection are kept, and connection testing is disabled until LDAP is enabled again.

Configure a company directory in Settings → LDAP: server, port, LDAPS (usually 636) or LDAP with StartTLS (usually 389), bind DN, password and search base. Save the settings, or test them before saving. Server certificates and hostnames are verified; passwords are stored encrypted on the device. LDAP settings can be included in Settings backup; certificate private keys are provisioned separately.

In Contacts, select **LDAP directory** from the phonebook selector. Search by name or number, or leave the search blank to browse. LDAP results stay separate from personal phonebooks and are queried online rather than imported. Tap a result to confirm a call. Each search is limited to 200 directory entries; narrow the search if the limit is reached. Name and number attributes and the base search filter can be adjusted for your directory schema. Defaults include `displayName`, `cn`, `givenName`, `sn` and common telephone attributes. For mutual TLS, use **Choose client certificate** to select a certificate and private key from Android's credential store; use **Install client certificate** to open Android's installer for your provider's `.p12`/`.pfx` if needed, then choose the installed certificate. The choice is saved immediately. **Clear client certificate** restores **None** for password-only access. Only the alias is saved in Sippy; the private key stays in Android's credential store. A missing, expired or revoked key requires selecting it again. Clearing the selection does not delete the certificate from Android. Both LDAPS and StartTLS support client certificates, while server certificates and hostnames remain verified.

A TLS `certificate_required` response occurs before LDAP authentication and is not an invalid-password result. Confirm the exact hostname, port, access policy and acceptable client-certificate issuer with your provider. Sippy sends the configured server name during TLS negotiation (SNI). A server-requested certificate must come from an issuer that server accepts; an arbitrary or self-signed certificate will not normally work.

## Codecs

The standard Linphone dependency supplies Opus, G.722, PCMA (G.711 A-law), and PCMU (G.711 μ-law). **Settings → Codecs** includes an account selector and controls the selected account's enabled codecs and offer order. Outgoing and incoming call negotiation uses the call's own account settings. Availability is read from the installed SDK. G.729, AMR and AMR-WB appear only when available; unavailable optional codecs are hidden. Reordering the visible list preserves saved options for codecs provided by other SDK builds.

G.729 (including G.729A interoperability), AMR, and AMR-WB require a compatible Linphone Android SDK build compiled with `ENABLE_G729`, `ENABLE_AMR`, and `ENABLE_AMRWB`, respectively. To package such a build, use:

```sh
./gradlew :app:assembleDebug -PsipSdkAar=/absolute/path/to/linphone-sdk-android.aar
```

See the [Linphone codec documentation](https://wiki.linphone.org/xwiki/wiki/public/view/Lib/Features/Codecs/) for codec build and licensing details.

## Event log and call history

Open **Settings → Event log** to filter Info, Warning, Error, and SIP signaling entries. Tap a row for details. Sippy keeps events for 3 days by default, with a configurable limit of 1 to 30 days. SIP records include the request or response line and selected headers. Authentication headers and message bodies are excluded from the saved log.

Open **History** for call direction, outcome, start time, and duration. Use **Redial** to confirm a new call to the saved number or SIP address. **Clear history**, next to the title, asks for confirmation before permanently removing the saved history. Cleared records stay cleared when the SIP engine reconnects. Event logs and contacts are unaffected.

During earpiece calls, supported phones use their proximity sensor to turn the display off and block accidental face touches. Moving the phone away restores the display. Speakerphone and headset routes do not enable this protection; ending the call releases it. No camera permission is required.

Version 1.2.0 supports Android 9 and later. Use `Sippy-1.2.0-arm32.apk` for 32-bit ARM devices, including the Galaxy A6+ on Android 10, or `Sippy-1.2.0-arm64.apk` for 64-bit ARM devices. Future releases use the same filename pattern with their version number.

## Network settings

**Settings → Network** starts with **Global network settings**. DTMF method (RTP events or SIP INFO), echo cancellation, IPv6 and local SIP/RTP listening ports apply to all accounts. Use **Save global network settings** to apply them; end all calls first. Saving this section reconnects accounts without changing their identities or encryption settings.

The **Account network settings** section below has an account selector and separate controls for SIP transport, media encryption, STUN and ICE. Use **Save account network settings** to update only the selected account's values. DTMF stays global even when simultaneous calls use different encryption policies.

Media encryption choices remain None (RTP), SRTP (SDES), ZRTP and DTLS-SRTP, subject to SDK support. Sippy applies the receiving/placing account's encryption and required-encryption policy to each call, including incoming answers, consultation calls and media updates. Encrypted modes reject calls that cannot negotiate the selected method. Switching the outgoing account does not change an existing call's policy. None leaves outgoing media unencrypted; encrypted SIP signaling (TLS) is a separate setting.

On upgrade, the old shared encryption selection is copied once to existing accounts. Their settings can then be changed independently. Settings backups include media encryption under **SIP accounts, media encryption and outgoing selection**, rather than device network/media settings. Older SIP-only and early v1.2.0 backups are still accepted; restoring an older shared encryption value requires selecting the SIP accounts category.
