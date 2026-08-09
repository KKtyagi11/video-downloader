'use strict';

const $ = (sel) => document.querySelector(sel);

const els = {
  urls: $('#urls'),
  paste: $('#paste'),
  format: $('#format'),
  browser: $('#browser'),
  dest: $('#dest'),
  browse: $('#browse'),
  openFolder: $('#open-folder'),
  playlist: $('#playlist'),
  download: $('#download'),
  queue: $('#queue'),
  empty: $('#empty'),
  status: $('#status'),
  badge: $('#engine-badge'),
  clearFinished: $('#clear-finished'),
  tpl: $('#card-tpl'),
  privacyInfo: $('#privacy-info'),
  privacySheet: $('#privacy-sheet'),
  privacyClose: $('#privacy-close'),
};

const cards = new Map(); // job id -> { el, job, spec }
let engineReady = false;
let uid = 0;
const newId = () => `j${Date.now().toString(36)}${(uid++).toString(36)}`;

/* ------------------------------------------------------------- formatting */

const fmtSize = (b) => {
  if (!b) return '';
  const u = ['B', 'KB', 'MB', 'GB'];
  let i = 0;
  while (b >= 1024 && i < u.length - 1) {
    b /= 1024;
    i++;
  }
  return `${b.toFixed(i ? 1 : 0)} ${u[i]}`;
};

const fmtSpeed = (s) => (s ? `${(s / 1048576).toFixed(1)} MB/s` : '');

const fmtEta = (s) => {
  if (!s) return '';
  const m = Math.floor(s / 60);
  return `${m}:${String(Math.floor(s % 60)).padStart(2, '0')} left`;
};

const STATUS_TEXT = {
  queued: 'Queued',
  starting: 'Resolving',
  downloading: 'Downloading',
  processing: 'Processing',
  done: 'Done',
  failed: 'Failed',
  cancelled: 'Cancelled',
};

const ACTIVE = new Set(['queued', 'starting', 'downloading', 'processing']);

/* ------------------------------------------------------------------ cards */

function cardFor(id) {
  let entry = cards.get(id);
  if (entry) return entry;

  const el = els.tpl.content.firstElementChild.cloneNode(true);
  const img = el.querySelector('img');
  img.addEventListener('load', () => img.classList.add('loaded'));
  img.addEventListener('error', () => img.classList.remove('loaded'));

  el.querySelector('.act-cancel').addEventListener('click', () => window.api.cancel(id));
  el.querySelector('.act-reveal').addEventListener('click', () => {
    const e = cards.get(id);
    window.api.reveal(e?.job?.filepath || els.dest.value);
  });
  el.querySelector('.act-retry').addEventListener('click', () => {
    const e = cards.get(id);
    if (!e) return;
    const spec = { ...e.spec, id: newId() };
    e.el.remove();
    cards.delete(id);
    enqueue(spec);
  });

  entry = { el, job: null, spec: null };
  cards.set(id, entry);
  els.queue.prepend(el);
  els.empty.style.display = 'none';
  return entry;
}

function render(job) {
  const entry = cardFor(job.id);
  entry.job = job;
  const el = entry.el;

  el.dataset.status = job.status;
  el.querySelector('.title').textContent = job.title || job.url;
  el.querySelector('.badge').textContent = STATUS_TEXT[job.status] || job.status;

  const img = el.querySelector('img');
  if (job.thumbnail && img.dataset.src !== job.thumbnail) {
    img.dataset.src = job.thumbnail;
    img.src = job.thumbnail;
  }
  el.querySelector('.fallback').textContent = img.classList.contains('loaded') ? '' : 'no preview';

  const bar = el.querySelector('.bar i');
  if (ACTIVE.has(job.status) && job.status !== 'queued' && job.status !== 'starting') {
    bar.style.width = `${job.percent}%`;
  } else if (job.status === 'done') {
    bar.style.width = '100%';
  } else if (job.status === 'failed' || job.status === 'cancelled') {
    bar.style.width = `${Math.max(job.percent, 4)}%`;
  }

  let meta;
  if (job.status === 'downloading') {
    meta = [`${job.percent.toFixed(0)}%`, fmtSize(job.size), fmtSpeed(job.speed), fmtEta(job.eta)]
      .filter(Boolean)
      .join('  ·  ');
  } else if (job.status === 'done') {
    meta = job.filepath || 'Saved';
  } else {
    meta = job.detail || '';
  }
  el.querySelector('.meta').textContent = meta;

  updateStatusbar();
}

