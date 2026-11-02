#!/usr/bin/env bash
# Downloads the portable toolchain the bundle is built with. Run once.
#
#   JDK 17              https://api.adoptium.net (Temurin)
#   ReVanced CLI 6.0.0  https://github.com/ReVanced/revanced-cli (contains the patcher and smali)
#   R8 / D8             https://dl.google.com/android/maven2/com/android/tools/r8
#   android.jar         https://dl.google.com/android/repository (platform 33)
#   okhttp/okio/volley  Maven Central (compile time signatures only)
set -euo pipefail

DEST="${1:-/tmp/opencode/toolchain}"
mkdir -p "$DEST"
cd "$DEST"

if [ ! -d jdk-17* ]; then
    curl -fSL -o jdk17.tar.gz 'https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse'
    tar xzf jdk17.tar.gz
fi
JDK="$(echo "$DEST"/jdk-17*)"
export PATH="$JDK/bin:$PATH"

[ -f cli.jar ] || curl -fSL -o cli.jar 'https://github.com/ReVanced/revanced-cli/releases/download/v6.0.0/revanced-cli-6.0.0-all.jar'
[ -f r8.jar ] || curl -fSL -o r8.jar 'https://dl.google.com/android/maven2/com/android/tools/r8/9.5.28/r8-9.5.28.jar'
[ -f okhttp.jar ] || curl -fSL -o okhttp.jar 'https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar'
[ -f okio.jar ] || curl -fSL -o okio.jar 'https://repo1.maven.org/maven2/com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar'
[ -f kotlin-stdlib.jar ] || curl -fSL -o kotlin-stdlib.jar 'https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.9.20/kotlin-stdlib-1.9.20.jar'
[ -f json.jar ] || curl -fSL -o json.jar 'https://repo1.maven.org/maven2/org/json/json/20231013/json-20231013.jar'
if [ ! -f volley.jar ]; then
    curl -fSL -o volley.aar 'https://repo1.maven.org/maven2/com/android/volley/volley/1.2.1/volley-1.2.1.aar'
    unzip -o -q volley.aar classes.jar
    mv classes.jar volley.jar
    rm -f volley.aar
fi
if [ ! -f android.jar ]; then
    curl -fSL -o platform-33.zip 'https://dl.google.com/android/repository/platform-33-ext3_r03.zip'
    unzip -o -q platform-33.zip 'android-13/android.jar'
    mv android-13/android.jar android.jar
    rm -rf android-13 platform-33.zip
fi

echo "toolchain ready:"
echo "  JAVA_HOME=$JDK"
echo "  TOOLCHAIN=$DEST"
