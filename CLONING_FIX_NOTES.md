# Split APK cloning — implementation and limitations

The installed-app cloning path now uses the source app's **complete installed
APK cluster** (base.apk + split APKs), parses it as a single Android package,
checks ABI compatibility across all APKs, copies all code/resource APKs to
HiddenBox-owned internal storage, extracts native libraries from each split,
and advertises sandbox-private paths through ApplicationInfo and package
resource loading. It also verifies the installed virtual package is listed.

Supported scenario: the APK cluster is readable from the host app's install
directory, and the BlackBox PackageParser/virtual runtime supports the
package. No APK file selection is required for installed-app cloning.

**Not guaranteed**: the copied app's existing login or app data, Google Play
Integrity, Firebase App Check, SafetyNet, DRM, hardware-backed authentication,
push notification registration, payments, or device certification. A bank or
payment app such as KAST may refuse to run inside a virtual environment even
when every APK is copied correctly. No security checks are bypassed.

**Required on-device acceptance test before deleting the host original:**
1. Install this build as an upgrade (do not clear HiddenBox data).
2. Tap "Choose from phone" and clone the desired installed application.
3. Verify it appears in HiddenBox, launches, displays resources, and logs in
   where supported.
4. Fully restart the phone, then confirm that the sandbox copy still works.
5. Only after independently confirming account access, consider removing
   the original via Android Settings; always keep another account recovery
   method available.

The GitHub Actions workflow builds the project; a successful APK build is
not the same as successful runtime testing.


## Google services compatibility and App Check diagnostics

- The **Google services status** menu now reports the host/virtual GMS, GSF and
  Play Store package presence separately. This is *not* a health or integrity verdict.
- KAST's launch flow asks to attempt installing genuine Google components from
  the host phone when GMS is absent in the virtual user.
- GMS installation now requires that the host and the virtual user actually have
  Google Play services; optional Play Store failures no longer count as GMS failures.
- Simulated Google accounts and authentication hooks are no longer registered.
  Fake tokens are not accepted by Google's services and should never be used as a
  substitute for real authentication.
- An App Check rejection cannot safely or reliably be fixed by returning mock
  tokens. If the provider requires Play Integrity, a virtual app engine may not
  meet its integrity requirements. Work Profile on a supported real Android
  device is a platform-supported alternative.

Do not delete the original KAST installation without testing the independent
copy, account access and reboot behavior.


## Legitimate KAST App Check diagnostic and APK integrity

- HiddenBox now verifies each cloned base/split APK using SHA-256 after
  copying, not just file length. This verifies the sandbox APK bytes are
  unchanged, without spoofing an attestation verdict.
- New menu: "تشخيص خطأ KAST APPCHECK-00401". It reports the status of the
  virtual package and its split APK files, private-storage placement, host
  installation source, Android SDK version and Google package presence.
- The user may **copy the report** or explicitly open an email composer
  addressed to `support@kast.xyz`. Nothing is transmitted automatically.
- The report deliberately excludes account data, private app files,
  device identifiers, secrets, auth tokens and network traffic.
- **Important limitation:** Play Integrity/App Check attestation belongs to
  the Google Play/KAST trust chain. A cloned package running under HiddenBox's
  host UID cannot obtain a guaranteed PLAY_RECOGNIZED verdict for KAST.
  KAST must provide an officially supported verification or environment
  policy for such a runtime. This change does not bypass their security.


## Native Android managed-profile mode for Play Integrity-sensitive apps

An optional **Native Android** mode is accessible in HiddenBox's overflow menu
("تشغيل KAST في Android الحقيقي"). The user explicitly approves Android's
standard managed-profile provisioning screen. A minimal DeviceAdminReceiver
sets the profile's name, enables genuine Google Play system apps where
available, and enables the profile.

Within the work profile, HiddenBox does NOT initialize BlackBox virtualization
hooks: KAST is installed and launched by the real Android PackageManager under
its own application UID and original signing certificate. The work user has
isolated application data and can survive uninstalling KAST only from the
personal profile.

Primary profile -> CrossProfileApps.startMainActivity -> the HiddenBox launcher
in the work profile -> NativeProfileActivity. The latter launches KAST's native
launcher activity, never loading it in HiddenBox's own process.

Installation choices within the work profile:
- Official Google Play listing (recommended), with the user supplying any
  necessary Google-account consent.
- DevicePolicyManager.installExistingPackage() if Android permits it. This API
  normally requires device/user affiliation, so personal BYOD profiles may
  reject it. A failed transfer offers the official Play route; no spoofing.

