"""Download engine: wraps yt-dlp and runs jobs on a small thread pool.

The GUI never touches yt-dlp directly. It submits URLs and drains `events`,
a thread-safe queue of (job_id, kind, payload) tuples.
"""

from __future__ import annotations

import os
import queue
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field


class Cancelled(Exception):
    """Raised inside a progress hook to abort a running download."""


# label -> (format selector, extra yt-dlp options)
FORMATS: dict[str, tuple[str, dict]] = {
    "Best quality (video + audio)": ("bv*+ba/b", {"merge_output_format": "mp4"}),
    "1080p or lower": ("bv*[height<=1080]+ba/b[height<=1080]", {"merge_output_format": "mp4"}),
    "720p or lower": ("bv*[height<=720]+ba/b[height<=720]", {"merge_output_format": "mp4"}),
    "480p or lower": ("bv*[height<=480]+ba/b[height<=480]", {"merge_output_format": "mp4"}),
    "Smallest file": ("wv*+wa/w", {"merge_output_format": "mp4"}),
    "Audio only (MP3)": (
        "ba/b",
        {
            "postprocessors": [
                {
                    "key": "FFmpegExtractAudio",
                    "preferredcodec": "mp3",
                    "preferredquality": "192",
                }
            ]
        },
    ),
    "Audio only (original)": ("ba/b", {}),
}

BROWSERS = ["None", "chrome", "edge", "firefox", "brave", "opera", "vivaldi"]


@dataclass
class Job:
    url: str
    dest: str
    fmt_label: str
    browser: str = "None"
    playlist: bool = False
    job_id: str = field(default_factory=lambda: uuid.uuid4().hex[:8])
    title: str = ""
    status: str = "Queued"
    percent: float = 0.0
    detail: str = ""
    filepath: str = ""
    cancel: threading.Event = field(default_factory=threading.Event)


class DownloadManager:
    def __init__(self, max_workers: int = 3) -> None:
        self.events: queue.Queue = queue.Queue()
        self.jobs: dict[str, Job] = {}
        self._pool = ThreadPoolExecutor(max_workers=max_workers, thread_name_prefix="dl")

    # -- public API ---------------------------------------------------------

    def submit(self, job: Job) -> Job:
        self.jobs[job.job_id] = job
        self._emit(job, "state")
        self._pool.submit(self._run, job)
        return job

    def cancel(self, job_id: str) -> None:
        job = self.jobs.get(job_id)
        if job and job.status in ("Queued", "Downloading", "Processing"):
            job.cancel.set()
            if job.status == "Queued":
                self._set(job, "Cancelled", detail="Cancelled before start")

    def shutdown(self) -> None:
        for job in self.jobs.values():
            job.cancel.set()
        self._pool.shutdown(wait=False, cancel_futures=True)

    # -- internals ----------------------------------------------------------

    def _emit(self, job: Job, kind: str = "state") -> None:
        self.events.put((job.job_id, kind, job))

    def _set(self, job: Job, status: str, **fields) -> None:
        job.status = status
        for key, value in fields.items():
            setattr(job, key, value)
        self._emit(job)

    def _run(self, job: Job) -> None:
        if job.cancel.is_set():
            return
        import yt_dlp  # imported lazily so the GUI can start without it

        self._set(job, "Starting", detail="Resolving link…")

        def hook(d: dict) -> None:
            if job.cancel.is_set():
                raise Cancelled()
            if d["status"] == "downloading":
                total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
                done = d.get("downloaded_bytes") or 0
                job.percent = (done / total * 100) if total else 0.0
                speed = d.get("speed")
                eta = d.get("eta")
                bits = []
                if speed:
                    bits.append(f"{speed / 1024 / 1024:.1f} MB/s")
                if eta:
                    bits.append(f"ETA {int(eta) // 60}:{int(eta) % 60:02d}")
                self._set(job, "Downloading", detail="  ".join(bits))
            elif d["status"] == "finished":
                self._set(job, "Processing", percent=100.0, detail="Merging / converting…")

        def postproc_hook(d: dict) -> None:
            if job.cancel.is_set():
                raise Cancelled()

        opts = {
            "outtmpl": os.path.join(job.dest, "%(title).150B [%(id)s].%(ext)s"),
            "progress_hooks": [hook],
            "postprocessor_hooks": [postproc_hook],
            "noplaylist": not job.playlist,
            "ignoreerrors": False,
            "quiet": True,
            "no_warnings": True,
            "noprogress": True,
            "consoletitle": False,
            "retries": 5,
            "fragment_retries": 5,
            "concurrent_fragment_downloads": 4,
            "restrictfilenames": False,
            "windowsfilenames": True,
        }
        selector, extra = FORMATS[job.fmt_label]
        opts["format"] = selector
        opts.update(extra)
        if job.browser != "None":
            opts["cookiesfrombrowser"] = (job.browser,)

        try:
            with yt_dlp.YoutubeDL(opts) as ydl:
                info = ydl.extract_info(job.url, download=False)
                if info.get("_type") == "playlist":
                    entries = [e for e in (info.get("entries") or []) if e]
                    job.title = f"{info.get('title', 'Playlist')} ({len(entries)} items)"
                else:
                    job.title = info.get("title") or job.url
                self._emit(job)

                ydl.download([job.url])
                job.filepath = job.dest
            self._set(job, "Done", percent=100.0, detail="Saved")
        except Cancelled:
            self._set(job, "Cancelled", detail="Stopped by user")
        except Exception as exc:  # noqa: BLE001 - surfaced verbatim in the UI
            msg = str(exc).replace("\n", " ").strip()
            if job.cancel.is_set():
                self._set(job, "Cancelled", detail="Stopped by user")
            else:
                self._set(job, "Failed", detail=msg[:300] or exc.__class__.__name__)