function updateStatusbar() {
  let active = 0;
  let done = 0;
  let failed = 0;
  for (const { job } of cards.values()) {
    if (!job) continue;
    if (ACTIVE.has(job.status)) active++;
    else if (job.status === 'done') done++;
    else if (job.status === 'failed') failed++;
  }
  const bits = [];
  if (active) bits.push(`${active} in progress`);
  if (done) bits.push(`${done} done`);
  if (failed) bits.push(`${failed} failed`);

  if (bits.length) els.status.textContent = bits.join('  ·  ');
  else setIdleStatus();
}

// The ffmpeg warning has to survive job activity, so it *is* the idle state.
let idleStatus = { html: '', text: 'Ready', onClick: null };

function setIdleStatus() {
  if (idleStatus.html) {
    els.status.innerHTML = idleStatus.html;
    if (idleStatus.onClick) {
      const link = els.status.querySelector('a');
      if (link) link.addEventListener('click', idleStatus.onClick);
    }
  } else {
    els.status.textContent = idleStatus.text;
  }
}

/* ------------------------------------------------------------------ queue */

function enqueue(spec) {
  const entry = cardFor(spec.id);
  entry.spec = spec;
  render({
    id: spec.id,
    url: spec.url,
    title: spec.url,
    thumbnail: '',
    status: 'queued',
    percent: 0,
    speed: 0,
    eta: 0,
    size: 0,
    detail: '',
    filepath: '',
  });
  window.api.probe({ id: spec.id, url: spec.url, browser: spec.browser });
  window.api.add(spec);
}

function startDownloads() {
  if (!engineReady) {
    els.status.textContent = 'Engine is not ready yet.';
    return;
  }
  const links = els.urls.value
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l && !l.startsWith('#'));

  if (!links.length) {
    els.status.textContent = 'Paste at least one link first.';
    return;
  }
  if (!els.dest.value.trim()) {
    els.status.textContent = 'Choose a folder to save to.';
    return;
  }

  for (const url of links) {
    enqueue({
      id: newId(),
      url,
      dest: els.dest.value.trim(),
      format: els.format.value,
      browser: els.browser.value,
      playlist: els.playlist.checked,
    });
  }
  els.urls.value = '';
  persist();
}

/* --------------------------------------------------------------- settings */

function persist() {
  window.api.saveSettings({
    dest: els.dest.value,
    format: els.format.value,
    browser: els.browser.value,
    playlist: els.playlist.checked,
  });
}

/* ------------------------------------------------------------------ wiring */

els.download.addEventListener('click', startDownloads);

els.paste.addEventListener('click', async () => {
  const text = (await window.api.readClipboard())?.trim();
  if (!text) return;
  els.urls.value = els.urls.value.trim() ? `${els.urls.value.trim()}\n${text}` : text;
  els.urls.focus();
});

els.browse.addEventListener('click', async () => {
  const dir = await window.api.pickFolder(els.dest.value);
  if (dir) {
    els.dest.value = dir;
    persist();
  }
});

els.openFolder.addEventListener('click', () => window.api.reveal(els.dest.value));
els.format.addEventListener('change', persist);
els.browser.addEventListener('change', persist);
els.playlist.addEventListener('change', persist);
els.dest.addEventListener('change', persist);

const showPrivacy = (show) => {
  els.privacySheet.hidden = !show;
};