**Limitations and safety**
- Does NOT promise KAST Play Integrity acceptance: only Google's official
  attestation and KAST's server can determine it. A supported, certified phone
  is still required.
- Android may reject provisioning if a managed profile already exists or the
  device administrator restricts user provisioning. The app does not replace
  or delete an existing work profile.
- The user must consent to provisioning and sign in to Play when needed.
- Native profiles are visible to Android. The feature does not promise the
  app leaves zero trace in Android settings.
- Debug builds produced on separate CI runners may have different signing keys.
  An installed profile-owner app **must be updated with the same signing key**.
  During development, the user may need to remove the test work profile
  before installing an incompatible debug build. Production deployment
  requires a persistent, securely stored signing keystore.
- Do not remove the original KAST instance until a legitimate sign-in and a
  reboot test succeed in the native Android profile.


## Fix: virtual Google Play account discovery / login pipeline

The user reported that the cloned Play Store can open but cannot sign in.

Confirmed source-level bugs fixed:
1. `BAccountManagerService.onPackageInstalled` used to replace the complete
   authenticator registry with services from the last installed package only.
   It now rebuilds the registry from all installed virtual packages.
2. `getAuthenticatorTypes` used to enumerate only types of accounts already
   present in the database, so the first sign-in could not discover a Google
   account authenticator. It now lists registered providers, subject to
   installation in the requested virtual user.
3. `addAccountAsUser` returned without invoking the provider or callback.
4. `getAuthToken` dereferenced nullable options and logged sensitive login
   bundles. Both are fixed.
5. A successful account-add response with an initially absent session account
   name could attempt to instantiate `new Account(null, type)` and crash.
6. Authenticator cache snapshots are replaced atomically, and an installed
   `com.google.android.gsf.login` package is included when supplied by the
   host phone. Re-running the GMS action now checks for missing packages rather
   than stopping when GMS is already present.

New Google component diagnosis includes whether a `com.google` authenticator
is discovered. **This is a registration check, not confirmation that an
authenticator can establish a secure Google login session**.

**Unresolved runtime compatibility:** Google sign-in may still reject a
third-party virtual Android application runtime because the OS user ID, service
security model, and attestation are not identical to a real Android process
registered under its own package. No tokens, certificates or device integrity
claims are forged, and no account data is copied from the host user.
The user must test Play Store sign-in on the Android device after upgrading.


## Google login: use the virtual authenticator service, not the host one

The account-manager server was binding the original Google authenticator
component directly with the real Android `Context.bindService`. The server
runs under HiddenBox's **host** package, so such a bind could reach the
personal-profile Google provider or fail due to permissions. The virtual
account never received its own provider connection.

Now the authenticator is resolved via `BActivityManagerService` to
BlackBox's own proxy-service intent for the requested *virtual user*, and
the actual Android bind is made only to that proxy. If the proxy cannot
be constructed, the operation fails instead of binding to the wrong user.
The service connection is unbound safely even when the authenticator
disconnects before returning a callback.

The virtual authenticator map is published in immutable snapshots using
`AuthenticatorRegistry`, with four JVM regression tests. GitHub Actions
must run these tests and produce a debug APK, but they cannot authenticate
a real Google account or measure KAST Play Integrity.

This remains a compatibility attempt, not a claim that a Google-secured
app supports a third-party virtual Android process.


## Follow-up: verified account creation and separate virtual-user security

Added fixes for the Google Play Store sign-in loop:

- The virtual account database did not initialize `BUserAccounts.userId`
  when creating or restoring users, causing all new sessions to default to
  virtual user 0. Now it normalizes the user ID from the map key.
- Requests for all account types previously merged account records across
  **every** virtual user; they now return only the requested user's records.
- `getAccountsForPackage` and `getAccountsByTypeForPackage` previously
  returned no Google accounts because newly added accounts have no visibility
  records by default. Google account visibility is granted by default only
  to the real authenticator package and the installed GMS/GSF/Play Store
  clients in the *same virtual user*. An explicit visibility record still
  takes precedence. Unrelated apps remain blocked.
- The completed first-account callback now persists its account identity in
  the correct virtual user's database. Intermediate login Intents do not
  create accounts; password and auth tokens are not added here.
- Account authentication metadata is parsed using public Android XML
  attributes instead of hidden `com.android.internal.R.styleable` reflection,
  and XML parsers are closed in every case.
- The session no longer injects a synthetic null ClipData into login Intents.
- Added JUnit regression tests for account completion and the restrictive
  Google client visibility policy.

These changes fix several deterministic source-level issues, but do NOT
assert Google or KAST accepts third-party virtual Android process identities.
