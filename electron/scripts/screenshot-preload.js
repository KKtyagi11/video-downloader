/* No-op stand-in for preload.js, used only by scripts/screenshot.js so that
   renderer/app.js can load without a live main process behind it. */

'use strict';

const { contextBridge } = require('electron');

const noop = () => {};
const async = () => Promise.resolve({});

contextBridge.exposeInMainWorld('api', {
  getSettings: () => Promise.resolve({ dest: '', format: 'best', browser: 'none', playlist: false }),
  saveSettings: async,
  pickFolder: async,
  readClipboard: () => Promise.resolve(''),
  reveal: noop,
  openExternal: noop,
  probe: noop,
  add: noop,
  cancel: noop,
  onEngineReady: noop,
  onEngineFatal: noop,
  onJobUpdate: noop,
  onProbeResult: noop,
});
