#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
fail() { echo "ERROR: $*" >&2; exit 1; }
command -v docker >/dev/null || fail "Docker is required"
docker compose version >/dev/null || fail "Docker Compose v2 is required"
docker info >/dev/null || fail "Docker is not accessible"
[[ -c /dev/kvm ]] || fail "No /dev/kvm: choose a Linux VM with nested virtualization enabled"
[[ ! -e .env ]] || fail "Refusing to overwrite an existing .env"
read -r -p "Public DNS hostname: " domain
[[ "$domain" =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]*[a-zA-Z0-9]$ ]] || fail "Invalid DNS hostname"
[[ "$domain" == *.* ]] || fail "A full DNS domain is required"
read -r -p "Login username (3 to 32 characters): " username
[[ "$username" =~ ^[a-zA-Z0-9_-]{3,32}$ ]] || fail "Invalid username"
read -r -s -p "Password (at least 16 characters): " password
echo
[[ ${#password} -ge 16 ]] || fail "Password too short"
echo "Creating bcrypt hash with Caddy"
hash="$(docker run --rm -i caddy:2 caddy hash-password --algorithm bcrypt < <(printf "%s\n" "$password"))" || fail "Could not create password hash"
unset password
[[ "$hash" == \$2* ]] || fail "Unexpected bcrypt hash"
gid="$(stat -c "%g" /dev/kvm)"
umask 077
{
  printf "KVM_GID=%s\n" "$gid"
  printf "CLOUD_DOMAIN=%s\n" "$domain"
  printf "CLOUD_USER=%s\n" "$username"
  printf "CLOUD_PASSWORD_HASH=\x27%s\x27\n" "$hash"
  printf "TZ=UTC\n"
} > .env
chmod 600 .env
docker compose --env-file .env -f compose.yml config --quiet
echo "Ready: check DNS for $domain, open only ports 80 and 443."
echo "Run: docker compose --env-file .env -f compose.yml up -d --build"
