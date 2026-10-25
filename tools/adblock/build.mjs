#!/usr/bin/env node
/*
 * Builds WebDroid's ad-block assets:
 *
 *   engine.js         uBO filtering engine bundled for QuickJS (see src/engine.js)
 *   selfie.json/.bin  default lists precompiled into uBO's engine tables
 *   cosmetic.json     cosmetic + scriptlet filter databases
 *   psl.dat           public suffix list (for on-device recompiles)
 *   scriptlets.js     uBO scriptlet library for page injection
 *   procedural.js     uBO procedural cosmetic filterer for page injection
 *   war/*             uBO redirect resources (neutered scripts, blank media)
 *   catalog.json      filter list catalog + build metadata
 *
 * Usage: node build.mjs [--out <dir>] [--offline]
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import * as esbuild from 'esbuild';

const here = path.dirname(fileURLToPath(import.meta.url));
const argv = process.argv.slice(2);
const argValue = name => {
    const i = argv.indexOf(name);
    return i !== -1 ? argv[i + 1] : undefined;
};
const outDir = path.resolve(argValue('--out') ?? path.join(here, '../../app/src/main/assets/adblock'));
const offline = argv.includes('--offline');
const cacheDir = path.join(here, '.cache');
const buildDir = path.join(here, 'build');

const log = (...args) => console.log('[adblock]', ...args);

fs.mkdirSync(outDir, { recursive: true });
fs.mkdirSync(cacheDir, { recursive: true });
fs.mkdirSync(buildDir, { recursive: true });

/******************************************************************************/

async function fetchText(urls, cacheName, { minLength = 64 } = {}) {
    const cacheFile = path.join(cacheDir, cacheName);
    if (offline === false) {
        for (const url of urls) {
            try {
                const response = await fetch(url, {
                    signal: AbortSignal.timeout(60000),
                    headers: { 'User-Agent': 'WebDroid-build (+https://github.com/darshan260802/WebDroid)' },
                });
                if (response.ok === false) {
                    log(`  ${response.status} ${url}`);
                    continue;
                }
                const text = await response.text();
                if (text.length < minLength) {
                    log(`  too short (${text.length}) ${url}`);
                    continue;
                }
                fs.writeFileSync(cacheFile, text);
                return { text, url };
            } catch (e) {
                log(`  failed ${url}: ${e.message}`);
            }
        }
    }
    if (fs.existsSync(cacheFile)) {
        log(`  using cached ${cacheName}`);
        return { text: fs.readFileSync(cacheFile, 'utf8'), url: 'cache' };
    }
    return null;
}

/******************************************************************************/
// 1. Scriptlet registry + page-side scriptlet library

const { builtinScriptlets } = await import('./vendor/ublock/resources/scriptlets.js');

const scriptletAliases = {};
const trustedScriptlets = [];
const functionCode = new Map();
const scriptletEntries = [];
for (const scriptlet of builtinScriptlets) {
    const { fn, name } = scriptlet;
    if (functionCode.has(fn.name) && functionCode.get(fn.name) !== fn.toString()) {
        throw new Error(`Scriptlet function name collision: ${fn.name}`);
    }
    functionCode.set(fn.name, fn.toString());
    if (name.endsWith('.js') === false) { continue; } // helper (".fn")
    scriptletAliases[name] = name;
    for (const alias of scriptlet.aliases ?? []) {
        scriptletAliases[alias] = name;
    }
    if (scriptlet.requiresTrust) { trustedScriptlets.push(name); }
    scriptletEntries.push(`${JSON.stringify(name.slice(0, -3))}: ${fn.name}`);
}
fs.writeFileSync(
    path.join(buildDir, 'scriptlet-registry.json'),
    JSON.stringify({ aliases: scriptletAliases, trusted: trustedScriptlets })
);

const scriptletLibrary = [
    '// uBlock Origin scriptlets - https://github.com/gorhill/uBlock - GPLv3',
    'function __webdroidScriptlets(list) {',
    'const scriptletGlobals = {};',
    ...functionCode.values(),
    `const registry = { ${scriptletEntries.join(',\n')} };`,
    'for (const entry of list) {',
    '  const fn = registry[entry[0]];',
    '  if (fn === undefined) { continue; }',
    '  try { fn(...entry.slice(1)); } catch (e) { }',
    '}',
    '}',
].join('\n');
const minifiedScriptlets = await esbuild.transform(scriptletLibrary, {
    minify: true,
    target: 'chrome100',
    legalComments: 'none',
});
fs.writeFileSync(
    path.join(outDir, 'scriptlets.js'),
    '// uBlock Origin scriptlets - https://github.com/gorhill/uBlock - GPLv3\n' + minifiedScriptlets.code
);
log(`scriptlets: ${scriptletEntries.length} (${minifiedScriptlets.code.length} bytes)`);

/******************************************************************************/
// 2. Procedural cosmetic filterer (page side)

{
    const source = fs.readFileSync(path.join(here, 'vendor/ublock/contentscript-extra.js'), 'utf8');
    const minified = await esbuild.transform(source, {
        minify: true,
        target: 'chrome100',
        legalComments: 'none',
    });
    fs.writeFileSync(
        path.join(outDir, 'procedural.js'),
        '// uBlock Origin procedural filterer - https://github.com/gorhill/uBlock - GPLv3\n' + minified.code
    );
}

