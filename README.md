# Sippy

![Sippy logo](logo.png)

Sippy is an Android SIP softphone written in Kotlin and powered by Linphone. It combines an intuitive dialer with flexible SIP settings, multiple contact sources, and support for managed deployment.

Features include:

- Simultaneous registration with multiple named SIP accounts
- Up to four simultaneous call lines across all accounts
- Support for Opus, G.722, and G.711 (PCMA and PCMU) codecs
- Configurable SIP, audio, and application settings, with password visibility controls
- Contacts from the phone's address book, imported CSV files, synchronized remote CSV files, and LDAP directories
- Managed configuration through mobile device management (MDM), including named account lists
- Per-account Do Not Disturb, with optional presence/feature synchronization and an emergency-priority exception
- Support for 32-bit and 64-bit ARM devices running Android 9 or later

See the [usage guide](docs/USAGE.md) for setup instructions and a feature overview, or the [MDM guide](docs/MDM.md) for managed deployment and configuration. The [DND guide](docs/DND.md) explains local call rejection and server synchronization.

Download the latest APK from the [GitHub releases page](https://github.com/thomasbruninx/Sippy/releases).

## Tested with

Sippy has been successfully tested with the following SIP platforms:

- NEC SV9100 (CP20)
- RingCentral
- Asterisk (24)
- Unifi Talk

## Build and run

Clone the repository and open its local directory in Android Studio. Use JDK 21 and Android SDK 36, select the `app` run configuration, and run it on a device or emulator. To build from the command line with the same tools, run `./gradlew :app:assembleDebug`.

For a smaller device-specific APK, use `./gradlew :app:assembleDebug -PtargetAbi=armeabi-v7a` for older 32-bit ARM phones or `./gradlew :app:assembleDebug -PtargetAbi=arm64-v8a` for modern 64-bit ARM phones. Without `targetAbi`, the APK includes all SDK-supported architectures.

Sippy supports Android 9 and later through its minimum SDK of 28. It targets Android 16 (API 36) and was tested on an Android 16 emulator.

## License

Sippy is licensed under the [GNU General Public License version 3](https://github.com/thomasbruninx/Sippy/blob/main/LICENSE.md). Copyright © 2026 Thomas Bruninx.

Sippy uses the following open-source libraries:

| Library | License |
| --- | --- |
| [Linphone SDK 5.4.127](https://github.com/BelledonneCommunications/linphone-sdk/tree/5.4.127), including its SIP and media components | GNU AGPLv3; its bundled third-party components retain their own licenses |
| [UnboundID LDAP SDK 7.0.6](https://github.com/pingidentity/ldapsdk) | Apache License 2.0 (the option used by Sippy) |
| [AndroidX](https://developer.android.com/jetpack/androidx), including Activity 1.13.0 and its dependencies | Apache License 2.0 |
| [Kotlin standard library 2.2.20](https://github.com/JetBrains/kotlin) and [kotlinx.coroutines 1.9.0](https://github.com/Kotlin/kotlinx.coroutines) | Apache License 2.0 |
| JetBrains annotations, Guava ListenableFuture, and JSpecify annotations | Apache License 2.0 |

See [third-party notices](THIRD_PARTY_NOTICES.md) for component details, upstream source links, and bundled license texts. Third-party code keeps its original licensing; Sippy's GPLv3 notice does not relicense those components. Linphone's versioned source identifies the SDK as AGPLv3, although its Maven metadata says GPLv3.
