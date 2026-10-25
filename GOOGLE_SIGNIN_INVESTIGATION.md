# Google sign-in: evidence-led investigation (2026-10-25)

## User-visible symptom

The Play Store opens, but pressing Sign in produces no visible response.
No device-side exception trace or Google authentication result has been captured.
Do not equate an APK build or a unit-test pass with a working Google login.

## Reproduced defect in production Java proxy

AOSP's `IAccountManager.accountAuthenticated(Account)` returns `boolean`.
HiddenBox's `IAccountManagerProxy.accountAuthenticated` called the virtual
manager, discarded its result, and returned `Integer(0)`. Java dynamic proxy
callers expect `Boolean`, so both true and false provider results caused a
`ClassCastException` at the call boundary.

The fix returns the virtual manager's actual boolean result. It neither
claims successful authentication nor changes a false result into true.

### Executed before/after tests

The exact reviewed proxy source blob was
`830904ee1a394df8a3ed948cc014997c7dbc9d66` (before the fix).
It was compiled as-is on OpenJDK 21 against temporary, isolated JVM fixtures.
A Java dynamic proxy exercised the real production nested hook classes.

- Before: 10 passed, 2 failed. Both failures were `accountAuthenticated`
  (`delegate=true` and `delegate=false`) with `ClassCastException`.
- After: 12 passed, 0 failed.
- Coverage: five boolean-returning hooks with both provider values, plus
  exact callback/argument forwarding for addAccount and addAccountAsUser.
- The fixtures are generated in a temporary directory and are NOT included
  in any Android APK, Google services or provider runtime.

Run with:

```bash
python3 tools/test_account_proxy_contract.py \
  Bcore/src/main/java/top/niunaijun/blackbox/fake/service/IAccountManagerProxy.java \
  Bcore/src/main/java/top/niunaijun/blackbox/fake/frameworks/BAccountManager.java
```

CI runs this before provisioning the Android SDK and uploads test evidence.

## What this does NOT prove

- It does not prove Play Store invokes accountAuthenticated at the point of
  the reported silent button failure. That causal link is still unknown.
- It does not exercise Android Binder, virtual service lifecycle, Google's
  login UI, a real Google account, or KAST's App Check / Play Integrity.
- It does not validate the other account-isolation or visibility changes
  introduced in previous commits.
- No physical device or working Android emulator was available in the
  current execution environment. No passwords, account tokens or financial
  credentials were requested or used.

## Further code-level gaps observed, not silently patched

AOSP defines modern session methods such as startAddAccountSession,
startUpdateCredentialsSession and finishSessionAsUser. The current proxy
has no explicit handlers for these. Its generic invocation layer delegates
unhandled calls to the host service. Whether Play Store uses those methods
on the affected device is unknown. Implementing them needs real lifecycle
and session-security tests; mapping them blindly to addAccount is unsafe.

The virtual session has cancellation/timeout methods, but the reviewed
binding path does not establish a bounded wait for every missing provider
callback. This is a candidate explanation for an inert UI, not a proven
cause. A future repair needs a reproducible callback-timeout test rather
than another speculative end-to-end success claim.

## Primary references consulted

- AOSP account manager interface (official mirror):
  https://github.com/aosp-mirror/platform_frameworks_base/blob/main/core/java/android/accounts/IAccountManager.aidl
- Android AccountManager API:
  https://developer.android.com/reference/android/accounts/AccountManager
- Google Play Integrity verdicts, including separate licensing/app/device
  verdicts:
  https://developer.android.com/google/play/integrity/verdicts

No root hiding, certificate substitution, device impersonation, fabricated
Google credentials or security-verdict bypass is part of this change.

## Release status

This is a narrowly reproduced Java bridge fix, NOT a verified KAST solution.
Keep the development pull request in draft. Do not ask the user to replace
or clear their existing installation merely because this test passed.
