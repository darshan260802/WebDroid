// WebDroid document-start script. Runs in every frame before page scripts.
// Assembled at runtime by InjectedScript.kt: the @@...@@ markers are replaced
// with the per-install bridge names, uBO's scriptlet library and uBO's
// procedural cosmetic filterer. SPDX-License-Identifier: GPL-3.0-or-later
// Deliberately not strict: uBO scriptlets are written for sloppy mode.
(function webdroidDocumentStart() {
  if (window.__webdroidStarted__) { return; }
  try { Object.defineProperty(window, '__webdroidStarted__', { value: true }); } catch (e) { }

  const COSM = window['@@COSM@@'];
  const MSG = window['@@MSG@@'];
  const isTop = window === window.top;

  const post = (o) => {
    try { if (MSG) { MSG.postMessage(JSON.stringify(o)); } } catch (e) { }
  };

  /* ------------------------------------------------------------------ */
  /* Ad blocking: uBO scriptlets + cosmetic filters                       */
  /* ------------------------------------------------------------------ */

  let cosmetic = null;
  try {
    if (COSM && typeof COSM.get === 'function' && /^https?:/.test(location.href)) {
      const raw = COSM.get(location.href);
      if (raw) { cosmetic = JSON.parse(raw); }
    }
  } catch (e) { }

  if (cosmetic && cosmetic.scriptlets && cosmetic.scriptlets.length !== 0) {
    try { __webdroidScriptlets(cosmetic.scriptlets); } catch (e) { }
  }

  /*@@SCRIPTLETS@@*/

  if (cosmetic) {
    const HIDE = 'display:none!important;';
    const exceptions = new Set(cosmetic.exceptions || []);
    let sheet = null;
    try { sheet = new CSSStyleSheet(); } catch (e) { }
    const fallbackRules = [];

    const insertRule = (rule) => {
      if (sheet !== null) {
        try { sheet.insertRule(rule, sheet.cssRules.length); return true; } catch (e) { return false; }
      }
      fallbackRules.push(rule);
      return true;
    };
    // Grouped for speed; a group with one invalid selector is retried one by one.
    const hideSelectors = (selectors, decl) => {
      decl = decl || HIDE;
      for (let i = 0; i < selectors.length; i += 100) {
        const chunk = selectors.slice(i, i + 100);
        if (insertRule(chunk.join(',\n') + '\n{' + decl + '}')) { continue; }
        for (const s of chunk) { insertRule(s + '{' + decl + '}'); }
      }
    };
    const adopt = () => {
      if (sheet !== null) {
        try {
          const sheets = document.adoptedStyleSheets;
          if (!sheets.includes(sheet)) { document.adoptedStyleSheets = [...sheets, sheet]; }
          return;
        } catch (e) { }
      }
      if (fallbackRules.length !== 0 && document.documentElement) {
        const style = document.createElement('style');
        style.textContent = fallbackRules.join('\n');
        (document.head || document.documentElement).appendChild(style);
        fallbackRules.length = 0;
      }
    };

    hideSelectors(cosmetic.hide || []);
    if (cosmetic.generic) {
      hideSelectors((cosmetic.highGeneric || []).filter(s => !exceptions.has(s)));
    }
    adopt();
    document.addEventListener('DOMContentLoaded', adopt, { once: true });
    window.addEventListener('load', adopt, { once: true });

    // Procedural filters (:has-text, :upward, :remove...) via uBO's filterer.
    if (cosmetic.procedural && cosmetic.procedural.length !== 0) {
      const domWatcherListeners = [];
      let commitTimer = 0;
      const domFilterer = {
        proceduralFilterer: null,
        addCSS(css) { insertRule(css); adopt(); },
        commit() {
          if (commitTimer !== 0) { return; }
          commitTimer = setTimeout(() => {
            commitTimer = 0;
            const pf = domFilterer.proceduralFilterer;
            if (pf) { try { pf.commitNow(); } catch (e) { } }
          }, 64);
        },
        hasListeners() { return false; },
        triggerListeners() { },
      };
      const vAPI = {
        hideStyle: HIDE,
        randomToken() { return 'wd' + Math.random().toString(36).slice(2, 10); },
        domFilterer,
        domWatcher: { addListener(l) { domWatcherListeners.push(l); } },
      };

      /*@@PROCEDURAL@@*/

      try {
        const pf = new vAPI.DOMProceduralFilterer(domFilterer);
        domFilterer.proceduralFilterer = pf;
        pf.addProceduralSelectors(cosmetic.procedural.map(s => JSON.parse(s)));
        const start = () => {
          const observer = new MutationObserver(mutations => {
            const added = [];
            let removed = false;
            for (const m of mutations) {
              for (const n of m.addedNodes) { if (n.nodeType === 1) { added.push(n); } }
              if (m.removedNodes.length !== 0) { removed = true; }
            }
            for (const l of domWatcherListeners) { try { l.onDOMChanged(added, removed); } catch (e) { } }
          });
          observer.observe(document.documentElement, { childList: true, subtree: true });
          domFilterer.commit();
        };
        if (document.readyState === 'loading') {
          document.addEventListener('DOMContentLoaded', start, { once: true });
        } else {
          start();
        }
      } catch (e) { }
    }

    // Low-generic filters: only selectors whose class/id exists in the page.
    if (cosmetic.generic && COSM && typeof COSM.lowGeneric === 'function') {
      const seen = new Set();
      let pending = [];
      let timer = 0;
      const flush = () => {
        timer = 0;
        if (pending.length === 0) { return; }
        const keys = pending;
        pending = [];
        try {
          const selectors = JSON.parse(COSM.lowGeneric(keys.join('\n')) || '[]').filter(s => !exceptions.has(s));
          if (selectors.length !== 0) { hideSelectors(selectors); adopt(); }
        } catch (e) { }
      };
      const queue = (key) => {
        if (seen.has(key)) { return; }
        seen.add(key);
        pending.push(key);
        if (timer === 0) { timer = setTimeout(flush, 50); }
      };
      const survey = (el) => {
        if (el.id) { queue('#' + el.id); }
        const cl = el.classList;
        if (cl) { for (let i = 0; i < cl.length; i++) { queue('.' + cl[i]); } }
      };
      const surveyTree = (root) => {
        if (root.nodeType !== 1) { return; }
        survey(root);
        const all = root.querySelectorAll('[id],[class]');
        for (let i = 0; i < all.length && i < 5000; i++) { survey(all[i]); }
      };
      const startSurvey = () => {
        surveyTree(document.documentElement);
        flush();
        new MutationObserver(mutations => {
          for (const m of mutations) {
            if (m.type === 'attributes') { survey(m.target); continue; }
            for (const n of m.addedNodes) { surveyTree(n); }
          }
        }).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'id'] });
      };
      if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', startSurvey, { once: true });
      } else {
        startSurvey();
      }
    }
  }

  /* ------------------------------------------------------------------ */
  /* Media: report videos to the native player                           */
  /* ------------------------------------------------------------------ */

  let lastGesture = 0;
  const onGesture = (ev) => { if (ev.isTrusted) { lastGesture = Date.now(); } };
  for (const type of ['pointerdown', 'touchstart', 'touchend', 'click', 'keydown']) {
    window.addEventListener(type, onGesture, { capture: true, passive: true });
  }

  let videoSeq = 0;
  const videos = new Map();
  let lastVideoId = 0;

  const sourceOf = (v) => {
    let src = v.currentSrc || v.src || '';
    if (!src) {
      const s = v.querySelector('source[src]');
      if (s) { src = s.src; }
    }
    return src;
  };

  const describe = (v, event) => {
    if (!v.__webdroidId) {
      try { Object.defineProperty(v, '__webdroidId', { value: ++videoSeq }); } catch (e) { return null; }
    }
    const id = v.__webdroidId;
    videos.set(id, new WeakRef(v));
    lastVideoId = id;
    const r = v.getBoundingClientRect();
    return {
      t: 'video',
      event,
      id,
      src: sourceOf(v),
      poster: v.poster || '',
      title: (isTop ? document.title : '') || '',
      pageUrl: location.href,
      gesture: Date.now() - lastGesture < 3000,
      muted: v.muted,
      loop: v.loop,
      w: v.videoWidth,
      h: v.videoHeight,
      dur: isFinite(v.duration) ? v.duration : 0,
      time: v.currentTime || 0,
      area: Math.round(Math.max(0, r.width) * Math.max(0, r.height)),
      viewport: Math.round(innerWidth * innerHeight),
      top: isTop,
    };
  };

  document.addEventListener('play', (ev) => {
    const v = ev.target;
    if (!(v instanceof HTMLVideoElement)) { return; }
    const d = describe(v, 'play');
    if (d) { post(d); }
  }, true);

  document.addEventListener('pause', (ev) => {
    const v = ev.target;
    if (v instanceof HTMLVideoElement && v.__webdroidId) { post({ t: 'videoPause', id: v.__webdroidId }); }
  }, true);

  const videoById = (id) => {
    const ref = videos.get(id || lastVideoId);
    return ref ? ref.deref() : null;
  };

  const activeVideo = () => {
    const fs = document.fullscreenElement || document.webkitFullscreenElement;
    if (fs) {
      if (fs instanceof HTMLVideoElement) { return fs; }
      const inner = fs.querySelector('video');
      if (inner) { return inner; }
    }
    return videoById(0);
  };

  /* ------------------------------------------------------------------ */
  /* Native -> page commands                                             */
  /* ------------------------------------------------------------------ */

  const blobToBase64 = (blob) => new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result).replace(/^data:[^,]*,/, ''));
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });

  const handle = async (msg) => {
    switch (msg.cmd) {
      case 'pause': {
        const v = videoById(msg.id);
        if (v) { try { v.pause(); } catch (e) { } }
        break;
      }
      case 'resume': {
        const v = videoById(msg.id);
        if (v) {
          try { if (msg.time) { v.currentTime = msg.time; } } catch (e) { }
        }
        break;
      }
      case 'seekBy': {
        const v = activeVideo();
        if (v && isFinite(v.duration)) {
          v.currentTime = Math.max(0, Math.min(v.duration, v.currentTime + msg.delta));
          post({ t: 'seeked', time: v.currentTime, dur: v.duration });
        }
        break;
      }
      case 'togglePlay': {
        const v = activeVideo();
        if (v) { if (v.paused) { v.play().catch(() => { }); } else { v.pause(); } }
        break;
      }
      case 'probe': {
        const v = activeVideo();
        if (v) { post({ t: 'seeked', time: v.currentTime, dur: isFinite(v.duration) ? v.duration : 0 }); }
        break;
      }
      case 'blob': {
        try {
          const response = await fetch(msg.url);
          const blob = await response.blob();
          const b64 = await blobToBase64(blob);
          const CHUNK = 512 * 1024;
          for (let i = 0; i < b64.length; i += CHUNK) {
            post({ t: 'blob', id: msg.id, chunk: b64.slice(i, i + CHUNK), done: i + CHUNK >= b64.length, mime: blob.type });
          }
          if (b64.length === 0) { post({ t: 'blob', id: msg.id, chunk: '', done: true, mime: blob.type }); }
        } catch (e) {
          post({ t: 'blob', id: msg.id, error: String(e) });
        }
        break;
      }
    }
  };

  if (MSG) {
    MSG.addEventListener('message', (ev) => {
      try { handle(JSON.parse(ev.data)); } catch (e) { }
    });
  }

  /* ------------------------------------------------------------------ */
  /* Top frame: theme colour for the native system bars                  */
  /* ------------------------------------------------------------------ */

  if (isTop) {
    let lastColor = '';
    const opaque = (c) => c && c !== 'transparent' && !/rgba\(.*,\s*0\)$/.test(c);
    const pick = () => {
      const metas = document.querySelectorAll('meta[name="theme-color"]');
      for (const m of metas) {
        const media = m.getAttribute('media');
        if (!media || matchMedia(media).matches) {
          if (m.content) { return { color: m.content, meta: true }; }
        }
      }
      let el = document.elementFromPoint(innerWidth / 2, 2);
      while (el) {
        const bg = getComputedStyle(el).backgroundColor;
        if (opaque(bg)) { return { color: bg, meta: false }; }
        el = el.parentElement;
      }
      const body = document.body ? getComputedStyle(document.body).backgroundColor : '';
      if (opaque(body)) { return { color: body, meta: false }; }
      return { color: getComputedStyle(document.documentElement).backgroundColor, meta: false };
    };
    let timer = 0;
    const report = () => {
      timer = 0;
      try {
        const c = pick();
        const key = c.color + c.meta;
        if (key !== lastColor) {
          lastColor = key;
          post({ t: 'theme', color: c.color, meta: c.meta });
        }
      } catch (e) { }
    };
    const schedule = () => { if (timer === 0) { timer = setTimeout(report, 120); } };
    document.addEventListener('DOMContentLoaded', () => {
      report();
      try {
        new MutationObserver(schedule).observe(document.head || document.documentElement, {
          childList: true, subtree: true, attributes: true, attributeFilter: ['content'],
        });
      } catch (e) { }
    }, { once: true });
    window.addEventListener('load', schedule, { once: true });
    window.addEventListener('scroll', () => { if (window.scrollY < 4) { schedule(); } }, { passive: true });
    try { matchMedia('(prefers-color-scheme: dark)').addEventListener('change', schedule); } catch (e) { }
  }
})();
