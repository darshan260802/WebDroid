/*
 * WebDroid ad-block engine.
 *
 * A synchronous façade over uBlock Origin's own filtering code so it can run
 * inside an embedded QuickJS interpreter (no event loop, no WASM, no DOM):
 *
 *  - network requests are matched by uBO's static network filtering engine
 *    (from the official @gorhill/ubo-core package);
 *  - cosmetic and scriptlet filters are parsed by uBO's own filter parser and
 *    stored in uBO's hostname database, mirroring uBO's cosmetic engine.
 *
 * Exposed as `globalThis.WD`. All methods take and return primitives/strings
 * so the Kotlin host can call them through QuickJS without object bridging.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import './polyfills.js';
import './vapi-shim.js';

import * as sfp from '@gorhill/ubo-core/js/static-filtering-parser.js';
import { CompiledListReader, CompiledListWriter } from '@gorhill/ubo-core/js/static-filtering-io.js';
import { domainFromHostname, entityFromDomain, hostnameFromURI } from '@gorhill/ubo-core/js/uri-utils.js';
import { FilteringContext } from '@gorhill/ubo-core/js/filtering-context.js';
import { LineIterator } from '@gorhill/ubo-core/js/text-utils.js';
import publicSuffixList from '@gorhill/ubo-core/lib/publicsuffixlist/publicsuffixlist.js';
import punycode from '@gorhill/ubo-core/lib/punycode.js';
import snfe from '@gorhill/ubo-core/js/static-net-filtering.js';

import { StaticExtFilteringHostnameDB } from '../vendor/ublock/static-ext-filtering-db.js';
import scriptletRegistry from '../build/scriptlet-registry.json';
import redirectRegistry from '../build/redirect-registry.json';

const SELFIE_VERSION = 4;

// uBO's `!#if` preprocessor environment for a Chromium-based mobile browser.
const PREPARSER_ENV = ['ublock', 'chromium', 'mobile'];

// Lists maintained by the uBO team may use privileged syntax/scriptlets.
const isTrustedList = name => /^(?:ublock-|user-)/.test(name);

/******************************************************************************/
// Cosmetic selector bucketing, mirrors uBO's cosmetic-filtering.js

