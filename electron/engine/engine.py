"""yt-dlp sidecar.

Speaks JSON-lines: one command object per line on stdin, one event object per
line on stdout. Self-contained on purpose — the Electron app ships this file and
nothing else from the repo.

Commands
    {"cmd":"probe",  "id":..., "url":..., "browser":...}
    {"cmd":"add",    "id":..., "url":..., "dest":..., "format":..., "browser":..., "playlist":bool}
    {"cmd":"cancel", "id":...}
    {"cmd":"quit"}

Events
    {"type":"ready"}
    {"type":"formats"}                         -- the quality menu, sent once at startup
    {"type":"job", ...job fields...}
    {"type":"probe", "id":..., "title":..., "thumbnail":..., "duration":..., "uploader":...}
    {"type":"fatal", "message":...}
"""

from __future__ import annotations

import json
import os
import re
import shutil
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor

MAX_CONCURRENT = 3
PROGRESS_INTERVAL = 0.2  # seconds between progress events per job
ATTEMPTS = 3             # total tries per job when the failure looks transient
RETRY_BACKOFF = 2.0      # seconds, multiplied by the attempt number


# A copy of yt-dlp ships inside the app (engine/vendor), so a machine only needs
# a Python interpreter — not a pip install. A system copy still wins if it is
# newer, since sites break often and users update yt-dlp far more than this app.
VENDOR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "vendor")
if os.path.isdir(VENDOR):
    sys.path.append(VENDOR)


class Cancelled(Exception):
    pass


def find_ffmpeg() -> str:
    """ffmpeg bundled beside the app wins over whatever is on PATH."""
    here = os.path.dirname(os.path.abspath(__file__))
    for candidate in (
        os.path.join(here, "bin", "ffmpeg.exe"),
        os.path.join(here, "bin", "ffmpeg"),
    ):
        if os.path.isfile(candidate):
            return candidate
    return shutil.which("ffmpeg") or ""


FFMPEG = find_ffmpeg()
HAS_FFMPEG = bool(FFMPEG)

# Each format carries two selectors. `merged` gives the best result but needs
# ffmpeg to stitch the separate video and audio streams; `single` restricts the
# choice to progressive streams that arrive as one file. Without this split
# yt-dlp happily picks a merged format and only fails at the merge step.
FORMATS = [
    {
        "id": "best",
        "label": "Best quality",
        "merged": "bv*+ba/b",
        "single": "b",
        "opts": {"merge_output_format": "mp4"},
    },
    {
        "id": "1080",
        "label": "1080p",
        "merged": "bv*[height<=1080]+ba/b[height<=1080]",
        "single": "b[height<=1080]/b",
        "opts": {"merge_output_format": "mp4"},
    },
    {
        "id": "720",
        "label": "720p",
        "merged": "bv*[height<=720]+ba/b[height<=720]",
        "single": "b[height<=720]/b",
        "opts": {"merge_output_format": "mp4"},
    },
    {
        "id": "480",
        "label": "480p",
        "merged": "bv*[height<=480]+ba/b[height<=480]",
        "single": "b[height<=480]/b",
        "opts": {"merge_output_format": "mp4"},
    },
    {
        "id": "small",
        "label": "Smallest file",
        "merged": "wv*+wa/w",
        "single": "w",
        "opts": {"merge_output_format": "mp4"},
    },
    {
        "id": "mp3",
        "label": "MP3",
        "merged": "ba/b",
        "single": None,  # conversion is impossible without ffmpeg
        "needs_ffmpeg": True,
        "opts": {
            "postprocessors": [
                {
                    "key": "FFmpegExtractAudio",
                    "preferredcodec": "mp3",
                    "preferredquality": "192",
                }
            ]
        },
    },
    {
        "id": "audio",
        "label": "Audio (original)",
        "merged": "ba/b",
        "single": "ba[vcodec=none]/ba/b",
        "opts": {},
    },
]
FORMAT_BY_ID = {f["id"]: f for f in FORMATS}


