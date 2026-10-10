#!/usr/bin/env bash
# Create the release keystore (once) and its gitignored properties file.
# The keystore is the app's identity: lose it and the installed app can only
# be replaced by uninstalling. Back the file up somewhere safe.
set -euo pipefail
cd "$(dirname "$0")/.."

[[ -f keystore.properties ]] && { echo "keystore.properties already exists — not touching it."; exit 1; }

read -rsp "Keystore password: " PASS; echo
umask 077
tools/build.sh shell -c "keytool -genkeypair -v \
  -keystore /src/freepsvoice-release.jks -alias freepsvoice \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass '$PASS' -keypass '$PASS' \
  -dname 'CN=freeps voice, O=rauhe.eu'"

cat > keystore.properties <<EOF
storeFile=freepsvoice-release.jks
storePassword=$PASS
keyAlias=freepsvoice
keyPassword=$PASS
EOF
echo "Wrote freepsvoice-release.jks + keystore.properties (both gitignored — back them up!)"
