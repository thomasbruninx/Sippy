Sippy — Third-party notices
==========================

Sippy's application code is licensed under GNU GPL version 3. Its libraries
retain their own copyrights and license terms. No commercial Linphone license
is implied. Source code: https://github.com/thomasbruninx/Sippy

Direct dependencies
-------------------

Linphone SDK for Android 5.4.127 — Belledonne Communications and contributors.
GNU Affero General Public License version 3 (AGPLv3), with a separate commercial
license available from the publisher. Sippy uses the open-source SDK.
Includes Liblinphone, Mediastreamer2, oRTP, Belle-SIP, BCToolbox and other SIP,
media and support components. These components and their external dependencies
retain their individual licenses.
The versioned SDK source identifies AGPLv3, although the Maven POM says GPLv3.
GPLv3 section 13 permits combination with AGPLv3; the combined work remains
subject to the relevant AGPLv3 requirements. Sippy's GPL notice does not
replace the SDK's license.
Source and versioned license:
https://github.com/BelledonneCommunications/linphone-sdk/tree/5.4.127
https://github.com/BelledonneCommunications/linphone-sdk/blob/5.4.127/LICENSE.txt
Bundled full text: licenses/LINPHONE-AGPL-3.0.txt

UnboundID LDAP SDK for Java 7.0.6 — Copyright 2007–2026 Ping Identity Corporation.
Apache License 2.0 is the license option used by Sippy. The upstream SDK also
offers GPLv2, LGPLv2.1 and the UnboundID LDAP SDK Free Use License as alternatives;
Sippy does not require users to select all of them.
https://github.com/pingidentity/ldapsdk
https://docs.ldap.com/ldap-sdk/docs/ldapsdk-faq.html

AndroidX Activity 1.13.0 — The Android Open Source Project and contributors.
Apache License 2.0. Its resolved runtime dependencies include:
- Annotation 1.9.1; Annotation Experimental 1.4.1
- Core and Core KTX 1.18.0; Core ViewTree 1.0.0
- Collection 1.4.2; Concurrent Futures 1.1.0; Interpolator 1.0.0
- Lifecycle Common, Runtime, ViewModel, ViewModel SavedState and LiveData Core 2.6.2
- Architecture Core Common and Runtime 2.2.0; SavedState 1.2.1
- NavigationEvent 1.0.0; Compose Runtime Annotation 1.9.0
- ProfileInstaller 1.4.0; Startup Runtime 1.1.1; Tracing 1.2.0
- VersionedParcelable 1.1.1
https://android.googlesource.com/platform/frameworks/support/
https://developer.android.com/jetpack/androidx

Kotlin standard library 2.2.20 — JetBrains and contributors.
Apache License 2.0.
https://github.com/JetBrains/kotlin/tree/v2.2.20

Other resolved JVM runtime dependencies
----------------------------------------

- kotlinx.coroutines Core and Android 1.9.0 — JetBrains and contributors;
  Apache License 2.0. https://github.com/Kotlin/kotlinx.coroutines/tree/1.9.0
- JetBrains annotations 23.0.0 — JetBrains; Apache License 2.0.
  https://github.com/JetBrains/java-annotations
- Guava ListenableFuture 1.0 — Google; Apache License 2.0.
  https://github.com/google/guava
- JSpecify annotations 1.0.0 — The JSpecify Authors; Apache License 2.0.
  https://github.com/jspecify/jspecify

Bundled full Apache text: licenses/APACHE-2.0.txt.
Notices preserved from resolved JVM artifacts: licenses/JVM-NOTICES.txt.

Native components supplied by Linphone
----------------------------------------

The SDK includes its own native dependency tree. The distributed Android AAR
contains liblinphone, libmediastreamer2, libortp, libbctoolbox, libmsaaudio,
libmsandroidcamera2 and libmswebrtc, plus the following shared libraries:

- ZXing-C++ — ZXing contributors; Apache License 2.0.
  https://github.com/zxing-cpp/zxing-cpp
- libsrtp — Cisco Systems and contributors; BSD 3-Clause.
  https://github.com/cisco/libsrtp
- JsonCpp — Baptiste Lepilleur, The JsonCpp Authors and contributors;
  MIT license or public domain, as described in its license.
  https://github.com/open-source-parsers/jsoncpp
- libjpeg-turbo — its authors and contributors; IJG, modified BSD 3-Clause
  and zlib licenses, depending on the source files.
  https://github.com/libjpeg-turbo/libjpeg-turbo
- LLVM libc++ runtime — LLVM contributors; Apache License 2.0 with LLVM Exceptions.
  https://github.com/llvm/llvm-project/tree/main/libcxx

The SDK also incorporates codec and support code internally, including Opus
(Xiph.Org and contributors, BSD-style), Speex (Xiph.Org and contributors,
BSD-style), and WebRTC-derived processing (The WebRTC project authors,
BSD 3-Clause with its additional upstream notices). The SDK's source tree is
the reference for further components and build options; this list of named
components is not a claim that every optional SDK codec is enabled.

Versioned SDK dependency inventory and source revisions:
https://github.com/BelledonneCommunications/linphone-sdk/blob/5.4.127/.gitmodules
https://github.com/BelledonneCommunications/linphone-sdk/tree/5.4.127/external

Reference license/copyright texts for these native projects are included in
licenses/. They are upstream license references, not a regenerated inventory
of every file compiled inside the prebuilt SDK. Custom SDK builds may change
these components and require updated notices.

Reference-text sources
-----------------------

Apache 2.0:
https://github.com/JetBrains/kotlin/blob/v2.2.20/license/LICENSE.txt
Linphone AGPLv3:
https://github.com/BelledonneCommunications/linphone-sdk/blob/5.4.127/LICENSE.txt
libsrtp:
https://github.com/cisco/libsrtp/blob/main/LICENSE
JsonCpp:
https://github.com/open-source-parsers/jsoncpp/blob/master/LICENSE
libjpeg-turbo:
https://github.com/libjpeg-turbo/libjpeg-turbo/blob/3.0.4/LICENSE.md
https://github.com/libjpeg-turbo/libjpeg-turbo/blob/3.0.4/README.ijg
Opus:
https://github.com/xiph/opus/blob/main/COPYING
Speex:
https://github.com/xiph/speex/blob/master/COPYING
WebRTC:
https://github.com/webrtc-mirror/webrtc/blob/main/LICENSE
LLVM libc++:
https://github.com/llvm/llvm-project/blob/release/18.x/libcxx/LICENSE.TXT
