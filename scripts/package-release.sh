#!/usr/bin/env bash
# Builds distributable artifacts into dist/:
#   - debug APK (installable, signed with the local debug key)
#   - release APK (signed only when AURALYNX_KEYSTORE_* variables are set; otherwise *-unsigned.apk)
#   - repository source ZIP (git archive of HEAD: no build output, caches, keys or local files)
#   - SHA256SUMS
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION=$(grep '^auralynx.versionName=' gradle.properties | cut -d= -f2)
TAG="v${VERSION}"
DIST=dist
rm -rf "$DIST" && mkdir -p "$DIST"

./gradlew --no-configuration-cache :app:assembleDebug :app:assembleRelease

cp app/build/outputs/apk/debug/app-debug.apk "$DIST/auralynx-${TAG}-debug.apk"
if [ -f app/build/outputs/apk/release/app-release.apk ]; then
  cp app/build/outputs/apk/release/app-release.apk "$DIST/auralynx-${TAG}-release.apk"
else
  cp app/build/outputs/apk/release/app-release-unsigned.apk "$DIST/auralynx-${TAG}-release-unsigned.apk"
fi

if [ -n "$(git status --porcelain)" ]; then
  echo "warning: working tree has uncommitted changes; the source ZIP contains HEAD only" >&2
fi
git archive --format=zip --prefix="auralynx-android-${TAG}/" -o "$DIST/auralynx-android-${TAG}-source.zip" HEAD

( cd "$DIST" && shasum -a 256 ./*.apk ./*.zip | sed 's# \./# #' > SHA256SUMS )
cat "$DIST/SHA256SUMS"
