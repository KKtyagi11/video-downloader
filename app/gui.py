"""Tkinter front-end for the downloader."""

from __future__ import annotations

import json
import os
import queue
import shutil
import subprocess
import sys
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

from .downloader import BROWSERS, FORMATS, DownloadManager, Job

APP_NAME = "Video Downloader"
CONFIG_PATH = Path.home() / ".video_downloader.json"
DEFAULT_DEST = str(Path.home() / "Videos" / "Downloads")

COLUMNS = ("title", "status", "progress", "detail")


class App(ttk.Frame):
    def __init__(self, root: tk.Tk) -> None:
        super().__init__(root, padding=10)
        self.root = root
        self.manager = DownloadManager(max_workers=3)
        self.cfg = self._load_config()
        self.row_of: dict[str, str] = {}

        root.title(APP_NAME)
        root.geometry("980x600")
        root.minsize(760, 460)
        self.pack(fill="both", expand=True)

        self._build_input()
        self._build_options()
        self._build_queue()
        self._build_status()

        root.protocol("WM_DELETE_WINDOW", self._on_close)
        self.after(150, self._drain_events)

    # -- layout -------------------------------------------------------------

    def _build_input(self) -> None:
        box = ttk.LabelFrame(self, text="Links (one per line)", padding=8)
        box.pack(fill="x")

        self.urls = tk.Text(box, height=4, wrap="none", undo=True)
        self.urls.pack(side="left", fill="both", expand=True)
        bar = ttk.Scrollbar(box, orient="vertical", command=self.urls.yview)
        bar.pack(side="left", fill="y")
        self.urls.configure(yscrollcommand=bar.set)

        side = ttk.Frame(box)
        side.pack(side="left", fill="y", padx=(8, 0))
        ttk.Button(side, text="Paste", command=self._paste).pack(fill="x")
        ttk.Button(side, text="Clear", command=lambda: self.urls.delete("1.0", "end")).pack(
            fill="x", pady=(4, 0)
        )

    def _build_options(self) -> None:
        box = ttk.Frame(self, padding=(0, 8))
        box.pack(fill="x")

        ttk.Label(box, text="Quality:").grid(row=0, column=0, sticky="w")
        self.fmt = tk.StringVar(value=self.cfg.get("format", next(iter(FORMATS))))
        ttk.Combobox(
            box, textvariable=self.fmt, values=list(FORMATS), state="readonly", width=26
        ).grid(row=0, column=1, sticky="w", padx=(4, 16))

        ttk.Label(box, text="Sign-in cookies from:").grid(row=0, column=2, sticky="w")
        self.browser = tk.StringVar(value=self.cfg.get("browser", "None"))
        ttk.Combobox(
            box, textvariable=self.browser, values=BROWSERS, state="readonly", width=10
        ).grid(row=0, column=3, sticky="w", padx=(4, 16))

        self.playlist = tk.BooleanVar(value=self.cfg.get("playlist", False))
        ttk.Checkbutton(box, text="Download whole playlist/channel", variable=self.playlist).grid(
            row=0, column=4, sticky="w"
        )

        ttk.Label(box, text="Save to:").grid(row=1, column=0, sticky="w", pady=(8, 0))
        self.dest = tk.StringVar(value=self.cfg.get("dest", DEFAULT_DEST))
        ttk.Entry(box, textvariable=self.dest).grid(
            row=1, column=1, columnspan=3, sticky="we", padx=(4, 8), pady=(8, 0)
        )
        ttk.Button(box, text="Browse…", command=self._pick_dest).grid(
            row=1, column=4, sticky="w", pady=(8, 0)
        )
        box.columnconfigure(3, weight=1)

        actions = ttk.Frame(self)
        actions.pack(fill="x", pady=(4, 8))
        ttk.Button(actions, text="Download", command=self._enqueue).pack(side="left")
        ttk.Button(actions, text="Cancel selected", command=self._cancel_selected).pack(
            side="left", padx=6
        )
        ttk.Button(actions, text="Open folder", command=self._open_dest).pack(side="left")
        ttk.Button(actions, text="Clear finished", command=self._clear_finished).pack(side="left")

    def _build_queue(self) -> None:
        box = ttk.LabelFrame(self, text="Queue", padding=6)
        box.pack(fill="both", expand=True)

        self.tree = ttk.Treeview(box, columns=COLUMNS, show="headings", selectmode="extended")
        for col, text, width in (
            ("title", "Title", 440),
            ("status", "Status", 100),
            ("progress", "Progress", 90),
            ("detail", "Details", 260),
        ):
            self.tree.heading(col, text=text)
            self.tree.column(col, width=width, anchor="w")
        self.tree.pack(side="left", fill="both", expand=True)
        bar = ttk.Scrollbar(box, orient="vertical", command=self.tree.yview)
        bar.pack(side="left", fill="y")
        self.tree.configure(yscrollcommand=bar.set)

        self.tree.tag_configure("Failed", foreground="#c0392b")
        self.tree.tag_configure("Done", foreground="#1e8449")
        self.tree.tag_configure("Cancelled", foreground="#7f8c8d")

    def _build_status(self) -> None:
        self.status = tk.StringVar()
        ttk.Label(self, textvariable=self.status, anchor="w").pack(fill="x", pady=(6, 0))
        if shutil.which("ffmpeg"):
            self.status.set("Ready.")
        else:
            self.status.set(
                "Ready — ffmpeg not found on PATH. High-quality merges and MP3 conversion "
                "will not work until you install it."
            )

    # -- actions ------------------------------------------------------------

    def _paste(self) -> None:
        try:
            text = self.root.clipboard_get()
        except tk.TclError:
            return
        if self.urls.get("1.0", "end").strip():
            self.urls.insert("end", "\n")
        self.urls.insert("end", text.strip())

    def _pick_dest(self) -> None:
        chosen = filedialog.askdirectory(initialdir=self.dest.get() or DEFAULT_DEST)
        if chosen:
            self.dest.set(chosen)

    def _open_dest(self) -> None:
        path = self.dest.get()
        if not os.path.isdir(path):
            messagebox.showinfo(APP_NAME, "That folder does not exist yet.")
            return
        if sys.platform == "win32":
            os.startfile(path)  # noqa: S606
        elif sys.platform == "darwin":
            subprocess.Popen(["open", path])
        else:
            subprocess.Popen(["xdg-open", path])

    def _enqueue(self) -> None:
        try:
            import yt_dlp  # noqa: F401
        except ImportError:
            messagebox.showerror(
                APP_NAME,
                "yt-dlp is not installed.\n\nRun:  pip install -r requirements.txt",
            )
            return

        raw = self.urls.get("1.0", "end")
        links = [line.strip() for line in raw.splitlines() if line.strip()]
        links = [u for u in links if not u.startswith("#")]
        if not links:
            messagebox.showinfo(APP_NAME, "Paste at least one link first.")
            return

        dest = self.dest.get().strip() or DEFAULT_DEST
        try:
            os.makedirs(dest, exist_ok=True)
        except OSError as exc:
            messagebox.showerror(APP_NAME, f"Cannot use that folder:\n{exc}")
            return

        for url in links:
            job = Job(
                url=url,
                dest=dest,
                fmt_label=self.fmt.get(),
                browser=self.browser.get(),
                playlist=self.playlist.get(),
            )
            job.title = url
            self.manager.submit(job)

        self.urls.delete("1.0", "end")
        self._save_config()
        self.status.set(f"Queued {len(links)} link(s).")

    def _cancel_selected(self) -> None:
        for row in self.tree.selection():
            self.manager.cancel(row)

    def _clear_finished(self) -> None:
        for job_id, row in list(self.row_of.items()):
            job = self.manager.jobs.get(job_id)
            if job and job.status in ("Done", "Failed", "Cancelled"):
                self.tree.delete(row)
                del self.row_of[job_id]

    # -- event pump ---------------------------------------------------------

    def _drain_events(self) -> None:
        try:
            while True:
                job_id, _kind, job = self.manager.events.get_nowait()
                values = (
                    job.title or job.url,
                    job.status,
                    f"{job.percent:.0f}%" if job.percent else "",
                    job.detail,
                )
                tag = job.status if job.status in ("Done", "Failed", "Cancelled") else ""
                if job_id in self.row_of:
                    self.tree.item(self.row_of[job_id], values=values, tags=(tag,))
                else:
                    self.row_of[job_id] = self.tree.insert(
                        "", "end", iid=job_id, values=values, tags=(tag,)
                    )
        except queue.Empty:
            pass

        active = sum(
            1 for j in self.manager.jobs.values() if j.status in ("Queued", "Starting", "Downloading", "Processing")
        )
        if active:
            self.status.set(f"{active} download(s) in progress…")
        self.after(200, self._drain_events)

    # -- persistence --------------------------------------------------------

    def _load_config(self) -> dict:
        try:
            return json.loads(CONFIG_PATH.read_text("utf-8"))
        except (OSError, ValueError):
            return {}

    def _save_config(self) -> None:
        data = {
            "dest": self.dest.get(),
            "format": self.fmt.get(),
            "browser": self.browser.get(),
            "playlist": self.playlist.get(),
        }
        try:
            CONFIG_PATH.write_text(json.dumps(data, indent=2), "utf-8")
        except OSError:
            pass

    def _on_close(self) -> None:
        active = [
            j for j in self.manager.jobs.values()
            if j.status in ("Queued", "Starting", "Downloading", "Processing")
        ]
        if active and not messagebox.askokcancel(
            APP_NAME, f"{len(active)} download(s) still running. Quit anyway?"
        ):
            return
        self._save_config()
        self.manager.shutdown()
        self.root.destroy()


def main() -> None:
    root = tk.Tk()
    try:
        ttk.Style().theme_use("vista" if sys.platform == "win32" else "clam")
    except tk.TclError:
        pass
    App(root)
    root.mainloop()
