package top.niunaijun.blackbox.core.system.accounts;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * An immutable-snapshot registry of virtual authentication providers.
 *
 * An authenticator discovery or package-install callback must never expose
 * a transient, partially cleared map to other login requests. This helper
 * has no Android dependency so it can be regression-tested on the CI JVM.
 */
public final class AuthenticatorRegistry<T> {
    private volatile Map<String, T> entries = Collections.emptyMap();

    public Map<String, T> snapshot() {
        return entries;
    }

    public void replaceAll(Map<String, T> refreshed) {
        if (refreshed == null) {
            throw new IllegalArgumentException("refreshed cannot be null");
        }
        entries = Collections.unmodifiableMap(new HashMap<>(refreshed));
    }

    public T find(String type) {
        return entries.get(type);
    }
}
