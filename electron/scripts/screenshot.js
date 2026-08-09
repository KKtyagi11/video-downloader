/* Renders the real UI with sample rows and saves docs/screenshot.png.

     npx electron scripts/screenshot.js

   This loads renderer/index.html exactly as the app does — the layout, colours
   and spacing are genuine. Only the queue contents are fixtures, since real
   downloads would put someone's personal links in the README. Re-run after
   changing the UI to refresh the image. */

'use strict';

const { app, BrowserWindow } = require('electron');
const fs = require('node:fs');
const path = require('node:path');

const OUT = path.join(__dirname, '..', '..', 'docs', 'screenshot.png');

const SAMPLE = [
  {
    title: 'Blender Open Movie — "Big Buck Bunny" (2008)',
    status: 'downloading',
    percent: 62,
    meta: '62%  ·  148.2 MB  ·  9.4 MB/s  ·  0:11 left',
    hue: [262, 320],
  },
  {
    title: 'NASA — Perseverance Rover Landing Highlights',
    status: 'processing',
    percent: 100,
    meta: 'Merging / converting…',
    hue: [200, 260],
  },
  {
    title: 'Sintel — Durian Open Movie Project',
    status: 'done',
    percent: 100,
    meta: 'C:\\Users\\You\\Videos\\Downloads\\Sintel [eRsGyueVLvQ].mp4',
    hue: [150, 200],
  },
  {
    title: 'https://www.example.com/watch?v=deleted',
    status: 'failed',
    percent: 8,
    meta: 'The post seems to be deleted, private, or the link is wrong.',
    hue: [0, 0],
  },
];

const POPULATE = `
(() => { try {
  const thumb = (h1, h2) => {
    const c = document.createElement('canvas');
    c.width = 264; c.height = 148;
    const g = c.getContext('2d');
    if (h1 === 0 && h2 === 0) return '';
    const grad = g.createLinearGradient(0, 0, 264, 148);
    grad.addColorStop(0, 'hsl(' + h1 + ', 55%, 42%)');
    grad.addColorStop(1, 'hsl(' + h2 + ', 60%, 30%)');
    g.fillStyle = grad; g.fillRect(0, 0, 264, 148);
    g.fillStyle = 'rgba(255,255,255,.82)';
    g.beginPath(); g.moveTo(112, 54); g.lineTo(160, 74); g.lineTo(112, 94); g.closePath(); g.fill();
    return c.toDataURL('image/png');
  };

  const STATUS = { downloading:'Downloading', processing:'Processing', done:'Done', failed:'Failed' };
  const tpl = document.querySelector('#card-tpl');
  const queue = document.querySelector('#queue');
  document.querySelector('#empty').style.display = 'none';

  for (const s of SAMPLE_JSON) {
    const el = tpl.content.firstElementChild.cloneNode(true);
    el.dataset.status = s.status;
    el.querySelector('.title').textContent = s.title;
    el.querySelector('.badge').textContent = STATUS[s.status];
    el.querySelector('.bar i').style.width = s.percent + '%';
    el.querySelector('.meta').textContent = s.meta;
    const src = thumb(s.hue[0], s.hue[1]);
    if (src) {
      const img = el.querySelector('img');
      img.src = src; img.classList.add('loaded');
    } else {
      el.querySelector('.fallback').textContent = 'no preview';
    }
    queue.append(el);
  }

  // Engine badge and quality menu, as they look once the sidecar reports in.
  const badge = document.querySelector('#engine-badge');
  badge.dataset.state = 'ready';
  badge.textContent = 'yt-dlp 2026.07.04 (bundled) · Python 3.12.8';

  const fmt = document.querySelector('#format');
  fmt.innerHTML = '';
  for (const [v, t] of [['best','Best quality — video + audio, merged'],
                        ['1080','1080p — or lower'],
                        ['720','720p — or lower'],
                        ['mp3','MP3 — audio only']]) {
    const o = document.createElement('option');
    o.value = v; o.textContent = t; fmt.append(o);
  }
  fmt.value = 'best';

  document.querySelector('#dest').value = 'C:\\\\Users\\\\You\\\\Videos\\\\Downloads';
  document.querySelector('#status').textContent = '2 in progress  ·  1 done  ·  1 failed';
  document.querySelector('#urls').value =
    'https://www.youtube.com/watch?v=aqz-KE-bpKQ\\nhttps://x.com/i/status/1234567890';

  return { ok: true, cards: document.querySelectorAll('.card').length };
  } catch (e) { return { ok: false, error: String(e && e.stack || e) }; }
})();
`;

app.whenReady().then(async () => {
  const win = new BrowserWindow({
    width: 1100,
    height: 700,
    backgroundColor: '#0f1016',
    // Must be visible: capturePage on a hidden window returns a stale frame,
    // because a non-compositing window never repaints after DOM changes.
    show: true,
    frame: false,
    webPreferences: {
      // A no-op stand-in for the real bridge, so renderer/app.js loads cleanly.
      preload: path.join(__dirname, 'screenshot-preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  const wait = (ms) => new Promise((r) => setTimeout(r, ms));

  win.webContents.on('console-message', (_e, _lvl, msg) => console.log('[page]', msg));

  await win.loadFile(path.join(__dirname, '..', 'renderer', 'index.html'));

  win.show();
  win.focus();
  await wait(800); // let the compositor produce a first real frame

  // app.js boots asynchronously and writes to the same fields, so let it settle
  // before injecting — otherwise it overwrites the sample values.
  await wait(400);

  const result = await win.webContents.executeJavaScript(
    `const SAMPLE_JSON = ${JSON.stringify(SAMPLE)};\n${POPULATE}`
  );
  console.log('populate result:', JSON.stringify(result));

  // Give the canvas thumbnails a frame to paint.
  await wait(800);

  const image = await win.webContents.capturePage();
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, image.toPNG());
  console.log(`wrote ${OUT} (${image.getSize().width}x${image.getSize().height})`);

  app.quit();
});
