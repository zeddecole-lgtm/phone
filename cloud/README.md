# HiddenBox Cloud (KVM Google Play emulator)

This is a **prototype cloud Android emulator**, completely independent of
the in-phone BlackBox virtualization engine. It runs the official Android 14
Google Play system image and exposes its screen through **noVNC protected by
Caddy HTTPS and a password**.

## Host requirements

- x86_64 Ubuntu Linux virtual machine with **working /dev/kvm** (nested
  virtualization enabled); 4 vCPU, 8 GB RAM and **50 GB free SSD** recommended
  for the initial experiment.
- Docker Engine, Docker Compose v2, and permission to run Docker.
- Domain DNS pointing to the Linux server and incoming ports **80/443** open.
- Do **not** publish ADB (5554/5555), VNC (5900), or noVNC (6080).
- Permission to accept Google's Android SDK licenses. Build downloads are large.

Railway-like platforms without /dev/kvm cannot run this project.

## Start

1. Checkout `feature/hiddenbox-cloud-kvm` and open the `cloud` folder.
2. Verify `ls -l /dev/kvm` on the Linux VM.
3. Run `bash setup.sh`, enter your own DNS name, username and a strong password.
   The script writes a private `.env` and a **bcrypt hash**, not a plaintext password.
4. Run `docker compose --env-file .env -f compose.yml up -d --build`.
5. Navigate to `https://YOUR-DOMAIN/vnc.html?autoconnect=true`, authenticate,
   wait for Android to start, and sign in to Play Store inside Android.
6. **Install KAST directly from the official Play Store inside the emulator**.
7. Run `bash check.sh` on the VM to check boot and package installation,
   then test KAST's actual login and whether `APPCHECK-00401` occurs.

Android's app state persists in Docker volume `android-data`.
`docker compose down` preserves volumes. **Do not use `down -v`** unless
you want to delete the virtual phone, apps and any accounts.

## KAST acceptance criteria

| Check | Status before real VM test |
|---|---|
| Android 14 boots with KVM | Untested |
| HTTPS login and browser display | Untested |
| Google Play Store usable | Untested |
| KAST available for this x86_64 emulator in Google Play | Unknown |
| KAST opens and permits sign-in | Unknown |
| App Check / Play Integrity accepted by KAST | Unknown |
| KAST state survives container restart | Untested |

If the Play Store does not offer KAST for this emulator, do not sideload
unverified APKs: identify device/ABI incompatibility and stop the experiment.
If it launches but shows `APPCHECK-00401`, the cloud VM did not solve the
provider's attestation requirements. No software-only override is promised.

**Security:** Host providers can access the virtual machine. The private
Android screen is intended for a single trusted user. Caddy provides
TLS and password authentication. Keep SSH locked down and avoid entering
real financial credentials until you have confidence in the hosting provider
and security. No credentials or attestation tokens are stored in GitHub.

If the emulator crashes before reaching the desktop, check
`docker compose --env-file .env -f compose.yml logs --tail=100 emulator`.
Verify KVM, memory, disk space and Android SDK installation.

## References

- https://github.com/google/android-emulator-container-scripts
- https://developer.android.com/studio/run/emulator
- https://developer.android.com/google/play/integrity/verdicts
- https://play.google.com/store/apps/details?id=com.kastfinance.app

**Note:** This project runs a true Android emulator, *not* a trusted physical
device. The presence of Google Play is not evidence of certified device
integrity or KAST compatibility.
