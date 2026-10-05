#!/usr/bin/env bash
# Builds a portable Windows x64 version of Tonearm on Linux: the app's jars, a Java runtime made from
# Temurin's Windows JDK, and libmpv-2.dll. Output: build/windows/Tonearm-<version>-windows-x64.zip.
# (CI builds the MSI installer on Windows; see .github/workflows/desktop.yml.)
set -euo pipefail
cd "$(dirname "$0")/.."
JDK=21.0.12.1+1
JDK_FILE=${JDK/+/_}
MPV_RELEASE="${MPV_RELEASE:-latest}"
cache=build/windows/cache
out=build/windows/Tonearm
mkdir -p "$cache"

./gradlew -q windowsJars
version=$(grep -oP '^version = "\K[^"]+' build.gradle.kts)

# A Linux and a Windows JDK of the same build: jlink from the first, modules from the second.
fetch() { [ -s "$cache/$2" ] || curl -fsSL -o "$cache/$2" "$1"; }
fetch "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-$JDK/OpenJDK21U-jdk_x64_windows_hotspot_$JDK_FILE.zip" jdk-windows.zip
fetch "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-$JDK/OpenJDK21U-jdk_x64_linux_hotspot_$JDK_FILE.tar.gz" jdk-linux.tar.gz
[ -d "$cache/jdk-windows" ] || { mkdir -p "$cache/jdk-windows" && unzip -q "$cache/jdk-windows.zip" -d "$cache/jdk-windows"; }
[ -d "$cache/jdk-linux" ] || { mkdir -p "$cache/jdk-linux" && tar xzf "$cache/jdk-linux.tar.gz" -C "$cache/jdk-linux"; }
winjdk=$(echo "$cache"/jdk-windows/*/)
linjdk=$(echo "$cache"/jdk-linux/*/)

# libmpv for Windows from mpv-winbuild-cmake (the builds mpv.io links to).
if [ ! -s "$cache/libmpv-2.dll" ]; then
  api="https://api.github.com/repos/shinchiro/mpv-winbuild-cmake/releases/$MPV_RELEASE"
  url=$(curl -fsSL "$api" | python3 -c "import json,sys; print(next(a['browser_download_url'] for a in json.load(sys.stdin)['assets'] if a['name'].startswith('mpv-dev-x86_64-') and 'v3' not in a['name'] and a['name'].endswith('.7z')))")
  curl -fsSL -o "$cache/mpv-dev.7z" "$url"
  7z e -y -o"$cache" "$cache/mpv-dev.7z" libmpv-2.dll >/dev/null
fi

rm -rf "$out" && mkdir -p "$out"
"$linjdk/bin/jlink" --module-path "$winjdk/jmods" \
  --add-modules java.base,java.desktop,java.logging,java.naming,java.net.http,java.sql,java.management,java.prefs,java.xml,jdk.httpserver,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported,jdk.zipfs,jdk.charsets,jdk.localedata \
  --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output "$out/runtime"
cp -r build/windows/app "$out/app"
cp "$cache/libmpv-2.dll" "$out/"
cp packaging/icon.ico "$out/"
cat > "$out/Tonearm.bat" <<'BAT'
@echo off
rem Starts Tonearm without a console window.
start "" "%~dp0runtime\bin\javaw.exe" -Dcompose.application.resources.dir="%~dp0." -Djna.library.path="%~dp0." -Dfile.encoding=UTF-8 -cp "%~dp0app\*" io.github.deadeyebarb.tonearm.desktop.MainKt
BAT
sed -i 's/$/\r/' "$out/Tonearm.bat"
(cd build/windows && rm -f "Tonearm-$version-windows-x64.zip" && zip -qr "Tonearm-$version-windows-x64.zip" Tonearm)
echo "Built build/windows/Tonearm-$version-windows-x64.zip"