els.privacyInfo.addEventListener('click', (e) => {
  e.preventDefault();
  showPrivacy(true);
});
els.privacyClose.addEventListener('click', () => showPrivacy(false));
els.privacySheet.addEventListener('click', (e) => {
  if (e.target === els.privacySheet) showPrivacy(false);
});
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') showPrivacy(false);
});

// First time someone actually turns cookies on, explain what they just enabled.
els.browser.addEventListener('change', () => {
  if (els.browser.value !== 'none' && !localStorage.getItem('privacy-seen')) {
    localStorage.setItem('privacy-seen', '1');
    showPrivacy(true);
  }
});

els.clearFinished.addEventListener('click', () => {
  for (const [id, entry] of cards) {
    if (entry.job && !ACTIVE.has(entry.job.status)) {
      entry.el.remove();
      cards.delete(id);
    }
  }
  if (!cards.size) els.empty.style.display = '';
  updateStatusbar();
});

// Ctrl/Cmd+Enter starts the queue from the textarea.
els.urls.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
    e.preventDefault();
    startDownloads();
  }
});

// Drag a link or a text selection onto the window to queue it.
document.addEventListener('dragover', (e) => e.preventDefault());
document.addEventListener('drop', (e) => {
  e.preventDefault();
  const text = e.dataTransfer.getData('text/uri-list') || e.dataTransfer.getData('text/plain');
  if (!text) return;
  els.urls.value = els.urls.value.trim() ? `${els.urls.value.trim()}\n${text.trim()}` : text.trim();
});

/* ---------------------------------------------------------- engine events */

window.api.onEngineReady((msg) => {
  engineReady = true;
  els.badge.dataset.state = 'ready';
  els.badge.textContent =
    `yt-dlp ${msg.ytdlp}${msg.ytdlp_bundled ? ' (bundled)' : ''} · Python ${msg.python}`;
  els.download.disabled = false;

  els.format.innerHTML = '';
  for (const f of msg.formats) {
    const opt = document.createElement('option');
    opt.value = f.id;
    opt.textContent = f.hint ? `${f.label} — ${f.hint}` : f.label;
    opt.disabled = f.enabled === false;
    els.format.append(opt);
  }
  // A remembered choice may now be unavailable (e.g. MP3 with no ffmpeg).
  const wanted = msg.formats.find((f) => f.id === pendingFormat && f.enabled !== false);
  els.format.value = (wanted || msg.formats.find((f) => f.enabled !== false)).id;

  if (!msg.ffmpeg) {
    idleStatus = {
      html:
        'ffmpeg not found — quality is capped at whatever the site serves as a single file, ' +
        'and MP3 is unavailable. <a href="#">How to fix</a>',
      onClick: (e) => {
        e.preventDefault();
        window.api.openExternal('https://www.gyan.dev/ffmpeg/builds/');
      },
    };
  }
  setIdleStatus();
});

window.api.onEngineFatal((msg) => {
  engineReady = false;
  els.badge.dataset.state = 'error';
  els.badge.textContent = 'Engine unavailable';
  els.status.textContent = msg.message;
  els.download.disabled = true;
});

window.api.onJobUpdate((job) => render(job));

window.api.onProbeResult((res) => {
  const entry = cards.get(res.id);
  if (!entry || !entry.job || !res.ok) return;
  // Only fill in metadata; never override a status the download thread owns.
  if (entry.job.title === entry.job.url && res.title) entry.job.title = res.title;
  if (!entry.job.thumbnail && res.thumbnail) entry.job.thumbnail = res.thumbnail;
  render(entry.job);
});

/* ------------------------------------------------------------------- boot */

let pendingFormat = null;

(async function boot() {
  els.download.disabled = true;
  const s = await window.api.getSettings();
  els.dest.value = s.dest || '';
  els.browser.value = s.browser || 'none';
  els.playlist.checked = !!s.playlist;
  pendingFormat = s.format || 'best';
})();