const reSimplestSelector = /^[#.][\w-]+$/;
const rePlainSelector = /^[#.][\w\\-]+/;
const rePlainSelectorEx = /^[^#.[(]+([#.][\w-]+)|([#.][\w-]+)$/;
const rePlainSelectorEscaped = /^[#.](?:\\[0-9A-Fa-f]+ |\\.|\w|-)+/;
const reEscapeSequence = /\\([0-9A-Fa-f]+ |.)/g;

const keyFromSelector = selector => {
    let matches = reSimplestSelector.exec(selector);
    if (matches !== null) { return matches[0]; }
    let key = '';
    matches = rePlainSelector.exec(selector);
    if (matches !== null) {
        key = matches[0];
    } else {
        matches = rePlainSelectorEx.exec(selector);
        if (matches === null) { return; }
        key = matches[1] || matches[2];
    }
    if (selector.includes(',')) { return; }
    if (key.includes('\\') === false) { return key; }
    matches = rePlainSelectorEscaped.exec(selector);
    if (matches === null) { return; }
    key = '';
    const escaped = matches[0];
    let beg = 0;
    reEscapeSequence.lastIndex = 0;
    for (;;) {
        matches = reEscapeSequence.exec(escaped);
        if (matches === null) {
            return key + escaped.slice(beg);
        }
        key += escaped.slice(beg, matches.index);
        beg = reEscapeSequence.lastIndex;
        key += matches[1].length === 1
            ? matches[1]
            : String.fromCharCode(parseInt(matches[1], 16));
    }
};

/******************************************************************************/

const fctx = new FilteringContext();
const cosmeticDB = new StaticExtFilteringHostnameDB();
const scriptletDB = new StaticExtFilteringHostnameDB();
let lowGeneric = new Map();     // ".class" / "#id" -> [selector, ...]
let highGeneric = new Set();    // selectors without a usable class/id key
let highGenericJSON = '[]';
let ready = false;
let session = null;
let blockedCount = 0;

const scriptletAliases = new Map(Object.entries(scriptletRegistry.aliases));
const trustedScriptlets = new Set(scriptletRegistry.trusted);

const redirectAliases = new Map(Object.entries(redirectRegistry.aliases));
const redirectEngine = {
    hasToken(token) {
        if (token === 'none') { return true; }
        return redirectAliases.has(token);
    },
    tokenToURL(fctxt, token) {
        if (token === 'none') { return; }
        const name = redirectAliases.get(token);
        if (name === undefined) { return; }
        return `webdroid-redirect:${name}`;
    },
};

function newParser(trusted) {
    return new sfp.AstFilterParser({
        maxTokenLength: snfe.MAX_TOKEN_LENGTH,
        trustedSource: trusted,
        nativeCssHas: true,
    });
}

function entityFromHostname(hostname, domain) {
    const entity = entityFromDomain(domain);
    if (entity === '') { return ''; }
    return hostname.slice(0, -domain.length) + entity;
}

/******************************************************************************/
// Compilation

function compileCosmetic(parser, counts) {
    const { compiled } = parser.result;
    if (compiled === undefined || compiled === '') {
        counts.rejected += 1;
        return;
    }
    const isException = parser.isException();
    const storeGeneric = () => {
        if (isException) {
            cosmeticDB.store('', `-${compiled}`);
            return;
        }
        // uBO does not allow generic procedural filters by default.
        if (compiled.charCodeAt(0) === 0x7B /* { */) { return; }
        const key = keyFromSelector(compiled);
        if (key !== undefined) {
            const bucket = lowGeneric.get(key);
            if (bucket === undefined) {
                lowGeneric.set(key, [compiled]);
            } else if (bucket.includes(compiled) === false) {
                bucket.push(compiled);
            }
        } else {
            highGeneric.add(compiled);
        }
    };
    if (parser.hasOptions() === false) {
        storeGeneric();
        counts.cosmetic += 1;
        return;
    }
    let applyGlobally = true;
    for (const { hn, not, bad } of parser.getExtFilterDomainIterator()) {
        if (bad) { continue; }
        if (not === false) { applyGlobally = false; }
        if (isException && not) { continue; }
        cosmeticDB.store(hn, `${isException || not ? '-' : '+'}${compiled}`);
    }
    if (applyGlobally) { storeGeneric(); }
    counts.cosmetic += 1;
}

function compileScriptlet(parser, trusted, counts) {
    const isException = parser.isException();
    const args = parser.getScriptletArgs();
    if (args.length !== 0) {
        let token = args[0];
        if (token.endsWith('.js') === false) { token += '.js'; }
        token = scriptletAliases.get(token) ?? token;
        if (scriptletAliases.has(token) === false) {
            counts.rejected += 1;
            return;
        }
        if (trustedScriptlets.has(token) && trusted !== true && isException === false) {
            counts.rejected += 1;
            return;
        }
        args[0] = token.slice(0, -3);
    }
    const normalized = JSON.stringify(args);
    if (normalized === '[]' && isException === false) { return; }
    if (parser.hasOptions() === false) {
        if (isException) { scriptletDB.store('', `-${normalized}`); }
        return;
    }
    for (const { hn, not, bad } of parser.getExtFilterDomainIterator()) {
        if (bad) { continue; }
        if (isException && not) { continue; }
        scriptletDB.store(hn, `${isException || not ? '-' : '+'}${normalized}`);
    }
    counts.scriptlet += 1;
}

function begin() {
    snfe.reset();
    cosmeticDB.clear();
    scriptletDB.clear();
    lowGeneric = new Map();
    highGeneric = new Set();
    highGenericJSON = '[]';
    ready = false;
    session = {
        compiler: snfe.createCompiler(),
        parsers: { trusted: newParser(true), untrusted: newParser(false) },
        lists: [],
    };
    return true;
}

function setPSL(raw) {
    if (typeof raw !== 'string' || raw.trim() === '') { return false; }
    publicSuffixList.parse(raw, punycode.toASCII);
    return true;
}

function addList(name, raw) {
    if (session === null) { begin(); }
    const trusted = isTrustedList(name);
    const parser = trusted ? session.parsers.trusted : session.parsers.untrusted;
    const counts = { name, network: 0, cosmetic: 0, scriptlet: 0, rejected: 0, lines: 0 };
    const writer = new CompiledListWriter();
    writer.properties.set('name', name);
    const text = sfp.utils.preparser.prune(raw, PREPARSER_ENV);
    const lineIter = new LineIterator(text);
    while (lineIter.eot() === false) {
        let line = lineIter.next();
        while (line.endsWith(' \\')) {
            if (lineIter.peek(4) !== '    ') { break; }
            line = line.slice(0, -2).trim() + lineIter.next().trim();
        }
        counts.lines += 1;
        parser.parse(line);
        if (parser.isFilter() === false) { continue; }
        if (parser.isNetworkFilter()) {
            if (session.compiler.compile(parser, writer)) {
                counts.network += 1;
            } else {
                counts.rejected += 1;
            }
            continue;
        }
        if (parser.isExtendedFilter() === false) { continue; }
        if (parser.hasError()) { counts.rejected += 1; continue; }
        if (parser.isScriptletFilter()) {
            compileScriptlet(parser, trusted, counts);
            continue;
        }
        // HTML filtering and response-header filtering need access to raw
        // responses, which a WebView does not provide.
        if (parser.isHtmlFilter() || parser.isResponseheaderFilter()) { continue; }
        if (parser.isCosmeticFilter()) {
            compileCosmetic(parser, counts);
        }
    }
    snfe.fromCompiled(new CompiledListReader(writer.toString()));
    session.lists.push(counts);
    return JSON.stringify(counts);
}

function finish() {
    if (session === null) { return '{}'; }
    snfe.freeze();
    snfe.optimize(0);
    if (typeof globalThis.__wdDrainTimers === 'function') { globalThis.__wdDrainTimers(); }
    cosmeticDB.collectGarbage();
    scriptletDB.collectGarbage();
    highGenericJSON = JSON.stringify(Array.from(highGeneric));
    const out = {
        lists: session.lists,
        network: snfe.getFilterCount(),
        cosmetic: cosmeticDB.size,
        scriptlets: scriptletDB.size,
        lowGeneric: lowGeneric.size,
        highGeneric: highGeneric.size,
    };
    for (const parser of Object.values(session.parsers)) {
        parser.selectorCompiler.finish();
    }
    session = null;
    ready = true;
    return JSON.stringify(out);
}

/******************************************************************************/
// Persistence
//
// The network engine is persisted as uBO's own selfie (its internal tables),
// with typed arrays stored as one raw binary blob next to a small JSON
// skeleton. The cosmetic/scriptlet databases are persisted as plain JSON so
// that loading only needs the interpreter's native JSON.parse.

const dbToJSON = db => {
    const s = db.toSelfie();
    return {
        VERSION: s.VERSION,
        hostnameToStringListMap: Array.from(s.hostnameToStringListMap),
        matcherMap: Array.from(s.matcherMap),
        hostnameToMatcherListMap: Array.from(s.hostnameToMatcherListMap),
        strSlots: s.strSlots,
        matcherSlots: s.matcherSlots,
        linkedLists: s.linkedLists,
        size: s.size,
    };
};

const dbFromJSON = (db, o) => {
    db.fromSelfie({
        VERSION: o.VERSION,
        hostnameToStringListMap: new Map(o.hostnameToStringListMap),
        matcherMap: new Map(o.matcherMap),
        hostnameToMatcherListMap: new Map(o.hostnameToMatcherListMap),
        strSlots: o.strSlots,
        matcherSlots: o.matcherSlots,
        linkedLists: o.linkedLists,
        size: o.size,
    });
};

// Binary payload handed over by the host. The Kotlin side injects it as an
// ArrayBuffer constant inside crafted QuickJS bytecode (a plain memcpy), which
// avoids decoding tens of megabytes through interpreted JavaScript.
let hostBlob = null;
globalThis.__wdBlob = buffer => {
    hostBlob = buffer;
    return buffer.byteLength;
};

const TYPED_ARRAYS = { Int32Array, Uint32Array, Uint8Array, Uint16Array, Int16Array, Int8Array, Float64Array, Float32Array };

// JSON with Maps and typed arrays lifted out into a binary blob.
function encodeTagged(value, blobs) {
    let offset = blobs.reduce((n, b) => n + b.length, 0);
    const json = JSON.stringify(value, function (key, v) {
        const original = this[key];
        if (ArrayBuffer.isView(original)) {
            const pad = (8 - (offset & 7)) & 7;
            if (pad !== 0) { blobs.push(new Uint8Array(pad)); offset += pad; }
            const bytes = new Uint8Array(original.buffer, original.byteOffset, original.byteLength);
            blobs.push(bytes);
            const out = { $ta: original.constructor.name, o: offset, n: original.length };
            offset += bytes.length;
            return out;
        }
        if (original instanceof Map) { return { $map: Array.from(original) }; }
        if (original instanceof Set) { return { $set: Array.from(original) }; }
        if (original instanceof RegExp) { return { $regex: [original.source, original.flags] }; }
        if (original !== null && typeof original === 'object' && Array.isArray(original) === false) {
            const proto = Object.getPrototypeOf(original);
            if (proto !== Object.prototype && proto !== null) {
                throw new TypeError(`Cannot persist ${proto.constructor && proto.constructor.name} at "${key}"`);
            }
        }
        return v;
    });
    return json;
}

function decodeTagged(value, buffer) {
    if (Array.isArray(value)) {
        for (let i = 0; i < value.length; i++) { value[i] = decodeTagged(value[i], buffer); }
        return value;
    }
    if (value === null || typeof value !== 'object') { return value; }
    if (typeof value.$ta === 'string') {
        // uBO's tries assume each typed array owns its whole ArrayBuffer, so
        // hand out (native) copies rather than views into the shared blob.
        const C = TYPED_ARRAYS[value.$ta];
        return new C(buffer.slice(value.o, value.o + value.n * C.BYTES_PER_ELEMENT));
    }
    if (Array.isArray(value.$map)) {
        return new Map(value.$map.map(([k, v]) => [decodeTagged(k, buffer), decodeTagged(v, buffer)]));
    }
    if (Array.isArray(value.$set)) {
        return new Set(value.$set.map(v => decodeTagged(v, buffer)));
    }
    if (Array.isArray(value.$regex)) {
        return new RegExp(value.$regex[0], value.$regex[1]);
    }
    for (const k of Object.keys(value)) { value[k] = decodeTagged(value[k], buffer); }
    return value;
}

let exportBlob = null;

// Network engine + PSL state. Binary parts are retrieved separately with
// exportBlobSize()/exportBlobChunk() (or exportBlobBytes() under node).
function exportSelfie() {
    const blobs = [];
    const json = encodeTagged({
        version: SELFIE_VERSION,
        psl: publicSuffixList.toSelfie(),
        snfe: snfe.serialize(),
    }, blobs);
    const size = blobs.reduce((n, b) => n + b.length, 0);
    exportBlob = new Uint8Array(size);
    let offset = 0;
    for (const b of blobs) { exportBlob.set(b, offset); offset += b.length; }
    return json;
}

const exportBlobSize = () => exportBlob === null ? 0 : exportBlob.length;
const exportBlobBytes = () => exportBlob;

// Latin-1 "binary string" chunk, the only bulk format QuickJS can return.
function exportBlobChunk(begin, end) {
    const bytes = exportBlob.subarray(begin, Math.min(end, exportBlob.length));
    const parts = [];
    for (let i = 0; i < bytes.length; i += 8192) {
        parts.push(String.fromCharCode.apply(null, bytes.subarray(i, i + 8192)));
    }
    return parts.join('');
}

function exportCosmetic() {
    return JSON.stringify({
        version: SELFIE_VERSION,
        cosmetic: dbToJSON(cosmeticDB),
        scriptlet: dbToJSON(scriptletDB),
        lowGeneric: Array.from(lowGeneric),
        highGeneric: Array.from(highGeneric),
    });
}

function releaseExport() {
    exportBlob = null;
}

// Load engine state exported by exportSelfie()/exportCosmetic(). The binary
// blob must have been delivered through __wdBlob() beforehand.
function importSelfie(selfieJSON, cosmeticJSON) {
    ready = false;
    session = null;
    try {
        if (hostBlob === null) { return JSON.stringify({ ok: false, error: 'no blob' }); }
        const selfie = decodeTagged(JSON.parse(selfieJSON), hostBlob);
        const cosmetic = JSON.parse(cosmeticJSON);
        if (selfie.version !== SELFIE_VERSION || cosmetic.version !== SELFIE_VERSION) {
            return JSON.stringify({ ok: false, error: 'version' });
        }
        if (publicSuffixList.fromSelfie(selfie.psl) === false) {
            return JSON.stringify({ ok: false, error: 'psl' });
        }
        if (snfe.unserialize(selfie.snfe) !== true) {
            return JSON.stringify({ ok: false, error: 'snfe' });
        }
        cosmeticDB.clear();
        scriptletDB.clear();
        dbFromJSON(cosmeticDB, cosmetic.cosmetic);
        dbFromJSON(scriptletDB, cosmetic.scriptlet);
        lowGeneric = new Map(cosmetic.lowGeneric);
        highGeneric = new Set(cosmetic.highGeneric);
        highGenericJSON = JSON.stringify(cosmetic.highGeneric);
        hostBlob = null;
        ready = true;
        return JSON.stringify({ ok: true, network: snfe.getFilterCount(), cosmetic: cosmeticDB.size, scriptlets: scriptletDB.size });
    } catch (e) {
        return JSON.stringify({ ok: false, error: String(e && e.stack || e) });
    }
}

/******************************************************************************/
// Matching

// Returns '' (no action), 'b' (block), 'a' (explicitly allowed) or
// 'r:<resource>' (block and serve a neutered replacement resource).
function match(url, originURL, type) {
    if (ready === false) { return ''; }
    fctx.redirectURL = undefined;
    const r = snfe.matchRequest(fctx.fromDetails({ originURL, url, type }));
    if (r === 2) { return 'a'; }
    if (r !== 1) { return ''; }
    blockedCount += 1;
    if (type !== 'main_frame') {
        snfe.redirectRequest(redirectEngine, fctx);
        if (typeof fctx.redirectURL === 'string') {
            return 'r:' + fctx.redirectURL.slice('webdroid-redirect:'.length);
        }
    }
    return 'b';
}

function lastMatchFilter() {
    const data = snfe.toLogData();
    return data ? String(data.raw || '') : '';
}

// Everything a document needs at document_start: declarative and procedural
// cosmetic filters, exceptions, scriptlets, and the high-generic set.
function cosmetic(url) {
    if (ready === false) { return ''; }
    const hostname = hostnameFromURI(url);
    if (hostname === '') { return ''; }
    const domain = domainFromHostname(hostname) || hostname;
    const entity = entityFromHostname(hostname, domain);

    const noSpecific = snfe.matchRequestReverse('specifichide', url) === 2;
    const noGeneric = snfe.matchRequestReverse('generichide', url) === 2;

    const all = new Set();
    if (noSpecific === false) {
        cosmeticDB.retrieveSpecifics(all, hostname);
        if (entity !== '') { cosmeticDB.retrieveSpecifics(all, entity); }
        cosmeticDB.retrieveSpecificsByRegex(all, hostname, url);
    }
    cosmeticDB.retrieveGenerics(all);

    const exceptions = new Set();
    for (const s of all) {
        if (s.charCodeAt(0) === 0x2D /* - */) { exceptions.add(s.slice(1)); }
    }
    const hide = [];
    const procedural = [];
    for (const s of all) {
        if (s.charCodeAt(0) !== 0x2B /* + */) { continue; }
        const selector = s.slice(1);
        if (exceptions.has(selector)) { continue; }
        if (selector.charCodeAt(0) === 0x7B /* { */) {
            try {
                const o = JSON.parse(selector);
                if (exceptions.has(o.raw)) { continue; }
            } catch {
                continue;
            }
            procedural.push(selector);
        } else {
            hide.push(selector);
        }
    }

    const scriptlets = [];
    const sall = new Set();
    scriptletDB.retrieveSpecifics(sall, hostname);
    if (entity !== '') { scriptletDB.retrieveSpecifics(sall, entity); }
    scriptletDB.retrieveSpecificsByRegex(sall, hostname, url);
    scriptletDB.retrieveGenerics(sall);
    if (sall.has('-[]') === false) {
        for (const s of sall) {
            if (s.charCodeAt(0) !== 0x2B /* + */) { continue; }
            const json = s.slice(1);
            if (sall.has(`-${json}`)) { continue; }
            try { scriptlets.push(JSON.parse(json)); } catch { }
        }
    }

    return '{"hide":' + JSON.stringify(hide) +
        ',"procedural":' + JSON.stringify(procedural) +
        ',"exceptions":' + JSON.stringify(Array.from(exceptions)) +
        ',"generic":' + (noGeneric ? 'false' : 'true') +
        ',"highGeneric":' + (noGeneric ? '[]' : highGenericJSON) +
        ',"scriptlets":' + JSON.stringify(scriptlets) + '}';
}

// Low-generic cosmetic filters for class/id keys seen in the page
// (`.foo` / `#bar`), newline separated. Returns a JSON array of selectors.
function lowGenericFor(keys) {
    if (ready === false || typeof keys !== 'string' || keys === '') { return '[]'; }
    const out = new Set();
    for (const key of keys.split('\n')) {
        const bucket = lowGeneric.get(key);
        if (bucket === undefined) { continue; }
        for (const selector of bucket) { out.add(selector); }
    }
    return JSON.stringify(Array.from(out));
}

globalThis.WD = {
    version: SELFIE_VERSION,
    begin,
    setPSL,
    addList,
    finish,
    exportSelfie,
    exportBlobSize,
    exportBlobBytes,
    exportBlobChunk,
    exportCosmetic,
    releaseExport,
    importSelfie,
    match,
    lastMatchFilter,
    cosmetic,
    lowGeneric: lowGenericFor,
    isReady: () => ready,
    blockedCount: () => blockedCount,
    logs: () => JSON.stringify(globalThis.__wdLogs || []),
};
