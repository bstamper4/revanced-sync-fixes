#!/usr/bin/env bash
# Runs the extension's pure logic (URLs, JSON conversion, imgur page parsing) on the JVM.
#
# The same sources are compiled against a stub android.util.Log, so the test does not need a device.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:?set JAVA_HOME to a JDK 17}"
TOOLCHAIN="${TOOLCHAIN:?set TOOLCHAIN to the toolchain directory}"
PATH="$JAVA_HOME/bin:$PATH"
export PATH

CLASSES="$ROOT/build/test-classes"
CP="$TOOLCHAIN/volley.jar:$TOOLCHAIN/okhttp.jar:$TOOLCHAIN/okio.jar:$TOOLCHAIN/kotlin-stdlib.jar"
JSON="$TOOLCHAIN/json.jar"

if [ ! -f "$JSON" ]; then
    curl -fSL -o "$JSON" 'https://repo1.maven.org/maven2/org/json/json/20231013/json-20231013.jar'
fi

rm -rf "$CLASSES"
mkdir -p "$CLASSES"
javac -nowarn -d "$CLASSES" -cp "$CP:$JSON" \
    $(find "$ROOT/tests/src" "$ROOT/extensions" -name '*.java')

cd "$ROOT"
java -cp "$CLASSES:$CP:$JSON" app.revanced.extension.syncfixes.ExtensionTest "$ROOT/tests/fixtures/album.html"
