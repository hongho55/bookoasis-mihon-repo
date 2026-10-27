// This function runs only in the configured BookOasis top frame. Credentials never enter JS.
(function installBookOasisNativeDashboard(baseUrl) {
  if (window.__bookOasisNativeDashboard || !window.BookOasisNative) return;
  window.__bookOasisNativeDashboard = true;
  // Some Android WebViews initially resolve vh/dvh to zero inside a Compose host.
  // Only repair that concrete collapsed viewport; preserve the server layout otherwise.
  if (document.body.getBoundingClientRect().height === 0 && window.innerHeight > 0) {
    const sizeViewport = () => {
      document.documentElement.style.height = window.innerHeight + 'px';
      document.body.style.height = window.innerHeight + 'px';
    };
    sizeViewport();
    window.addEventListener('resize', sizeViewport);
  }
  const entry = Array.from(document.querySelectorAll('script[type="module"][src]'))
    .find((script) => /\/static\/(?:m\/[^/]+\/)?js\//.test(new URL(script.src).pathname));
  const stateUrl = entry ? new URL('./state.js', entry.src).href
    : baseUrl.replace(/\/$/, '') + '/static/js/state.js';
  import(stateUrl).then(({ state }) => {
    const supported = new Set(['zip', 'cbz', 'imgdir', 'epub', 'pdf', 'txt']);
    const bookLibrary = () => ['general', 'adult'].includes(state.currentLibraryType)
      ? state.currentLibraryType : null;
    const findCard = (bookId) => Array.from(document.querySelectorAll('.book-card[data-book-id]'))
      .find((card) => String(card.dataset.bookId) === String(bookId));
    function post(action, bookId, format, card, dbType = bookLibrary()) {
      const id = Number(bookId);
      if (!dbType || !Number.isSafeInteger(id) || id <= 0) return false;
      if (format && !supported.has(String(format).toLowerCase())) return false;
      card = card || findCard(id);
      window.BookOasisNative.postMessage(JSON.stringify({
        action, bookId: id, dbType, libraryId: card?.dataset.libraryId || 'all',
      }));
      return true;
    }
    // The server also assigns these functions lazily when a reader module is imported.
    function wrap(name, hasFormat) {
      let original = window[name];
      const wrapped = function (...args) {
        if (post('open', args[0], hasFormat ? args[1] : null)) return Promise.resolve();
        return typeof original === 'function' ? original.apply(this, args) : undefined;
      };
      Object.defineProperty(window, name, {
        configurable: true,
        get: () => wrapped,
        set: (fn) => { if (fn !== wrapped) original = fn; },
      });
    }
    wrap('openReader', true);
    wrap('openBookViewer', false);
    wrap('openMediaViewer', false);
    document.addEventListener('click', (event) => {
      const target = event.target.closest?.('[data-item-action="open-reader"]');
      if (target && post('open', target.dataset.bookId, target.dataset.fileFormat, target.closest('.book-card'))) {
        event.preventDefault();
        event.stopImmediatePropagation();
      }
    }, true);
    function addDownloadButtons() {
      const dbType = bookLibrary();
      if (!dbType) return;
      document.querySelectorAll('.book-card[data-book-id]').forEach((card) => {
        if (card.dataset.boNativeReady || !supported.has(card.dataset.fileFormat)) return;
        card.dataset.boNativeReady = '1';
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'bo-native-download';
        button.textContent = '↓ 오프라인 저장';
        button.setAttribute('aria-label', '이 기기에 오프라인 저장');
        button.style.cssText = 'display:block;width:100%;padding:7px 4px;margin-top:4px;cursor:pointer;' +
          'border:1px solid #64748b88;border-radius:6px;background:transparent;color:inherit;font-size:12px';
        button.addEventListener('click', (event) => {
          event.preventDefault();
          event.stopImmediatePropagation();
          post('download', card.dataset.bookId, card.dataset.fileFormat, card, dbType);
        });
        card.append(button);
      });
    }
    let pending = false;
    new MutationObserver(() => {
      if (pending) return;
      pending = true;
      requestAnimationFrame(() => { pending = false; addDownloadButtons(); });
    }).observe(document.body, { childList: true, subtree: true });
    addDownloadButtons();
  }).catch(() => { window.__bookOasisNativeDashboard = false; });
})
