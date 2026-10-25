package top.niunaijun.blackbox.core.system.accounts;

import org.junit.Test;
import static org.junit.Assert.*;

public class AccountResponsePolicyTest {
    @Test public void acceptsFinalResultWithMatchingAccountType() {
        assertTrue(AccountResponsePolicy.isCompletedAccountAdd(
                "com.google", "user@example.invalid", "com.google", false));
    }
    @Test public void doesNotPersistIntermediateSignInIntent() {
        assertFalse(AccountResponsePolicy.isCompletedAccountAdd(
                "com.google", "user@example.invalid", "com.google", true));
    }
    @Test public void rejectsInvalidOrMismatchedAccountIdentity() {
        assertFalse(AccountResponsePolicy.isCompletedAccountAdd(
                "com.google", null, "com.google", false));
        assertFalse(AccountResponsePolicy.isCompletedAccountAdd(
                "com.google", "  ", "com.google", false));
        assertFalse(AccountResponsePolicy.isCompletedAccountAdd(
                "com.google", "name", "com.example", false));
        assertFalse(AccountResponsePolicy.isCompletedAccountAdd(
                null, "name", "com.google", false));
    }
}
