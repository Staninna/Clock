#!/usr/bin/env bash
# One-time setup for the release pipeline (.github/workflows/release.yml).
#
# Creates a release signing key with random passwords and stores everything the pipeline needs
# as GitHub Actions secrets of this repository. Run it once, on your own computer, from the
# repository root:
#
#   ./scripts/setup-release-signing.sh
#
# Requirements: keytool (any JDK), openssl, and the GitHub CLI (gh) logged in with access to
# this repository (`gh auth login`).
#
# The keystore and its passwords are written to ~/.clock-release-signing/. BACK THAT FOLDER UP
# (e.g. in your password manager): every future update must be signed with the same key, and
# GitHub secrets cannot be read back once stored.

set -euo pipefail

ALIAS="clock"
OUT_DIR="${CLOCK_SIGNING_DIR:-$HOME/.clock-release-signing}"
KEYSTORE="$OUT_DIR/release.jks"

for tool in keytool openssl gh; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "Error: '$tool' is required but not installed." >&2
        exit 1
    fi
done

if ! gh auth status >/dev/null 2>&1; then
    echo "Error: the GitHub CLI is not logged in. Run 'gh auth login' first." >&2
    exit 1
fi

REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner)"
echo "Repository: $REPO"

if [ -e "$KEYSTORE" ]; then
    echo "A keystore already exists at $KEYSTORE."
    echo "Re-uploading its secrets instead of creating a new key (a new key would break updates)."
    # shellcheck disable=SC1091
    source "$OUT_DIR/passwords.env"
else
    mkdir -p "$OUT_DIR"
    chmod 700 "$OUT_DIR"

    # PKCS12 keystores use the same password for the store and the key.
    STORE_PASSWORD="$(openssl rand -base64 32 | tr -d '/+=' | cut -c1-32)"
    KEY_PASSWORD="$STORE_PASSWORD"

    keytool -genkeypair \
        -keystore "$KEYSTORE" \
        -storetype PKCS12 \
        -storepass "$STORE_PASSWORD" \
        -keypass "$KEY_PASSWORD" \
        -alias "$ALIAS" \
        -keyalg RSA \
        -keysize 4096 \
        -validity 10000 \
        -dname "CN=Clock release, O=$REPO"

    {
        echo "STORE_PASSWORD='$STORE_PASSWORD'"
        echo "KEY_PASSWORD='$KEY_PASSWORD'"
        echo "ALIAS='$ALIAS'"
    } > "$OUT_DIR/passwords.env"
    chmod 600 "$KEYSTORE" "$OUT_DIR/passwords.env"

    echo "Created $KEYSTORE"
fi

base64 < "$KEYSTORE" | tr -d '\n' | gh secret set KEYSTORE_BASE64 --repo "$REPO"
printf '%s' "$STORE_PASSWORD" | gh secret set KEYSTORE_PASSWORD --repo "$REPO"
printf '%s' "$ALIAS" | gh secret set KEY_ALIAS --repo "$REPO"
printf '%s' "$KEY_PASSWORD" | gh secret set KEY_PASSWORD --repo "$REPO"

echo
echo "Done. The release pipeline secrets are set for $REPO."
echo "Certificate fingerprint (to verify your APKs):"
keytool -list -keystore "$KEYSTORE" -storepass "$STORE_PASSWORD" -alias "$ALIAS" | grep -i "SHA-256\|SHA256" || true
echo
echo "IMPORTANT: back up $OUT_DIR somewhere safe. Without it you cannot publish updates"
echo "that install over this app."