def format_menu() -> list[dict]:
    """What the UI shows, adjusted for whether ffmpeg is actually present."""
    menu = []
    for f in FORMATS:
        if f.get("needs_ffmpeg") and not HAS_FFMPEG:
            hint, enabled = "install ffmpeg to enable", False
        elif f["id"] in ("mp3", "audio"):
            hint, enabled = ("audio only", True)
        elif HAS_FFMPEG:
            hint, enabled = ("video + audio, merged" if f["id"] == "best" else "or lower"), True
        else:
            hint, enabled = "single stream — install ffmpeg for higher", True
        menu.append({"id": f["id"], "label": f["label"], "hint": hint, "enabled": enabled})
    return menu

_out_lock = threading.Lock()

# Windows consoles default to cp1252, which mangles the pipe the moment a title
# contains a non-Latin-1 character. Force UTF-8 here rather than relying on the
# launcher to set PYTHONIOENCODING.
for stream in (sys.stdout, sys.stdin):
    try:
        stream.reconfigure(encoding="utf-8", errors="replace", newline="\n")
    except (AttributeError, ValueError):
        pass


def emit(payload: dict) -> None:
    line = json.dumps(payload, ensure_ascii=False, default=str)
    with _out_lock:
        sys.stdout.write(line + "\n")
        sys.stdout.flush()


class Job:
    def __init__(self, spec: dict) -> None:
        self.id = spec["id"]
        self.url = spec["url"]
        self.dest = spec["dest"]
        self.format = spec.get("format", "best")
        self.browser = spec.get("browser") or "none"
        self.playlist = bool(spec.get("playlist"))
        self.title = spec.get("title") or spec["url"]
        self.thumbnail = spec.get("thumbnail") or ""
        self.status = "queued"
        self.percent = 0.0
        self.speed = 0.0
        self.eta = 0
        self.size = 0
        self.detail = ""
        self.filepath = ""
        self.cancel = threading.Event()
        self._last_emit = 0.0

    def send(self, force: bool = True) -> None:
        now = time.monotonic()
        if not force and now - self._last_emit < PROGRESS_INTERVAL:
            return
        self._last_emit = now
        emit(
            {
                "type": "job",
                "id": self.id,
                "url": self.url,
                "title": self.title,
                "thumbnail": self.thumbnail,
                "status": self.status,
                "percent": round(self.percent, 1),
                "speed": self.speed,
                "eta": self.eta,
                "size": self.size,
                "detail": self.detail,
                "filepath": self.filepath,
            }
        )


JOBS: dict[str, Job] = {}
POOL = ThreadPoolExecutor(max_workers=MAX_CONCURRENT, thread_name_prefix="dl")
PROBE_POOL = ThreadPoolExecutor(max_workers=4, thread_name_prefix="probe")


def base_opts(browser: str) -> dict:
    opts = {
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        "consoletitle": False,
        "retries": 5,
        "fragment_retries": 5,
        "extractor_retries": 3,
        "concurrent_fragment_downloads": 4,
        "windowsfilenames": True,
        "ignoreerrors": False,
        "color": {"stdout": "no_color", "stderr": "no_color"},
    }
    if browser and browser.lower() != "none":
        opts["cookiesfrombrowser"] = (browser.lower(),)
    return opts


def probe(spec: dict) -> None:
    import yt_dlp

    try:
        with yt_dlp.YoutubeDL({**base_opts(spec.get("browser", "none")), "skip_download": True}) as ydl:
            info = ydl.extract_info(spec["url"], download=False, process=False)
        is_playlist = info.get("_type") == "playlist"
        emit(
            {
                "type": "probe",
                "id": spec["id"],
                "ok": True,
                "title": info.get("title") or spec["url"],
                "thumbnail": pick_thumb(info),
                "duration": info.get("duration") or 0,
                "uploader": info.get("uploader") or info.get("channel") or "",
                "extractor": info.get("extractor_key") or "",
                "playlist": is_playlist,
            }
        )
    except Exception as exc:  # noqa: BLE001
        emit({"type": "probe", "id": spec["id"], "ok": False, "message": clean(exc)})


