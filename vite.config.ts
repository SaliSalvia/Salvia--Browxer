import type { IncomingMessage, ServerResponse } from 'node:http';
import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

const DEFAULT_USER_AGENT_MOBILE =
  'Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36';
const DEFAULT_USER_AGENT_DESKTOP =
  'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36';

const BLOCKED_HOSTS = new Set([
  'doubleclick.net',
  'googleadservices.com',
  'googlesyndication.com',
  'googletagmanager.com',
  'google-analytics.com',
  'adservice.google.com',
  'adnxs.com',
  'adsrvr.org',
  'amazon-adsystem.com',
  'criteo.com',
  'criteo.net',
  'openx.net',
  'pubmatic.com',
  'rubiconproject.com',
  'taboola.com',
  'outbrain.com',
  'scorecardresearch.com',
  'quantserve.com',
  'hotjar.com',
  'mixpanel.com',
  'amplitude.com',
  'segment.io',
  'sentry.io',
]);

function isPrivateOrLocalHostname(hostname: string): boolean {
  const host = hostname.toLowerCase().replace(/^\[|\]$/g, '');
  if (
    host === 'localhost' ||
    host === '127.0.0.1' ||
    host === '::1' ||
    host === '0.0.0.0' ||
    host.endsWith('.local')
  ) {
    return true;
  }
  if (
    host.startsWith('10.') ||
    host.startsWith('192.168.') ||
    host.startsWith('169.254.')
  ) {
    return true;
  }
  if (host.startsWith('172.')) {
    const second = Number(host.split('.')[1]);
    if (Number.isFinite(second) && second >= 16 && second <= 31) return true;
  }
  return false;
}

function validateRemoteUrl(rawUrl: string | null): URL | null {
  if (!rawUrl) return null;
  try {
    const parsed = new URL(rawUrl);
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return null;
    if (parsed.username || parsed.password) return null;
    if (isPrivateOrLocalHostname(parsed.hostname)) return null;
    return parsed;
  } catch {
    return null;
  }
}

function hostMatchesBlocked(hostname: string): boolean {
  const h = hostname.toLowerCase();
  for (const domain of BLOCKED_HOSTS) {
    if (h === domain || h.endsWith(`.${domain}`)) return true;
  }
  return false;
}

