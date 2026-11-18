# Potatoflix

<p align="center"><img src="icon.png" width="128" alt="Potatoflix logo: a potato on a sofa with a remote"></p>

<p align="center"><b>Movies, series and anime on Android phones, tablets and TV — built for low-end devices.</b><br>
A fork of <a href="https://github.com/sherazi5110/stream-goat">Stream Goat</a> (which itself runs the
<a href="https://github.com/recloudstream/cloudstream">CloudStream</a> extension ecosystem).</p>

Potatoflix is only a media player and organiser. It does not host, provide, link to or endorse any content, and it
ships with no content sources: you add your own extensions and Stremio add-ons, and you are responsible for having the
rights to whatever you play. Titles, posters and logos belong to their owners.

## What it does

- **One app on every screen:** Android phones, tablets and TV, from one APK.
- **CloudStream extensions and Stremio add-ons** from the same repositories the official CloudStream Android app uses,
  with no changes to the extensions: search, install, update (on start or by hand), uninstall, and each extension's own
  settings behind its gear button.
- **A real player (ExoPlayer / AndroidX Media3):** hardware decoding with FFmpeg software fallback for codecs your
  hardware can't handle (AV1/HEVC 10-bit), HLS/DASH/SmoothStreaming/RTSP, DRM (Widevine, ClearKey), sideloaded audio
  tracks merged in, subtitles from the source, online (OpenSubtitles, no account) or translated, plus subtitle style,
  sync, skip intro/credits, next-episode countdown, torrent streaming (opt-in) and aim for low-RAM boxes.
- **Built for low-end TVs:** memory-tiered buffer sizes and bitmap memory for 500 MB–1 GB Android TV boxes, leanback
  focus UI for remotes and wireless mice, hardware decoding preferred on TV, posters with a bounded memory footprint.
- **4K and surround-ish streams:** big-buffer profile for high bitrates (Settings → Player → Buffer: Automatic /
  Low RAM / Balanced / 4K), adaptive quality inside the source, pinned quality via track selection, and a 1080p-class
  Remote D-pad UI throughout.
- **Backup and sync:** a backup file that moves between devices (it also carries your extensions), plus WeTrakr
  scrobbling (now watching, watched at 80%).

## Differences from upstream (Stream Goat)

- Android and Android TV only; the Linux, Windows, macOS and web versions are removed.
- libVLC replaced with AndroidX Media3 (ExoPlayer) — codec/buffer settings like CloudStream's
  (Settings → Player → Codec / Buffer).
- Live TV removed (menus, settings, guides, everything).
- Brand renamed to Potatoflix; goat artwork and the start animation are gone.
- Vertical videos are detected automatically and played in portrait mode.
- Stream links start playing as soon as a decent one is found (480p+), like the official player.
- The player UI was tightened up: compact control chips, a scrollable bottom bar and an accent Next episode button.
- Wireless-mouse compatible: a mouse acts on the first click everywhere.

## Build and install

See [android/README.md](android/README.md). In short: `cd android && ../scripts/fetch-upstream.sh && ./gradlew assembleRelease`.
Releases with the APKs are built by CI on every push to `main` (see `.github/workflows/android.yml`).

## License

GPL-3.0-only (inherited from CloudStream and Stream Goat). See [LICENSE](LICENSE).
