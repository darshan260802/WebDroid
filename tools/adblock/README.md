# WebDroid ad-block engine

WebDroid blocks ads with **uBlock Origin's own filtering code**, embedded in the
app and executed by a small QuickJS interpreter (via [Zipline](https://github.com/cashapp/zipline)):

| Piece | Source |
| --- | --- |
| Network filtering (`||ads.example^`, `$script`, `$redirect`, exceptions…) | [`@gorhill/ubo-core`](https://www.npmjs.com/package/@gorhill/ubo-core) – uBO's static network filtering engine |
| Filter parsing (all lists, incl. cosmetic + scriptlet syntax) | uBO's `static-filtering-parser.js` (in ubo-core) |
| Cosmetic / scriptlet hostname database | `vendor/ublock/static-ext-filtering-db.js` |
| Scriptlets (`##+js(...)`) | `vendor/ublock/resources/*` |
| Procedural cosmetic filters (`:has-text()`, `:upward()`…) | `vendor/ublock/contentscript-extra.js` |
| Redirect resources (neutered scripts, blank media) | uBO `web_accessible_resources` (fetched at build) |

`vendor/ublock` is a snapshot of [gorhill/uBlock](https://github.com/gorhill/uBlock) `master`
(October 2026), GPLv3. Refresh it by re-fetching those files.

## Build

```sh
npm ci
node build.mjs --out ../../app/src/main/assets/adblock   # fetch lists + precompile
node test.mjs ../../app/src/main/assets/adblock          # smoke test
```

The build precompiles the default lists into uBO's engine tables (`selfie.json`
+ `selfie.bin`, cosmetic DB in `cosmetic.json`) so the app loads them in about a second
without parsing filter syntax. On the device, lists are refreshed in the
background and recompiled with the very same `engine.js`.
