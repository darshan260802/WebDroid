#!/usr/bin/env node
/*
 * Smoke test for the built ad-block assets: loads engine.js + selfie into a
 * bare VM context (no TextEncoder/URL/timers, like QuickJS) and checks a few
 * well-known requests.
 *
 * Usage: node test.mjs [assetDir]
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const dir = path.resolve(process.argv[2] ?? path.join(here, '../../app/src/main/assets/adblock'));

const context = vm.createContext({});
vm.runInContext(fs.readFileSync(path.join(dir, 'engine.js'), 'utf8'), context);
const WD = context.WD;

const bin = fs.readFileSync(path.join(dir, 'selfie.bin'));
// Copy into an ArrayBuffer created inside the VM realm, as QuickJS would.
const ab = vm.runInContext(`new ArrayBuffer(${bin.length})`, context);
new Uint8Array(ab).set(bin);
context.__wdBlob(ab);

let t = Date.now();
const status = JSON.parse(WD.importSelfie(
    fs.readFileSync(path.join(dir, 'selfie.json'), 'utf8'),
    fs.readFileSync(path.join(dir, 'cosmetic.json'), 'utf8'),
));
console.log('import', status, `${Date.now() - t} ms`);
if (status.ok !== true) { process.exit(1); }

const cases = [
    ['https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js', 'https://news.example.org/a/b', 'script', /^(b|r:)/],
    ['https://www.google-analytics.com/analytics.js', 'https://example.com/', 'script', /^(b|r:)/],
    ['https://connect.facebook.net/en_US/fbevents.js', 'https://shop.example.com/', 'script', /^(b|r:)/],
    ['https://cdn.example.org/static/app.bundle.js?v=123', 'https://example.org/', 'script', /^$/],
    ['https://i.ytimg.com/vi/abc/hqdefault.jpg', 'https://www.youtube.com/', 'image', /^$/],
    ['https://example.org/', 'https://example.org/', 'main_frame', /^$/],
];
let failed = 0;
for (const [url, origin, type, expected] of cases) {
    const r = WD.match(url, origin, type);
    const ok = expected.test(r);
    if (!ok) { failed += 1; }
    console.log(ok ? 'ok  ' : 'FAIL', JSON.stringify(r), url);
}

t = Date.now();
const N = 20000;
for (let i = 0; i < N; i++) {
    const [url, origin, type] = cases[i % cases.length];
    WD.match(url + (url.includes('?') ? '&' : '?') + 'n=' + i, origin, type);
}
console.log(`match avg ${((Date.now() - t) * 1000 / N).toFixed(1)} us`);

const cosmetic = JSON.parse(WD.cosmetic('https://www.youtube.com/watch?v=x'));
console.log('youtube cosmetic:', {
    hide: cosmetic.hide.length,
    procedural: cosmetic.procedural.length,
    exceptions: cosmetic.exceptions.length,
    highGeneric: cosmetic.highGeneric.length,
    scriptlets: cosmetic.scriptlets.map(s => s[0]),
});
if (cosmetic.scriptlets.length === 0) { failed += 1; console.log('FAIL no youtube scriptlets'); }
console.log('lowGeneric:', WD.lowGeneric('.adsbygoogle\n#ad-banner\n.content'));

process.exit(failed === 0 ? 0 : 1);
