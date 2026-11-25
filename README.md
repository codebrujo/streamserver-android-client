# Streamclient

Streamclient: native Android client (Kotlin, Media3/ExoPlayer) for [streamserver](https://github.com/codebrujo/streamserver):
the home video and music library on mediaserver.

## Features

- **Library tree.** Video and music folders with files; the folders you open stay open. The rescan button
  makes the server re-read the disk (`?refresh=1`).
- **Search** by file name across video and music.
- **Two addresses and a password** in Settings: the local one (http, LAN) and the domain one (https).
- **Connection switching.** On Wi-Fi the local address is tried first. When a request to it fails, the
  app moves to the domain address at once, including streams that are already playing. While on Wi-Fi the
  local address is re-checked every 15 s. The status line shows *Local connection* or *Global connection*.
- **Recently played.** The last 5 started videos or tracks, with the position where playback stopped. Tap one
  to resume; *Clear* empties the list.
- **Continuous playback.** Video and music go on file after file and folder after folder, in the order of the
  fully expanded tree. Music keeps playing in the background, with notification and lock-screen controls.
- **Playback info.** File name, type and bitrate. For video, also the codecs, resolution and whether the phone
  plays the original file or the server converts it. For music, the quality label, the decoder bitrate and how
  the track is delivered (original or converted).
- **Album covers** on the music screen, in the mini player and in the notification.

- **Playlists**, shared with the web UI (`/api/playlists`): a *Playlists* folder at the top level next to Video
  and Music. *New playlist* creates one; tap a playlist to see its tracks, tap a track to play the playlist
  from it, long-press the playlist to play it from the start. The ✎ icon opens the editor:
  - *Contents*: the music tree with a check box at every track (and *All tracks* per folder);
  - *Order*: the chosen tracks; drag a row by its handle (or long-press it) to move it, ✕ removes it.

  *Save* stores the changes on the server; the trash icon deletes the playlist (the files stay on the drive).
  Tracks whose files are gone are shown greyed and skipped when playing.

- **Playlists on the phone**: *New playlist* asks where to keep it. *On the server* is shared with the web and
  holds server tracks. *On this phone* is stored in the app and can mix server tracks with files from the local
  Audiobooks and Music folders; it also works offline with its local files. In the editor, the *Contents* tab
  shows *Music on the server* next to *Audiobooks / Music on this phone*. Every playlist has a
  **Play order**: *Linear* stops after the last track, *Loop* starts over from the first. For server playlists
  it is stored on the server (streamserver v1.4+, shared with the web), for phone playlists in the app. Their tracks show
  where the file is: phone icon = local file, cloud icon = server.
- **Local media**: folders on the phone for audiobooks, music and films. Set them in Settings → *Local media*
  with the system folder picker (Storage Access Framework, so no "all files" permission; access survives
  restarts). A scan (*Scan local folders* in Settings, or the rescan button) walks them and shows the files in
  the top-level *Local media* folder: Audiobooks / Music / Films with their subfolders. They play without the
  server, continuously (file after file, folder after folder), and are included in search and *Recently
  played* (handy for audiobooks). Covers come from the pictures embedded in the files.

Long-press a music folder to play it from the first track.

## How video is played

1. **Original file on the phone.** `/api/stream/:id` with HTTP Range. The phone's decoders handle MKV, HEVC
   and 10-bit, so nothing is converted on the Pi.
2. **Server stream.** If the phone can't decode a track (often AC3/DTS audio) or can't open the file, the
   player switches to `/api/transcode/:id`. Seeking restarts the stream at the new position through
   `/api/seekpoint`, and a broken stream continues from the same place.

Music is streamed as the original file at home and as AAC (`q=eco`) over the domain address, like the
web UI.

## Build

GitHub Actions (`.github/workflows/build.yml`):
- for a pull request, the APK goes to the run's artifacts;
- for a push to `main`, the APK is published as a GitHub Release (Releases → latest → `streamclient-N.apk`).

Signing uses two repository secrets: `KEYSTORE_BASE64` (the keystore in base64, alias `cinema`) and
`KEYSTORE_PASSWORD`. Without them the APK is signed with a throwaway debug key, and every update has to be
installed from scratch.

Local build: `./gradlew assembleRelease` (needs the Android SDK and JDK 17).
