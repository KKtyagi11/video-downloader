'use strict';

const { contextBridge, ipcRenderer } = require('electron');

// The renderer gets exactly these calls — no Node, no require, no fs.
contextBridge.exposeInMainWorld('api', {
  getSettings: () => ipcRenderer.invoke('settings:get'),
  saveSettings: (s) => ipcRenderer.invoke('settings:set', s),
  pickFolder: (current) => ipcRenderer.invoke('dialog:pick-folder', current),
  readClipboard: () => ipcRenderer.invoke('clipboard:read'),
  reveal: (target) => ipcRenderer.invoke('shell:reveal', target),
  openExternal: (url) => ipcRenderer.invoke('shell:open-external', url),

  probe: (spec) => ipcRenderer.send('engine:probe', spec),
  add: (spec) => ipcRenderer.send('engine:add', spec),
  cancel: (id) => ipcRenderer.send('engine:cancel', id),

  onEngineReady: (fn) => ipcRenderer.on('engine-ready', (_e, m) => fn(m)),
  onEngineFatal: (fn) => ipcRenderer.on('engine-fatal', (_e, m) => fn(m)),
  onJobUpdate: (fn) => ipcRenderer.on('job-update', (_e, m) => fn(m)),
  onProbeResult: (fn) => ipcRenderer.on('probe-result', (_e, m) => fn(m)),
});
