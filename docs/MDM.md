# MDM / Android Enterprise managed configuration

Sippy supports Android Enterprise AppConfig through `RestrictionsManager`, including Knox Manage. The APK advertises its fields using `android.content.APP_RESTRICTIONS` and [app_restrictions.xml](../app/src/main/res/xml/app_restrictions.xml). No Knox SDK or device-administrator permission is needed by Sippy. The configuration belongs to the installed app in its managed device or work profile, with package ID **`org.sippy`**.

In Knox Manage, add/update Sippy as an internal Android APK and enable **Managed Configuration (Android Enterprise)**. Assign the app to your Android Enterprise devices or work profiles, open **Set Configuration**, and set **Enable managed configuration** (`managedConfigurationEnabled`) to `true`. Configure the required SIP values and save/push the assignment. Enable the MDM's **Auto-run after Install** option when provisioning new devices so Sippy reads its settings on first launch. Console wording varies between Knox Manage versions. See [Samsung's AppConfig instructions](https://docs.samsungknox.com/admin/knox-manage/kbas/kba-196/) and [internal-app instructions](https://docs.samsungknox.com/admin/knox-manage/original-console/configure/applications/add-applications/add-internal-android-ios-and-macos-apps/).

Use [mdm-example.json](mdm-example.json) as an account-template example, replacing the example server. Users enter their own extension credentials in the app. It represents the managed configuration, not the SIP backup-file format. MDM consoles typically provide a form generated from the APK; consoles supporting JSON AppConfig can use the same keys and primitive JSON types. For accounts whose credentials are fully supplied by MDM, configure your console’s supported per-user/device substitutions; Sippy expects resolved values and does not expand template variables itself.

Only keys actually included in the MDM payload are managed. Omit keys users should be able to edit; an explicitly supplied empty string clears/owns a setting. Some consoles insert schema defaults automatically, so review which fields are included. `codecOrder` is the exception: an empty value leaves codecs unmanaged. The enable switch defaults to `false`, and no settings are managed on ordinary personal installations.

Managed fields are marked **Managed** and cannot be edited locally. Local values stay separate, so removing a key, removing the policy, or setting `managedConfigurationEnabled=false` restores its local value. Saving/importing other settings cannot overwrite managed values. Managed SIP and LDAP passwords are read from Android's policy bundle, are not copied into Sippy's local password storage, and managed SIP passwords are excluded from exports even when password export is requested. Your MDM still holds the delivered credentials and controls access to its console/policy exports.

Updates are read on startup, resume, and Android's managed-restrictions broadcast while Sippy's process is running. SIP/network/codec reconnection waits until all current calls finish; the Application page reports a pending change. Appearance, transfer mode and other non-connection settings take effect immediately. Changing the managed CSV source clears the previous synced results and reschedules sync. Removing management keeps Local/Imported contacts and history. Invalid configurations are rejected as a whole with a value-free error on the Application page; a running app keeps its last valid policy. If no valid policy is available at startup, an invalid/pending managed policy blocks SIP connection until corrected. Force-stopped apps must be started again by the user or MDM.

AppConfig does not grant microphone/contacts/notification permissions, install credentials, exempt the app from battery restrictions, or bypass Do Not Disturb. Configure those through your MDM's normal device/app policies or allow the user's Android prompts. For an LDAP client certificate, deploy the certificate/private key and grant `org.sippy` access through the MDM (or have the user select it), then provide its alias. Ringtone URIs must refer to audio already accessible on the device; Sippy does not download remote ringtone URLs from AppConfig.

The supplied APKs use the existing debug signing identity and can be distributed as internal APKs. A Managed Google Play publication requires your production release/signing workflow. This build uses version name **1.2.0** and Android's version code **13** so MDMs can recognize it as an update to earlier builds. Keep the package/signing identity consistent when updating enrolled devices.

## Multiple named accounts (1.2.0)

Use the `sipAccounts` bundle array to provision up to 20 named managed accounts. Accounts register simultaneously, and users may choose the outgoing account in the dialer or Account(s) settings. The choice is local and is not enforced by MDM. Give each account a stable, unique `id` (letters, digits, dot, underscore or hyphen; up to 64 characters) and unique `name` (1–80 characters). Updating an account with the same ID replaces its managed settings; removing it from the array removes that managed account. Personal accounts remain stored separately. A managed account takes precedence over a personal account with the same SIP identity while the policy is active; removing the managed account restores the personal copy. Managed entries sharing an identity cannot provide conflicting credentials.

Each bundle contains `id`, `name`, and account settings using the following keys: `accountUsername`, `accountAuthUser`, `accountPassword`, `accountDisplayName`, `accountDomain`, `accountServer`, `accountProxy`, `accountRealm`, `accountTransport`, `accountRegister`, `accountExpiry`, `accountStun`, and `accountIce`. The types and allowed values are listed in the account-fields table below. Username and domain must form a valid SIP account before it can register. A partial account may be provisioned so the user can fill omitted settings. Supplied fields are locked; omitted fields use defaults and may be edited locally. Credentials supplied by MDM stay in the OS policy bundle and memory, are not copied into personal account storage, and cannot be included in account exports. User-supplied values for omitted fields are retained separately; user-entered passwords are encrypted.

The nested keys have an `account` prefix so every key in the Android restrictions schema is unique. MDM consoles supporting bundle arrays show an account list editor. The shared device media and codec settings continue using top-level keys. Do not place shared media settings inside an account bundle.

The old single-account top-level keys (`username`, `authUser`, `password`, `displayName`, `domain`, `server`, `proxy`, `realm`, `transport`, `register`, `expiry`, `stun`, and `ice`) have been removed from the MDM schema and are ignored if an older console still sends them. All SIP identity, registration, transport and NAT settings must now be inside `sipAccounts`. Shared device media, codecs and listening-port settings remain at the top level. This MDM change does not remove or change existing personal accounts or their encrypted credentials.

### Managed server settings with local extension credentials

For each managed account, enable **Enter extension credentials in app** (`accountLocalCredentials: true`). Set the account’s ID, name, domain, registrar override, outbound proxy, transport and other infrastructure settings through MDM. Supplied infrastructure fields are marked **Managed** and cannot be edited locally. The user selects that account in **Settings → Account(s)**, enters their username/extension, authentication ID and password, then taps **Save and connect**.

With this option enabled, `accountUsername`, `accountAuthUser`, and `accountPassword` are ignored even if the MDM console sends empty defaults or previously configured values. Those three fields always remain editable in the app. The credentials are stored separately for each stable account ID, with passwords encrypted on the device; changing the registrar or other managed settings does not erase them. Credentials entered for one account are not copied to another. Managed accounts can be provisioned before a username is known and begin registering once the user completes their extension details.

Leave **Enter extension credentials in app** off (the default) for fully provisioned accounts. In that mode, each supplied credential field is managed and locked, while omitted fields remain editable. An explicitly supplied empty string is still a managed value, so use the local-credentials option when the MDM console includes blank defaults automatically.

| Key inside each `sipAccounts` bundle | Type | Meaning / values |
| --- | --- | --- |
| `id` | string | Unique stable ID, up to 64 characters. |
| `name` | string | Account label, 1–80 characters. |
| `accountLocalCredentials` | boolean | `true`: users enter username, authentication ID and password in Sippy. Default `false`. |
| `accountUsername` | string | SIP username/extension; ignored when local credentials are enabled. |
| `accountAuthUser` | string | Authentication ID; ignored when local credentials are enabled. |
| `accountPassword` | string | SIP password; ignored when local credentials are enabled. |
| `accountDisplayName` | string | Caller display name. |
| `accountDomain` | string | SIP domain or IP address, without protocol. |
| `accountServer` | string | Registrar override, optionally with a port. |
| `accountProxy` | string | Outbound proxy, optionally with a port. |
| `accountRealm` | string | Authentication realm. |
| `accountTransport` | string | `UDP`, `TCP`, or `TLS`. |
| `accountRegister` | boolean | Register this account with its server. |
| `accountExpiry` | integer | Registration expiry, 60–86400 seconds. |
| `accountStun` | string | STUN server, optionally with a port. |
| `accountIce` | boolean | Enable ICE for this account. |

DND behaviour fields are also per account; see the [DND guide](DND.md).

`doNotDisturb` is an optional managed boolean. When supplied, it enforces the active state on accounts with Show DND enabled and locks their dialer toggles. Omit it to let users control DND per account. Per-account technical settings use `accountDndShow`, `accountDndReasonHeader`, `accountDndServerSync`, `accountDndFacOn`, `accountDndFacOff`, `accountDndFacMethod`, `accountDndFeatureSchema`, and `accountDndPriorityException`. See the [DND guide](DND.md) for types, defaults and server protocols.

See [mdm-accounts-example.json](mdm-accounts-example.json) for two managed server templates with locally entered extension credentials. Both examples use placeholder servers and must be customized before deployment. Account/network policy updates wait until current calls end; DND changes apply immediately.

## Managed fields (only supplied fields are enforced):

| Key | Type | Values / meaning |
| --- | --- | --- |
| `managedConfigurationEnabled` | boolean | Must be true to apply the payload. |
| `sipAccounts` | bundle array | Named managed accounts; see above. |
| `doNotDisturb` | boolean | Automatically decline incoming calls in Sippy. |
| `localPort` | integer | 0–65535; 0 means automatic. |
| `rtpPort` | integer | 0–65534; 0 means automatic. |
| `encryption` | string | `None`, `SRTP`, `ZRTP`, `DTLS` |
| `dtmf` | string | `RTP`, `INFO` |
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