function injectBrowserBridge(html: string, finalUrl: string, adBlock: boolean): { html: string; blockedCount: number } {
  let blockedCount = 0;
  let cleaned = html
    .replace(/<meta[^>]+http-equiv=["']?Content-Security-Policy["']?[^>]*>/gi, '')
    .replace(/<meta[^>]+http-equiv=["']?X-Frame-Options["']?[^>]*>/gi, '');

  if (adBlock) {
    cleaned = cleaned.replace(/<script\b[^>]*\bsrc=["']([^"']+)["'][^>]*>[\s\S]*?<\/script>/gi, (match, src) => {
      try {
        const u = new URL(src, finalUrl);
        if (hostMatchesBlocked(u.hostname)) {
          blockedCount++;
          return '<!-- blocked by SalviaBrowxer -->';
        }
      } catch {
        // ignore invalid src
      }
      return match;
    });
  }

  const baseTag = `<base href="${finalUrl.replace(/"/g, '&quot;')}">`;
  const bridgeScript = `
<script>
(function() {
  var PAGE_URL = ${JSON.stringify(finalUrl)};
  var SEEN = Object.create(null);
  function report(type, payload) {
    try { window.parent.postMessage({ source: 'salvia-browser-bridge', type: type, payload: payload }, '*'); } catch (e) {}
  }
  function isMediaUrl(u) {
    if (!u || typeof u !== 'string' || u.startsWith('data:') || u.startsWith('javascript:')) return false;
    return /\.(mp4|webm|mov|m4v|m3u8|mpd|mp3|m4a|aac|ogg)(\?|#|$)/i.test(u);
  }
  function classifyEl(el, url) {
    var ctx = '';
    var p = el;
    var depth = 0;
    while (p && depth < 6) {
      ctx += ' ' + (p.id || '') + ' ' + (p.className || '') + ' ' + (p.getAttribute ? (p.getAttribute('aria-label') || '') + ' ' + (p.getAttribute('data-testid') || '') : '');
      if (p.tagName === 'ASIDE') ctx += ' aside ';
      if (p.hasAttribute && (p.hasAttribute('data-ad') || p.hasAttribute('data-ad-slot'))) ctx += ' data_ad ';
      p = p.parentElement;
      depth++;
    }
    var lowCtx = ctx.toLowerCase();
    var lowUrl = (url || '').toLowerCase();
    var isAdUrl = /(\\/ads\\/|\\/ad\\/|preroll|pre-roll|midroll|imasdk|doubleclick|googlesyndication|vmap|\\/vast|adserver|sponsor|bumper)/i.test(lowUrl);
    var isAdDom = /\\b(ad-player|preroll|pre-roll|ima-ad|vast|vmap|ad-container|advertisement|sponsored|data_ad)\\b/i.test(lowCtx);
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
    if (fresh.length > 0) report('media-dom', { pageUrl: PAGE_URL, items: fresh });
  }
  function pushCandidate(list, rawUrl, tag, type, visible, meta) {
    if (!rawUrl || rawUrl.startsWith('data:') || rawUrl.startsWith('javascript:')) return;
    try {
      var abs = new URL(rawUrl, PAGE_URL).href;
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
    if (window.performance && performance.getEntriesByType) {
      try {
        var res = performance.getEntriesByType('resource') || [];
        for (var i = 0; i < res.length; i++) {
          if (isMediaUrl(res[i].name)) pushCandidate(items, res[i].name, 'video', '', false, null);
        }
      } catch (e) {}
    }
    emitItems(items);
  }
  if (window.fetch) {
    var origFetch = window.fetch;
    window.fetch = function(input) {
      var u = typeof input === 'string' ? input : (input && input.url);
      if (isMediaUrl(u)) emitItems([{ url: new URL(u, PAGE_URL).href, tag: 'video', type: '', visible: false }]);
      return origFetch.apply(this, arguments).then(function(resp) {
        try {
          var ct = (resp.headers && resp.headers.get('content-type')) || '';
          if (ct.indexOf('video/') === 0 || ct.indexOf('audio/') === 0 || ct.indexOf('mpegurl') !== -1 || ct.indexOf('dash+xml') !== -1) {
            emitItems([{ url: resp.url || u, tag: ct.indexOf('audio/') === 0 ? 'audio' : 'video', type: ct, visible: false }]);
          }
        } catch (e) {}
        return resp;
      });
    };
  }
  if (window.XMLHttpRequest) {
    var origOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(method, url) {
      try {
        if (typeof url === 'string' && isMediaUrl(url)) {
          emitItems([{ url: new URL(url, PAGE_URL).href, tag: 'video', type: '', visible: false }]);
        }
      } catch (e) {}
      return origOpen.apply(this, arguments);
    };
  }
  if (window.MutationObserver) {
    var obs = new MutationObserver(function() { scanMedia(); });
    window.addEventListener('DOMContentLoaded', function() {
      scanMedia();
      if (document.body) obs.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['src'] });
    });
  } else {
    window.addEventListener('DOMContentLoaded', scanMedia);
  }
  window.addEventListener('load', scanMedia);
  function handlePlaybackEvent(e) {
    var t = e.target;
    if (t && (t.tagName === 'VIDEO' || t.tagName === 'AUDIO')) {
      var src = t.currentSrc || t.src;
      if (src) {
        try {
          var abs = new URL(src, PAGE_URL).href;
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
            report('visible-media', { pageUrl: PAGE_URL, mediaUrl: abs });
          }
        } catch(err) {}
      }
    }
  }
  document.addEventListener('loadedmetadata', handlePlaybackEvent, true);
  document.addEventListener('durationchange', handlePlaybackEvent, true);
  document.addEventListener('playing', handlePlaybackEvent, true);
  document.addEventListener('play', handlePlaybackEvent, true);
  document.addEventListener('click', function(e) {
    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (a && a.href && !a.href.startsWith('javascript:')) {
      e.preventDefault();
      report('navigate', { url: a.href });
    }
  }, true);
  window.addEventListener('message', function(ev) {
    if (!ev.data || ev.data.target !== 'salvia-iframe') return;
    if (ev.data.type === 'find') {
      var q = (ev.data.query || '').trim();
      if (!q) { report('find-result', { matches: 0, active: 0 }); return; }
      var text = document.body ? (document.body.innerText || '') : '';
      var lower = text.toLowerCase();
      var needle = q.toLowerCase();
      var count = 0, idx = 0;
      while ((idx = lower.indexOf(needle, idx)) !== -1) { count++; idx += needle.length; }
      if (window.find && count > 0) {
        window.find(q, false, !!ev.data.backwards, true, false, true, false);
      }
      report('find-result', { matches: count, active: count > 0 ? 1 : 0 });
    }
  });
})();
</script>`;

  if (/<head[^>]*>/i.test(cleaned)) {
    cleaned = cleaned.replace(/<head([^>]*)>/i, `<head$1>${baseTag}${bridgeScript}`);
  } else {
    cleaned = `${baseTag}${bridgeScript}${cleaned}`;
  }

  return { html: cleaned, blockedCount };
}

function sendJson(res: ServerResponse, status: number, body: unknown) {
  res.statusCode = status;
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.end(JSON.stringify(body));
}

function salviaMiddlewarePlugin(): Plugin {
  return {
    name: 'salvia-browser-middleware',
    configureServer(server) {
      server.middlewares.use(async (req: IncomingMessage, res: ServerResponse, next) => {
        const reqUrl = req.url || '';
        if (!reqUrl.startsWith('/api/')) {
          return next();
        }

        const parsedReq = new URL(reqUrl, 'http://localhost');

        if (parsedReq.pathname === '/api/browse') {
          const target = validateRemoteUrl(parsedReq.searchParams.get('url'));
          if (!target) {
            return sendJson(res, 400, { error: 'Invalid or restricted URL' });
          }
          const desktop = parsedReq.searchParams.get('desktop') === '1';
          const adBlock = parsedReq.searchParams.get('adblock') === '1';
          try {
            const controller = new AbortController();
            const timer = setTimeout(() => controller.abort(), 12000);
            const response = await fetch(target.toString(), {
              headers: {
                'User-Agent': desktop ? DEFAULT_USER_AGENT_DESKTOP : DEFAULT_USER_AGENT_MOBILE,
                Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
              },
              redirect: 'follow',
              signal: controller.signal,
            });
            clearTimeout(timer);

            const contentType = response.headers.get('content-type') || '';
            const finalUrl = response.url || target.toString();

            if (!response.ok) {
              return sendJson(res, 200, {
                ok: false,
                status: response.status,
                statusText: response.statusText,
                finalUrl,
              });
            }

            if (contentType.includes('text/html') || contentType.includes('application/xhtml+xml') || contentType === '') {
              const rawHtml = await response.text();
              const titleMatch = rawHtml.match(/<title[^>]*>([^<]*)<\/title>/i);
              const title = titleMatch ? titleMatch[1].trim() : target.hostname;
              const { html, blockedCount } = injectBrowserBridge(rawHtml, finalUrl, adBlock);
              return sendJson(res, 200, {
                ok: true,
                finalUrl,
                title,
                contentType,
                blockedCount,
                html,
              });
            }

            return sendJson(res, 200, {
              ok: true,
              finalUrl,
              title: finalUrl.split('/').pop() || target.hostname,
              contentType,
              blockedCount: 0,
              isDirectMedia:
                contentType.startsWith('video/') ||
                contentType.startsWith('audio/') ||
                contentType.includes('mpegurl') ||
                contentType.includes('dash+xml'),
              contentLength: Number(response.headers.get('content-length') || 0) || null,
            });
          } catch (err) {
            return sendJson(res, 200, {
              ok: false,
              error: err instanceof Error ? err.message : 'Network error',
              finalUrl: target.toString(),
            });
          }
        }

        if (parsedReq.pathname === '/api/media/probe') {
          const target = validateRemoteUrl(parsedReq.searchParams.get('url'));
          if (!target) {
            return sendJson(res, 400, { error: 'Invalid or restricted URL' });
          }
          try {
            const controller = new AbortController();
            const timer = setTimeout(() => controller.abort(), 10000);
            let response = await fetch(target.toString(), {
              method: 'HEAD',
              headers: { 'User-Agent': DEFAULT_USER_AGENT_MOBILE },
              redirect: 'follow',
              signal: controller.signal,
            });
            if (!response.ok || response.status === 405) {
              response = await fetch(target.toString(), {
                method: 'GET',
                headers: {
                  'User-Agent': DEFAULT_USER_AGENT_MOBILE,
                  Range: 'bytes=0-0',
                },
                redirect: 'follow',
                signal: controller.signal,
              });
            }
            clearTimeout(timer);

            const contentType = response.headers.get('content-type') || '';
            const contentDisposition = response.headers.get('content-disposition') || '';
            const contentRange = response.headers.get('content-range') || '';
            let size: number | null = Number(response.headers.get('content-length') || 0) || null;
            if (contentRange.includes('/')) {
              const totalPart = Number(contentRange.split('/')[1]);
              if (Number.isFinite(totalPart) && totalPart > 0) size = totalPart;
            }
            const acceptRanges = (response.headers.get('accept-ranges') || '').toLowerCase() === 'bytes' || response.status === 206;

            let manifestText: string | null = null;
            const lowerUrl = target.toString().toLowerCase();
            if (
              lowerUrl.includes('.m3u8') ||
              lowerUrl.includes('.mpd') ||
              contentType.includes('mpegurl') ||
              contentType.includes('dash+xml')
            ) {
              const mRes = await fetch(target.toString(), {
                headers: { 'User-Agent': DEFAULT_USER_AGENT_MOBILE },
              });
              if (mRes.ok) {
                manifestText = await mRes.text();
              }
            }

            return sendJson(res, 200, {
              ok: true,
              finalUrl: response.url || target.toString(),
              contentType,
              contentDisposition,
              size,
              acceptRanges,
              manifestText,
            });
          } catch (err) {
            return sendJson(res, 200, {
              ok: false,
              error: err instanceof Error ? err.message : 'Probe failed',
            });
          }
        }

        if (parsedReq.pathname === '/api/media/extract') {
          const target = validateRemoteUrl(parsedReq.searchParams.get('url'));
          if (!target) {
            return sendJson(res, 400, { error: 'Invalid or restricted URL' });
          }
          const collectUrls = (html: string, baseUrl: string, out: Map<string, string>) => {
            const unescaped = html
              .replace(/\\u002F/gi, '/')
              .replace(/\\\//g, '/')
              .replace(/&amp;/g, '&');
            const directMatches =
              unescaped.match(/https?:\/\/[^\s"'<>\\]+\.(?:mp4|webm|mov|m4v|m3u8|mpd|mp3|m4a)(?:\?[^\s"'<>\\]*)?/gi) || [];
            for (const u of directMatches) {
              if (!out.has(u)) out.set(u, 'JS');
            }
            const keyRegex =
              /"(?:contentUrl|playbackUrl|playable_url(?:_quality_hd)?|video_url|hd_src|sd_src|hls_url|file)"\s*:\s*"(https?:\/\/[^"]+)"/gi;
            let km: RegExpExecArray | null;
            while ((km = keyRegex.exec(unescaped)) !== null) {
              if (!out.has(km[1])) out.set(km[1], 'DOM');
            }
            const tagRegex = /<(?:source|video|audio)\b[^>]+?\bsrc=["']([^"']+)["']/gi;
            let tm: RegExpExecArray | null;
            while ((tm = tagRegex.exec(html)) !== null) {
              try {
                const abs = new URL(tm[1], baseUrl).href;
                if (abs.startsWith('http') && !out.has(abs)) out.set(abs, 'DOM');
              } catch {
                // ignore
              }
            }
            const ogRegex =
              /<meta[^>]+(?:property=["']og:video(?::url)?["']|name=["']twitter:player:stream["'])[^>]+content=["']([^"']+)["']/gi;
            let om: RegExpExecArray | null;
            while ((om = ogRegex.exec(html)) !== null) {
              try {
                const abs = new URL(om[1], baseUrl).href;
                if (abs.startsWith('http') && !out.has(abs)) out.set(abs, 'DOM');
              } catch {
                // ignore
              }
            }
          };

          try {
            const controller = new AbortController();
            const timer = setTimeout(() => controller.abort(), 12000);
            const response = await fetch(target.toString(), {
              headers: {
                'User-Agent': DEFAULT_USER_AGENT_MOBILE,
                Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
              },
              redirect: 'follow',
              signal: controller.signal,
            });
            clearTimeout(timer);
            if (!response.ok) {
              return sendJson(res, 200, { ok: false, status: response.status, error: `HTTP ${response.status}` });
            }
            const ct = response.headers.get('content-type') || '';
            const finalUrl = response.url || target.toString();
            if (ct.startsWith('video/') || ct.startsWith('audio/') || ct.includes('mpegurl') || ct.includes('dash+xml')) {
              return sendJson(res, 200, {
                ok: true,
                finalUrl,
                title: finalUrl.split('/').pop() || target.hostname,
                items: [{ url: finalUrl, mimeType: ct, source: 'FETCH' }],
              });
            }

            const rawHtml = await response.text();
            const titleMatch = rawHtml.match(/<title[^>]*>([^<]*)<\/title>/i);
            const title = titleMatch ? titleMatch[1].trim() : new URL(finalUrl).hostname;
            const thumbMatch = rawHtml.match(
              /<meta[^>]+(?:property=["']og:image["']|name=["']twitter:image["'])[^>]+content=["']([^"']+)["']/i
            );
            let thumbnail: string | null = null;
            if (thumbMatch?.[1]) {
              try {
                thumbnail = new URL(thumbMatch[1], finalUrl).href;
              } catch {
                thumbnail = null;
              }
            }

            const found = new Map<string, string>();
            collectUrls(rawHtml, finalUrl, found);

            const iframeMatches: string[] = [];
            const iframeRegex = /<iframe\b[^>]+?\bsrc=["']([^"']+)["']/gi;
            let im: RegExpExecArray | null;
            while ((im = iframeRegex.exec(rawHtml)) !== null && iframeMatches.length < 2) {
              try {
                const resolvedIframe = validateRemoteUrl(new URL(im[1], finalUrl).href);
                if (resolvedIframe && !iframeMatches.includes(resolvedIframe.toString())) {
                  iframeMatches.push(resolvedIframe.toString());
                }
              } catch {
                // ignore
              }
            }
            for (const iframeUrl of iframeMatches) {
              try {
                const ir = await fetch(iframeUrl, {
                  headers: { 'User-Agent': DEFAULT_USER_AGENT_MOBILE, Referer: finalUrl },
                  redirect: 'follow',
                });
                if (ir.ok) {
                  collectUrls(await ir.text(), ir.url || iframeUrl, found);
                }
              } catch {
                // ignore iframe error
              }
            }

            const items = Array.from(found.entries()).map(([url, source]) => ({ url, source }));
            return sendJson(res, 200, { ok: true, finalUrl, title, thumbnail, items });
          } catch (err) {
            return sendJson(res, 200, {
              ok: false,
              error: err instanceof Error ? err.message : 'Extract failed',
            });
          }
        }

        if (parsedReq.pathname === '/api/media/fetch') {
          const target = validateRemoteUrl(parsedReq.searchParams.get('url'));
          if (!target) {
            return sendJson(res, 400, { error: 'Invalid or restricted URL' });
          }
          try {
            const upstreamHeaders: Record<string, string> = {
              'User-Agent': DEFAULT_USER_AGENT_MOBILE,
            };
            if (req.headers.range) {
              upstreamHeaders.Range = String(req.headers.range);
            }
            const upstream = await fetch(target.toString(), {
              headers: upstreamHeaders,
              redirect: 'follow',
            });
            res.statusCode = upstream.status;
            const ct = upstream.headers.get('content-type');
            const cl = upstream.headers.get('content-length');
            const cr = upstream.headers.get('content-range');
            const ar = upstream.headers.get('accept-ranges');
            if (ct) res.setHeader('Content-Type', ct);
            if (cl) res.setHeader('Content-Length', cl);
            if (cr) res.setHeader('Content-Range', cr);
            if (ar) res.setHeader('Accept-Ranges', ar);
            if (!upstream.body) {
              res.end();
              return;
            }
            const reader = upstream.body.getReader();
            while (true) {
              const { done, value } = await reader.read();
              if (done) break;
              if (value) res.write(Buffer.from(value));
            }
            res.end();
            return;
          } catch (err) {
            return sendJson(res, 502, {
              error: err instanceof Error ? err.message : 'Fetch proxy failed',
            });
          }
        }

        next();
      });
    },
  };
}

export default defineConfig({
  base: './',
  plugins: [react(), tailwindcss(), salviaMiddlewarePlugin()],
  server: {
    host: '0.0.0.0',
    port: 3000,
    strictPort: true,
  },
  preview: {
    host: '0.0.0.0',
    port: 3000,
    strictPort: true,
  },
});
