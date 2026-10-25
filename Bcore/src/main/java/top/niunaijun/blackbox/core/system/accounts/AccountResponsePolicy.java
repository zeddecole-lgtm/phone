package top.niunaijun.blackbox.core.system.accounts;

/**
 * Only final authenticator responses containing a real account identity
 * can be recorded as successful first-account additions.
 */
public final class AccountResponsePolicy {
    private AccountResponsePolicy() {}

    public static boolean isCompletedAccountAdd(String expectedType,
            String returnedName, String returnedType, boolean hasLoginIntent) {
        return !hasLoginIntent && expectedType != null
                && expectedType.equals(returnedType)
                && returnedName != null && !returnedName.trim().isEmpty();
    }
}
