/*
 * WebDroid - minimal host environment for running uBlock Origin's engine
 * inside QuickJS (no DOM, no event loop, no TextEncoder, no URL).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import punycode from '@gorhill/ubo-core/lib/punycode.js';

const g = globalThis;

if (typeof g.self === 'undefined') { g.self = g; }

if (typeof g.console === 'undefined') {
    const logs = [];
    const push = level => (...args) => {
        if (logs.length > 200) { logs.shift(); }
        logs.push(level + ': ' + args.map(String).join(' '));
    };
    g.console = { log: push('log'), info: push('info'), warn: push('warn'), error: push('error'), debug: push('debug') };
    g.__wdLogs = logs;
}

// No event loop in the embedded engine: timers are queued and only run when
// the host explicitly drains them. uBO only uses them for optional deferred
// optimisation work which we run synchronously instead.
if (typeof g.setTimeout === 'undefined') {
    const timers = new Map();
    let nextId = 1;
    g.setTimeout = (fn, _ms, ...args) => { const id = nextId++; timers.set(id, () => fn(...args)); return id; };
    g.clearTimeout = id => { timers.delete(id); };
    g.setInterval = () => 0;
    g.clearInterval = () => {};
    g.__wdDrainTimers = () => {
        let guard = 1000;
        while (timers.size !== 0 && guard-- > 0) {
            const [id, fn] = timers.entries().next().value;
            timers.delete(id);
            try { fn(); } catch (e) { console.error(e); }
        }
    };
}
if (typeof g.queueMicrotask === 'undefined') {
    g.queueMicrotask = fn => { Promise.resolve().then(fn); };
}
if (typeof g.requestIdleCallback === 'undefined') {
    g.requestIdleCallback = fn => g.setTimeout(() => fn({ didTimeout: true, timeRemaining: () => 0 }), 1);
    g.cancelIdleCallback = id => g.clearTimeout(id);
}

if (typeof g.TextEncoder === 'undefined') {
    g.TextEncoder = class TextEncoder {
        get encoding() { return 'utf-8'; }
        encode(s = '') {
            s = String(s);
            const out = new Uint8Array(s.length * 3);
            let j = 0;
            for (let i = 0; i < s.length; i++) {
                let c = s.charCodeAt(i);
                if (c >= 0xD800 && c <= 0xDBFF && i + 1 < s.length) {
                    const d = s.charCodeAt(i + 1);
                    if (d >= 0xDC00 && d <= 0xDFFF) {
                        c = 0x10000 + ((c - 0xD800) << 10) + (d - 0xDC00);
                        i++;
                    }
                }
                if (c < 0x80) {
                    out[j++] = c;
                } else if (c < 0x800) {
                    out[j++] = 0xC0 | (c >> 6);
                    out[j++] = 0x80 | (c & 0x3F);
                } else if (c < 0x10000) {
                    out[j++] = 0xE0 | (c >> 12);
                    out[j++] = 0x80 | ((c >> 6) & 0x3F);
                    out[j++] = 0x80 | (c & 0x3F);
                } else {
                    out[j++] = 0xF0 | (c >> 18);
                    out[j++] = 0x80 | ((c >> 12) & 0x3F);
                    out[j++] = 0x80 | ((c >> 6) & 0x3F);
                    out[j++] = 0x80 | (c & 0x3F);
                }
            }
            return out.slice(0, j);
        }
        encodeInto(s, dest) {
            const bytes = this.encode(s);
            const n = Math.min(bytes.length, dest.length);
            dest.set(bytes.subarray(0, n));
            return { read: s.length, written: n };
        }
    };
}

if (typeof g.TextDecoder === 'undefined') {
    g.TextDecoder = class TextDecoder {
        get encoding() { return 'utf-8'; }
        decode(input) {
            if (input === undefined) { return ''; }
            const bytes = input instanceof Uint8Array
                ? input
                : new Uint8Array(input.buffer || input, input.byteOffset || 0, input.byteLength);
            const parts = [];
            let chunk = [];
            for (let i = 0; i < bytes.length;) {
                const b = bytes[i++];
                let c;
                if (b < 0x80) {
                    c = b;
                } else if (b < 0xE0) {
                    c = ((b & 0x1F) << 6) | (bytes[i++] & 0x3F);
                } else if (b < 0xF0) {
                    c = ((b & 0x0F) << 12) | ((bytes[i++] & 0x3F) << 6) | (bytes[i++] & 0x3F);
                } else {
                    c = ((b & 0x07) << 18) | ((bytes[i++] & 0x3F) << 12) | ((bytes[i++] & 0x3F) << 6) | (bytes[i++] & 0x3F);
                }
                if (c > 0xFFFF) {
                    c -= 0x10000;
                    chunk.push(0xD800 + (c >> 10), 0xDC00 + (c & 0x3FF));
                } else {
                    chunk.push(c);
                }
                if (chunk.length > 8192) {
                    parts.push(String.fromCharCode.apply(null, chunk));
                    chunk = [];
                }
            }
            parts.push(String.fromCharCode.apply(null, chunk));
            return parts.join('');
        }
    };
}

const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
if (typeof g.btoa === 'undefined') {
    g.btoa = s => {
        let out = '';
        for (let i = 0; i < s.length; i += 3) {
            const a = s.charCodeAt(i), b = s.charCodeAt(i + 1), c = s.charCodeAt(i + 2);
            const n = (a << 16) | ((b || 0) << 8) | (c || 0);
            out += B64[(n >> 18) & 63] + B64[(n >> 12) & 63] +
                (i + 1 < s.length ? B64[(n >> 6) & 63] : '=') +
                (i + 2 < s.length ? B64[n & 63] : '=');
        }
        return out;
    };
    g.atob = s => {
        s = String(s).replace(/[^A-Za-z0-9+/]/g, '');
        let out = '';
        for (let i = 0; i < s.length; i += 4) {
            const n = (B64.indexOf(s[i]) << 18) | (B64.indexOf(s[i + 1]) << 12) |
                ((B64.indexOf(s[i + 2]) & 63) << 6) | (B64.indexOf(s[i + 3]) & 63);
            out += String.fromCharCode((n >> 16) & 255);
            if (s[i + 2] !== undefined) { out += String.fromCharCode((n >> 8) & 255); }
            if (s[i + 3] !== undefined) { out += String.fromCharCode(n & 255); }
        }
        return out;
    };
}

if (typeof Object.hasOwn !== 'function') {
    Object.hasOwn = (o, p) => Object.prototype.hasOwnProperty.call(o, p);
}
for (const C of [Array, String]) {
    if (typeof C.prototype.at !== 'function') {
        Object.defineProperty(C.prototype, 'at', {
            configurable: true, writable: true,
            value: function (i) { i = Math.trunc(i) || 0; if (i < 0) { i += this.length; } return this[i]; },
        });
    }
}
if (typeof Array.prototype.findLast !== 'function') {
    Object.defineProperty(Array.prototype, 'findLast', {
        configurable: true, writable: true,
        value: function (fn, t) { for (let i = this.length - 1; i >= 0; i--) { if (fn.call(t, this[i], i, this)) { return this[i]; } } },
    });
}
if (typeof String.prototype.replaceAll !== 'function') {
    Object.defineProperty(String.prototype, 'replaceAll', {
        configurable: true, writable: true,
        value: function (a, b) {
            if (a instanceof RegExp) { return this.replace(a, b); }
            return this.split(a).join(typeof b === 'function' ? b(a) : b);
        },
    });
}

// Minimal WHATWG URL: enough for uBO's parser (hostname punycoding) and
// the occasional href/searchParams access during filter compilation.
if (typeof g.URL === 'undefined') {
    class URLSearchParams {
        constructor(init = '') {
            this.list = [];
            const s = String(init).replace(/^\?/, '');
            if (s === '') { return; }
            for (const part of s.split('&')) {
                if (part === '') { continue; }
                const i = part.indexOf('=');
                const k = i === -1 ? part : part.slice(0, i);
                const v = i === -1 ? '' : part.slice(i + 1);
                this.list.push([dec(k), dec(v)]);
            }
        }
        get(k) { const e = this.list.find(a => a[0] === k); return e ? e[1] : null; }
        getAll(k) { return this.list.filter(a => a[0] === k).map(a => a[1]); }
        has(k) { return this.list.some(a => a[0] === k); }
        set(k, v) { this.delete(k); this.list.push([k, String(v)]); }
        append(k, v) { this.list.push([k, String(v)]); }
        delete(k) { this.list = this.list.filter(a => a[0] !== k); }
        keys() { return this.list.map(a => a[0])[Symbol.iterator](); }
        entries() { return this.list.map(a => [a[0], a[1]])[Symbol.iterator](); }
        [Symbol.iterator]() { return this.entries(); }
        toString() { return this.list.map(a => enc(a[0]) + '=' + enc(a[1])).join('&'); }
    }
    const dec = s => { try { return decodeURIComponent(s.replace(/\+/g, ' ')); } catch { return s; } };
    const enc = s => encodeURIComponent(s);
    const reURL = /^([a-zA-Z][a-zA-Z0-9+.-]*:)(?:\/\/(?:([^@/?#]*)@)?([^/?#:]*|\[[^\]]*\])(?::(\d*))?)?([^?#]*)(\?[^#]*)?(#.*)?$/;
    class URL {
        constructor(url, base) {
            url = String(url).trim();
            let m = reURL.exec(url);
            if (m === null && base !== undefined) {
                const b = new URL(base);
                if (url.startsWith('//')) {
                    m = reURL.exec(b.protocol + url);
                } else if (url.startsWith('/')) {
                    m = reURL.exec(b.origin + url);
                } else {
                    m = reURL.exec(b.origin + b.pathname.replace(/[^/]*$/, '') + url);
                }
            }
            if (m === null) { throw new TypeError('Invalid URL: ' + url); }
            this.protocol = m[1].toLowerCase();
            this.username = m[2] || '';
            this.password = '';
            this._hostname = '';
            this.hostname = m[3] || '';
            this.port = m[4] || '';
            this.pathname = m[5] || (m[3] !== undefined ? '/' : '');
            this.search = m[6] && m[6] !== '?' ? m[6] : '';
            this.hash = m[7] && m[7] !== '#' ? m[7] : '';
        }
        get hostname() { return this._hostname; }
        set hostname(v) {
            v = String(v).toLowerCase();
            // eslint-disable-next-line no-control-regex
            this._hostname = /[^\x00-\x7F]/.test(v) ? punycode.toASCII(v) : v;
        }
        get host() { return this._hostname + (this.port ? ':' + this.port : ''); }
        get origin() { return this.protocol + '//' + this.host; }
        get href() { return this.origin + this.pathname + this.search + this.hash; }
        set href(v) { Object.assign(this, new URL(v)); }
        get searchParams() {
            const sp = new URLSearchParams(this.search);
            const self = this;
            const sync = () => { const s = sp.toString(); self.search = s ? '?' + s : ''; };
            for (const k of ['set', 'append', 'delete']) {
                const f = sp[k].bind(sp);
                sp[k] = (...a) => { f(...a); sync(); };
            }
            return sp;
        }
        toString() { return this.href; }
        toJSON() { return this.href; }
    }
    g.URL = URL;
    g.URLSearchParams = URLSearchParams;
}
