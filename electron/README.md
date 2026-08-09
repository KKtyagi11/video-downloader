# Video Downloader — Electron

The desktop UI. An Electron shell talking to a Python sidecar that drives
[yt-dlp](https://github.com/yt-dlp/yt-dlp) as a library.

## Setup

```bash
npm install
npm run setup   # creates ../.venv and installs yt-dlp into it
npm start
```

`npm run setup` is optional — if there's no `.venv`, the app falls back to `py`
then `python` on PATH, as long as `yt-dlp` is importable there. The badge in the
top-right shows which interpreter and yt-dlp version it found; it turns red with
an explanation if neither works.

**ffmpeg** is still needed for merged high-quality video and MP3 output:
`winget install Gyan.FFmpeg`.

## Packaging

```bash
npm run dist
```

Produces an NSIS installer on Windows (dmg / AppImage on mac / Linux). Note the
installer bundles the app but **not** Python — the shipped build still resolves
an interpreter at runtime. To make it fully standalone, swap the sidecar for a
PyInstaller-built `yt-dlp.exe` and change `pythonCandidates()` in `main.js` to
point at it.

## Architecture

```
┌─────────────┐  IPC (contextBridge)  ┌──────────┐  JSON-lines over stdio  ┌───────────┐
│  renderer   │ ────────────────────► │  main.js │ ──────────────────────► │ engine.py │
│  (no Node)  │ ◄──────────────────── │          │ ◄────────────────────── │  yt-dlp   │
└─────────────┘   job-update events   └──────────┘   {"type":"job",...}    └───────────┘
```

| File | Role |
|---|---|
| `main.js` | Window, settings, dialogs, spawns and supervises the sidecar |
| `preload.js` | The entire renderer API surface — 12 calls, nothing else |
| `engine/engine.py` | yt-dlp driver: thread pool (3 at once), progress, cancel, probe |
| `renderer/` | UI — no framework, no build step |

**Security posture:** `contextIsolation: true`, `nodeIntegration: false`, a CSP
that allows only self-hosted scripts and styles (remote images are permitted so
thumbnails load), in-app navigation blocked, and external links forced out to the
system browser.

## Privacy — what touches disk

| Data | Where it goes |
|---|---|
| Browser cookies | Read from the browser profile only while a download runs, held in memory, sent only to the site being downloaded from. **Never written by this app.** |
| Settings | `userData/settings.json` — download folder, quality, playlist flag, and the browser's *name* (`"chrome"`), never its contents. |
| Window cookie jar | Emptied on every launch; `Set-Cookie` is stripped from all responses, so thumbnail CDNs cannot write to it. |
| Downloaded files | Only the folder you chose. |

`hardenSession()` in `main.js` enforces the last two, and also cancels any
renderer request that isn't `file://` or an image — the window has no reason to
make other network calls, so it can't.

**Redistributing:** `npm run dist` packages `main.js`, `preload.js`, `renderer/`,
`engine/` and the icons — source only. Settings live in the per-user `userData`
directory, outside the build, and are created fresh on first run. A build you
hand to someone else contains none of your data.

Do be upfront with anyone you share it with that the cookie option reads a
browser's cookie database — that is a privileged operation, and antivirus tools
may flag it on those grounds alone.

**Protocol** — commands in (`probe`, `add`, `cancel`, `quit`), events out
(`ready`, `job`, `probe`, `fatal`). One JSON object per line, UTF-8 forced on
both ends. Progress is throttled to 5 events/sec per job so a fast download
can't flood the IPC channel.

## UI notes

- Paste several links at once, one per line; **Ctrl+Enter** starts them.
- Drag a link from a browser onto the window to queue it.
- Cards show a thumbnail, live progress bar, speed and ETA; failed and cancelled
  rows get a **Retry** button, finished rows a **Show file** button.
- **Cookies from** reads your browser's cookie store so private X posts,
  Instagram, and age-restricted YouTube work. Close the browser first —
  Chrome and Edge lock the cookie DB while running.
- Settings persist to Electron's `userData/settings.json`.

Cancelling mid-download leaves a `.part` file behind; yt-dlp resumes from it if
you retry the same URL to the same folder.
