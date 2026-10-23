# Independent cloning safety fix

The package-name clone path previously used InstallOption.installBySystem(), retaining a dependency on the host app APK. It now uses installByStorage() so the base APK is copied into HiddenBox storage.

Apps with split APKs are explicitly rejected: this engine does not yet install split sets correctly. Do not uninstall the original app if cloning fails.

This does not migrate application data, guarantee Google Play Services / Play Integrity support, or erase historical OS/cloud traces. Validate launching, signing in, and restarting the phone before removing any original application. KAST compatibility has not been verified on a device.
