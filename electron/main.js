'use strict';

const { app, BrowserWindow, ipcMain, dialog, shell, clipboard, session } = require('electron');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const readline = require('node:readline');

const CONFIG_PATH = path.join(app.getPath('userData'), 'settings.json');

let win = null;
let engine = null;

/* ---------------------------------------------------------------- settings */

function loadSettings() {
  try {
    return JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
  } catch {
    return {
      dest: path.join(app.getPath('videos'), 'Downloads'),
      format: 'best',
      browser: 'none',
      playlist: false,
    };
  }
}

function saveSettings(settings) {
  try {
    fs.mkdirSync(path.dirname(CONFIG_PATH), { recursive: true });
    fs.writeFileSync(CONFIG_PATH, JSON.stringify(settings, null, 2), 'utf8');
  } catch {
    /* non-fatal */
  }
}

/* ------------------------------------------------------------ python probe */

// Packed builds keep the app inside app.asar, which Python cannot read into.
// engine/ is unpacked next to it (see asarUnpack), so redirect the path there.
// __dirname is ".../resources/app.asar" with no trailing separator when main.js
// sits at the archive root, so match the segment itself rather than a prefix.
const APP_DIR = __dirname.replace(/app\.asar(?=$|[\\/])/, 'app.asar.unpacked');

// Prefer a venv next to the app, then the launcher, then whatever is on PATH.
function pythonCandidates() {
  const roots = [path.join(__dirname, '..')]; // dev checkout
  if (process.resourcesPath) roots.push(process.resourcesPath); // installed build
  const list = [];
  for (const root of roots) {
    const venv =
      process.platform === 'win32'
        ? path.join(root, '.venv', 'Scripts', 'python.exe')
        : path.join(root, '.venv', 'bin', 'python');
    if (fs.existsSync(venv)) list.push(venv);
  }
  if (process.platform === 'win32') list.push('py', 'python');
  else list.push('python3', 'python');
  return list;
}

function startEngine() {
  const script = path.join(APP_DIR, 'engine', 'engine.py');
  const candidates = pythonCandidates();

  const tryNext = (i) => {
    if (i >= candidates.length) {
      send('engine-fatal', {
        message:
          'No Python interpreter found. Install Python 3.10+ and run `npm run setup` in the app folder.',
      });
      return;
    }
    const exe = candidates[i];
    const args = exe === 'py' ? ['-3', '-u', script] : ['-u', script];
    const child = spawn(exe, args, {
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true,
      env: { ...process.env, PYTHONIOENCODING: 'utf-8', PYTHONUTF8: '1' },
    });

    let started = false;

    child.on('error', () => {
      if (!started) tryNext(i + 1);
    });

    readline.createInterface({ input: child.stdout }).on('line', (line) => {
      let msg;
      try {
        msg = JSON.parse(line);
      } catch {
        return;
      }
      started = true;
      if (msg.type === 'ready') send('engine-ready', msg);
      else if (msg.type === 'job') send('job-update', msg);
      else if (msg.type === 'probe') send('probe-result', msg);
      else if (msg.type === 'fatal') send('engine-fatal', msg);
    });

    let stderrTail = '';
    child.stderr.on('data', (buf) => {
      stderrTail = (stderrTail + buf.toString()).slice(-2000);
    });

    child.on('exit', (code) => {
      if (!started) {
        tryNext(i + 1);
      } else if (code !== 0 && !app.isQuitting) {
        send('engine-fatal', {
          message: `Download engine stopped unexpectedly (exit ${code}).\n${stderrTail.trim()}`,
        });
      }
    });

    engine = child;
  };

  tryNext(0);
}

function toEngine(cmd) {
  if (engine && engine.stdin.writable) engine.stdin.write(JSON.stringify(cmd) + '\n');
}

function send(channel, payload) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, payload);
}

/* ------------------------------------------------------------- privacy */

// Thumbnails come from the same CDNs that serve the sites themselves, and those
// respond with Set-Cookie headers. Nothing here needs a cookie jar, so strip
// them on arrival and start from an empty store every launch — that way "the
// app keeps no cookies" is enforced, not just claimed.
function hardenSession() {
  const ses = session.defaultSession;

  ses.clearStorageData({ storages: ['cookies'] });

  ses.webRequest.onHeadersReceived((details, callback) => {
    const headers = { ...details.responseHeaders };
    for (const key of Object.keys(headers)) {
      if (key.toLowerCase() === 'set-cookie') delete headers[key];
    }
    callback({ responseHeaders: headers });
  });

  // Thumbnails are the only remote traffic the window should ever make.
  ses.webRequest.onBeforeRequest((details, callback) => {
    const ok =
      details.url.startsWith('file://') ||
      details.url.startsWith('devtools://') ||
      details.resourceType === 'image';
    callback({ cancel: !ok });
  });
}

/* -------------------------------------------------------------------- window */

function createWindow() {
  win = new BrowserWindow({
    width: 1080,
    height: 720,
    minWidth: 820,
    minHeight: 540,
    backgroundColor: '#12131a',
    show: false,
    autoHideMenuBar: true,
    title: 'Video Downloader',
    icon: path.join(
      __dirname,
      'build',
      process.platform === 'win32' ? 'icon.ico' : 'icon.png'
    ),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });

  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.once('ready-to-show', () => win.show());

  // External links open in the real browser, never in-app.
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  win.webContents.on('will-navigate', (e) => e.preventDefault());
}

/* ----------------------------------------------------------------------- ipc */

ipcMain.handle('settings:get', () => loadSettings());
ipcMain.handle('settings:set', (_e, settings) => saveSettings(settings));

ipcMain.handle('dialog:pick-folder', async (_e, current) => {
  const res = await dialog.showOpenDialog(win, {
    title: 'Choose a download folder',
    defaultPath: current,
    properties: ['openDirectory', 'createDirectory'],
  });
  return res.canceled ? null : res.filePaths[0];
});

ipcMain.handle('clipboard:read', () => clipboard.readText());

ipcMain.handle('shell:reveal', (_e, target) => {
  if (!target) return;
  if (fs.existsSync(target)) {
    fs.statSync(target).isDirectory() ? shell.openPath(target) : shell.showItemInFolder(target);
  }
});

ipcMain.handle('shell:open-external', (_e, url) => {
  if (/^https?:/.test(url)) shell.openExternal(url);
});

ipcMain.on('engine:probe', (_e, spec) => toEngine({ cmd: 'probe', ...spec }));
ipcMain.on('engine:add', (_e, spec) => toEngine({ cmd: 'add', ...spec }));
ipcMain.on('engine:cancel', (_e, id) => toEngine({ cmd: 'cancel', id }));

/* --------------------------------------------------------------- lifecycle */

app.whenReady().then(() => {
  hardenSession();
  createWindow();
  startEngine();
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('before-quit', () => {
  app.isQuitting = true;
  toEngine({ cmd: 'quit' });
  if (engine) setTimeout(() => engine.kill(), 300);
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
