import { DEFAULT_SEARCH_ENGINE, SEARCH_ENGINES } from './constants';
import type { SearchEngineKey } from '../types/models';

const EXPLICIT_SCHEME_PREFIXES = [
  'http://',
  'https://',
];

/** Schemes the engine cannot navigate to; typing one of these is treated as a search query. */
const BLOCKED_SCHEME_PREFIXES = [
  'javascript:',
  'data:',
  'file:',
  'ftp://',
  'ws://',
  'wss://',
  'blob:',
  'content://',
  'about:',
];

export function hasExplicitScheme(input: string): boolean {
  const lower = input.trim().toLowerCase();
  return EXPLICIT_SCHEME_PREFIXES.some((prefix) => lower.startsWith(prefix));
}

export function looksLikeHost(input: string): boolean {
  const trimmed = input.trim();
  if (!trimmed || trimmed.includes(' ') || trimmed.includes('"') || trimmed.includes('<')) {
    return false;
  }
  if (trimmed.includes(':') && !trimmed.includes('.') && !trimmed.startsWith('localhost')) {
    return false;
  }
  return trimmed.includes('.') || trimmed.startsWith('localhost') || trimmed.includes(':');
}

export function buildSearchUrl(
  query: string,
  searchEngine: SearchEngineKey = DEFAULT_SEARCH_ENGINE
): string {
  const template = SEARCH_ENGINES[searchEngine] ?? SEARCH_ENGINES[DEFAULT_SEARCH_ENGINE];
  return template.replace('%s', encodeURIComponent(query.trim()));
}

export function resolveAddressBarInput(
  input: string,
  searchEngine: SearchEngineKey = DEFAULT_SEARCH_ENGINE
): string | null {
  const trimmed = input.trim();
  if (!trimmed) return null;
  const lower = trimmed.toLowerCase();
  // Schemes the native engine refuses (its bridge only accepts http/https) would otherwise
  // leave the tab spinning forever with a poisoned history entry — turn them into a search.
  if (BLOCKED_SCHEME_PREFIXES.some((prefix) => lower.startsWith(prefix))) {
    return buildSearchUrl(trimmed, searchEngine);
  }
  if (hasExplicitScheme(trimmed)) return trimmed;
  return looksLikeHost(trimmed) ? `https://${trimmed}` : buildSearchUrl(trimmed, searchEngine);
}

const URL_REGEX = /https?:\/\/[^\s"'<>]+/i;

export function extractFirstSharedUrl(text: string | null | undefined): string | null {
  if (!text) return null;
  const match = text.match(URL_REGEX);
  if (!match) return null;
  const cleaned = match[0].replace(/[.,;:!?)\]\}»"'،]+$/, '');
  return cleaned.length > 'https://'.length ? cleaned : null;
}

export function normalizeLinkInput(raw: string): string | null {
  const trimmed = raw.trim();
  if (!trimmed) return null;
  const extracted = extractFirstSharedUrl(trimmed);
  if (extracted) return extracted;
  if (looksLikeHost(trimmed)) return `https://${trimmed}`;
  return null;
}
