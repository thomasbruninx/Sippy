# Do Not Disturb

Sippy's DND is saved **per account**. Select an outgoing account in the dialer, then use its **Do Not Disturb** button at the bottom of the screen. Selecting a different account shows that account's saved DND state. The persistent notification identifies the outgoing account and lists accounts with DND enabled. Server-originated changes also update the button and notification.

DND keeps accounts registered and automatically rejects new incoming calls to the affected account with **SIP 603 Decline**. It stops those calls before Sippy starts its incoming-call alert. Enabling DND also declines calls already ringing on that account. It does not end connected calls, prevent outgoing calls, or change Android's system-wide DND setting. Declined calls can still appear in call history.

## DND behaviour settings

Open **Settings → DND behaviour** (immediately after Network in the settings dropdown) and select an account. Use **Save DND behaviour** to apply changes. These settings are separate from device audio settings. Codec order, ringtone and transfer mode are also account-specific.

| Setting | Default | Behaviour |
| --- | --- | --- |
| Show DND | On | Show the dialer toggle and enable DND for this account. Off hides the toggle and bypasses local DND, emergency checks and server synchronization for the account. The saved toggle state is retained but has no effect while this setting is off. |
| RFC 3326 reason header | Off | Include `Reason: SIP;cause=603;text="Do Not Disturb"` when Sippy rejects a call because of DND. The response remains SIP 603 with or without this optional header. |
| Server-Sync (Shared Presence & BLF) | Disabled | Choose one of the server integration methods below. |
| Emergency / Priority Exception | Off | Allow a call carrying `Priority: emergency` through local DND. Other priorities, including `urgent`, do not bypass it. |

The emergency exception uses the caller-provided SIP header; it does not authenticate the caller as an emergency service. It cannot bypass calls blocked by the provider before they reach Sippy, or override Android’s own sound, vibration and DND policies.

Changing DND behaviour applies without reconnecting accounts or ending existing calls. When disabling or replacing an active sync method, Sippy attempts to clear the old server state. A server outage or a deferred FAC request can leave server-side DND active until that reset succeeds. Check the **Server sync** status on the DND behaviour page or your provider's controls when needed.

## Server synchronization

Server sync is opt-in and uses the account's SIP identity, credentials, transport and routing. Keep it **Disabled** for local-only DND. A server must support the selected method and map its result to the relevant presence/BLF features; SIP presence publication does not by itself guarantee a change to every PBX's BLF lamps.

### SIP PUBLISH

Sippy sends `PUBLISH` with `Event: presence` and an `application/pidf+xml` body. DND on publishes an RPID busy activity and a “Do Not Disturb” note. DND off publishes available presence without the busy activity. Publications use a 600-second lifetime and are refreshed while the SIP service is running. Linphone handles SIP authentication, publication responses and entity tags.

This method publishes local state to the server; it does not subscribe to other devices' presence and treat that as this account's DND state. See [RFC 3903](https://www.rfc-editor.org/rfc/rfc3903.html) and [RFC 4480](https://www.rfc-editor.org/rfc/rfc4480.html).

### Feature Access Codes (FAC)

Configure the **DND enable code** and **DND disable code** for your provider. Defaults are `*78` and `*79`. Use codes that set a known state rather than a code that toggles whatever state the server currently has.

Choose **SIP INVITE** or **SIP MESSAGE**:

- **INVITE** sends a technical call with inactive audio, no video, and no dialer UI. It ends the request when the server accepts it, or times out after 15 seconds. Technical FAC calls are excluded from Sippy's call lines and history. Requests wait until existing user calls end, so they do not hold or interrupt a conversation. Rapid changes while waiting are coalesced to the latest state. A user call takes priority over a pending technical FAC call.
- **MESSAGE** sends a SIP text request to the configured code without an audio call. The body is `DND on` or `DND off`.

Sippy sends the current state when the account's FAC integration starts and sends subsequent changes. Server acceptance confirms the SIP request, not that every provider applied the requested feature. FAC does not supply remote state notifications. A DND setting made by FAC may persist at the provider even after Sippy disconnects; it can be cleared with the provider's disable code or controls.

### As-Feature-Event

Sippy subscribes to `as-feature-event` and processes `application/x-as-feature-event+xml` notifications on that subscription. Supported `DoNotDisturbEvent` messages update the account's local DND state without echoing the change back to the server. Subscription refreshes keep state current. XML is bounded and parsed without document-type or external-entity support; unrelated notifications are ignored.

Choose the appropriate **Feature event schema**:

- **BroadSoft (CSTA ed3)** sends local changes in a SUBSCRIBE body containing `SetDoNotDisturb`, the account's SIP identity as `device`, and `doNotDisturbOn`. Server notifications contain `DoNotDisturbEvent`.
- **3CX (CSTA ed5)** sends local changes using a SIP NOTIFY containing `DoNotDisturbEvent`, `monitorCrossRefID`, a `deviceIdentifier` containing the account's SIP identity, and `doNotDisturbOn`. The subscription is also used to receive server state when supported.

These are vendor-specific integrations, not a universal SIP DND standard. Provider versions and provisioning can require different feature packages or device identifiers. Sippy accepts remote state only on its tracked subscription; it does not accept arbitrary unsolicited network notifications as commands. The DND behaviour page reports subscription, publication, timeout and send errors; local DND stays effective if server synchronization fails. When MDM enforces a state, a conflicting server notification does not override it.

Protocol references: [Kamailio's as-feature-event implementation](https://github.com/kamailio/kamailio/blob/master/src/modules/presence_dfks/add_events.c), [OpenSIPS feature package definitions](https://github.com/OpenSIPS/opensips/blob/master/modules/presence_dfks/presence_dfks.h), and [Grandstream's documented 3CX DoNotDisturbEvent format](https://firmware.grandstream.com/Release_Note_GXP21xxColor_1.0.11.48.pdf) (page 86).

## Managed configuration and backups

An administrator can provide these per-account settings in the `sipAccounts` array. Fields actually supplied by MDM are locked, while omitted settings remain editable. MDM consoles may include schema defaults automatically, making those values managed too. The optional top-level `doNotDisturb` boolean enforces the active state across accounts that have Show DND enabled; omit it to let users control their account toggles.

| Managed key inside an account bundle | Type | Values / default |
| --- | --- | --- |
| `accountDndShow` | boolean | `true` |
| `accountDndReasonHeader` | boolean | `false` |
| `accountDndServerSync` | string | `DISABLED`, `PUBLISH`, `FAC`, `AS_FEATURE_EVENT`; default `DISABLED` |
| `accountDndFacOn` | string | `*78` |
| `accountDndFacOff` | string | `*79` |
| `accountDndFacMethod` | string | `INVITE` or `MESSAGE`; default `INVITE` |
| `accountDndFeatureSchema` | string | `BROADSOFT` or `THREE_CX`; default `BROADSOFT` |
| `accountDndPriorityException` | boolean | `false` |

See the [MDM guide](MDM.md) and [two-account example](mdm-accounts-example.json). Per-account DND behaviour and on/off state can be selected in **Settings backup** during export and import. Managed fields and managed on/off state are skipped during restore. Older backups without DND settings import with the defaults above.

The optional refusal header follows [RFC 3326](https://www.rfc-editor.org/rfc/rfc3326.html); local call rejection remains independent of server sync.
