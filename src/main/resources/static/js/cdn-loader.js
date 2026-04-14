(function (window, document) {
    'use strict';

    const STORE_PREFIX = 'ts3ab.cdn.choice.';
    const STORE_TTL_MS = 6 * 60 * 60 * 1000;
    const PROBE_TIMEOUT_MS = 2200;

    const DEPENDENCIES = {
        bootstrapCss: {
            mainland: 'https://cdn.bootcdn.net/ajax/libs/twitter-bootstrap/5.3.3/css/bootstrap.min.css',
            global: 'https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css'
        },
        jquery: {
            mainland: 'https://cdn.bootcdn.net/ajax/libs/jquery/3.7.1/jquery.min.js',
            global: 'https://code.jquery.com/jquery-3.7.1.min.js'
        },
        bootstrapJs: {
            mainland: 'https://cdn.bootcdn.net/ajax/libs/twitter-bootstrap/5.3.3/js/bootstrap.bundle.min.js',
            global: 'https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js'
        },
        fontAwesomeCss: {
            mainland: 'https://cdn.bootcdn.net/ajax/libs/font-awesome/6.4.0/css/all.min.css',
            global: 'https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/css/all.min.css'
        },
        qrious: {
            mainland: 'https://cdn.bootcdn.net/ajax/libs/qrious/4.0.2/qrious.min.js',
            global: 'https://cdn.jsdelivr.net/npm/qrious@4.0.2/dist/qrious.min.js'
        }
    };

    const probePromises = {};

    function safeGetStorage(key) {
        try {
            return window.localStorage.getItem(key);
        } catch (error) {
            return null;
        }
    }

    function safeSetStorage(key, value) {
        try {
            window.localStorage.setItem(key, value);
        } catch (error) {
            // ignore storage failures
        }
    }

    function escapeAttr(value) {
        return String(value)
            .replace(/&/g, '&amp;')
            .replace(/"/g, '&quot;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;');
    }

    function escapeSingleQuotedJs(value) {
        return String(value).replace(/\\/g, '\\\\').replace(/'/g, "\\'");
    }

    function storageKey(dependency) {
        return STORE_PREFIX + dependency;
    }

    function readChoice(dependency) {
        const raw = safeGetStorage(storageKey(dependency));
        if (!raw) {
            return null;
        }
        try {
            const parsed = JSON.parse(raw);
            if (!parsed || typeof parsed.choice !== 'string' || typeof parsed.expireAt !== 'number') {
                return null;
            }
            if (parsed.expireAt <= Date.now()) {
                return null;
            }
            if (!DEPENDENCIES[dependency] || !DEPENDENCIES[dependency][parsed.choice]) {
                return null;
            }
            return parsed.choice;
        } catch (error) {
            return null;
        }
    }

    function writeChoice(dependency, choice) {
        safeSetStorage(storageKey(dependency), JSON.stringify({
            choice,
            expireAt: Date.now() + STORE_TTL_MS
        }));
    }

    function resolveChoice(dependency) {
        const cached = readChoice(dependency);
        return cached || 'mainland';
    }

    function fallbackChoice(choice) {
        return choice === 'mainland' ? 'global' : 'mainland';
    }

    function addProbeTag(url) {
        if (url.indexOf('?') >= 0) {
            return url + '&_ts3ab_probe=' + Date.now();
        }
        return url + '?_ts3ab_probe=' + Date.now();
    }

    function probeUrl(url) {
        if (typeof window.fetch !== 'function') {
            return Promise.reject(new Error('fetch not available'));
        }
        const startedAt = (window.performance && typeof window.performance.now === 'function')
            ? window.performance.now()
            : Date.now();

        return new Promise((resolve, reject) => {
            const controller = typeof window.AbortController === 'function' ? new window.AbortController() : null;
            const timeoutId = window.setTimeout(() => {
                if (controller) {
                    controller.abort();
                }
                reject(new Error('probe timeout'));
            }, PROBE_TIMEOUT_MS);

            const options = { mode: 'no-cors', cache: 'no-store' };
            if (controller) {
                options.signal = controller.signal;
            }

            window.fetch(addProbeTag(url), options)
                .then(() => {
                    window.clearTimeout(timeoutId);
                    const endedAt = (window.performance && typeof window.performance.now === 'function')
                        ? window.performance.now()
                        : Date.now();
                    resolve(endedAt - startedAt);
                })
                .catch((error) => {
                    window.clearTimeout(timeoutId);
                    reject(error);
                });
        });
    }

    function firstResolved(promises) {
        return new Promise((resolve, reject) => {
            let rejected = 0;
            const total = Array.isArray(promises) ? promises.length : 0;
            if (total <= 0) {
                reject(new Error('empty promises'));
                return;
            }
            promises.forEach((promise) => {
                Promise.resolve(promise)
                    .then(resolve)
                    .catch(() => {
                        rejected += 1;
                        if (rejected >= total) {
                            reject(new Error('all probes failed'));
                        }
                    });
            });
        });
    }

    function probeDependency(dependency) {
        if (!DEPENDENCIES[dependency]) {
            return Promise.resolve('mainland');
        }
        if (probePromises[dependency]) {
            return probePromises[dependency];
        }

        const urls = DEPENDENCIES[dependency];
        const mainlandProbe = probeUrl(urls.mainland).then((cost) => ({ choice: 'mainland', cost }));
        const globalProbe = probeUrl(urls.global).then((cost) => ({ choice: 'global', cost }));

        const winner = firstResolved([mainlandProbe, globalProbe])
            .then((result) => {
                writeChoice(dependency, result.choice);
                return result.choice;
            })
            .catch(() => resolveChoice(dependency))
            .finally(() => {
                delete probePromises[dependency];
            });

        probePromises[dependency] = winner;
        return winner;
    }

    function injectCss(dependency) {
        const urls = DEPENDENCIES[dependency];
        if (!urls) {
            return;
        }
        const choice = resolveChoice(dependency);
        const fallback = fallbackChoice(choice);
        const href = urls[choice];
        const backupHref = urls[fallback];

        const html = '<link rel="stylesheet" href="' + escapeAttr(href) +
            '" onerror="this.onerror=null;this.href=\'' + escapeSingleQuotedJs(backupHref) + '\';" />';

        if (document.readyState === 'loading') {
            document.write(html);
        } else {
            const link = document.createElement('link');
            link.rel = 'stylesheet';
            link.href = href;
            link.onerror = function () {
                this.onerror = null;
                this.href = backupHref;
            };
            (document.head || document.documentElement).appendChild(link);
        }

        probeDependency(dependency);
    }

    function injectScript(dependency) {
        const urls = DEPENDENCIES[dependency];
        if (!urls) {
            return;
        }
        const choice = resolveChoice(dependency);
        const fallback = fallbackChoice(choice);
        const src = urls[choice];
        const backupSrc = urls[fallback];

        const html = '<script src="' + escapeAttr(src) +
            '" onerror="this.onerror=null;this.src=\'' + escapeSingleQuotedJs(backupSrc) + '\';"><\\/script>';

        if (document.readyState === 'loading') {
            document.write(html);
        } else {
            const script = document.createElement('script');
            script.src = src;
            script.async = false;
            script.onerror = function () {
                this.onerror = null;
                this.src = backupSrc;
            };
            (document.body || document.head || document.documentElement).appendChild(script);
        }

        probeDependency(dependency);
    }

    function probeAll() {
        Object.keys(DEPENDENCIES).forEach((dependency) => {
            probeDependency(dependency);
        });
    }

    window.TS3CdnLoader = {
        injectCss,
        injectScript,
        probeDependency,
        probeAll,
        resolveChoice
    };
}(window, document));
