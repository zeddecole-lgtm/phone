package top.niunaijun.blackbox.core.system.accounts;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Regression suite for Google auth discovery and concurrent installs.
 * This does not test an actual Google login or KAST Play Integrity.
 */
public class AuthenticatorRegistryTest {
    @Test
    public void firstGoogleAuthenticatorIsVisibleWithNoAccounts() {
        AuthenticatorRegistry<String> registry = new AuthenticatorRegistry<>();
        assertTrue(registry.snapshot().isEmpty());

        Map<String, String> available = new HashMap<>();
        available.put("com.google", "com.google.android.gms");
        registry.replaceAll(available);

        assertEquals("com.google.android.gms", registry.find("com.google"));
        assertEquals(1, registry.snapshot().size());
    }

    @Test
    public void subsequentPackageInstallKeepsExistingGoogleProvider() {
        AuthenticatorRegistry<String> registry = new AuthenticatorRegistry<>();
        Map<String, String> all = new HashMap<>();
        all.put("com.google", "com.google.android.gms");
        registry.replaceAll(all);

        all.put("com.example", "com.example.app");
        registry.replaceAll(all);

        assertEquals("com.google.android.gms", registry.find("com.google"));
        assertEquals("com.example.app", registry.find("com.example"));
    }

    @Test
    public void snapshotsAreIndependentAndImmutable() {
        AuthenticatorRegistry<String> registry = new AuthenticatorRegistry<>();
        Map<String, String> mutable = new HashMap<>();
        mutable.put("com.google", "gms");
        registry.replaceAll(mutable);
        Map<String, String> oldSnapshot = registry.snapshot();

        mutable.clear();
        assertEquals("gms", oldSnapshot.get("com.google"));
        assertEquals("gms", registry.find("com.google"));

        Map<String, String> next = new HashMap<>();
        next.put("com.other", "other");
        registry.replaceAll(next);
        assertEquals("gms", oldSnapshot.get("com.google"));
        assertNull(registry.find("com.google"));

        try {
            oldSnapshot.clear();
            fail("The published snapshot must not be mutable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullProviderMap() {
        new AuthenticatorRegistry<String>().replaceAll(null);
    }
}
