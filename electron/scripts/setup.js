/* Fetches yt-dlp into engine/vendor so the app carries its own copy and the
   machine only needs a Python interpreter. Run once after cloning:

     npm run setup

   engine/vendor is gitignored — it's third-party code that goes stale fast, so
   it's fetched at setup time and refreshed with `npm run update-ytdlp`. */

'use strict';

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const vendor = path.join(__dirname, '..', 'engine', 'vendor');

function python() {
  const candidates = process.platform === 'win32' ? ['py', 'python'] : ['python3', 'python'];
  for (const exe of candidates) {
    const args = exe === 'py' ? ['-3', '--version'] : ['--version'];
    const r = spawnSync(exe, args, { stdio: 'ignore' });
    if (r.status === 0) return exe;
  }
  return null;
}

const exe = python();
if (!exe) {
  console.error('\nNo Python found. Install Python 3.9+ from https://python.org and re-run.');
  process.exit(1);
}

const prefix = exe === 'py' ? ['-3'] : [];
console.log(`Using ${exe} — installing yt-dlp into engine/vendor …`);

const r = spawnSync(
  exe,
  [...prefix, '-m', 'pip', 'install', '--target', vendor, '--upgrade', '--no-compile', 'yt-dlp'],
  { stdio: 'inherit' }
);

if (r.status !== 0) {
  console.error('\nFailed to install yt-dlp. Check your network connection and that pip works.');
  process.exit(1);
}

if (!fs.existsSync(path.join(vendor, 'yt_dlp'))) {
  console.error('\nyt-dlp did not land in engine/vendor. Aborting.');
  process.exit(1);
}

console.log('\nDone. Run `npm start`.');
