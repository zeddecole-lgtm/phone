# Manual test checklist

Never attach screenshots showing your balance, access tokens, email, or
payment information. Record only pass/fail and error codes.

- [ ] Linux host has a working `/dev/kvm`.
- [ ] Docker stack starts, only HTTPS is publicly accessible.
- [ ] Browser asks for password, displays Android 14.
- [ ] Google Play Store is usable.
- [ ] Play Store offers the official KAST app.
- [ ] KAST installation succeeds.
- [ ] KAST opens its login screen.
- [ ] KAST accepts its own App Check / Play Integrity requirements.
- [ ] KAST preserves its state through emulator restart.

**Status:** No cloud VM has yet been provisioned or tested in this task.
The repository contains an implementation for user-side provisioning and
validation; it does not contain test evidence for KAST.
