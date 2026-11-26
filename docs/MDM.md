# MDM / Android Enterprise managed configuration

Sippy supports Android Enterprise AppConfig through `RestrictionsManager`, including Knox Manage. The APK advertises its fields using `android.content.APP_RESTRICTIONS` and [app_restrictions.xml](../app/src/main/res/xml/app_restrictions.xml). No Knox SDK or device-administrator permission is needed by Sippy. The configuration belongs to the installed app in its managed device or work profile, with package ID **`org.sippy`**.

In Knox Manage, add/update Sippy as an internal Android APK and enable **Managed Configuration (Android Enterprise)**. Assign the app to your Android Enterprise devices or work profiles, open **Set Configuration**, and set **Enable managed configuration** (`managedConfigurationEnabled`) to `true`. Configure the required SIP values and save/push the assignment. Enable the MDM's **Auto-run after Install** option when provisioning new devices so Sippy reads its settings on first launch. Console wording varies between Knox Manage versions. See [Samsung's AppConfig instructions](https://docs.samsungknox.com/admin/knox-manage/kbas/kba-196/) and [internal-app instructions](https://docs.samsungknox.com/admin/knox-manage/original-console/configure/applications/add-applications/add-internal-android-ios-and-macos-apps/).

Use [mdm-example.json](mdm-example.json) as a key/value example, replacing the example server and credentials. It represents the managed configuration, not the SIP backup-file format. MDM consoles typically provide a form generated from the APK; consoles supporting JSON AppConfig can use the same keys and primitive JSON types. Configure your MDM's supported per-user/device substitutions for individual SIP credentials; Sippy expects already-resolved values and does not expand template variables itself.

Only keys actually included in the MDM payload are managed. Omit keys users should be able to edit; an explicitly supplied empty string clears/owns a setting. Some consoles insert schema defaults automatically, so review which fields are included. `codecOrder` is the exception: an empty value leaves codecs unmanaged. The enable switch defaults to `false`, and no settings are managed on ordinary personal installations.

Managed fields are marked **Managed** and cannot be edited locally. Local values stay separate, so removing a key, removing the policy, or setting `managedConfigurationEnabled=false` restores its local value. Saving/importing other settings cannot overwrite managed values. Managed SIP and LDAP passwords are read from Android's policy bundle, are not copied into Sippy's local password storage, and managed SIP passwords are excluded from exports even when password export is requested. Your MDM still holds the delivered credentials and controls access to its console/policy exports.

Updates are read on startup, resume, and Android's managed-restrictions broadcast while Sippy's process is running. SIP/network/codec reconnection waits until all current calls finish; the Application page reports a pending change. Appearance, transfer mode and other non-connection settings take effect immediately. Changing the managed CSV source clears the previous synced results and reschedules sync. Removing management keeps Local/Imported contacts and history. Invalid configurations are rejected as a whole with a value-free error on the Application page; a running app keeps its last valid policy. If no valid policy is available at startup, an invalid/pending managed policy blocks SIP connection until corrected. Force-stopped apps must be started again by the user or MDM.

AppConfig does not grant microphone/contacts/notification permissions, install credentials, exempt the app from battery restrictions, or bypass Do Not Disturb. Configure those through your MDM's normal device/app policies or allow the user's Android prompts. For an LDAP client certificate, deploy the certificate/private key and grant `org.sippy` access through the MDM (or have the user select it), then provide its alias. Ringtone URIs must refer to audio already accessible on the device; Sippy does not download remote ringtone URLs from AppConfig.

The supplied APKs use the existing debug signing identity and can be distributed as internal APKs. A Managed Google Play publication requires your production release/signing workflow. This build uses version name **1.1.8** and Android's version code **12** so MDMs can recognize it as an update to earlier builds. Keep the package/signing identity consistent when updating enrolled devices.

## Managed fields (only supplied fields are enforced):

| Key | Type | Values / meaning |
| --- | --- | --- |
| `managedConfigurationEnabled` | boolean | Must be true to apply the payload. |
| `username` | string | SIP username. |
| `authUser` | string | SIP authentication ID. |
| `password` | string | SIP secret; empty explicitly selects no password. |
| `displayName` | string | Caller display name. |
| `domain` | string | SIP domain. |
| `server` | string | Registrar override. |
| `proxy` | string | Outbound proxy. |
| `realm` | string | Authentication realm. |
| `transport` | string | `UDP`, `TCP`, `TLS` |
| `register` | boolean | Register with SIP server. |
| `expiry` | integer | 60–86400 seconds. |
| `localPort` | integer | 0–65535; 0 means automatic. |
| `rtpPort` | integer | 0–65534; 0 means automatic. |
| `encryption` | string | `None`, `SRTP`, `ZRTP`, `DTLS` |
| `dtmf` | string | `RTP`, `INFO` |
| `stun` | string | STUN server. |
| `ice` | boolean | Enable ICE. |
| `echo` | boolean | Echo cancellation. |
| `ipv6` | boolean | Enable IPv6. |
| `codecOrder` | string | Comma-separated enabled IDs in priority order: opus, g722, pcma, pcmu, g729, amr, amr-wb. SDK availability still applies. |
| `appearance` | string | `LIGHT`, `DARK`, `SYSTEM` |
| `transferMode` | string | `DIRECT_ONLY`, `ATTENDED_ONLY`, `BOTH` |
| `dialpadTones` | boolean | Dialpad tones. |
| `vibrateOnIncoming` | boolean | Vibrate on incoming calls. |
| `ringtoneUri` | string | Empty: default ringtone. Otherwise accessible content://, android.resource:// or file:// URI. |
| `backgroundCalling` | boolean | Receive calls in background. |
| `startOnBoot` | boolean | Start after phone restart. |
| `eventRetentionDays` | integer | 1–30 days. |
| `csvUrl` | string | CSV phonebook URL. |
| `csvInterval` | integer | 15–10080 minutes. |
| `ldapEnabled` | boolean | Enable LDAP directory. |
| `ldapHost` | string | LDAP server. |
| `ldapPort` | integer | 1–65535. |
| `ldapTls` | boolean | true: LDAPS; false: LDAP with StartTLS. |
| `ldapBindDn` | string | LDAP bind DN or username. |
| `ldapPassword` | string | LDAP secret. |
| `ldapBaseDn` | string | LDAP search base. |
| `ldapFilter` | string | LDAP search filter. |
| `ldapNames` | string | LDAP name attributes. |
| `ldapPhones` | string | LDAP number attributes. |
| `ldapClientCertificateAlias` | string | Android-installed client certificate alias; access must be granted separately. |

Validation: integer/boolean values must have their actual types, choices must match the documented strings, endpoints must omit protocols, and text values are limited to 16,384 characters. Unsupported keys are ignored. LDAP may be provisioned partially so users can fill unmanaged credentials before searching.

For development-only end-to-end tests, the test APK contains a temporary device-policy controller. On a disposable emulator with no device/profile owner, install it with `adb install -t`, set `org.sippy.test/org.sippy.ManagedTestAdmin` as device owner, apply its `full` fixture before starting Sippy, and run `am instrument -w -e checks mdm org.sippy.test/org.sippy.SettingsChecks`. Remove the test owner with `dpm remove-active-admin` and restore the emulator afterward. The controller is excluded from both distributable Sippy APKs; do not provision it on a user's phone.
