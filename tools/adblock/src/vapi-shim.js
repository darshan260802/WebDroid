/*
 * WebDroid - the tiny subset of uBO's `vAPI` that the vendored engine
 * modules touch when running outside of a browser extension.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

if (typeof globalThis.vAPI === 'undefined') {
    globalThis.vAPI = {
        canWASM: false,
        localStorage: {
            getItem() { return null; },
            setItem() {},
            removeItem() {},
        },
        defer: {
            create(callback) {
                return {
                    ongoing() { return false; },
                    on() {},
                    onidle() {},
                    off() {},
                    callback,
                };
            },
        },
    };
}