def pick_thumb(info: dict) -> str:
    if info.get("thumbnail"):
        return info["thumbnail"]
    thumbs = info.get("thumbnails") or []
    return thumbs[-1].get("url", "") if thumbs else ""


# Errors worth retrying: the site hiccuped, nothing is actually wrong with the
# request. Facebook in particular serves a page variant yt-dlp can't parse every
# so often, and a plain retry usually lands on a good one.
TRANSIENT = (
    "cannot parse data",
    "unable to download webpage",
    "unable to extract",
    "temporary failure",
    "timed out",
    "connection reset",
    "http error 5",
    "http error 429",
    "read timed out",
)

# Patterns worth translating. yt-dlp's own wording assumes you know the tool.
HINTS = (
    (("login required", "log in", "sign in", "cookies", "not logged in", "private video",
      "this video is only available for registered users"),
     "This needs a signed-in session — set “Cookies from” to the browser you're logged in with, "
     "then retry (close that browser first)."),
    (("age-restricted", "age restricted", "confirm your age"),
     "Age-restricted — set “Cookies from” to a browser signed in to that site, then retry."),
    (("video unavailable", "content isn't available", "has been removed", "no longer available",
      "404", "not found"),
     "The post seems to be deleted, private, or the link is wrong."),
    (("not available in your country", "geo", "geoblock"),
     "Blocked in your region."),
    (("cannot parse data", "unable to extract"),
     "The site served a page the downloader couldn't read. Usually temporary — hit Retry. "
     "If it keeps failing, run: pip install -U yt-dlp"),
    (("ffmpeg",),
     "Install ffmpeg (winget install Gyan.FFmpeg) and restart the app."),
)


ANSI_RE = re.compile(r"\x1b\[[0-9;]*m")


def clean(exc: Exception) -> str:
    # yt-dlp colours its messages even when writing to a pipe, so strip the
    # escape sequences before they reach the UI.
    msg = ANSI_RE.sub("", str(exc)).replace("\n", " ").strip()
    msg = re.sub(r"^ERROR:\s*", "", msg)
    msg = re.sub(r"^\[[a-zA-Z0-9_:]+\]\s*[^:]{0,40}:\s*", "", msg)  # drop "[generic] id:" prefix
    return msg[:400] or exc.__class__.__name__


def is_transient(msg: str) -> bool:
    low = msg.lower()
    return any(p in low for p in TRANSIENT)


def explain(msg: str) -> str:
    """Prepend a human explanation when we recognise the failure."""
    low = msg.lower()
    for needles, hint in HINTS:
        if any(n in low for n in needles):
            return hint
    return msg


