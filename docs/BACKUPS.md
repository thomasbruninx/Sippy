# Settings backup and restore

Open **Settings → Application → Settings backup**. Export and import each show a checkbox list so you can choose the configuration categories to include:

- SIP accounts and the outgoing-account selection
- Account audio codecs
- Account ringtones, including custom WAV/MP3 audio
- Account transfer modes
- Account DND behaviour and saved on/off state
- Device network and media settings
- Device sounds and vibration
- Application settings
- Phonebook sync settings
- LDAP settings
- Passwords (optional and off by default)

Save edits on the relevant settings page before exporting. A backup is a versioned JSON file named `Sippy-settings-backup.json`. It can contain multiple accounts and settings categories. Contacts, call history, event logs, the last dialed number, Android permissions, battery exemptions, certificate private keys and the MDM policy itself are not backed up. Local contacts still have their separate CSV export/import in Phonebook settings.

## Passwords and managed settings

Passwords are excluded by default. If you explicitly include passwords, user-entered SIP and LDAP passwords are readable in the JSON file. An SIP account password supplied by MDM is **never exported**, even if the Passwords checkbox is selected. MDM-managed LDAP passwords are excluded too. The export result identifies passwords that were excluded because they are managed.

An imported file cannot change your organization's policy or unlock a managed field. Import skips each locked value and applies editable values in the selected categories. This includes managed account identity/server settings, codecs, ringtones, transfer mode, DND behaviour and app-wide policies. Locally entered credentials for managed server templates remain editable. Managed account references are restored only when their matching account is already provisioned on the destination phone; importing a backup does not create unmanaged copies of managed accounts.

The final result reports success, failure, or completion with skipped settings, including the setting names and reasons. A validation or storage failure rolls back the imported preferences and removes newly created ringtone files. Errors identify the section being restored without displaying passwords or raw backup values. A successful settings import does not by itself guarantee SIP registration: server availability, credentials and Android permissions still apply.

## Accounts and merging

Import matches existing accounts by stable ID, then by name. Selected categories update matching accounts; other categories remain unchanged. With **SIP accounts** selected, missing personal accounts are created. Existing accounts not present in the file are kept. Without that category selected, options can be restored to matching accounts, but missing accounts are skipped. A backup without passwords leaves existing passwords unchanged; a new account without a password must be completed afterward.

Sippy still accepts the older `sippy-sip-settings` JSON format from previous releases. Its single account appears in the import category chooser with an editable account name. Codec order and shared network/media values from that file can be selected separately. Import does not require changing the file by hand.

Restore settings after ending all active calls. Sippy reconnects accounts and applies the new settings after a successful import. Phonebook sync scheduling, event-log retention and appearance are updated as well.

## Ringtones and certificates

Custom WAV/MP3 files are embedded in the JSON when Account ringtones is selected, with a 20 MB limit per ringtone and 80 MB of custom audio per backup. The maximum backup size is 128 MB. An unavailable custom tone is reported during export and represented as the default ringtone. Imported audio is validated and copied into the app's own storage.

Android built-in ringtone references are restored when accessible on the destination phone. If a reference is unavailable, the default ringtone is used and the import report explains the fallback. Managed ringtone settings always take precedence. Restoring an LDAP client-certificate alias does not install its certificate/private key or grant access: provision those separately through Android or MDM.

## Upgrading from v1.1.8

Updating the installed app with the same package and signing identity preserves its data. On first access, Sippy converts the old single SIP profile into a named **Default account**, retaining the extension, authentication ID, encrypted password, domain, registrar, transport and NAT settings. Its old codec order, ringtone selection and transfer mode are copied into that account's options. The migration also handles accounts created in earlier development builds of v1.2.0 that still used shared options.

The existing local password encryption key remains in Android Keystore. Custom ringtone files remain in the app's storage. Device-wide settings—appearance, background reception, startup, vibration, dialpad tones, local listening ports, media encryption, DTMF signaling, LDAP and phonebook sync—stay in place. Contacts and history are retained. Migration runs once; later changes to one account's codecs, ringtone or transfer mode do not affect other accounts.

Uninstalling the app or clearing its data is different from updating: it removes local settings and may remove the encryption key and custom audio. MDM administrators must also update their policy to the v1.2.0 `sipAccounts` schema. Old single-account policy fields are ignored, and codecs, ringtone and transfer mode now use per-account MDM keys. See the [MDM guide](MDM.md).
