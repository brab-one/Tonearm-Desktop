# Tonearm Desktop

The desktop version of [Tonearm](https://github.com/brab-one/Tonearm-PhoneApp) for Linux and Windows: a lossless
player for **Subsonic / OpenSubsonic** servers (Navidrome and others) with **mutual TLS**, a
**Lidarr** dashboard, **AI picks**, and **Tonearm Connect** so the phone can control it.

The short install and setup guide for all parts is in [Tonearm](https://github.com/brab-one/Tonearm).

It's written in Kotlin with Compose for Desktop: a native UI drawn with Skia, no browser engine, in the
same neon HUD style as the phone. The Subsonic client, TLS, Lidarr, Connect, weekly picks and YouTube Music
code is the phone app's own (`shared/` in the [Tonearm-PhoneApp](https://github.com/brab-one/Tonearm-PhoneApp) repository, included here as the `tonearm`
submodule).

## Features

- Library: home (recently added and played, most played, random), artists, albums, playlists, liked, search
- **Likes**: hover a song and click the heart, or right-click it. Liking a YouTube Music song requests its
  album in Lidarr and likes it in your library once it's downloaded. Playing alone requests nothing. Likes of
  songs you don't have yet are shared with the phone through Tonearm Connect, and such
  songs show whether they're in your library, downloading in Lidarr or requested
- **Playlists**: create, rename, delete, reorder, add from any song's menu or save the queue. Songs you don't
  have yet can go in too: they play from YouTube Music and move into the playlist on your server once
  Lidarr has them ("Request missing" asks for their albums)
- **Import** playlists from YouTube Music / YouTube and Spotify links (Spotify: the first 100 songs), CSV
  exports (Exportify, TuneMyMusic, Soundiiz…) and Spotify's own data export (Playlist1.json)
- **More like this**: right-click a song, album, artist or playlist (or use the button on its page) for a
  page of similar artists (in your library or not; right-click to request), **Play similar songs** (the
  server's similar songs, else YouTube Music's radio) and, with the Tonearm server's AI, albums like it
- **Weekly picks** (Discover screen): the first few AI picks downloaded every week into a playlist that's
  deleted a week later (music included) unless you like it and give it a name; albums with liked songs stay
  either way
- **YouTube Music artists and albums**: bios, popular songs, radio and the albums you don't have, playable
  and requestable
- **This computer**: music folders on the PC (FLAC, MP3, AAC, Opus, WAV…), played straight from disk
- **Hi-res output and equalizer**: pick the output device (an `alsa/hw:` device or WASAPI exclusive mode
  for bit-perfect playback at the file's own rate and depth; the player bar shows what reaches the device),
  and a 10-band equalizer with presets and automatic headroom
- **When the queue ends**: stop, similar music (library first, then YouTube Music's radio), or another playlist
- Back/forward like a browser: the mouse's side buttons, Alt+← / Alt+→ or the arrows at the top
- Mouse-friendly rows: arrows at the edges of every horizontal row, a scrollbar under it, click-and-drag, Shift + wheel
- Playback through **libmpv**: bit-perfect FLAC up to 32-bit/384 kHz, gapless, ReplayGain, Opus/AAC.
  The queue lives in Tonearm (shuffle, repeat, play next, remove), mpv only holds the current and next
  song so it can prefetch the transition
- mTLS like the phone: a `.p12` client certificate and an optional extra CA. A loopback-only proxy with a
  random path token feeds mpv, so mpv never sees credentials or the certificate
- **Lidarr**: request artists and albums, follow the downloads
- **Search** across the library, YouTube Music and Deezer at once, best matches first whatever the source,
  with "Ask the AI" for searches that describe music
- **Remove from server**: a song, an album or an artist goes from Lidarr with its files (after a yes), and
  Navidrome after its scan; Lidarr won't download it again unless you ask
- **Dislike** a song (thumbs-down in the player bar or its menu, with the Tonearm server 1.5.4 or later): it's
  skipped, goes to the end of searches and out of mixes and picks, the same on the phone
- **Wanted** tab on the Lidarr screen: what Lidarr is still looking for, each searchable again
- **Discover** (with the Tonearm server): discovery picks (artists you don't have that yours point to, from
  Deezer's related artists, each with an album) and AI picks (from the server's AI: Ollama, an OpenAI-style
  API or Claude); open one on YouTube Music or request it, or give it a thumbs-down for "not for me"
- **YouTube Music** for what you don't have (in search), with the artist requested in Lidarr when it plays
- **Tonearm Connect**: with the [Tonearm server](https://github.com/brab-one/Tonearm-Server) next to your
  Navidrome (or the [plugin](https://github.com/brab-one/Tonearm-Connect) in Lidarr), the phone's
  Devices screen shows this player and controls it (play/pause, skip, seek, volume, shuffle, repeat,
  queue), and playback moves between phone and desktop
- Scrobbles to the music server (now playing, and at half the song or 4 minutes), and every song it plays
  (YouTube Music and skips too) goes to the Tonearm server's listening history, which the picks learn from
- **Player bar**: click the song to go back to where you started it, the artist for their page, right-click
  for the song's menu
- Seeking works behind proxies that drop range requests (the whole song is buffered and seeked in)
- Shortcuts: Ctrl+P play/pause, Ctrl+← / Ctrl+→ previous/next, and the media keys while the window has focus
- Passwords and API keys go to the desktop keyring (Secret Service: KWallet, GNOME Keyring) on Linux and
  are DPAPI-encrypted on Windows. Settings: `~/.config/tonearm` / `%APPDATA%\Tonearm`

## Install

**Linux** (needs libmpv: `sudo pacman -S mpv`, or `libmpv2` on Debian/Ubuntu): download
`Tonearm-desktop-linux-x64.tar.gz` from the [releases](https://github.com/brab-one/Tonearm-Desktop/releases),
unpack it and run `Tonearm/bin/Tonearm`, or build and install it for your user (menu entry, icon and a
`tonearm` command, with its own Java runtime):

```bash
git clone --recursive https://github.com/brab-one/Tonearm-Desktop.git
cd Tonearm-Desktop
./gradlew createReleaseDistributable
packaging/install-linux.sh
```

`./gradlew run` starts it straight from the source. There's also a `.deb` in the releases.

**Windows**: the releases have an MSI installer (built by GitHub Actions) and a portable zip (unpack it
and start `Tonearm.bat`). Both include their own Java runtime and `libmpv-2.dll`.
`packaging/build-windows.sh` builds the portable zip on Linux.

On first start, enter the server address, your username and password, and, for mTLS, the same `.p12`
client certificate as on the phone. Lidarr goes under Settings (address and API key; use the music
server's client certificate if Lidarr sits behind the same proxy), unless the Tonearm server holds it: then
it comes through that with your music server login and there's nothing to enter. Liking a YouTube Music
song asks Lidarr for its album, found in Lidarr's own track lists (the app doesn't contact MusicBrainz).

For Tonearm Connect, run the [Tonearm server](https://github.com/brab-one/Tonearm-Server) at
`<music server>/connect-tonearm/` (the app finds it; it serves every user with their own login), or install
the [plugin](https://github.com/brab-one/Tonearm-Connect) in Lidarr. Use the same music server address as on
the phone (song ids are per server).

## Build

Needs JDK 21. Clone with `--recursive` (or run `git submodule update --init`) for the shared code.

```bash
./gradlew run                          # start it
./gradlew test                         # unit tests
./gradlew createReleaseDistributable   # build/compose/binaries/main-release/app/Tonearm
./gradlew packageReleaseMsi            # Windows installer (on Windows)
packaging/build-windows.sh             # portable Windows zip (on Linux)
```

## Known limitations

- No MPRIS/system media integration yet: media keys only work while the window has focus.
- The Windows build is tested by CI and under Wine, not on real Windows hardware.
