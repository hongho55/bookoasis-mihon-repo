import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../app/src/main/assets/bookoasis_dashboard.js', import.meta.url), 'utf8');
const messages = [];
const state = { currentLibraryType: 'general' };
const cards = [
  { dataset: { bookId: '42', fileFormat: 'epub', libraryId: '7' }, children: [], append(button) { this.children.push(button); } },
];
let capturedClick;
let observer;
let imported;
let webCalls = 0;
const window = { innerHeight: 661, addEventListener() {}, BookOasisNative: { postMessage: (raw) => messages.push(JSON.parse(raw)) }, openReader: () => webCalls++ };
const document = {
  documentElement: { style: {} },
  body: { style: {}, getBoundingClientRect: () => ({ height: 0 }) },
  querySelectorAll: (selector) => selector.startsWith('script')
    ? [{ src: 'https://books.example.test/static/m/contracthash/js/tab_media_library.js' }] : cards,
  addEventListener: (_, callback) => { capturedClick = callback; },
  createElement: () => ({ style: {}, listeners: {}, setAttribute() {}, addEventListener(event, fn) { this.listeners[event] = fn; } }),
};
const context = vm.createContext({ window, document, URL, console,
  MutationObserver: class { constructor(callback) { observer = callback; } observe() {} },
  requestAnimationFrame: (callback) => callback(),
});
const module = new vm.SyntheticModule(['state'], function () { this.setExport('state', state); }, { context });
await module.link(() => {});
await module.evaluate();
vm.runInContext(`(${source})('https://books.example.test')`, context, {
  importModuleDynamically: async (specifier) => { imported = specifier; return module; },
});
await new Promise(setImmediate);
await new Promise(setImmediate);
assert.equal(document.body.style.height, '661px', 'repair a collapsed Android WebView viewport');
assert.equal(imported, 'https://books.example.test/static/m/contracthash/js/state.js', 'must share the server hashed module instance');
window.openReader(42, 'epub');
assert.deepEqual(messages.pop(), { action: 'open', bookId: 42, dbType: 'general', libraryId: '7' });
assert.equal(webCalls, 0);
assert.equal(cards[0].children.length, 1);
cards[0].children[0].listeners.click({ preventDefault() {}, stopImmediatePropagation() {} });
assert.deepEqual(messages.pop(), { action: 'download', bookId: 42, dbType: 'general', libraryId: '7' });
observer();
assert.equal(cards[0].children.length, 1, 'DOM updates must not duplicate download buttons');
state.currentLibraryType = 'adult';
window.openReader(43, 'pdf');
assert.deepEqual(messages.pop(), { action: 'open', bookId: 43, dbType: 'adult', libraryId: 'all' });
state.currentLibraryType = 'video';
window.openReader(43, 'mp4');
assert.equal(messages.length, 0);
assert.equal(webCalls, 1, 'non-book libraries stay in the normal web player');
window.openReader = () => webCalls += 10;
state.currentLibraryType = 'general';
window.openReader(42, 'txt');
assert.equal(messages.pop().action, 'open', 'lazy reader assignment must preserve the native bridge');
window.openReader(42, 'mp3');
assert.equal(webCalls, 11);
window.openBookViewer(0.5);
assert.equal(messages.length, 0, 'reject invalid ids');
assert.equal(typeof capturedClick, 'function');
console.log('PASS: hashed module identity, native open/download, single button, live library context, lazy wrappers, media fallback, invalid IDs');
