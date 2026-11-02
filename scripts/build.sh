#!/usr/bin/env bash
# Builds the ReVanced patch bundle (.rvp) without Gradle.
#
# The ReVanced patches Gradle plugin lives on GitHub Packages; this script does the same work with the
# portable toolchain instead, so the bundle can be built anywhere.
#
# Required environment:
#   JAVA_HOME     JDK 17
#   TOOLCHAIN     directory with cli.jar, r8.jar, android.jar, okhttp.jar, okio.jar, volley.jar
#                 (see scripts/fetch-toolchain.sh)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:?set JAVA_HOME to a JDK 17}"
TOOLCHAIN="${TOOLCHAIN:?set TOOLCHAIN to the toolchain directory}"
PATH="$JAVA_HOME/bin:$PATH"
export PATH

CLI_JAR="$TOOLCHAIN/cli.jar"
R8_JAR="$TOOLCHAIN/r8.jar"
ANDROID_JAR="$TOOLCHAIN/android.jar"

for file in "$CLI_JAR" "$R8_JAR" "$ANDROID_JAR" \
    "$TOOLCHAIN/okhttp.jar" "$TOOLCHAIN/okio.jar" "$TOOLCHAIN/kotlin-stdlib.jar" \
    "$TOOLCHAIN/volley.jar"; do
    if [ ! -f "$file" ]; then
        echo "Missing $file (run scripts/fetch-toolchain.sh)" >&2
        exit 1
    fi
done

VERSION="${VERSION:-1.0.0}"
BUILD="$ROOT/build"
rm -rf "$BUILD"
mkdir -p "$BUILD/extension-classes" "$BUILD/extension-dex" \
    "$BUILD/patch-classes" "$BUILD/patch-dex" "$BUILD/rvp/extensions"

echo "==> Compiling the extension"
javac -nowarn -source 8 -target 8 -Xlint:-options \
    -bootclasspath "$ANDROID_JAR" \
    -cp "$TOOLCHAIN/okhttp.jar:$TOOLCHAIN/okio.jar:$TOOLCHAIN/kotlin-stdlib.jar:$TOOLCHAIN/volley.jar" \
    -d "$BUILD/extension-classes" \
    $(find "$ROOT/extensions" -name '*.java')
jar --create --file "$BUILD/extension.jar" -C "$BUILD/extension-classes" .

echo "==> Dexing the extension"
java -cp "$R8_JAR" com.android.tools.r8.D8 --release --min-api 23 \
    --lib "$ANDROID_JAR" \
    --lib "$TOOLCHAIN/volley.jar" \
    --lib "$TOOLCHAIN/okhttp.jar" \
    --lib "$TOOLCHAIN/okio.jar" \
    --output "$BUILD/extension-dex" "$BUILD/extension.jar"
cp "$BUILD/extension-dex/classes.dex" "$BUILD/rvp/extensions/syncfixes.rve"

echo "==> Compiling the patches"
javac -nowarn -source 8 -target 8 -Xlint:-options \
    -cp "$CLI_JAR" \
    -d "$BUILD/patch-classes" \
    $(find "$ROOT/patches" -name '*.java')

echo "==> Dexing the patches"
# The patch classes run inside the patcher, so its classes are compile-only libraries. The dex is only
# used by ReVanced Manager on Android, which supplies kotlin-stdlib itself.
jar --create --file "$BUILD/patches.jar" -C "$BUILD/patch-classes" .
java -cp "$R8_JAR" com.android.tools.r8.D8 --release --min-api 26 \
    --lib "$CLI_JAR" \
    --lib "$ANDROID_JAR" \
    --lib "$TOOLCHAIN/kotlin-stdlib.jar" \
    --output "$BUILD/patch-dex" "$BUILD/patches.jar"
cp "$BUILD/patch-dex/classes.dex" "$BUILD/rvp/classes.dex"

echo "==> Assembling the bundle"
# The .rvp is the patches jar (patch classes, loaded from the JVM) with the dex and the extension added
# (loaded by ReVanced Manager on Android).
cp -r "$BUILD/patch-classes/app" "$BUILD/rvp/app"

cat >"$BUILD/MANIFEST.MF" <<EOF
Manifest-Version: 1.0
Name: sync fixes
Description: ReVanced patches that restore Reddit galleries, load more comments, Redgifs and imgur albums in Sync for Reddit (free, Pro and dev) v23.06.30-13:39.
Version: $VERSION
Timestamp: $(date +%s)000
Source: ${SOURCE:-https://github.com/bstamper4/revanced-sync-fixes}
Author: ${AUTHOR:-bstamper4}
Contact: ${CONTACT:-https://github.com/bstamper4/revanced-sync-fixes/issues}
Website: ${WEBSITE:-https://github.com/bstamper4/revanced-sync-fixes}
License: GNU General Public License v3.0
EOF

(cd "$BUILD/rvp" && jar --create --file "$BUILD/revanced-sync-fixes.rvp" \
    --manifest "$BUILD/MANIFEST.MF" app classes.dex extensions)

echo "==> $BUILD/revanced-sync-fixes.rvp"
unzip -l "$BUILD/revanced-sync-fixes.rvp" | tail -8
