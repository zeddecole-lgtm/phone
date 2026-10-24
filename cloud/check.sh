#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
compose=(docker compose --env-file .env -f compose.yml)
"${compose[@]}" ps
echo "Checking emulator boot..."
timeout 120 "${compose[@]}" exec -T emulator adb wait-for-device || { echo "Android did not start yet"; exit 1; }
boot="$("${compose[@]}" exec -T emulator adb shell getprop sys.boot_completed | tr -d "\r\n")"
abi="$("${compose[@]}" exec -T emulator adb shell getprop ro.product.cpu.abilist | tr -d "\r\n")"
echo "Android boot complete: ${boot:-unknown}"
echo "Supported CPU ABIs: ${abi:-unknown}"
for pkg in com.google.android.gms com.android.vending com.kastfinance.app; do
  if "${compose[@]}" exec -T emulator adb shell pm path "$pkg" 2>/dev/null | grep -q "^package:"; then
    echo "$pkg: installed"
  else
    echo "$pkg: absent"
  fi
done
echo "Open Google Play in the browser and install KAST officially."
echo "This does not prove that Play Integrity will accept this emulator."