def run_job(job: Job) -> None:
    import yt_dlp

    if job.cancel.is_set():
        job.status = "cancelled"
        job.detail = "Cancelled"
        job.send()
        return

    job.status = "starting"
    job.detail = "Resolving link…"
    job.send()

    def hook(d: dict) -> None:
        if job.cancel.is_set():
            raise Cancelled()
        if d["status"] == "downloading":
            total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
            done = d.get("downloaded_bytes") or 0
            job.size = total
            job.percent = (done / total * 100) if total else 0.0
            job.speed = d.get("speed") or 0
            job.eta = d.get("eta") or 0
            job.status = "downloading"
            job.detail = ""
            job.send(force=False)
        elif d["status"] == "finished":
            job.percent = 100.0
            job.status = "processing"
            job.detail = "Merging / converting…"
            job.send()

    def pp_hook(d: dict) -> None:
        if job.cancel.is_set():
            raise Cancelled()

    fmt = FORMAT_BY_ID.get(job.format, FORMAT_BY_ID["best"])

    if not HAS_FFMPEG and fmt["single"] is None:
        job.status = "failed"
        job.detail = (
            f"{fmt['label']} needs ffmpeg, which isn't installed. "
            "Install it (winget install Gyan.FFmpeg), restart the app, then retry."
        )
        job.send()
        return

    opts = base_opts(job.browser)
    opts.update(
        {
            "outtmpl": os.path.join(job.dest, "%(title).150B [%(id)s].%(ext)s"),
            "format": fmt["merged"] if HAS_FFMPEG else fmt["single"],
            "noplaylist": not job.playlist,
            "progress_hooks": [hook],
            "postprocessor_hooks": [pp_hook],
        }
    )
    if HAS_FFMPEG:
        opts["ffmpeg_location"] = FFMPEG
        opts.update(fmt["opts"])

    os.makedirs(job.dest, exist_ok=True)

    for attempt in range(1, ATTEMPTS + 1):
        try:
            with yt_dlp.YoutubeDL(opts) as ydl:
                info = ydl.extract_info(job.url, download=True)
                if info.get("_type") == "playlist":
                    entries = [e for e in (info.get("entries") or []) if e]
                    job.title = f"{info.get('title', 'Playlist')} — {len(entries)} items"
                    job.filepath = job.dest
                else:
                    job.title = info.get("title") or job.title
                    job.thumbnail = pick_thumb(info) or job.thumbnail
                    job.filepath = resolve_path(info, job.dest)
            job.status = "done"
            job.percent = 100.0
            job.detail = ""
            job.send()
            return
        except Cancelled:
            job.status = "cancelled"
            job.detail = "Stopped"
            job.send()
            return
        except Exception as exc:  # noqa: BLE001
            if job.cancel.is_set():
                job.status = "cancelled"
                job.detail = "Stopped"
                job.send()
                return

            raw = clean(exc)
            if attempt < ATTEMPTS and is_transient(raw):
                job.status = "starting"
                job.percent = 0.0
                job.detail = f"Site hiccuped — retrying ({attempt + 1}/{ATTEMPTS})…"
                job.send()
                # Back off a little; hammering a rate-limited site never helps.
                if job.cancel.wait(RETRY_BACKOFF * attempt):
                    job.status = "cancelled"
                    job.detail = "Stopped"
                    job.send()
                    return
                continue

            job.status = "failed"
            job.detail = explain(raw)
            job.send()
            return


def resolve_path(info: dict, dest: str) -> str:
    downloads = info.get("requested_downloads") or []
    if downloads:
        return downloads[0].get("filepath") or downloads[0].get("_filename") or dest
    return info.get("filepath") or info.get("_filename") or dest


def handle(msg: dict) -> None:
    cmd = msg.get("cmd")
    if cmd == "probe":
        PROBE_POOL.submit(probe, msg)
    elif cmd == "add":
        job = Job(msg)
        JOBS[job.id] = job
        job.send()
        POOL.submit(run_job, job)
    elif cmd == "cancel":
        job = JOBS.get(msg.get("id", ""))
        if job and job.status in ("queued", "starting", "downloading", "processing"):
            job.cancel.set()
            if job.status == "queued":
                job.status = "cancelled"
                job.detail = "Cancelled"
                job.send()
    elif cmd == "quit":
        for job in JOBS.values():
            job.cancel.set()
        POOL.shutdown(wait=False, cancel_futures=True)
        sys.exit(0)


def main() -> None:
    try:
        import yt_dlp  # noqa: F401
    except ImportError:
        emit(
            {
                "type": "fatal",
                "message": (
                    "Could not load yt-dlp. The bundled copy is missing or damaged — "
                    "reinstall the app, or run: py -3 -m pip install -U yt-dlp"
                ),
            }
        )
        return

    import yt_dlp

    emit(
        {
            "type": "ready",
            "ytdlp": yt_dlp.version.__version__,
            "ytdlp_bundled": os.path.abspath(yt_dlp.__file__).startswith(os.path.abspath(VENDOR)),
            "python": sys.version.split()[0],
            "ffmpeg": FFMPEG,
            "formats": format_menu(),
        }
    )

    for line in sys.stdin:
        # Some shells prepend a BOM to the first line of a piped stream.
        line = line.strip().lstrip("﻿")
        if not line:
            continue
        try:
            handle(json.loads(line))
        except SystemExit:
            raise
        except Exception as exc:  # noqa: BLE001
            emit({"type": "fatal", "message": clean(exc)})


if __name__ == "__main__":
    main()