/******************************************************************************/
// 3. Redirect resources

const redirectResources = (await import('./vendor/ublock/redirect-resources.js')).default;
const redirectAliases = {};
const warDir = path.join(outDir, 'war');
fs.mkdirSync(warDir, { recursive: true });
for (const [name, details] of redirectResources) {
    if (details.requiresTrust) { continue; }
    if (name !== 'empty') {
        const target = path.join(warDir, name);
        let ok = fs.existsSync(target);
        const cached = path.join(cacheDir, `war-${name}`);
        if (ok === false && offline === false) {
            try {
                const r = await fetch(`https://raw.githubusercontent.com/gorhill/uBlock/master/src/web_accessible_resources/${name}`);
                if (r.ok) {
                    fs.writeFileSync(cached, Buffer.from(await r.arrayBuffer()));
                }
            } catch { }
        }
        if (fs.existsSync(cached)) {
            fs.copyFileSync(cached, target);
            ok = true;
        }
        if (ok === false) {
            log(`  missing redirect resource ${name}`);
            continue;
        }
    }
    redirectAliases[name] = name;
    const aliases = Array.isArray(details.alias) ? details.alias : (details.alias ? [details.alias] : []);
    for (const alias of aliases) { redirectAliases[alias] = name; }
}
fs.writeFileSync(path.join(buildDir, 'redirect-registry.json'), JSON.stringify({ aliases: redirectAliases }));
log(`redirect resources: ${Object.keys(redirectAliases).length} tokens`);

/******************************************************************************/
// 4. Engine bundle for QuickJS

await esbuild.build({
    entryPoints: [path.join(here, 'src/engine.js')],
    bundle: true,
    format: 'iife',
    platform: 'neutral',
    target: 'es2020',
    minify: true,
    legalComments: 'none',
    banner: { js: '/* WebDroid ad-block engine, built on uBlock Origin (https://github.com/gorhill/uBlock). GPLv3. */' },
    outfile: path.join(outDir, 'engine.js'),
    logLevel: 'warning',
});
const engineCode = fs.readFileSync(path.join(outDir, 'engine.js'), 'utf8');
log(`engine.js: ${engineCode.length} bytes`);

/******************************************************************************/
// 5. Fetch and precompile the default-enabled lists

const catalog = JSON.parse(fs.readFileSync(path.join(here, 'lists.json'), 'utf8'));

// A bare VM context has no TextEncoder/URL/console/timers, like QuickJS.
const context = vm.createContext({});
vm.runInContext(engineCode, context, { filename: 'engine.js' });
const WD = context.WD;

const psl = await fetchText(catalog.psl.urls, 'psl.dat', { minLength: 10000 });
if (psl === null) { throw new Error('Unable to fetch the public suffix list'); }
WD.begin();
WD.setPSL(psl.text);

const builtAt = new Date().toISOString();
const listStats = {};
const t0 = Date.now();
for (const list of catalog.lists) {
    if (list.enabled !== true) { continue; }
    log(`fetching ${list.id}`);
    const result = await fetchText(list.urls, `${list.id}.txt`);
    if (result === null) {
        log(`  WARNING: ${list.id} unavailable, skipped`);
        continue;
    }
    const stats = JSON.parse(WD.addList(list.id, result.text));
    listStats[list.id] = { ...stats, source: result.url, fetchedAt: builtAt };
    log(`  ${stats.network} network, ${stats.cosmetic} cosmetic, ${stats.scriptlet} scriptlet, ${stats.rejected} rejected`);
}
const totals = JSON.parse(WD.finish());
const t1 = Date.now();
const selfieJSON = WD.exportSelfie();
const blob = WD.exportBlobBytes();
const cosmeticJSON = WD.exportCosmetic();
WD.releaseExport();
fs.writeFileSync(path.join(outDir, 'selfie.json'), selfieJSON);
fs.writeFileSync(path.join(outDir, 'selfie.bin'), Buffer.from(blob.buffer, blob.byteOffset, blob.byteLength));
fs.writeFileSync(path.join(outDir, 'cosmetic.json'), cosmeticJSON);
fs.writeFileSync(path.join(outDir, 'psl.dat'), psl.text);
log(`compiled in ${t1 - t0} ms; selfie ${selfieJSON.length} chars + ${blob.length} bytes, cosmetic ${cosmeticJSON.length} chars`);
log(`totals: ${JSON.stringify({ ...totals, lists: undefined })}`);

const logs = JSON.parse(WD.logs());
if (logs.length !== 0) { log('engine logs:', logs.slice(-10)); }

fs.writeFileSync(path.join(outDir, 'catalog.json'), JSON.stringify({
    engineVersion: WD.version,
    builtAt,
    blobSize: blob.length,
    psl: catalog.psl,
    lists: catalog.lists.map(l => ({ ...l, stats: listStats[l.id] })),
    totals: { ...totals, lists: undefined },
}, null, 1));

log(`done -> ${outDir}`);
