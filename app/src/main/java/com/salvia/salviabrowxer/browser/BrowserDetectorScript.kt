package com.salvia.salviabrowxer.browser

/**
 * Script injected into every frame of the native browser (document start when the WebView
 * supports it). It reports media it can see in the DOM and in fetch/XHR traffic to the
 * `SalviaBrowserBridge.report(type, json)` interface. Network-level sniffing happens separately
 * in BrowserHost.shouldInterceptRequest; this script adds what the network layer cannot know:
 * element size, duration, whether it is an ad / suggestion, and what is actually playing.
 *
 * Written without template placeholders on purpose: this is a Kotlin raw string.
 */
object BrowserDetectorScript {
    val SOURCE: String = """
(function() {
  if (window.__salviaDetector) return;
  window.__salviaDetector = true;
  var BR = window.SalviaBrowserBridge;
  if (!BR || typeof BR.report !== 'function') return;
  var SEEN = Object.create(null);
  function report(type, payload) {
    try { BR.report(type, JSON.stringify(payload)); } catch (e) {}
  }
  function pageUrl() { try { return location.href; } catch (e) { return ''; } }
  function isMediaUrl(u) {
    if (!u || typeof u !== 'string') return false;
    if (u.indexOf('data:') === 0 || u.indexOf('javascript:') === 0 || u.indexOf('blob:') === 0) return false;
    return /\.(mp4|webm|mov|m4v|m3u8|mpd|mp3|m4a|ogg)(\?|#|$)/i.test(u);
  }
  function classifyEl(el, url) {
    var ctx = '';
    var p = el;
    var depth = 0;
    while (p && depth < 6) {
      ctx += ' ' + (p.id || '') + ' ' + (typeof p.className === 'string' ? p.className : '') + ' ' +
        (p.getAttribute ? (p.getAttribute('aria-label') || '') + ' ' + (p.getAttribute('data-testid') || '') : '');
      if (p.tagName === 'ASIDE') ctx += ' aside ';
      if (p.hasAttribute && (p.hasAttribute('data-ad') || p.hasAttribute('data-ad-slot'))) ctx += ' data_ad ';
      p = p.parentElement;
      depth++;
    }
    var lowCtx = ctx.toLowerCase();
    var lowUrl = (url || '').toLowerCase();
    var isAdUrl = /(\/ads\/|\/ad\/|preroll|pre-roll|midroll|imasdk|doubleclick|googlesyndication|vmap|\/vast|adserver|sponsor|bumper)/i.test(lowUrl);
    var isAdDom = /\b(ad-player|preroll|pre-roll|ima-ad|vast|vmap|ad-container|advertisement|sponsored|data_ad)\b/i.test(lowCtx);
    var isSugUrl = /(suggest|recommend|related|upnext|up-next|preview_clip|thumb_video)/i.test(lowUrl);
    var isSugDom = /(suggest|recommend|related|upnext|up-next|sidebar|playlist|aside)/i.test(lowCtx);
    var rect = el && el.getBoundingClientRect ? el.getBoundingClientRect() : { width: 0, height: 0 };
    var durMs = el && Number.isFinite(el.duration) && el.duration > 0 ? Math.round(el.duration * 1000) : null;
    var w = Math.round(rect.width || 0);
    var h = Math.round(rect.height || 0);
    return {
      durationMs: durMs,
      width: w,
      height: h,
      muted: Boolean(el && el.muted),
      hasControls: Boolean(el && el.controls),
      isAd: isAdUrl || isAdDom || (durMs !== null && durMs > 0 && durMs <= 15000 && Boolean(el && el.muted) && !Boolean(el && el.controls)),
      isSuggested: isSugUrl || isSugDom || (w > 0 && w < 250 && h > 0 && h < 150)
    };
  }
  function emitItems(items) {
    var fresh = [];
    for (var i = 0; i < items.length; i++) {
      var it = items[i];
      if (!it || !it.url) continue;
      var key = it.url + '|' + (it.durationMs || 0) + '|' + (it.isAd ? 1 : 0) + '|' + (it.isSuggested ? 1 : 0) + '|' + (it.width || 0);
      if (SEEN[key]) continue;
      SEEN[key] = true;
      fresh.push(it);
    }
    if (fresh.length > 0) report('media-dom', { pageUrl: pageUrl(), items: fresh });
  }
  function pushCandidate(list, rawUrl, tag, type, visible, meta) {
    if (!rawUrl || rawUrl.indexOf('data:') === 0 || rawUrl.indexOf('javascript:') === 0) return;
    try {
      var abs = new URL(rawUrl, pageUrl()).href;
      if (!/^https?:/i.test(abs)) return;
      var entry = { url: abs, tag: tag || 'video', type: type || '', visible: !!visible };
      if (meta) {
        entry.durationMs = meta.durationMs;
        entry.width = meta.width;
        entry.height = meta.height;
        entry.muted = meta.muted;
        entry.hasControls = meta.hasControls;
        entry.isAd = meta.isAd;
        entry.isSuggested = meta.isSuggested;
      }
      list.push(entry);
    } catch (e) {}
  }
  function scanMedia() {
    var items = [];
    try {
      document.querySelectorAll('video, audio').forEach(function(el) {
        var src = el.currentSrc || el.src || el.getAttribute('src') || el.getAttribute('data-src') || el.getAttribute('data-video-src');
        if (src) {
          var meta = classifyEl(el, src);
          pushCandidate(items, src, el.tagName.toLowerCase(), el.getAttribute('type') || (el.tagName === 'AUDIO' ? 'audio/mpeg' : 'video/mp4'), meta.width >= 200 && meta.height >= 110, meta);
        }
        el.querySelectorAll('source').forEach(function(s) {
          var ssrc = s.getAttribute('src') || s.getAttribute('data-src');
          if (ssrc) pushCandidate(items, ssrc, el.tagName.toLowerCase(), s.getAttribute('type') || '', false, classifyEl(el, ssrc));
        });
      });
    } catch (e) {}
    try {
      var res = (window.performance && performance.getEntriesByType) ? (performance.getEntriesByType('resource') || []) : [];
      for (var i = 0; i < res.length; i++) {
        if (isMediaUrl(res[i].name)) pushCandidate(items, res[i].name, 'video', '', false, null);
      }
    } catch (e) {}
    emitItems(items);
  }
  if (window.fetch) {
    var origFetch = window.fetch;
    window.fetch = function(input) {
      try {
        var u = typeof input === 'string' ? input : (input && input.url);
        if (isMediaUrl(u)) emitItems([{ url: new URL(u, pageUrl()).href, tag: 'video', type: '', visible: false }]);
      } catch (e) {}
      return origFetch.apply(this, arguments);
    };
  }
  if (window.XMLHttpRequest) {
    var origOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(method, url) {
      try {
        if (typeof url === 'string' && isMediaUrl(url)) {
          emitItems([{ url: new URL(url, pageUrl()).href, tag: 'video', type: '', visible: false }]);
        }
        registerFeed(url);
      } catch (e) {}
      return origOpen.apply(this, arguments);
    };
  }

  // ------------------------------------------------------------------ MSE / blob: mapping

  // Streams played through MediaSource (YouTube-like players) only ever expose a blob: URL to
  // the DOM, so the page-side script keeps a short history of the URLs that actually feed the
  // SourceBuffers and reports them together with the buffer mimeTypes when a video plays a blob.
  var FEEDS = [];
  var FEED_RE = /\.(m4s|ts|cmfv|cmfa|mp4|webm|aac|ac3|m3u8|mpd)(\?|#|$)/i;
  var seenFeed = Object.create(null);
  function registerFeed(rawUrl) {
    try {
      if (!rawUrl || typeof rawUrl !== 'string') return;
      if (rawUrl.indexOf('data:') === 0 || rawUrl.indexOf('blob:') === 0) return;
      var abs = new URL(rawUrl, pageUrl()).href;
      if (!/^https?:/i.test(abs)) return;
      if (!FEED_RE.test(abs) && !/(segment|chunk|frag|videoplayback|init)/i.test(abs)) return;
      if (seenFeed[abs]) return;
      seenFeed[abs] = true;
      FEEDS.push(abs);
      if (FEEDS.length > 40) FEEDS.shift();
    } catch (e) {}
  }
  if (window.fetch) {
    var origFetchInner = window.fetch;
    window.fetch = function(input) {
      try {
        var u = typeof input === 'string' ? input : (input && input.url);
        registerFeed(u);
      } catch (e) {}
      return origFetchInner.apply(this, arguments);
    };
  }
  var origOpenInner = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(method, url) {
    try { registerFeed(url); } catch (e) {}
    return origOpenInner.apply(this, arguments);
  };

  var BLOB_TO_MS = Object.create(null);
  if (window.URL && URL.createObjectURL && window.MediaSource) {
    var origCreate = URL.createObjectURL;
    URL.createObjectURL = function(obj) {
      var out = origCreate.apply(this, arguments);
      try {
        if (obj instanceof MediaSource) {
          BLOB_TO_MS[out] = { mimes: [], feedUrls: [] };
          var ms = obj;
          var origAdd = ms.addSourceBuffer ? ms.addSourceBuffer.bind(ms) : null;
          if (origAdd) {
            ms.addSourceBuffer = function(mime) {
              var sb = origAdd.apply(this, arguments);
              try {
                var info = BLOB_TO_MS[out];
                if (info && info.mimes.indexOf(mime) < 0) info.mimes.push(mime);
                if (sb && sb.appendBuffer) {
                  var origAppend = sb.appendBuffer.bind(sb);
                  sb.appendBuffer = function(data) {
                    try {
                      var latest = FEEDS[FEEDS.length - 1];
                      var i2 = BLOB_TO_MS[out];
                      if (i2 && latest && i2.feedUrls.indexOf(latest) < 0 && i2.feedUrls.length < 12) {
                        i2.feedUrls.push(latest);
                      }
                    } catch (e) {}
                    return origAppend.apply(this, arguments);
                  };
                }
              } catch (e) {}
              return sb;
            };
          }
        }
      } catch (e) {}
      return out;
    };
  }
  function reportMse(blobUrl) {
    try {
      var info = BLOB_TO_MS[blobUrl];
      if (!info || info.reported) return;
      info.reported = true;
      var feeds = [];
      var urls = info.feedUrls.length > 0 ? info.feedUrls : FEEDS.slice(-8);
      for (var i = 0; i < urls.length && feeds.length < 12; i++) {
        feeds.push({ url: urls[i] });
      }
      if (feeds.length > 0) {
        report('mse-media', { pageUrl: pageUrl(), blobUrl: blobUrl, mimes: info.mimes, feeds: feeds });
      }
    } catch (e) {}
  }

  function startObserver() {
    scanMedia();
    if (window.MutationObserver && document.body) {
      var timer = null;
      var obs = new MutationObserver(function() {
        if (timer) return;
        timer = setTimeout(function() { timer = null; scanMedia(); }, 400);
      });
      obs.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['src'] });
    }
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', startObserver);
  } else {
    startObserver();
  }
  window.addEventListener('load', scanMedia);
  function handlePlaybackEvent(e) {
    var t = e.target;
    if (!t || (t.tagName !== 'VIDEO' && t.tagName !== 'AUDIO')) return;
    var src = t.currentSrc || t.src;
    if (!src) return;
    try {
      var abs = new URL(src, pageUrl()).href;
      if (abs.indexOf('blob:') === 0) {
        // MSE-backed playback: map the blob to the URLs that feed its SourceBuffers.
        reportMse(abs);
        return;
      }
      if (!/^https?:/i.test(abs)) return;
      var meta = classifyEl(t, abs);
      var isShortPreRoll = meta.durationMs !== null && meta.durationMs > 0 && meta.durationMs < 16000;
      emitItems([{
        url: abs,
        tag: t.tagName.toLowerCase(),
        type: '',
        visible: !meta.isAd && !meta.isSuggested && !isShortPreRoll,
        durationMs: meta.durationMs,
        width: meta.width,
        height: meta.height,
        muted: meta.muted,
        hasControls: meta.hasControls,
        isAd: meta.isAd,
        isSuggested: meta.isSuggested
      }]);
      if (!meta.isAd && !meta.isSuggested && !isShortPreRoll) {
        report('visible-media', { pageUrl: pageUrl(), mediaUrl: abs });
      }
    } catch (err) {}
  }
  document.addEventListener('loadedmetadata', handlePlaybackEvent, true);
  document.addEventListener('durationchange', handlePlaybackEvent, true);
  document.addEventListener('playing', handlePlaybackEvent, true);
  document.addEventListener('play', handlePlaybackEvent, true);
})();
""".trimIndent()
}
