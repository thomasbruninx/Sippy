package org.sippy;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.os.Bundle;

public class ManagedTestAdminActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String mode = getIntent().getStringExtra("case");
        Bundle policy = new Bundle();
        if (!"remove".equals(mode)) {
            policy.putBoolean("managedConfigurationEnabled", true);
            policy.putString("username", "changed".equals(mode) ? "managed201" : "managed200");
            policy.putString("domain", "127.0.0.1");
            policy.putString("password", "invalid".equals(mode) ? "DO_NOT_LOG_INVALID_FIXTURE" : "MDM_FIXTURE_SECRET");
            policy.putBoolean("register", false);
            policy.putString("transport", "invalid".equals(mode) ? "INVALID" : "UDP");
            policy.putString("transferMode", "ATTENDED_ONLY");
            policy.putString("appearance", "LIGHT");
            policy.putString("codecOrder", "pcmu,opus");
            policy.putString("csvUrl", "");
            policy.putBoolean("vibrateOnIncoming", false);
            policy.putBoolean("backgroundCalling", true);
            if ("invalid_type".equals(mode)) policy.putString("expiry", "600");
            if ("pending".equals(mode)) { policy.clear(); policy.putBoolean("restrictions_pending", true); }
        }
        getSystemService(DevicePolicyManager.class).setApplicationRestrictions(
            new ComponentName(this, ManagedTestAdmin.class), "org.sippy", policy);
        finish();
    }
}
