import type { MediaCandidate } from '../types/models';
import {
  AUDIO_EXTENSIONS,
  DASH_EXTENSION,
  DASH_MIME_TYPE,
  MEDIA_EXTENSIONS,
  hasMediaExtension,
  isDashUrl,
  isPlaylistUrl,
  pathExtension,
} from './mediaUrlRules';

export type PastedLinkKind = 'MEDIA_FILE' | 'WEB_PAGE';

const PAGE_HOSTS = new Set([
  'instagram.com',
  'tiktok.com',
  'youtube.com',
  'youtu.be',
  'facebook.com',
  'x.com',
]);

function parseWebUri(url: string): URL | null {
  try {
    const parsed = new URL(url.trim());
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return null;
    if (!parsed.hostname || parsed.username || parsed.password) return null;
    return parsed;
  } catch {
    return null;
  }
}

export function classifyPastedLink(url: string): PastedLinkKind | null {
  const parsed = parseWebUri(url);
  if (!parsed) return null;
  return isPastedMediaFile(parsed.toString()) ? 'MEDIA_FILE' : 'WEB_PAGE';
}

export function isPastedMediaFile(url: string): boolean {
  const parsed = parseWebUri(url);
  if (!parsed) return false;
  const host = parsed.hostname.toLowerCase().replace(/\.+$/, '');
  for (const pageHost of PAGE_HOSTS) {
    if (host === pageHost || host.endsWith(`.${pageHost}`)) return false;
  }
  const normalized = parsed.toString();
  return hasMediaExtension(normalized) || isDashUrl(normalized) || isPlaylistUrl(normalized);
}

function titleFromUrl(url: string): string | null {
  const clean = url.split('#')[0].split('?')[0];
  const last = clean.slice(clean.lastIndexOf('/') + 1);
  const dot = last.lastIndexOf('.');
  const base = dot > 0 ? last.slice(0, dot) : last;
  return base.trim().length > 2 ? base.trim() : null;
}

export function pastedMediaCandidate(url: string): MediaCandidate | null {
  if (classifyPastedLink(url) !== 'MEDIA_FILE') return null;
  const rawExt = pathExtension(url);
  const extension =
    rawExt && (MEDIA_EXTENSIONS.has(rawExt) || rawExt === DASH_EXTENSION)
      ? rawExt
      : isPlaylistUrl(url)
        ? 'm3u8'
        : null;
  if (!extension) return null;

  let mimeType: string;
  if (extension === DASH_EXTENSION) {
    mimeType = DASH_MIME_TYPE;
  } else if (extension === 'm3u8' || extension === 'ts') {
    mimeType = 'application/vnd.apple.mpegurl';
  } else if (AUDIO_EXTENSIONS.has(extension)) {
    mimeType = `audio/${extension}`;
  } else {
    mimeType = `video/${extension}`;
  }

  return {
    id: `pasted-${url}`,
    pageUrl: url,
    mediaUrl: url,
    title: titleFromUrl(url),
    mimeType,
    extension,
    source: 'JS',
    confidence: 0.8,
  };
}
