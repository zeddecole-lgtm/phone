package top.niunaijun.blackbox.core.system.accounts;

import org.junit.Test;
import static org.junit.Assert.*;

public class GoogleAccountVisibilityPolicyTest {
    @Test public void allowsOfficialGoogleAccountClients() {
        for (String pkg : new String[] {
                "com.google.android.gms",
                "com.google.android.gsf",
                "com.google.android.gsf.login",
                "com.android.vending" }) {
            assertTrue(GoogleAccountVisibilityPolicy.isTrustedGoogleClient("com.google", pkg));
        }
    }

    @Test public void blocksUnrelatedAppsAndOtherAccountTypes() {
        assertFalse(GoogleAccountVisibilityPolicy.isTrustedGoogleClient(
                "com.google", "com.example.untrusted"));
        assertFalse(GoogleAccountVisibilityPolicy.isTrustedGoogleClient(
                "com.example.account", "com.android.vending"));
        assertFalse(GoogleAccountVisibilityPolicy.isTrustedGoogleClient(
                "com.google", null));
        assertFalse(GoogleAccountVisibilityPolicy.isTrustedGoogleClient(
                null, "com.google.android.gms"));
    }
}
