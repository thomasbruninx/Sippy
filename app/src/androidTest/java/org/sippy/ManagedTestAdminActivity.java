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
            policy.putString("csvContent", "name,number\nManaged desk,401\nManaged office,402\n");
            policy.putBoolean("vibrateOnIncoming", false);
            policy.putBoolean("backgroundCalling", true);
            if ("invalid_type".equals(mode)) policy.putString("expiry", "600");
            Bundle primary = new Bundle();
            primary.putString("id", "primary"); primary.putString("name", "Managed account");
            for (String key : new String[] {"username", "domain", "password", "transport", "transferMode", "codecOrder"}) {
                primary.putString("account" + Character.toUpperCase(key.charAt(0)) + key.substring(1), policy.getString(key));
                policy.remove(key);
            }
            primary.putBoolean("accountRegister", false); policy.remove("register");
            if ("invalid_type".equals(mode)) { policy.remove("expiry"); primary.putString("accountExpiry", "600"); }
            policy.putParcelableArray("sipAccounts", new Bundle[] {primary});
            if ("pending".equals(mode)) { policy.clear(); policy.putBoolean("restrictions_pending", true); }
        }
        if ("accounts".equals(mode) || "accounts_invalid".equals(mode)) {
            policy.clear();
            policy.putBoolean("managedConfigurationEnabled", true);
            policy.putBoolean("backgroundCalling", true);
            Bundle office = new Bundle();
            office.putString("id", "office"); office.putString("name", "MDM Office");
            office.putString("accountUsername", "mdm300"); office.putString("accountDomain", "127.0.0.1");
            office.putString("accountPassword", "MDM_MULTI_OFFICE_SECRET"); office.putBoolean("accountRegister", false);
            office.putBoolean("accountDndShow", true); office.putBoolean("accountDndReasonHeader", true);
            office.putString("accountDndServerSync", "accounts_invalid".equals(mode) ? "INVALID" : "DISABLED");
            Bundle support = new Bundle();
            support.putString("id", "support"); support.putString("name", "MDM Support");
            support.putString("accountUsername", "mdm301"); support.putString("accountDomain", "127.0.0.2");
            support.putString("accountPassword", "MDM_MULTI_SUPPORT_SECRET"); support.putBoolean("accountRegister", false);
            support.putBoolean("accountDndShow", false);
            policy.putParcelableArray("sipAccounts", new Bundle[] {office, support});
        }
        if ("local_credentials".equals(mode) || "local_credentials_changed".equals(mode)) {
            policy.clear(); policy.putBoolean("managedConfigurationEnabled", true); policy.putBoolean("backgroundCalling", true);
            // Old top-level fields are intentionally ignored, including invalid values.
            policy.putString("username", "IGNORED"); policy.putString("transport", "INVALID");
            Bundle first = new Bundle();
            first.putString("id", "template-office"); first.putString("name", "Office template");
            first.putBoolean("accountLocalCredentials", true);
            first.putString("accountUsername", ""); first.putString("accountPassword", "IGNORED_POLICY_SECRET"); first.putString("accountAuthUser", "");
            first.putString("accountDomain", "127.0.0.1");
            first.putString("accountServer", "local_credentials_changed".equals(mode) ? "127.0.0.1:5091" : "127.0.0.1:5090");
            first.putString("accountTransport", "UDP"); first.putBoolean("accountRegister", false);
            Bundle second = new Bundle(first);
            second.putString("id", "template-home"); second.putString("name", "Home template"); second.putString("accountDomain", "127.0.0.2");
            second.putString("accountServer", "127.0.0.2:5090");
            policy.putParcelableArray("sipAccounts", new Bundle[] {first, second});
        }
        getSystemService(DevicePolicyManager.class).setApplicationRestrictions(
            new ComponentName(this, ManagedTestAdmin.class), "org.sippy", policy);
        finish();
    }
}
