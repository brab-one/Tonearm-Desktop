#!/usr/bin/env bash
# Installs the Tonearm desktop app for the current user: the app (with its own Java runtime) in
# ~/.local/share/tonearm, a menu entry, an icon and a `tonearm` command. Needs libmpv (the mpv package).
# Run from the desktop/ folder after `./gradlew createReleaseDistributable`, or pass the built folder.
set -euo pipefail
cd "$(dirname "$0")/.."
src="${1:-build/compose/binaries/main-release/app/Tonearm}"
[ -x "$src/bin/Tonearm" ] || { echo "Build it first: ./gradlew createReleaseDistributable"; exit 1; }
if ! ldconfig -p 2>/dev/null | grep -q libmpv.so && [ ! -e /usr/lib/libmpv.so.2 ] && [ ! -e /usr/lib/libmpv.so ]; then
  echo "Warning: libmpv not found. Install mpv (e.g. 'sudo pacman -S mpv' or 'sudo apt install libmpv2')."
fi
dest="$HOME/.local/share/tonearm"
rm -rf "$dest" && mkdir -p "$dest" && cp -r "$src/." "$dest/"
mkdir -p "$HOME/.local/bin" "$HOME/.local/share/applications" "$HOME/.local/share/icons/hicolor/512x512/apps"
ln -sf "$dest/bin/Tonearm" "$HOME/.local/bin/tonearm"
cp src/main/resources/icon.png "$HOME/.local/share/icons/hicolor/512x512/apps/tonearm.png"
cat > "$HOME/.local/share/applications/tonearm.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Tonearm
GenericName=Music Player
Comment=Lossless Subsonic player with Lidarr and Brainarr
Exec=$dest/bin/Tonearm
Icon=tonearm
Terminal=false
Categories=AudioVideo;Audio;Player;
StartupWMClass=io-github-deadeyebarb-tonearm-desktop-MainKt
DESKTOP
update-desktop-database "$HOME/.local/share/applications" >/dev/null 2>&1 || true
echo "Installed Tonearm in $dest (menu entry 'Tonearm', command 'tonearm')."
echo "Uninstall: rm -rf $dest ~/.local/bin/tonearm ~/.local/share/applications/tonearm.desktop ~/.local/share/icons/hicolor/512x512/apps/tonearm.png"
