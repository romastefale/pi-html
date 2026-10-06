package com.example.webview

import org.json.JSONObject

/**
 * Provides the injected JavaScript runtime for:
 *  1. High-performance native WebGL2 context configuration & context-loss recovery
 *  2. Zero-loss Blob / DataURL / showSaveFilePicker / saveAs download interception to Android Downloads
 *  3. Complete state freeze & hibernation restoration (DOM inputs, contenteditable, scroll, localStorage, sessionStorage)
 *  4. Viewport boundary background color detection for status bar & navigation bar adaptation
 */
object WebViewRuntimeBridgeScripts {

    fun buildRestoreFrozenStateEvalScript(frozenJson: String): String {
        val quotedJson = JSONObject.quote(frozenJson)
        return "window.__piRestoreFromNativeJson && window.__piRestoreFromNativeJson($quotedJson);"
    }

    val RUNTIME_BOOTSTRAP_JS: String = """
        (function() {
            if (window.__piAutoResumeInitialized) {
                if (window.__piDetectViewportColors) window.__piDetectViewportColors();
                return;
            }
            window.__piAutoResumeInitialized = true;

            // =========================================================================
            // 1. NATIVE WEBGL2 & HIGH-PERFORMANCE GPU PIPELINE ENHANCEMENT
            // =========================================================================
            function enhanceWebGLContextCreation(proto) {
                if (!proto || !proto.getContext || proto.__piWebGLEnhanced) return;
                proto.__piWebGLEnhanced = true;
                var origGetContext = proto.getContext;
                proto.getContext = function(contextType, contextAttributes) {
                    if (typeof contextType === "string") {
                        var lower = contextType.toLowerCase();
                        if (lower === "webgl2" || lower === "webgl" || lower === "experimental-webgl") {
                            var attrs = Object.assign({
                                powerPreference: "high-performance",
                                failIfMajorPerformanceCaveat: false
                            }, contextAttributes || {});
                            var ctx = origGetContext.call(this, contextType, attrs);
                            if (!ctx && lower === "webgl2") {
                                ctx = origGetContext.call(this, "webgl", attrs) ||
                                      origGetContext.call(this, "experimental-webgl", attrs);
                            }
                            if (ctx && this.addEventListener && !this.__piContextLossBound) {
                                this.__piContextLossBound = true;
                                this.addEventListener("webglcontextlost", function(ev) {
                                    ev.preventDefault();
                                }, false);
                            }
                            return ctx;
                        }
                    }
                    return origGetContext.apply(this, arguments);
                };
            }

            if (typeof HTMLCanvasElement !== "undefined") {
                enhanceWebGLContextCreation(HTMLCanvasElement.prototype);
            }
            if (typeof OffscreenCanvas !== "undefined") {
                enhanceWebGLContextCreation(OffscreenCanvas.prototype);
            }

            // =========================================================================
            // 2. VIEWPORT BOUNDARY BACKGROUND COLOR DETECTION (STATUS BAR & NAV BAR)
            // =========================================================================
            var lastTopHex = "";
            var lastBottomHex = "";

            function parseCssRgb(str) {
                if (!str || str === "transparent") return null;
                var m = str.match(/rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([\d.]+))?\s*\)/i);
                if (!m) return null;
                var a = m[4] !== undefined ? parseFloat(m[4]) : 1.0;
                if (a <= 0.02) return null;
                return {
                    r: Math.min(255, Math.max(0, parseInt(m[1], 10))),
                    g: Math.min(255, Math.max(0, parseInt(m[2], 10))),
                    b: Math.min(255, Math.max(0, parseInt(m[3], 10))),
                    a: Math.min(1.0, Math.max(0.0, a))
                };
            }

            function extractColorFromStyle(style) {
                if (!style) return null;
                var c = parseCssRgb(style.backgroundColor);
                if (c) return c;
                var bgImg = style.backgroundImage;
                if (bgImg && bgImg !== "none") {
                    var gm = bgImg.match(/rgba?\(\s*\d+\s*,\s*\d+\s*,\s*\d+(?:\s*,\s*[\d.]+)?\s*\)/i);
                    if (gm) return parseCssRgb(gm[0]);
                }
                return null;
            }

            function compositeOver(fg, bg) {
                if (!fg) return bg;
                if (fg.a >= 0.98) return fg;
                var a = fg.a;
                return {
                    r: Math.round(fg.r * a + bg.r * (1 - a)),
                    g: Math.round(fg.g * a + bg.g * (1 - a)),
                    b: Math.round(fg.b * a + bg.b * (1 - a)),
                    a: 1.0
                };
            }

            function getBaseDocumentColor() {
                var htmlCol = document.documentElement ? extractColorFromStyle(window.getComputedStyle(document.documentElement)) : null;
                var bodyCol = document.body ? extractColorFromStyle(window.getComputedStyle(document.body)) : null;
                var fallback = { r: 255, g: 255, b: 255, a: 1.0 };
                var metaTheme = document.querySelector("meta[name='theme-color']");
                if (metaTheme && metaTheme.content && !htmlCol && !bodyCol) {
                    var probe = document.createElement("span");
                    probe.style.color = metaTheme.content;
                    if (document.body) {
                        document.body.appendChild(probe);
                        var computedMeta = parseCssRgb(window.getComputedStyle(probe).color);
                        document.body.removeChild(probe);
                        if (computedMeta) return computedMeta;
                    }
                }
                var base = htmlCol ? compositeOver(htmlCol, fallback) : fallback;
                return bodyCol ? compositeOver(bodyCol, base) : base;
            }

            function sampleBoundaryColor(isTop, baseColor) {
                var vw = window.innerWidth || (document.documentElement ? document.documentElement.clientWidth : 360) || 360;
                var vh = window.innerHeight || (document.documentElement ? document.documentElement.clientHeight : 640) || 640;
                var sampleY = isTop ? 2 : Math.max(2, vh - 3);
                var sampleX = Math.floor(vw * 0.5);
                var el = document.elementFromPoint ? document.elementFromPoint(sampleX, sampleY) : null;
                var curr = el;
                while (curr && curr !== document.body && curr !== document.documentElement && curr.nodeType === 1) {
                    var rect = curr.getBoundingClientRect ? curr.getBoundingClientRect() : null;
                    var spansWidth = !rect || rect.width >= vw * 0.80;
                    var touchesBoundary = !rect || (isTop ? rect.top <= 8 : rect.bottom >= vh - 8);
                    if (spansWidth && touchesBoundary) {
                        var col = extractColorFromStyle(window.getComputedStyle(curr));
                        if (col) {
                            return compositeOver(col, baseColor);
                        }
                    }
                    curr = curr.parentElement;
                }
                return baseColor;
            }

            function rgbToHex(c) {
                return "#" + ((1 << 24) + (c.r << 16) + (c.g << 8) + c.b).toString(16).slice(1);
            }

            window.__piDetectViewportColors = function() {
                if (!window.PiDownloadBridge || !window.PiDownloadBridge.onViewportColorsDetected) return;
                var base = getBaseDocumentColor();
                var topCol = sampleBoundaryColor(true, base);
                var bottomCol = sampleBoundaryColor(false, base);
                var topHex = rgbToHex(topCol);
                var bottomHex = rgbToHex(bottomCol);
                if (topHex !== lastTopHex || bottomHex !== lastBottomHex) {
                    lastTopHex = topHex;
                    lastBottomHex = bottomHex;
                    window.PiDownloadBridge.onViewportColorsDetected(topHex, bottomHex);
                }
            };

            window.__piDetectViewportColors();
            if (window.requestAnimationFrame) {
                window.requestAnimationFrame(window.__piDetectViewportColors);
            }
            setTimeout(window.__piDetectViewportColors, 120);

            if (window.MutationObserver) {
                var colorObserver = new MutationObserver(function() {
                    if (window.requestAnimationFrame) {
                        window.requestAnimationFrame(window.__piDetectViewportColors);
                    } else {
                        window.__piDetectViewportColors();
                    }
                });
                if (document.documentElement) {
                    colorObserver.observe(document.documentElement, {
                        attributes: true,
                        attributeFilter: ["class", "style", "data-theme", "data-mode", "data-color-mode"]
                    });
                }
                if (document.body) {
                    colorObserver.observe(document.body, {
                        attributes: true,
                        attributeFilter: ["class", "style", "data-theme", "data-mode", "data-color-mode"]
                    });
                }
            }

            // =========================================================================
            // 3. ROBUST BLOB / DATA URL / SAVE-PICKER DOWNLOAD ENGINE
            // =========================================================================
            var blobRegistry = new Map();
            if (typeof URL !== "undefined" && URL.createObjectURL) {
                var origCreateObjectURL = URL.createObjectURL.bind(URL);
                var origRevokeObjectURL = URL.revokeObjectURL ? URL.revokeObjectURL.bind(URL) : null;

                URL.createObjectURL = function(obj) {
                    var url = origCreateObjectURL(obj);
                    if (obj instanceof Blob) {
                        blobRegistry.set(url, obj);
                        setTimeout(function() {
                            blobRegistry.delete(url);
                        }, 120000);
                    }
                    return url;
                };

                if (origRevokeObjectURL) {
                    URL.revokeObjectURL = function(url) {
                        // Delay actual revocation so synchronous a.click(); URL.revokeObjectURL(url) never aborts download
                        setTimeout(function() {
                            blobRegistry.delete(url);
                            origRevokeObjectURL(url);
                        }, 30000);
                    };
                }
            }

            var CHUNK_BYTE_LIMIT = 2 * 1024 * 1024; // 2 MB chunks for large files

            function transferBlobToAndroidDownloads(blob, suggestedFileName, fallbackMime) {
                if (!window.PiDownloadBridge || !blob) return;
                var effectiveName = suggestedFileName || (blob.name ? String(blob.name) : "");
                var effectiveMime = blob.type || fallbackMime || "application/octet-stream";

                if (blob.size <= 4 * 1024 * 1024) {
                    var reader = new FileReader();
                    reader.onloadend = function() {
                        if (typeof reader.result === "string") {
                            window.PiDownloadBridge.saveDataUrlToDownloads(
                                reader.result,
                                effectiveName,
                                effectiveMime
                            );
                        } else if (window.PiDownloadBridge.onDownloadFailed) {
                            window.PiDownloadBridge.onDownloadFailed("Empty FileReader result");
                        }
                    };
                    reader.onerror = function() {
                        if (window.PiDownloadBridge.onDownloadFailed) {
                            window.PiDownloadBridge.onDownloadFailed("FileReader error on blob");
                        }
                    };
                    reader.readAsDataURL(blob);
                } else {
                    var sessionId = "dl_" + Date.now() + "_" + Math.floor(Math.random() * 100000);
                    window.PiDownloadBridge.beginChunkedDownload(sessionId, effectiveName, effectiveMime);
                    var offset = 0;

                    function sendNextChunk() {
                        if (offset >= blob.size) {
                            window.PiDownloadBridge.finishChunkedDownload(sessionId);
                            return;
                        }
                        var slice = blob.slice(offset, offset + CHUNK_BYTE_LIMIT);
                        offset += CHUNK_BYTE_LIMIT;
                        var chunkReader = new FileReader();
                        chunkReader.onloadend = function() {
                            var res = chunkReader.result;
                            if (typeof res === "string") {
                                var commaIdx = res.indexOf(",");
                                var b64 = commaIdx !== -1 ? res.substring(commaIdx + 1) : res;
                                window.PiDownloadBridge.appendDownloadChunk(sessionId, b64);
                                sendNextChunk();
                            } else if (window.PiDownloadBridge.onDownloadFailed) {
                                window.PiDownloadBridge.onDownloadFailed("Chunk read failed");
                            }
                        };
                        chunkReader.onerror = function() {
                            if (window.PiDownloadBridge.onDownloadFailed) {
                                window.PiDownloadBridge.onDownloadFailed("Chunk reader error");
                            }
                        };
                        chunkReader.readAsDataURL(slice);
                    }
                    sendNextChunk();
                }
            }

            window.__piDownloadBlobUrl = function(blobUrl, fileName, fallbackMime) {
                if (!window.PiDownloadBridge) return;
                var cachedBlob = blobRegistry.get(blobUrl);
                if (cachedBlob) {
                    transferBlobToAndroidDownloads(cachedBlob, fileName, fallbackMime);
                    return;
                }
                fetch(blobUrl)
                    .then(function(res) {
                        if (!res.ok) throw new Error("HTTP " + res.status);
                        return res.blob();
                    })
                    .then(function(blob) {
                        transferBlobToAndroidDownloads(blob, fileName, fallbackMime);
                    })
                    .catch(function(err) {
                        var xhr = new XMLHttpRequest();
                        xhr.open("GET", blobUrl, true);
                        xhr.responseType = "blob";
                        xhr.onload = function() {
                            if (xhr.response instanceof Blob) {
                                transferBlobToAndroidDownloads(xhr.response, fileName, fallbackMime);
                            } else if (window.PiDownloadBridge.onDownloadFailed) {
                                window.PiDownloadBridge.onDownloadFailed(String(err));
                            }
                        };
                        xhr.onerror = function() {
                            if (window.PiDownloadBridge.onDownloadFailed) {
                                window.PiDownloadBridge.onDownloadFailed(String(err));
                            }
                        };
                        xhr.send();
                    });
            };

            function interceptAnchorDownload(anchor) {
                if (!anchor || !window.PiDownloadBridge) return false;
                var href = anchor.href || anchor.getAttribute("href") || "";
                if (!href) return false;
                var downloadAttr = anchor.getAttribute("download");
                var hasDownload = downloadAttr !== null;
                var suggestedName = downloadAttr || "";

                if (href.indexOf("blob:") === 0) {
                    window.__piDownloadBlobUrl(href, suggestedName, "");
                    return true;
                }
                if (href.indexOf("data:") === 0) {
                    window.PiDownloadBridge.saveDataUrlToDownloads(href, suggestedName, "");
                    return true;
                }
                return false;
            }

            var origAnchorClick = HTMLAnchorElement.prototype.click;
            HTMLAnchorElement.prototype.click = function() {
                if (interceptAnchorDownload(this)) {
                    return;
                }
                return origAnchorClick.apply(this, arguments);
            };

            var origDispatchEvent = EventTarget.prototype.dispatchEvent;
            EventTarget.prototype.dispatchEvent = function(ev) {
                if (ev && ev.type === "click" && this instanceof HTMLAnchorElement) {
                    if (interceptAnchorDownload(this)) {
                        return true;
                    }
                }
                return origDispatchEvent.apply(this, arguments);
            };

            document.addEventListener("click", function(e) {
                var anchor = e.target && e.target.closest ? e.target.closest("a") : null;
                if (anchor && interceptAnchorDownload(anchor)) {
                    e.preventDefault();
                    e.stopPropagation();
                }
                setTimeout(window.__piDetectViewportColors, 50);
            }, true);

            // Polyfill File System Access API (window.showSaveFilePicker) & saveAs for Web Mini-Apps
            if (typeof window.showSaveFilePicker !== "function") {
                window.showSaveFilePicker = function(options) {
                    var opts = options || {};
                    var suggestedName = opts.suggestedName || "arquivo_exportado";
                    var chunks = [];
                    return Promise.resolve({
                        kind: "file",
                        name: suggestedName,
                        createWritable: function() {
                            return Promise.resolve({
                                write: function(data) {
                                    if (data && typeof data === "object" && data.type === "write" && data.data !== undefined) {
                                        chunks.push(data.data);
                                    } else {
                                        chunks.push(data);
                                    }
                                    return Promise.resolve();
                                },
                                close: function() {
                                    var blob = new Blob(chunks);
                                    transferBlobToAndroidDownloads(blob, suggestedName, "");
                                    return Promise.resolve();
                                }
                            });
                        }
                    });
                };
            }

            if (typeof window.saveAs !== "function") {
                window.saveAs = function(blobOrUrl, fileName) {
                    if (blobOrUrl instanceof Blob) {
                        transferBlobToAndroidDownloads(blobOrUrl, fileName || "", blobOrUrl.type || "");
                    } else if (typeof blobOrUrl === "string") {
                        if (blobOrUrl.indexOf("blob:") === 0) {
                            window.__piDownloadBlobUrl(blobOrUrl, fileName || "", "");
                        } else if (blobOrUrl.indexOf("data:") === 0 && window.PiDownloadBridge) {
                            window.PiDownloadBridge.saveDataUrlToDownloads(blobOrUrl, fileName || "", "");
                        }
                    }
                };
            }

            if (typeof navigator !== "undefined" && typeof navigator.msSaveOrOpenBlob !== "function") {
                navigator.msSaveOrOpenBlob = function(blob, fileName) {
                    transferBlobToAndroidDownloads(blob, fileName || "", blob.type || "");
                    return true;
                };
            }

            // =========================================================================
            // 4. COMPLETE STATE FREEZE & HIBERNATION RESTORATION ENGINE
            // =========================================================================
            var KEY = "__pi_web_runner_dom_state_v2__";
            var cachedFields = {};
            var cachedEditable = {};
            var saveScheduled = false;

            function getElementKey(el, fallbackIndex) {
                if (el.id) return "#" + el.id;
                if (el.name) return el.tagName + "[name='" + el.name + "']" + (fallbackIndex !== undefined ? ":" + fallbackIndex : "");
                if (fallbackIndex !== undefined) return el.tagName + ":" + fallbackIndex;
                return null;
            }

            function collectStorageMap(storageObj) {
                var result = {};
                if (!storageObj) return result;
                for (var i = 0; i < storageObj.length; i++) {
                    var k = storageObj.key(i);
                    if (k && k !== KEY) {
                        var v = storageObj.getItem(k);
                        if (typeof v === "string" && v.length <= 250000) {
                            result[k] = v;
                        }
                    }
                }
                return result;
            }

            function buildFreezeSnapshotObject(fullScan) {
                if (fullScan) {
                    var inputs = document.querySelectorAll("input, textarea, select");
                    for (var i = 0; i < inputs.length; i++) {
                        var el = inputs[i];
                        if (el.type === "file" || el.type === "password" || el.type === "hidden") continue;
                        var k = getElementKey(el, i);
                        if (!k) continue;
                        if (el.type === "checkbox" || el.type === "radio") {
                            cachedFields[k] = { c: el.checked };
                        } else if (el.tagName === "SELECT") {
                            cachedFields[k] = { v: el.value, si: el.selectedIndex };
                        } else {
                            cachedFields[k] = { v: el.value };
                        }
                    }
                    var editables = document.querySelectorAll("[contenteditable='true'], [contenteditable='']");
                    for (var j = 0; j < editables.length; j++) {
                        var ed = editables[j];
                        var ek = getElementKey(ed, j);
                        if (ek && ed.innerHTML && ed.innerHTML.length <= 200000) {
                            cachedEditable[ek] = ed.innerHTML;
                        }
                    }
                }

                return {
                    v: 2,
                    ts: Date.now(),
                    sx: Math.round(window.scrollX || 0),
                    sy: Math.round(window.scrollY || 0),
                    f: cachedFields,
                    ed: cachedEditable,
                    ls: collectStorageMap(window.localStorage),
                    ss: collectStorageMap(window.sessionStorage)
                };
            }

            function persistSnapshotNow(fullScan) {
                saveScheduled = false;
                var stateObj = buildFreezeSnapshotObject(fullScan);
                var jsonStr = JSON.stringify(stateObj);
                if (window.localStorage) {
                    try {
                        window.localStorage.setItem(KEY, jsonStr);
                    } catch (_e) {}
                }
                if (window.PiDownloadBridge && window.PiDownloadBridge.onFreezeSnapshotCaptured) {
                    window.PiDownloadBridge.onFreezeSnapshotCaptured(jsonStr);
                }
                return jsonStr;
            }

            function scheduleFlush() {
                if (saveScheduled) return;
                saveScheduled = true;
                setTimeout(function() {
                    if (window.requestIdleCallback) {
                        window.requestIdleCallback(function() { persistSnapshotNow(false); }, { timeout: 1200 });
                    } else {
                        persistSnapshotNow(false);
                    }
                }, 800);
            }

            window.__piSaveDomState = function() {
                return persistSnapshotNow(true);
            };

            function applyStateObject(state) {
                if (!state) return;
                if (state.ls && window.localStorage) {
                    var lsKeys = Object.keys(state.ls);
                    for (var a = 0; a < lsKeys.length; a++) {
                        var lk = lsKeys[a];
                        if (window.localStorage.getItem(lk) === null) {
                            try { window.localStorage.setItem(lk, state.ls[lk]); } catch (_e) {}
                        }
                    }
                }
                if (state.ss && window.sessionStorage) {
                    var ssKeys = Object.keys(state.ss);
                    for (var b = 0; b < ssKeys.length; b++) {
                        var sk = ssKeys[b];
                        if (window.sessionStorage.getItem(sk) === null) {
                            try { window.sessionStorage.setItem(sk, state.ss[sk]); } catch (_e) {}
                        }
                    }
                }
                if (state.f) {
                    cachedFields = Object.assign({}, state.f, cachedFields);
                    var inputs = document.querySelectorAll("input, textarea, select");
                    for (var i = 0; i < inputs.length; i++) {
                        var el = inputs[i];
                        if (el.type === "file" || el.type === "password" || el.type === "hidden") continue;
                        var k = getElementKey(el, i) || getElementKey(el);
                        var entry = cachedFields[k] || (el.id ? cachedFields["#" + el.id] : null);
                        if (entry) {
                            if (typeof entry.c === "boolean") {
                                el.checked = entry.c;
                            } else if (el.tagName === "SELECT" && typeof entry.si === "number") {
                                el.selectedIndex = entry.si;
                                el.dispatchEvent(new Event("change", { bubbles: true }));
                            } else if (typeof entry.v === "string" && el.value !== entry.v) {
                                el.value = entry.v;
                                el.dispatchEvent(new Event("input", { bubbles: true }));
                            }
                        }
                    }
                }
                if (state.ed) {
                    cachedEditable = Object.assign({}, state.ed, cachedEditable);
                    var editables = document.querySelectorAll("[contenteditable='true'], [contenteditable='']");
                    for (var j = 0; j < editables.length; j++) {
                        var ed = editables[j];
                        var ek = getElementKey(ed, j);
                        if (ek && cachedEditable[ek]) {
                            ed.innerHTML = cachedEditable[ek];
                        }
                    }
                }
                if (state.sx || state.sy) {
                    window.scrollTo(state.sx || 0, state.sy || 0);
                }
            }

            window.__piRestoreFromNativeJson = function(rawJson) {
                if (!rawJson) return;
                try {
                    var parsed = typeof rawJson === "string" ? JSON.parse(rawJson) : rawJson;
                    applyStateObject(parsed);
                } catch (_e) {}
            };

            function restoreInitialState() {
                if (window.localStorage) {
                    var raw = window.localStorage.getItem(KEY);
                    if (raw) {
                        window.__piRestoreFromNativeJson(raw);
                    }
                }
            }

            restoreInitialState();

            function onFieldMutated(e) {
                var el = e.target;
                if (!el || !el.tagName) return;
                if (el.isContentEditable) {
                    var ek = getElementKey(el, 0);
                    if (ek && el.innerHTML && el.innerHTML.length <= 200000) {
                        cachedEditable[ek] = el.innerHTML;
                        scheduleFlush();
                    }
                    return;
                }
                if (el.type === "file" || el.type === "password" || el.type === "hidden") return;
                var k = getElementKey(el);
                if (k) {
                    if (el.type === "checkbox" || el.type === "radio") {
                        cachedFields[k] = { c: el.checked };
                    } else if (el.tagName === "SELECT") {
                        cachedFields[k] = { v: el.value, si: el.selectedIndex };
                    } else {
                        cachedFields[k] = { v: el.value };
                    }
                    scheduleFlush();
                }
            }

            document.addEventListener("input", onFieldMutated, { capture: true, passive: true });
            document.addEventListener("change", onFieldMutated, { capture: true, passive: true });
            window.addEventListener("scroll", scheduleFlush, { passive: true });
            document.addEventListener("visibilitychange", function() {
                if (document.visibilityState === "hidden") {
                    window.__piSaveDomState();
                }
            });
        })();
    """.trimIndent()
}
