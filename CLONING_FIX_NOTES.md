# HiddenBox cloning reliability patch

## Changed
- Cloning an installed, single-APK application now uses `InstallOption.installByStorage()` rather than `installBySystem()`. This causes `CopyExecutor` to copy the APK to the virtual app directory, avoiding a reference to the host app installation.
- Installed applications with `splitSourceDirs` are rejected with an explicit message rather than silently cloning only the base APK. Split APK support is **not** implemented.
- Updated misleading independence claim in `VirtualCore.kt`.

## Important limitations
- **Do not uninstall your original app until the virtual copy has been tested after a device reboot.**
- This copies application code, **not private app data, login sessions, or cloud account state**.
- Google Play Services / Play Integrity-dependent applications (including KAST) may fail in BlackBox. No integrity bypass or guaranteed KAST compatibility is provided.
- Android uninstall cannot guarantee removal of every historical trace, server record, or backup.
- Full split APK support requires changes to the virtual package parser, classloader, resources, and native library loading, not just copying files.

## Verification steps on an Android test device
1. Build the debug APK using the repository workflow.
2. Install a simple single-APK test app on the phone.
3. Clone it from the installed apps list and launch it inside HiddenBox.
4. Reboot and confirm the virtual copy launches.
5. Uninstall the original **test app** and verify the virtual copy still launches.
6. Test split APK app: it must display the explicit unsupported error, not a false success.
7. Separately test KAST without uninstalling its original app; Play Integrity compatibility is not assured.

No Android build or device test was performed in this environment.
