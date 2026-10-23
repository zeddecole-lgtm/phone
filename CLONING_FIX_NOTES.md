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
