package top.niunaijun.blackbox.core.system.accounts;

/**
 * Minimal default account visibility for the genuine Google auth clients.
 * This policy never returns visibility for non-Google account types, and
 * callers must additionally be installed in the same BlackBox virtual user.
 */
public final class GoogleAccountVisibilityPolicy {
    private GoogleAccountVisibilityPolicy() {}

    public static boolean isTrustedGoogleClient(String type, String clientPackage) {
        if (!"com.google".equals(type) || clientPackage == null) return false;
        switch (clientPackage) {
            case "com.google.android.gms":
            case "com.google.android.gsf":
            case "com.google.android.gsf.login":
            case "com.android.vending":
                return true;
            default:
                return false;
        }
    }
}
