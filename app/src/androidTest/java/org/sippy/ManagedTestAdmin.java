package org.sippy;

import android.app.admin.DeviceAdminReceiver;

/** Standalone test-only controller: deliberately independent of the target APK's Kotlin runtime. */
public class ManagedTestAdmin extends DeviceAdminReceiver {}
