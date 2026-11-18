import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import {
  APP_VERSION,
  BUILTIN_SHOWCASE_PAGES,
  DEFAULT_SETTINGS,
  MAX_LIVE_TABS,
  RELEASES_API_URL,
  WIFI_HOLD_MARKER,
} from '../core/constants';
import {
  cancelTransfer,
  getCompletedBlobUrl,
  pauseTransfer,
  releaseBlobUrl,
  resumeNativeTransfer,
  setUnmatchedNativeDownloadHandler,
  startTransfer,
  triggerBrowserFileSave,
} from '../core/downloadEngine';
import {
  extractMediaFromPageUrl,
  initialMediaInfoForCandidate,
  probeMediaCandidate,
  sanitizeFilename,
  scanHtmlForMedia,
} from '../core/mediaResolver';
import {
  rankAndClassifyCandidates,
  type ClassifiedCandidate,
} from '../core/mainContentSelector';
import { unsupportedReasonFor, type UnsupportedMedia } from '../core/mediaOfferability';
import { classifyPastedLink, pastedMediaCandidate } from '../core/pastedLink';
import { resolveAddressBarInput } from '../core/addressBarResolver';
import { isBlockedAdHost, hostOfUrl } from '../core/adBlockRules';
import {
  LockoutPolicy,
  isPasswordConfigured,
  removeLockedFolderPassword,
  setLockedFolderPassword,
  verifyLockedFolderPassword,
} from '../core/security';
import {
  apiUrl,
  getSalviaNative,
  isNativeAndroid,
  registerNativeBackHandler,
  isNativeBrowserAvailable,
  registerNativeBrowserListener,
  type NativeBrowserEvent,
} from '../core/nativeBridge';
import { getTranslator, resolveActiveLocale, type TranslationMap } from '../i18n/translations';
import type {
  AddressSuggestion,
  AppRoute,
  AppSettings,
  BookmarkItem,
  DiagnosticsEntry,
  DownloadItemRecord,
  HistoryItem,
  MediaCandidate,
  MediaFormat,
  MediaInfo,
  Tab,
} from '../types/models';

const STORAGE_KEYS = {
  settings: 'salvia_settings_v1',
  bookmarks: 'salvia_bookmarks_v1',
  history: 'salvia_history_v1',
  downloads: 'salvia_downloads_v1',
  tabs: 'salvia_session_tabs_v1',
  currentTab: 'salvia_session_current_tab_v1',
  diagnostics: 'salvia_diagnostics_v1',
};

/** Per-tab history cap: unbounded stacks grow the persisted session without limit. */
const MAX_HISTORY_STACK = 200;

function loadJson<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return fallback;
    return JSON.parse(raw) as T;
  } catch {
    return fallback;
  }
}

function makeId(prefix: string): string {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`;
}

export interface TabPageRenderState {
  url: string;
  title: string;
  html: string | null;
  isLoading: boolean;
  progress: number;
  error: string | null;
  isDemoShowcase: boolean;
}

export interface QualitySheetState {
  candidate: MediaCandidate;
  mediaInfo: MediaInfo;
  isResolving: boolean;
  unsupported: UnsupportedMedia | 'DRM' | null;
  canRecord: boolean;
}

export interface RecordingState {
  isRecording: boolean;
  bytes: number;
}

export interface UpdateCheckState {
  isChecking: boolean;
  availableVersion: string | null;
  availableUrl: string | null;
  availableNotes: string | null;
  /** Direct download URL of the signed release APK asset, when the release has one. */
  availableApkUrl: string | null;
}

/** Parses "1.2.3"-style versions into comparable number triples. */
const parseSemver = (v: string): number[] =>
  v
    .replace(/^v/i, '')
    .split('.')
    .map((part) => parseInt(part, 10) || 0);

/** True only when `remote` is strictly newer than `local` (avoids "update!" on reinstalling an older build). */
const isNewerVersion = (remote: string, local: string): boolean => {
  const r = parseSemver(remote);
  const l = parseSemver(local);
  for (let i = 0; i < 3; i += 1) {
    if ((r[i] || 0) > (l[i] || 0)) return true;
    if ((r[i] || 0) < (l[i] || 0)) return false;
  }
  return false;
};

export interface PlayerTarget {
  url: string;
  title: string;
  downloadId?: string;
}

interface AppContextValue {
  route: AppRoute;
  downloadsInitialTab: number;
  playerTarget: PlayerTarget | null;
  navigate: (route: AppRoute, options?: { downloadsTab?: number; player?: PlayerTarget }) => void;

  locale: 'en' | 'fa';
  isRtl: boolean;
  t: (key: keyof TranslationMap, vars?: Record<string, string | number>) => string;

  settings: AppSettings;
  updateSettings: (patch: Partial<AppSettings>) => void;

  tabs: Tab[];
  currentTabId: string;
  currentTab: Tab;
  hibernatedTabIds: Set<string>;
  tabPages: Record<string, TabPageRenderState>;
  tabMedia: Record<string, MediaCandidate[]>;
  tabVisibleMedia: Record<string, string | null>;
  isTabSwitcherVisible: boolean;
  openTabSwitcher: () => void;
  closeTabSwitcher: () => void;
  createTab: (options?: { isPrivate?: boolean; url?: string }) => void;
  switchTab: (tabId: string) => void;
  closeTab: (tabId: string) => void;

  navigateCurrentTab: (input: string) => void;
  openPastedLink: (url: string) => void;
  isExtractingLink: boolean;
  extractFromPastedPage: (url: string) => Promise<void>;
  reloadCurrentTab: () => void;
  stopLoadingCurrentTab: () => void;
  goBackCurrentTab: () => void;
  goForwardCurrentTab: () => void;
  goHomeInCurrentTab: () => void;

  blockedCleartextUrl: string | null;
  allowCleartextAndRetry: () => void;
  dismissCleartextBlock: () => void;

  isAdBlockOffForCurrentSite: () => boolean;
  toggleAdBlockForCurrentSite: () => void;

  bookmarks: BookmarkItem[];
  isCurrentTabBookmarked: boolean;
  addBookmarkForCurrentTab: () => void;
  toggleBookmarkForCurrentTab: () => void;
  deleteBookmark: (id: string) => void;
  openUrlFromLibrary: (url: string) => void;
  duplicateTab: (tabId: string) => void;
  closeOtherTabs: (tabId: string) => void;
  pageZoom: number;
  setPageZoom: (zoom: number) => void;

  history: HistoryItem[];
  deleteHistoryEntry: (id: string) => void;
  clearHistory: () => void;
  clearCookiesAndStorage: () => void;
  clearAllBrowsingData: () => void;

  getAddressSuggestions: (query: string) => AddressSuggestion[];

  currentTabCandidates: MediaCandidate[];
  rankedTabCandidates: ClassifiedCandidate[];
  currentMainCandidate: MediaCandidate | null;
  isMediaTrayVisible: boolean;
  openMediaTray: () => void;
  closeMediaTray: () => void;
  qualitySheet: QualitySheetState | null;
  openQualitySheetForCandidate: (candidate: MediaCandidate) => void;
  closeQualitySheet: () => void;
  startDownloadFromFormat: (format: MediaFormat, customFilename: string | null) => void;
  startBatchDownloadFromCandidates: (candidates: MediaCandidate[]) => Promise<void>;
  downloadMainMedia: () => void;

  recording: RecordingState;
  startRecordingPlayback: () => void;
  stopRecordingPlayback: () => void;

  downloads: DownloadItemRecord[];
  activeDownloadsCount: number;
  completedDownloadsCount: number;
  pauseDownload: (id: string) => void;
  resumeDownload: (id: string) => void;
  cancelDownload: (id: string) => void;
  retryDownload: (id: string) => void;
  deleteDownload: (id: string) => void;
  clearDownloadsByBucket: (bucket: 'active' | 'completed' | 'failed') => void;
  exportDownloadToDevice: (id: string) => void;
  playDownloadInApp: (id: string) => void;
  shareDownload: (id: string) => void;
  setDownloadLocked: (id: string, locked: boolean) => void;

  isLockedFolderConfigured: boolean;
  isLockedFolderUnlocked: boolean;
  isUnlockingFolder: boolean;
  unlockLockedFolder: (password: string) => Promise<void>;
  lockLockedFolder: () => void;
  saveLockedFolderPassword: (password: string) => Promise<boolean>;
  removeLockedFolderPasswordAction: () => Promise<boolean>;

  findInPage: { query: string; matches: number; activeMatch: number } | null;
  openFindInPage: () => void;
  closeFindInPage: () => void;
  updateFindQuery: (query: string) => void;
  setFindResult: (matches: number, activeMatch: number) => void;

  reportDetectedMediaFromBridge: (
    tabId: string,
    pageUrl: string,
    items: Array<{
      url: string;
      tag: string;
      type: string;
      visible: boolean;
      label?: string | null;
      kind?: string | null;
      durationMs?: number | null;
      width?: number | null;
      height?: number | null;
      muted?: boolean;
      hasControls?: boolean;
      isAd?: boolean;
      isSuggested?: boolean;
    }>
  ) => void;
  reportVisibleMediaFromBridge: (tabId: string, pageUrl: string, mediaUrl: string) => void;

  connectionKind: 'WIFI' | 'MOBILE' | 'OTHER' | 'OFFLINE';

  updateCheck: UpdateCheckState;
  checkForUpdates: () => Promise<void>;
  /** Queues the release APK download through the normal download pipeline. */
  downloadUpdate: () => void;
  dismissUpdateDialog: () => void;

  diagnostics: DiagnosticsEntry[];
  exportDiagnostics: () => void;

  toastMessage: string | null;
  showToast: (msg: string) => void;
}

const AppContext = createContext<AppContextValue | null>(null);

export const AppProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [settings, setSettings] = useState<AppSettings>(() => ({
    ...DEFAULT_SETTINGS,
    ...loadJson<Partial<AppSettings>>(STORAGE_KEYS.settings, {}),
  }));

  const locale = resolveActiveLocale(settings.language);
  const isRtl = locale === 'fa';
  const t = useMemo(() => getTranslator(locale), [locale]);

  useEffect(() => {
    document.documentElement.lang = locale;
    document.documentElement.dir = isRtl ? 'rtl' : 'ltr';
  }, [locale, isRtl]);

  useEffect(() => {
    localStorage.setItem(STORAGE_KEYS.settings, JSON.stringify(settings));
  }, [settings]);

  const [route, setRoute] = useState<AppRoute>('home');
  const [downloadsInitialTab, setDownloadsInitialTab] = useState(0);
  const [playerTarget, setPlayerTarget] = useState<PlayerTarget | null>(null);

  const [toastMessage, setToastMessage] = useState<string | null>(null);
  const toastTimerRef = useRef<number | null>(null);
  const showToast = useCallback((msg: string) => {
    if (!msg) return;
    setToastMessage(msg);
    if (toastTimerRef.current) window.clearTimeout(toastTimerRef.current);
    toastTimerRef.current = window.setTimeout(() => setToastMessage(null), 3200);
  }, []);

  const [diagnostics, setDiagnostics] = useState<DiagnosticsEntry[]>(() =>
    loadJson<DiagnosticsEntry[]>(STORAGE_KEYS.diagnostics, [])
  );
  const logEvent = useCallback((kind: DiagnosticsEntry['kind'], host: string, event: string) => {
    setDiagnostics((prev) => {
      const next = [{ timestamp: Date.now(), kind, host, event }, ...prev].slice(0, 120);
      localStorage.setItem(STORAGE_KEYS.diagnostics, JSON.stringify(next));
      return next;
    });
  }, []);

  const [bookmarks, setBookmarks] = useState<BookmarkItem[]>(() =>
    loadJson<BookmarkItem[]>(STORAGE_KEYS.bookmarks, [
      {
        id: 'bm-showcase',
        title: 'Blender Open Movies — Sample Media Gallery',
        url: BUILTIN_SHOWCASE_PAGES[0].url,
        createdAt: Date.now(),
      },
    ])
  );
  useEffect(() => {
    localStorage.setItem(STORAGE_KEYS.bookmarks, JSON.stringify(bookmarks));
  }, [bookmarks]);

  const [history, setHistory] = useState<HistoryItem[]>(() =>
    loadJson<HistoryItem[]>(STORAGE_KEYS.history, [])
  );
  useEffect(() => {
    localStorage.setItem(STORAGE_KEYS.history, JSON.stringify(history));
  }, [history]);

  const [downloads, setDownloads] = useState<DownloadItemRecord[]>(() =>
    loadJson<DownloadItemRecord[]>(STORAGE_KEYS.downloads, []).map((d) =>
      d.status === 'DOWNLOADING' || d.status === 'PREPARING' || d.status === 'RESOLVING'
        ? { ...d, status: 'PAUSED', bytesPerSecond: 0, etaSeconds: null }
        : d
    )
  );
  useEffect(() => {
    // Persist a hygiene-passed snapshot: dead blob: URLs and base64 thumbnails only bloat the
    // quota, and unbounded completed records eventually throw QuotaExceededError (which would
    // crash the app — there is no error boundary).
    const toPersisted = (d: DownloadItemRecord): DownloadItemRecord => ({
      ...d,
      thumbnail: null,
      blobUrl: d.blobUrl && !d.blobUrl.startsWith('blob:') ? d.blobUrl : null,
    });
    try {
      localStorage.setItem(STORAGE_KEYS.downloads, JSON.stringify(downloads.slice(0, 150).map(toPersisted)));
    } catch {
      try {
        localStorage.setItem(STORAGE_KEYS.downloads, JSON.stringify(downloads.slice(0, 50).map(toPersisted)));
      } catch {
        // Quota still exceeded: keep the in-memory state working, drop persistence.
      }
    }
  }, [downloads]);

  const [tabs, setTabs] = useState<Tab[]>(() => {
    // Validate the stored shape: one corrupt entry would crash the very first render that
    // reads currentTab.historyIndex, with no error boundary to catch it.
    const saved = loadJson<unknown[]>(STORAGE_KEYS.tabs, []).filter(
      (tab): tab is Tab =>
        Boolean(tab) &&
        typeof (tab as Tab).id === 'string' &&
        typeof (tab as Tab).url === 'string' &&
        Array.isArray((tab as Tab).historyStack) &&
        typeof (tab as Tab).historyIndex === 'number'
    );
    if (saved.length > 0) return saved;
    return [
      {
        id: 'tab-initial',
        title: 'New Tab',
        url: '',
        isPrivate: false,
        isDesktopMode: false,
        createdAt: Date.now(),
        lastVisited: Date.now(),
        position: 0,
        historyStack: [],
        historyIndex: -1,
      },
    ];
  });
  const [currentTabId, setCurrentTabId] = useState<string>(() => {
    // Restore the tab the user was actually on (falls back to the oldest when absent).
    const savedId = loadJson<string>(STORAGE_KEYS.currentTab, '');
    return typeof savedId === 'string' && tabs.some((item) => item.id === savedId && !item.isPrivate)
      ? savedId
      : tabs[0]?.id || 'tab-initial';
  });

  useEffect(() => {
    const persistable = tabs.filter((tab) => !tab.isPrivate);
    localStorage.setItem(STORAGE_KEYS.tabs, JSON.stringify(persistable));
    if (persistable.some((tab) => tab.id === currentTabId)) {
      localStorage.setItem(STORAGE_KEYS.currentTab, JSON.stringify(currentTabId));
    }
  }, [tabs, currentTabId]);

  const hibernatedTabIds = useMemo(() => {
    if (tabs.length <= MAX_LIVE_TABS) return new Set<string>();
    const sorted = [...tabs].sort((a, b) => b.lastVisited - a.lastVisited);
    const liveIds = new Set(sorted.slice(0, MAX_LIVE_TABS).map((item) => item.id));
    liveIds.add(currentTabId);
    return new Set(tabs.filter((item) => !liveIds.has(item.id)).map((item) => item.id));
  }, [tabs, currentTabId]);

  const currentTab = useMemo(
    () => tabs.find((item) => item.id === currentTabId) ?? tabs[0],
    [tabs, currentTabId]
  );

  // Latest tabs for callbacks that must not re-subscribe on every tab change (native events).
  const tabsRef = useRef(tabs);
  tabsRef.current = tabs;
  // Current tab id for callbacks registered once (window.__salviaOpenUrl, closeTab).
  const currentTabIdRef = useRef(currentTabId);
  currentTabIdRef.current = currentTabId;
  // Latest downloads for handlers that must not re-create on every progress tick.
  const downloadsRef = useRef(downloads);
  downloadsRef.current = downloads;
  // isPrivate of tabs created in the same tick as their first load (createTab → loadUrlIntoTab
  // runs before the state update commits, so tabsRef cannot see the new tab yet).
  const pendingTabPrivateRef = useRef<Record<string, boolean>>({});
  // Monotonic per-tab navigation tokens: a late proxy response for a superseded navigation
  // must never overwrite the state of the newer one.
  const navSeqRef = useRef<Record<string, number>>({});
  // Tabs whose next native "url" event belongs to a navigation started from the React side
  // (typed address, back/forward, reload): it replaces the history entry instead of adding one.
  const nativePendingNavRef = useRef<Record<string, boolean>>({});

  const [tabPages, setTabPages] = useState<Record<string, TabPageRenderState>>({});
  const [tabMedia, setTabMedia] = useState<Record<string, MediaCandidate[]>>({});
  const [tabVisibleMedia, setTabVisibleMedia] = useState<Record<string, string | null>>({});
  const [isTabSwitcherVisible, setIsTabSwitcherVisible] = useState(false);
  const [isMediaTrayVisible, setIsMediaTrayVisible] = useState(false);
  const [qualitySheet, setQualitySheet] = useState<QualitySheetState | null>(null);
  const [blockedCleartextUrl, setBlockedCleartextUrl] = useState<string | null>(null);
  const [findInPage, setFindInPage] = useState<{ query: string; matches: number; activeMatch: number } | null>(null);

  // Connection state
  const [connectionKind, setConnectionKind] = useState<'WIFI' | 'MOBILE' | 'OTHER' | 'OFFLINE'>(() => {
    if (typeof navigator === 'undefined' || !navigator.onLine) return 'OFFLINE';
    const conn = (navigator as unknown as { connection?: { type?: string } }).connection;
    if (conn?.type === 'wifi') return 'WIFI';
    if (conn?.type === 'cellular') return 'MOBILE';
    return 'WIFI';
  });

  useEffect(() => {
    const refresh = () => {
      if (!navigator.onLine) {
        setConnectionKind('OFFLINE');
        return;
      }
      const conn = (navigator as unknown as { connection?: { type?: string } }).connection;
      if (conn?.type === 'cellular') setConnectionKind('MOBILE');
      else if (conn?.type === 'wifi') setConnectionKind('WIFI');
      else setConnectionKind('WIFI');
    };
    window.addEventListener('online', refresh);
    window.addEventListener('offline', refresh);
    return () => {
      window.removeEventListener('online', refresh);
      window.removeEventListener('offline', refresh);
    };
  }, []);

  const updateSettings = useCallback((patch: Partial<AppSettings>) => {
    setSettings((prev) => ({ ...prev, ...patch }));
  }, []);

  const navigate = useCallback(
    (nextRoute: AppRoute, options?: { downloadsTab?: number; player?: PlayerTarget }) => {
      if (nextRoute === 'downloads') {
        setDownloadsInitialTab(options?.downloadsTab ?? 0);
      }
      if (nextRoute === 'player' && options?.player) {
        setPlayerTarget(options.player);
      }
      setRoute(nextRoute);
    },
    []
  );

  const mergeTabCandidates = useCallback((tabId: string, incoming: MediaCandidate[]) => {
    if (incoming.length === 0) return;
    setTabMedia((prev) => {
      const existing = prev[tabId] || [];
      const byUrl = new Map<string, MediaCandidate>();
      for (const item of existing) byUrl.set(item.mediaUrl, item);
      for (const item of incoming) {
        const current = byUrl.get(item.mediaUrl);
        if (!current) {
          byUrl.set(item.mediaUrl, item);
        } else {
          byUrl.set(item.mediaUrl, {
            ...current,
            ...item,
            confidence: Math.max(current.confidence, item.confidence),
            duration: item.duration ?? current.duration ?? null,
            estimatedSize: item.estimatedSize ?? current.estimatedSize ?? null,
            width: item.width ?? current.width ?? null,
            height: item.height ?? current.height ?? null,
            isAd: item.isAd ?? current.isAd,
            isSuggested: item.isSuggested ?? current.isSuggested,
            muted: item.muted ?? current.muted,
            hasControls: item.hasControls ?? current.hasControls,
          });
        }
      }
      return { ...prev, [tabId]: Array.from(byUrl.values()) };
    });
  }, []);

  const loadUrlIntoTab = useCallback(
    async (tabId: string, rawUrl: string, pushHistory = true, overrideCleartext = false) => {
      const url = rawUrl.trim();
      if (!url) return;
      // Token for this navigation: async continuations below must bail out when a newer
      // navigation for the same tab (or the tab's death) has happened meanwhile.
      const navToken = (navSeqRef.current[tabId] = (navSeqRef.current[tabId] || 0) + 1);
      const isStale = () => navSeqRef.current[tabId] !== navToken || !tabsRef.current.some((t) => t.id === tabId);

      if (url.startsWith('http://') && !settings.isCleartextAllowed && !overrideCleartext) {
        setBlockedCleartextUrl(url);
        return;
      }
      setBlockedCleartextUrl(null);

      // Update tab metadata and reset media candidates for this new page
      setTabMedia((prev) => ({ ...prev, [tabId]: [] }));
      setTabVisibleMedia((prev) => ({ ...prev, [tabId]: null }));
      setTabs((prev) =>
        prev.map((tab) => {
          if (tab.id !== tabId) return tab;
          // Navigating to the exact current URL must not stack a duplicate entry (it would make
          // the next back-press appear dead).
          const sameAsCurrent = tab.historyStack[tab.historyIndex] === url;
          const doPush = pushHistory && !sameAsCurrent;
          const nextStack = doPush
            ? [...tab.historyStack.slice(0, tab.historyIndex + 1), url].slice(-MAX_HISTORY_STACK)
            : tab.historyStack;
          const nextIdx = doPush ? nextStack.length - 1 : tab.historyIndex;
          return {
            ...tab,
            url,
            lastVisited: Date.now(),
            historyStack: nextStack,
            historyIndex: nextIdx,
          };
        })
      );

      // Built-in showcase page
      const showcase = BUILTIN_SHOWCASE_PAGES.find((p) => p.url === url);
      if (showcase) {
        setTabPages((prev) => ({
          ...prev,
          [tabId]: {
            url,
            title: showcase.title,
            html: null,
            isLoading: false,
            progress: 100,
            error: null,
            isDemoShowcase: true,
          },
        }));
        setTabs((prev) => prev.map((tab) => (tab.id === tabId ? { ...tab, title: showcase.title } : tab)));
        const candidates: MediaCandidate[] = showcase.media.map((m, idx) => ({
          id: `demo-${idx}-${m.mediaUrl}`,
          pageUrl: url,
          mediaUrl: m.mediaUrl,
          title: m.title,
          mimeType: m.mimeType,
          extension: m.extension,
          source: 'DOM',
          confidence: 0.95 - idx * 0.05,
          duration: m.duration ?? null,
          estimatedSize: m.estimatedSize ?? null,
          isLive: m.isLive ?? false,
        }));
        setTabMedia((prev) => ({ ...prev, [tabId]: candidates }));
        setTabVisibleMedia((prev) => ({ ...prev, [tabId]: candidates[0]?.mediaUrl ?? null }));
        if (!tabsRef.current.find((tItem) => tItem.id === tabId)?.isPrivate) {
          setHistory((prev) => [
            { id: makeId('hist'), title: showcase.title, url, visitedAt: Date.now() },
            ...prev.filter((h) => h.url !== url).slice(0, 199),
          ]);
        }
        return;
      }

      const host = hostOfUrl(url) || '';
      const adBlockActive = settings.isAdBlockEnabled && !settings.adBlockDisabledSites.includes(host);

      // Real browsing: hand the URL to the native WebView. Progress, title, redirects, media and
      // errors come back through handleNativeBrowserEvent below.
      if (isNativeBrowserAvailable()) {
        nativePendingNavRef.current[tabId] = true;
        setTabPages((prev) => ({
          ...prev,
          [tabId]: {
            url,
            title: host || url,
            html: null,
            isLoading: true,
            progress: 10,
            error: null,
            isDemoShowcase: false,
          },
        }));
        const targetTab = tabsRef.current.find((item) => item.id === tabId) ??
          (pendingTabPrivateRef.current[tabId] !== undefined ? { isPrivate: pendingTabPrivateRef.current[tabId] } : undefined);
        getSalviaNative()?.browserLoad?.(tabId, url, settings.isDesktopSite, adBlockActive, targetTab?.isPrivate);
        return;
      }

      setTabPages((prev) => ({
        ...prev,
        [tabId]: {
          url,
          title: url,
          html: null,
          isLoading: true,
          progress: 25,
          error: null,
          isDemoShowcase: false,
        },
      }));

      try {
        const res = await fetch(
          apiUrl(`/api/browse?url=${encodeURIComponent(url)}&desktop=${settings.isDesktopSite ? '1' : '0'}&adblock=${adBlockActive ? '1' : '0'}`)
        );
        if (isStale()) return;
        const data = (await res.json()) as {
          ok?: boolean;
          status?: number;
          statusText?: string;
          finalUrl?: string;
          title?: string;
          html?: string;
          blockedCount?: number;
          isDirectMedia?: boolean;
          contentType?: string;
          contentLength?: number | null;
          error?: string;
        };

        if (!data.ok) {
          const errMsg = data.status
            ? t('error_page_http', { status: `${data.status} ${data.statusText || ''}`.trim() })
            : data.error || t('error_page_load_failed');
          setTabPages((prev) => ({
            ...prev,
            [tabId]: {
              url,
              title: host || url,
              html: null,
              isLoading: false,
              progress: 100,
              error: errMsg,
              isDemoShowcase: false,
            },
          }));
          logEvent('navigation', host, `load_failed:${data.status || 'network'}`);
          return;
        }

        const finalUrl = data.finalUrl || url;
        const pageTitle = data.title || host || finalUrl;

        if (data.blockedCount && data.blockedCount > 0) {
          setSettings((prev) => ({
            ...prev,
            adBlockBlockedRequests: prev.adBlockBlockedRequests + (data.blockedCount || 0),
          }));
          logEvent('adblock', host, `blocked:${data.blockedCount}`);
        }

        if (data.isDirectMedia) {
          const candidate: MediaCandidate = {
            id: `direct-${finalUrl}`,
            pageUrl: finalUrl,
            mediaUrl: finalUrl,
            title: pageTitle,
            mimeType: data.contentType || 'video/mp4',
            extension: finalUrl.split('.').pop()?.split('?')[0] || 'mp4',
            source: 'FETCH',
            confidence: 0.95,
            estimatedSize: data.contentLength ?? null,
          };
          mergeTabCandidates(tabId, [candidate]);
        }

        const scanned = data.html ? scanHtmlForMedia(data.html, finalUrl, pageTitle) : [];
        mergeTabCandidates(tabId, scanned);

        setTabPages((prev) => ({
          ...prev,
          [tabId]: {
            url: finalUrl,
            title: pageTitle,
            html: data.html ?? null,
            isLoading: false,
            progress: 100,
            error: null,
            isDemoShowcase: false,
          },
        }));

        setTabs((prev) =>
          prev.map((tab) => (tab.id === tabId ? { ...tab, url: finalUrl, title: pageTitle } : tab))
        );

        const targetTab = tabsRef.current.find((item) => item.id === tabId);
        if (!targetTab?.isPrivate) {
          setHistory((prev) => [
            { id: makeId('hist'), title: pageTitle, url: finalUrl, visitedAt: Date.now() },
            ...prev.filter((h) => h.url !== finalUrl).slice(0, 199),
          ]);
        }
      } catch {
        if (isStale()) return;
        setTabPages((prev) => ({
          ...prev,
          [tabId]: {
            url,
            title: host || url,
            html: null,
            isLoading: false,
            progress: 100,
            error: t('error_page_load_failed'),
            isDemoShowcase: false,
          },
        }));
      }
    },
    [settings.isCleartextAllowed, settings.isDesktopSite, settings.isAdBlockEnabled, settings.adBlockDisabledSites, t, logEvent, mergeTabCandidates]
  );

  const navigateCurrentTab = useCallback(
    (input: string) => {
      const resolved = resolveAddressBarInput(input, settings.searchEngine);
      if (!resolved) return;
      loadUrlIntoTab(currentTabId, resolved, true);
    },
    [currentTabId, settings.searchEngine, loadUrlIntoTab]
  );

  const openQualitySheetForCandidate = useCallback(async (candidate: MediaCandidate) => {
    const unsupported = unsupportedReasonFor({
      url: candidate.mediaUrl,
      mimeType: candidate.mimeType,
      extension: candidate.extension,
      isLive: candidate.isLive,
      isMediaSource: candidate.isMediaSource,
      isBlobFile: candidate.isBlobFile,
      dashIsSupported: true,
    });
    const initial = initialMediaInfoForCandidate(candidate);
    setQualitySheet({
      candidate,
      mediaInfo: initial,
      isResolving: unsupported === null,
      unsupported,
      canRecord: unsupported === 'BLOB_STREAM' || candidate.isMediaSource === true,
    });

    if (unsupported !== null) return;

    const probed = await probeMediaCandidate(candidate);
    setQualitySheet((prev) => {
      if (!prev || prev.candidate.mediaUrl !== candidate.mediaUrl) return prev;
      return {
        ...prev,
        mediaInfo: probed.mediaInfo,
        isResolving: false,
        unsupported: probed.unsupported,
      };
    });
  }, []);

  const [isExtractingLink, setIsExtractingLink] = useState(false);

  const openPastedLink = useCallback(
    (rawUrl: string) => {
      const kind = classifyPastedLink(rawUrl);
      if (kind === 'MEDIA_FILE') {
        const candidate = pastedMediaCandidate(rawUrl);
        if (candidate) {
          setRoute('browser');
          openQualitySheetForCandidate(candidate);
          return;
        }
      }
      setRoute('browser');
      const resolved = resolveAddressBarInput(rawUrl, settings.searchEngine);
      if (resolved) loadUrlIntoTab(currentTabId, resolved, true);
    },
    [currentTabId, settings.searchEngine, loadUrlIntoTab, openQualitySheetForCandidate]
  );

  const extractFromPastedPage = useCallback(
    async (rawUrl: string) => {
      const kind = classifyPastedLink(rawUrl);
      if (kind === 'MEDIA_FILE') {
        const candidate = pastedMediaCandidate(rawUrl);
        if (candidate) {
          openQualitySheetForCandidate(candidate);
          return;
        }
      }
      const resolved = resolveAddressBarInput(rawUrl, settings.searchEngine);
      if (!resolved) return;
      setIsExtractingLink(true);
      showToast(t('extracting_media'));
      try {
        const extracted = await extractMediaFromPageUrl(resolved);
        if (extracted.ok && extracted.candidates.length > 0) {
          setRoute('browser');
          setTabMedia((prev) => ({
            ...prev,
            [currentTabId]: extracted.candidates,
          }));
          if (extracted.candidates.length === 1) {
            openQualitySheetForCandidate(extracted.candidates[0]);
          } else {
            setIsMediaTrayVisible(true);
          }
          return;
        }
        showToast(t('extract_none_found'));
        setRoute('browser');
        loadUrlIntoTab(currentTabId, resolved, true);
      } finally {
        setIsExtractingLink(false);
      }
    },
    [currentTabId, settings.searchEngine, loadUrlIntoTab, openQualitySheetForCandidate, showToast, t]
  );

  const reloadCurrentTab = useCallback(() => {
    if (!currentTab?.url) return;
    const native = getSalviaNative();
    const existing = tabPages[currentTab.id];
    if (isNativeBrowserAvailable() && native?.browserReload && existing && !existing.error) {
      setTabPages((prev) => {
        const cur = prev[currentTab.id];
        return cur ? { ...prev, [currentTab.id]: { ...cur, isLoading: true, progress: 10 } } : prev;
      });
      native.browserReload(currentTab.id);
      return;
    }
    loadUrlIntoTab(currentTab.id, currentTab.url, false);
  }, [currentTab, tabPages, loadUrlIntoTab]);

  const stopLoadingCurrentTab = useCallback(() => {
    getSalviaNative()?.browserStop?.(currentTabId);
    setTabPages((prev) => {
      const cur = prev[currentTabId];
      if (!cur) return prev;
      return { ...prev, [currentTabId]: { ...cur, isLoading: false, progress: 100 } };
    });
  }, [currentTabId]);

  const goBackCurrentTab = useCallback(() => {
    if (!currentTab || currentTab.historyIndex <= 0) return;
    const nextIdx = currentTab.historyIndex - 1;
    const targetUrl = currentTab.historyStack[nextIdx];
    setTabs((prev) =>
      prev.map((tab) => (tab.id === currentTab.id ? { ...tab, historyIndex: nextIdx } : tab))
    );
    loadUrlIntoTab(currentTab.id, targetUrl, false);
  }, [currentTab, loadUrlIntoTab]);

  const goForwardCurrentTab = useCallback(() => {
    if (!currentTab || currentTab.historyIndex >= currentTab.historyStack.length - 1) return;
    const nextIdx = currentTab.historyIndex + 1;
    const targetUrl = currentTab.historyStack[nextIdx];
    setTabs((prev) =>
      prev.map((tab) => (tab.id === currentTab.id ? { ...tab, historyIndex: nextIdx } : tab))
    );
    loadUrlIntoTab(currentTab.id, targetUrl, false);
  }, [currentTab, loadUrlIntoTab]);

  const goHomeInCurrentTab = useCallback(() => {
    if (currentTab?.url) {
      loadUrlIntoTab(currentTab.id, settings.homepage, true);
    } else {
      setRoute('home');
    }
  }, [currentTab, settings.homepage, loadUrlIntoTab]);

  const createTab = useCallback(
    (options?: { isPrivate?: boolean; url?: string }) => {
      const newTab: Tab = {
        id: makeId('tab'),
        title: options?.isPrivate ? t('new_private_tab') : t('new_tab'),
        url: options?.url || '',
        isPrivate: Boolean(options?.isPrivate),
        isDesktopMode: settings.isDesktopSite,
        createdAt: Date.now(),
        lastVisited: Date.now(),
        position: tabs.length,
        historyStack: options?.url ? [options.url] : [],
        historyIndex: options?.url ? 0 : -1,
      };
      // loadUrlIntoTab runs before the setTabs commit is visible, so publish the private flag
      // for the same-tick lookup — otherwise a private tab would get a non-private WebView.
      pendingTabPrivateRef.current[newTab.id] = newTab.isPrivate;
      setTabs((prev) => [...prev, newTab]);
      setCurrentTabId(newTab.id);
      setIsTabSwitcherVisible(false);
      if (options?.url) {
        loadUrlIntoTab(newTab.id, options.url, false);
      }
    },
    [settings.isDesktopSite, tabs.length, t, loadUrlIntoTab]
  );

  const switchTab = useCallback(
    (tabId: string) => {
      const wasHibernated = hibernatedTabIds.has(tabId);
      setTabs((prev) =>
        prev.map((tab) => (tab.id === tabId ? { ...tab, lastVisited: Date.now() } : tab))
      );
      setCurrentTabId(tabId);
      setIsTabSwitcherVisible(false);
      if (wasHibernated) {
        const target = tabs.find((item) => item.id === tabId);
        if (target?.url) {
          loadUrlIntoTab(tabId, target.url, false);
        }
      }
    },
    [hibernatedTabIds, tabs, loadUrlIntoTab]
  );

  /** Drops every per-tab record of [tabIds] (page state, candidates, navigation bookkeeping). */
  const pruneTabRecords = useCallback((tabIds: string[]) => {
    setTabPages((prev) => {
      const next = { ...prev };
      let changed = false;
      for (const id of tabIds) if (id in next) { delete next[id]; changed = true; }
      return changed ? next : prev;
    });
    setTabMedia((prev) => {
      const next = { ...prev };
      let changed = false;
      for (const id of tabIds) if (id in next) { delete next[id]; changed = true; }
      return changed ? next : prev;
    });
    setTabVisibleMedia((prev) => {
      const next = { ...prev };
      let changed = false;
      for (const id of tabIds) if (id in next) { delete next[id]; changed = true; }
      return changed ? next : prev;
    });
    for (const id of tabIds) {
      delete nativePendingNavRef.current[id];
      delete navSeqRef.current[id];
      delete pendingTabPrivateRef.current[id];
    }
  }, []);

  const closeTab = useCallback(
    (tabId: string) => {
      getSalviaNative()?.browserDestroy?.(tabId);
      // Computed from the ref, not inside the setTabs updater: updaters must stay pure
      // (React StrictMode double-invokes them) and setCurrentTabId cannot be called there.
      const remaining = tabsRef.current.filter((item) => item.id !== tabId);
      pruneTabRecords([tabId]);
      if (remaining.length === 0) {
        const fresh: Tab = {
          id: makeId('tab'),
          title: t('new_tab'),
          url: '',
          isPrivate: false,
          isDesktopMode: settings.isDesktopSite,
          createdAt: Date.now(),
          lastVisited: Date.now(),
          position: 0,
          historyStack: [],
          historyIndex: -1,
        };
        setTabs([fresh]);
        setCurrentTabId(fresh.id);
        return;
      }
      setTabs(remaining);
      if (tabId === currentTabIdRef.current) {
        setCurrentTabId(remaining[remaining.length - 1].id);
      }
    },
    [settings.isDesktopSite, t, pruneTabRecords]
  );

  // Download Queue & Scheduling
  const runDownloadItem = useCallback(
    (record: DownloadItemRecord) => {
      if (settings.isWifiOnly && connectionKind !== 'WIFI') {
        setDownloads((prev) =>
          prev.map((d) =>
            d.id === record.id
              ? { ...d, status: 'PAUSED', error: WIFI_HOLD_MARKER, bytesPerSecond: 0, etaSeconds: null }
              : d
          )
        );
        return;
      }

      setDownloads((prev) =>
        prev.map((d) =>
          d.id === record.id ? { ...d, status: 'DOWNLOADING', error: null } : d
        )
      );

      startTransfer(
        record.id,
        record.url,
        Boolean(record.isHls),
        record.mimeType || 'video/mp4',
        {
          onProgress: (downloadedBytes, totalBytes, bytesPerSecond, etaSeconds) => {
            setDownloads((prev) =>
              prev.map((d) =>
                d.id === record.id
                  ? {
                      ...d,
                      status: 'DOWNLOADING',
                      downloadedBytes,
                      totalBytes: totalBytes ?? d.totalBytes,
                      bytesPerSecond,
                      etaSeconds,
                    }
                  : d
              )
            );
          },
          onComplete: (blobUrl, finalSize, mimeType, nativeUri) => {
            setDownloads((prev) =>
              prev.map((d) =>
                d.id === record.id
                  ? {
                      ...d,
                      status: 'COMPLETED',
                      downloadedBytes: finalSize,
                      totalBytes: finalSize,
                      bytesPerSecond: 0,
                      etaSeconds: null,
                      mimeType,
                      blobUrl,
                      nativeUri: nativeUri ?? d.nativeUri ?? null,
                      exportedToGallery: settings.isExportToGallery,
                      completedAt: Date.now(),
                    }
                  : d
              )
            );
            if (settings.isExportToGallery && !isNativeAndroid()) {
              triggerBrowserFileSave(blobUrl, record.filename);
            }
            showToast(t('download_finished_named', { name: record.mediaTitle || record.filename }));
            logEvent('transfer', hostOfUrl(record.url) || 'media', 'completed');
          },
          onPaused: () => {
            setDownloads((prev) =>
              prev.map((d) =>
                d.id === record.id ? { ...d, status: 'PAUSED', bytesPerSecond: 0, etaSeconds: null } : d
              )
            );
          },
          onCancelled: () => {
            setDownloads((prev) =>
              prev.map((d) =>
                d.id === record.id
                  ? { ...d, status: 'CANCELLED', bytesPerSecond: 0, etaSeconds: null }
                  : d
              )
            );
          },
          onError: (message) => {
            const friendly = message.includes('Insufficient storage')
              ? t('download_insufficient_space')
              : message;
            setDownloads((prev) =>
              prev.map((d) =>
                d.id === record.id
                  ? { ...d, status: 'FAILED', error: friendly, bytesPerSecond: 0, etaSeconds: null }
                  : d
              )
            );
            logEvent('transfer', hostOfUrl(record.url) || 'media', `failed:${message}`);
          },
        },
        {
          filename: record.filename,
          parallelConnections: settings.parallelConnections,
          autoSaveToGallery: settings.isExportToGallery,
          audioTrackUrl: record.audioTrackUrl ?? null,
          referer: record.pageUrl ?? null,
          wifiOnly: settings.isWifiOnly,
        }
      );
    },
    [settings.isWifiOnly, settings.isExportToGallery, settings.parallelConnections, connectionKind, showToast, t, logEvent]
  );

  // Process queue slots up to maxSimultaneousDownloads
  useEffect(() => {
    const activeCount = downloads.filter((d) => d.status === 'DOWNLOADING' || d.status === 'PREPARING').length;
    const freeSlots = settings.maxSimultaneousDownloads - activeCount;
    if (freeSlots <= 0) return;
    if (settings.isWifiOnly && connectionKind !== 'WIFI') return;
    const queued = downloads.filter((d) => d.status === 'QUEUED');
    for (const item of queued.slice(0, freeSlots)) {
      runDownloadItem(item);
    }
  }, [downloads, settings.maxSimultaneousDownloads, settings.isWifiOnly, connectionKind, runDownloadItem]);

  // Resume wifi-held transfers when back on Wi-Fi
  useEffect(() => {
    if (!settings.isWifiOnly || connectionKind === 'WIFI') {
      const held = downloads.filter((d) => d.status === 'PAUSED' && d.error === WIFI_HOLD_MARKER);
      if (held.length > 0) {
        setDownloads((prev) =>
          prev.map((d) =>
            d.status === 'PAUSED' && d.error === WIFI_HOLD_MARKER
              ? { ...d, status: 'QUEUED', error: null }
              : d
          )
        );
      }
    }
  }, [settings.isWifiOnly, connectionKind, downloads]);

  const startDownloadFromFormat = useCallback(
    (format: MediaFormat, customFilename: string | null) => {
      const existing = downloads.find(
        (d) =>
          d.url === format.url &&
          (d.status === 'DOWNLOADING' || d.status === 'QUEUED' || d.status === 'PAUSED')
      );
      if (existing) {
        showToast(t('download_already_downloading', { name: existing.mediaTitle || existing.filename }));
        setQualitySheet(null);
        return;
      }

      const title = qualitySheet?.mediaInfo.title || format.format;
      const baseName = sanitizeFilename(customFilename || title);
      const ext = format.extension || 'mp4';
      const filename = baseName.endsWith(`.${ext}`) ? baseName : `${baseName}.${ext}`;

      const record: DownloadItemRecord = {
        id: makeId('dl'),
        url: format.url,
        mediaTitle: title,
        filename,
        mimeType: format.mimeType,
        thumbnail: qualitySheet?.mediaInfo.thumbnail ?? null,
        status: 'QUEUED',
        downloadedBytes: 0,
        totalBytes: format.size ?? null,
        bytesPerSecond: 0,
        etaSeconds: null,
        isLocked: false,
        exportedToGallery: false,
        isHls: format.isHls,
        isDash: format.isDash,
        audioTrackUrl: format.audioTrackUrl ?? null,
        createdAt: Date.now(),
        pageUrl: qualitySheet?.mediaInfo.webpageUrl,
      };

      setDownloads((prev) => [record, ...prev]);
      setQualitySheet(null);
      setIsMediaTrayVisible(false);
      showToast(t('download_enqueued'));
    },
    [downloads, qualitySheet, showToast, t]
  );

  const startBatchDownloadFromCandidates = useCallback(
    async (selectedCandidates: MediaCandidate[]) => {
      const offerables = selectedCandidates.filter(
        (c) =>
          unsupportedReasonFor({
            url: c.mediaUrl,
            mimeType: c.mimeType,
            extension: c.extension,
            isLive: c.isLive,
            isMediaSource: c.isMediaSource,
            isBlobFile: c.isBlobFile,
            dashIsSupported: true,
          }) === null
      );
      if (offerables.length === 0) {
        showToast(t('no_media_detected'));
        return;
      }

      const resolved = await Promise.all(
        offerables.map(async (candidate) => {
          const fallback = initialMediaInfoForCandidate(candidate);
          try {
            const probed = await probeMediaCandidate(candidate);
            const fmt = probed.mediaInfo.formats[0] || probed.mediaInfo.audioFormats[0] || fallback.formats[0];
            return { candidate, info: probed.mediaInfo, fmt };
          } catch {
            return { candidate, info: fallback, fmt: fallback.formats[0] };
          }
        })
      );

      const activeUrls = new Set(
        downloads
          .filter((d) => d.status === 'DOWNLOADING' || d.status === 'QUEUED' || d.status === 'PAUSED')
          .map((d) => d.url)
      );

      const newRecords: DownloadItemRecord[] = [];
      for (const item of resolved) {
        const fmt = item.fmt;
        if (!fmt || activeUrls.has(fmt.url)) continue;
        activeUrls.add(fmt.url);
        const title = item.info.title || item.candidate.title || fmt.format;
        const baseName = sanitizeFilename(title);
        const ext = fmt.extension || item.candidate.extension || 'mp4';
        const filename = baseName.endsWith(`.${ext}`) ? baseName : `${baseName}.${ext}`;
        newRecords.push({
          id: makeId('dl'),
          url: fmt.url,
          mediaTitle: title,
          filename,
          mimeType: fmt.mimeType,
          thumbnail: item.info.thumbnail ?? item.candidate.thumbnail ?? null,
          status: 'QUEUED',
          downloadedBytes: 0,
          totalBytes: fmt.size ?? item.candidate.estimatedSize ?? null,
          bytesPerSecond: 0,
          etaSeconds: null,
          isLocked: false,
          exportedToGallery: false,
          isHls: fmt.isHls,
          isDash: fmt.isDash,
          audioTrackUrl: fmt.audioTrackUrl ?? null,
          createdAt: Date.now(),
          pageUrl: item.candidate.pageUrl,
        });
      }

      if (newRecords.length > 0) {
        setDownloads((prev) => [...newRecords, ...prev]);
        setIsMediaTrayVisible(false);
        showToast(t('download_enqueued'));
      } else {
        setIsMediaTrayVisible(false);
      }
    },
    [downloads, showToast, t]
  );

  const pauseDownload = useCallback((id: string) => {
    pauseTransfer(id);
    setDownloads((prev) =>
      prev.map((d) => (d.id === id ? { ...d, status: 'PAUSED', bytesPerSecond: 0, etaSeconds: null } : d))
    );
  }, []);

  const resumeDownload = useCallback(
    (id: string) => {
      const record = downloads.find((d) => d.id === id);
      if (!record) return;
      const resumed = resumeNativeTransfer(id, {
        onProgress: (downloadedBytes, totalBytes, bytesPerSecond, etaSeconds) => {
          setDownloads((prev) =>
            prev.map((d) =>
              d.id === id
                ? { ...d, status: 'DOWNLOADING', downloadedBytes, totalBytes: totalBytes ?? d.totalBytes, bytesPerSecond, etaSeconds }
                : d
            )
          );
        },
        onComplete: (blobUrl, finalSize, mimeType, nativeUri) => {
          setDownloads((prev) =>
            prev.map((d) =>
              d.id === id
                ? {
                    ...d,
                    status: 'COMPLETED',
                    downloadedBytes: finalSize,
                    totalBytes: finalSize,
                    bytesPerSecond: 0,
                    etaSeconds: null,
                    mimeType,
                    blobUrl,
                    nativeUri: nativeUri ?? d.nativeUri ?? null,
                    completedAt: Date.now(),
                  }
                : d
            )
          );
          showToast(t('download_finished_named', { name: record.mediaTitle || record.filename }));
        },
        onPaused: () => {
          setDownloads((prev) => prev.map((d) => (d.id === id ? { ...d, status: 'PAUSED', bytesPerSecond: 0, etaSeconds: null } : d)));
        },
        onCancelled: () => {
          setDownloads((prev) => prev.map((d) => (d.id === id ? { ...d, status: 'CANCELLED', bytesPerSecond: 0, etaSeconds: null } : d)));
        },
        onError: (message) => {
          setDownloads((prev) => prev.map((d) => (d.id === id ? { ...d, status: 'FAILED', error: message, bytesPerSecond: 0, etaSeconds: null } : d)));
        },
      });
      if (resumed) {
        setDownloads((prev) => prev.map((d) => (d.id === id ? { ...d, status: 'DOWNLOADING', error: null } : d)));
        return;
      }
      // Native engine does not know this download (web-mode session): restart from scratch.
      setDownloads((prev) =>
        prev.map((d) => (d.id === id ? { ...d, status: 'QUEUED', error: null } : d))
      );
    },
    [downloads, showToast, t]
  );

  const cancelDownload = useCallback((id: string) => {
    cancelTransfer(id);
    setDownloads((prev) =>
      prev.map((d) => (d.id === id ? { ...d, status: 'CANCELLED', bytesPerSecond: 0, etaSeconds: null } : d))
    );
  }, []);

  const retryDownload = useCallback((id: string) => {
    setDownloads((prev) =>
      prev.map((d) =>
        d.id === id ? { ...d, status: 'QUEUED', downloadedBytes: 0, error: null } : d
      )
    );
  }, []);

  const deleteDownload = useCallback((id: string) => {
    cancelTransfer(id);
    releaseBlobUrl(id);
    setDownloads((prev) => prev.filter((d) => d.id !== id));
  }, []);

  const clearDownloadsByBucket = useCallback((bucket: 'active' | 'completed' | 'failed') => {
    // "Active" must cover everything the queue scheduler can still pick up (QUEUED and
    // RESOLVING included), or clearing would visibly fail as the queue restarts them.
    setDownloads((prev) =>
      prev.filter((d) => {
        if (bucket === 'active') {
          const isAct =
            d.status === 'DOWNLOADING' ||
            d.status === 'PAUSED' ||
            d.status === 'PREPARING' ||
            d.status === 'QUEUED' ||
            d.status === 'RESOLVING';
          if (isAct) cancelTransfer(d.id);
          return !isAct;
        }
        if (bucket === 'completed') {
          if (d.status === 'COMPLETED' && !d.isLocked) {
            releaseBlobUrl(d.id);
            return false;
          }
          return true;
        }
        return !(d.status === 'FAILED' || d.status === 'CANCELLED');
      })
    );
  }, []);

  const exportDownloadToDevice = useCallback(
    (id: string) => {
      const target = downloads.find((d) => d.id === id);
      if (!target) return;
      const native = getSalviaNative();
      if (native && target.nativeUri) {
        // Self-update APKs go through the installer (with the "unknown sources" flow).
        if (target.mimeType === 'application/vnd.android.package-archive') {
          const installed = native.installApk
            ? native.installApk(target.nativeUri)
            : native.openNativeFile(target.nativeUri, target.mimeType);
          if (!installed) showToast(t('update_install_permission_hint'));
          return;
        }
        native.openNativeFile(target.nativeUri, target.mimeType || 'video/mp4');
        return;
      }
      const liveBlob = getCompletedBlobUrl(id) || target.blobUrl;
      if (liveBlob) {
        triggerBrowserFileSave(liveBlob, target.filename);
      } else {
        triggerBrowserFileSave(apiUrl(`/api/media/fetch?url=${encodeURIComponent(target.url)}`), target.filename);
      }
      setDownloads((prev) =>
        prev.map((d) => (d.id === id ? { ...d, exportedToGallery: true } : d))
      );
      showToast(t('download_exported'));
    },
    [downloads, showToast, t]
  );

  const playDownloadInApp = useCallback(
    (id: string) => {
      const target = downloads.find((d) => d.id === id);
      if (!target) return;
      // Live blob: (this session) or the native local-file proxy URL (survives restarts).
      // Both play with Range support; the proxy also rewrites HLS playlists to absolute URLs.
      const localProxyUrl =
        target.blobUrl && target.blobUrl.startsWith('https://appassets.androidplatform.net/')
          ? target.blobUrl
          : null;
      const playableUrl =
        getCompletedBlobUrl(id) || localProxyUrl || apiUrl(`/api/media/fetch?url=${encodeURIComponent(target.url)}`);
      navigate('player', {
        player: {
          url: playableUrl,
          title: target.mediaTitle || target.filename,
          downloadId: target.id,
        },
      });
    },
    [downloads, navigate]
  );

  const shareDownload = useCallback(
    async (id: string) => {
      const target = downloads.find((d) => d.id === id);
      if (!target) return;
      const native = getSalviaNative();
      if (native && target.nativeUri) {
        if (native.shareNativeFile(target.nativeUri, target.mediaTitle || target.filename, target.mimeType || 'video/mp4')) {
          return;
        }
      }
      if (navigator.share) {
        try {
          await navigator.share({ title: target.mediaTitle || target.filename, url: target.url });
          return;
        } catch {
          // fallback to clipboard
        }
      }
      await navigator.clipboard?.writeText(target.url);
      showToast(t('link_copied'));
    },
    [downloads, showToast, t]
  );

  // Locked Folder State
  const [isLockedFolderConfigured, setIsLockedFolderConfigured] = useState(() => isPasswordConfigured());
  const [isLockedFolderUnlocked, setIsLockedFolderUnlocked] = useState(false);
  const [isUnlockingFolder, setIsUnlockingFolder] = useState(false);
  const lockoutRef = useRef(new LockoutPolicy());

  const unlockLockedFolder = useCallback(
    async (password: string) => {
      const now = Date.now();
      if (lockoutRef.current.isLockedOut(now)) {
        const seconds = Math.ceil(lockoutRef.current.remainingMillis(now) / 1000);
        showToast(t('locked_folder_try_later', { seconds }));
        return;
      }
      setIsUnlockingFolder(true);
      try {
        const ok = await verifyLockedFolderPassword(password);
        if (ok) {
          lockoutRef.current.onSuccess();
          setIsLockedFolderUnlocked(true);
        } else {
          lockoutRef.current.onFailure(now);
          showToast(t('locked_folder_wrong_password'));
        }
      } finally {
        setIsUnlockingFolder(false);
      }
    },
    [showToast, t]
  );

  const lockLockedFolder = useCallback(() => {
    setIsLockedFolderUnlocked(false);
  }, []);

  const saveLockedFolderPassword = useCallback(
    async (password: string) => {
      const ok = await setLockedFolderPassword(password);
      if (ok) {
        setIsLockedFolderConfigured(true);
        showToast(t('locked_folder_set'));
      }
      return ok;
    },
    [showToast, t]
  );

  const removeLockedFolderPasswordAction = useCallback(async () => {
    removeLockedFolderPassword();
    setIsLockedFolderConfigured(false);
    setIsLockedFolderUnlocked(false);
    setDownloads((prev) => prev.map((d) => ({ ...d, isLocked: false })));
    showToast(t('locked_folder_removed'));
    return true;
  }, [showToast, t]);

  const setDownloadLocked = useCallback(
    (id: string, locked: boolean) => {
      if (locked && !isLockedFolderConfigured) {
        showToast(t('locked_folder_not_configured'));
        return;
      }
      setDownloads((prev) => prev.map((d) => (d.id === id ? { ...d, isLocked: locked } : d)));
      showToast(t(locked ? 'download_locked' : 'download_unlocked'));
    },
    [isLockedFolderConfigured, showToast, t]
  );

  // Media candidates for current tab
  const currentTabCandidates = useMemo(() => tabMedia[currentTabId] || [], [tabMedia, currentTabId]);
  const { main: currentMainCandidate, ranked: rankedTabCandidates } = useMemo(
    () =>
      rankAndClassifyCandidates(
        currentTabCandidates,
        tabVisibleMedia[currentTabId] ?? null,
        currentTab?.url ?? null
      ),
    [currentTabCandidates, tabVisibleMedia, currentTabId, currentTab?.url]
  );

  const downloadMainMedia = useCallback(() => {
    if (rankedTabCandidates.length === 1 && currentMainCandidate) {
      openQualitySheetForCandidate(currentMainCandidate);
    } else if (currentTabCandidates.length > 0) {
      setIsMediaTrayVisible(true);
    } else {
      showToast(t('no_media_detected'));
    }
  }, [rankedTabCandidates.length, currentMainCandidate, currentTabCandidates.length, openQualitySheetForCandidate, showToast, t]);

  // Playback Recording simulation/controller for MSE streams
  const [recording, setRecording] = useState<RecordingState>({ isRecording: false, bytes: 0 });
  const recordTimerRef = useRef<number | null>(null);
  const startRecordingPlayback = useCallback(() => {
    setRecording({ isRecording: true, bytes: 0 });
    if (recordTimerRef.current) window.clearInterval(recordTimerRef.current);
    recordTimerRef.current = window.setInterval(() => {
      setRecording((prev) => (prev.isRecording ? { isRecording: true, bytes: prev.bytes + 65536 } : prev));
    }, 500);
  }, []);

  const stopRecordingPlayback = useCallback(() => {
    if (recordTimerRef.current) {
      window.clearInterval(recordTimerRef.current);
      recordTimerRef.current = null;
    }
    setRecording((prev) => {
      if (prev.isRecording && prev.bytes > 0) {
        const title = qualitySheet?.mediaInfo.title || 'Recorded stream';
        const rec: DownloadItemRecord = {
          id: makeId('rec'),
          url: qualitySheet?.candidate.mediaUrl || currentTab?.url || '',
          mediaTitle: `${title} (Recording)`,
          filename: `${sanitizeFilename(title)}_recording.webm`,
          mimeType: 'video/webm',
          status: 'COMPLETED',
          downloadedBytes: prev.bytes,
          totalBytes: prev.bytes,
          bytesPerSecond: 0,
          etaSeconds: null,
          isLocked: false,
          exportedToGallery: false,
          createdAt: Date.now(),
          completedAt: Date.now(),
        };
        setDownloads((d) => [rec, ...d]);
        showToast(t('recording_saved'));
      }
      return { isRecording: false, bytes: 0 };
    });
  }, [qualitySheet, currentTab?.url, showToast, t]);

  // Bridge callbacks
  const reportDetectedMediaFromBridge = useCallback(
    (
      tabId: string,
      pageUrl: string,
      items: Array<{
        url: string;
        tag: string;
        type: string;
        visible: boolean;
        label?: string | null;
        kind?: string | null;
        durationMs?: number | null;
        width?: number | null;
        height?: number | null;
        muted?: boolean;
        hasControls?: boolean;
        isAd?: boolean;
        isSuggested?: boolean;
      }>
    ) => {
      const converted: MediaCandidate[] = items.map((item, idx) => ({
        id: `bridge-${idx}-${item.url}`,
        pageUrl,
        mediaUrl: item.url,
        title: item.label || tabPages[tabId]?.title || item.url.split('/').pop(),
        mimeType: item.type || (item.tag === 'audio' ? 'audio/mpeg' : 'video/mp4'),
        extension: item.url.split('.').pop()?.split('?')[0]?.toLowerCase() || 'mp4',
        source: 'DOM',
        confidence: item.kind === 'variant' || item.kind === 'mse-feed' ? 0.92 : item.visible && !item.isAd && !item.isSuggested ? 0.94 : item.isAd ? 0.25 : 0.78,
        label: item.label ?? null,
        kind: item.kind ?? null,
        duration: item.durationMs ?? null,
        width: item.width ?? null,
        height: item.height ?? null,
        muted: item.muted,
        hasControls: item.hasControls,
        isAd: item.isAd,
        isSuggested: item.isSuggested,
      }));
      mergeTabCandidates(tabId, converted);
    },
    [tabPages, mergeTabCandidates]
  );

  const reportVisibleMediaFromBridge = useCallback((tabId: string, _pageUrl: string, mediaUrl: string) => {
    setTabVisibleMedia((prev) => ({ ...prev, [tabId]: mediaUrl }));
  }, []);

  // Events from the native browser engine (BrowserHost.kt).
  const handleNativeBrowserEvent = useCallback(
    (ev: NativeBrowserEvent) => {
      // Events that are not bound to a tab.
      if (ev.type === 'browsing-data-cleared') {
        return;
      }
      if (ev.type === 'new-tab') {
        if (ev.url && /^https?:\/\//i.test(ev.url)) {
          createTab({ url: ev.url });
        }
        return;
      }
      if (ev.type === 'download-folder-changed') {
        const label = ev.uri ? getSalviaNative()?.getDownloadFolderName?.() || null : null;
        updateSettings({
          destinationMode: ev.uri ? 'chosen' : 'internal',
          chosenFolderLabel: label,
        });
        showToast(t('settings_download_folder_saved'));
        return;
      }

      const tabId = ev.tabId;
      const tab = tabsRef.current.find((item) => item.id === tabId);
      if (!tab) return;

      switch (ev.type) {
        case 'loading': {
          if (ev.started) {
            // New page: forget media found on the previous one.
            setTabMedia((prev) => ({ ...prev, [tabId]: [] }));
            setTabVisibleMedia((prev) => ({ ...prev, [tabId]: null }));
          }
          setTabPages((prev) => {
            const cur = prev[tabId];
            if (!cur || cur.isDemoShowcase) return prev;
            const progress = ev.started ? ev.progress : Math.max(cur.progress, ev.progress);
            return {
              ...prev,
              [tabId]: { ...cur, isLoading: ev.progress < 100, progress: Math.min(99, progress), error: null },
            };
          });
          break;
        }
        case 'url': {
          if (!ev.url || ev.url === tab.url) {
            nativePendingNavRef.current[tabId] = false;
            break;
          }
          const replace = nativePendingNavRef.current[tabId] === true;
          nativePendingNavRef.current[tabId] = false;
          setTabs((prev) =>
            prev.map((item) => {
              if (item.id !== tabId) return item;
              if (replace && item.historyIndex >= 0) {
                const stack = item.historyStack.slice();
                stack[item.historyIndex] = ev.url;
                return { ...item, url: ev.url, historyStack: stack, lastVisited: Date.now() };
              }
              // Consecutive duplicates never deserve their own entry (e.g. a redirect
              // settling on the same URL, or the pendingNav race with an older event).
              if (item.historyStack[item.historyIndex] === ev.url) {
                return { ...item, url: ev.url, lastVisited: Date.now() };
              }
              const grown = [...item.historyStack.slice(0, item.historyIndex + 1), ev.url];
              const stack = grown.length > MAX_HISTORY_STACK ? grown.slice(grown.length - MAX_HISTORY_STACK) : grown;
              return {
                ...item,
                url: ev.url,
                historyStack: stack,
                historyIndex: stack.length - 1,
                lastVisited: Date.now(),
              };
            })
          );
          setTabPages((prev) => {
            const cur = prev[tabId];
            return cur ? { ...prev, [tabId]: { ...cur, url: ev.url } } : prev;
          });
          break;
        }
        case 'title': {
          setTabs((prev) => prev.map((item) => (item.id === tabId ? { ...item, title: ev.title } : item)));
          setTabPages((prev) => {
            const cur = prev[tabId];
            return cur ? { ...prev, [tabId]: { ...cur, title: ev.title } } : prev;
          });
          break;
        }
        case 'finished': {
          const pageTitle = ev.title || hostOfUrl(ev.url) || ev.url;
          nativePendingNavRef.current[tabId] = false;
          setTabPages((prev) => {
            const cur = prev[tabId];
            if (!cur) return prev;
            return {
              ...prev,
              [tabId]: { ...cur, url: ev.url, title: pageTitle, isLoading: false, progress: 100, error: null },
            };
          });
          setTabs((prev) => prev.map((item) => (item.id === tabId ? { ...item, title: pageTitle } : item)));
          if (ev.blocked > 0) {
            setSettings((prev) => ({ ...prev, adBlockBlockedRequests: prev.adBlockBlockedRequests + ev.blocked }));
            logEvent('adblock', hostOfUrl(ev.url) || '', `blocked:${ev.blocked}`);
          }
          if (!tab.isPrivate && ev.url && !ev.url.startsWith('about:')) {
            setHistory((prev) => [
              { id: makeId('hist'), title: pageTitle, url: ev.url, visitedAt: Date.now() },
              ...prev.filter((h) => h.url !== ev.url).slice(0, 199),
            ]);
          }
          break;
        }
        case 'error': {
          const host = hostOfUrl(ev.url) || ev.url;
          setTabPages((prev) => {
            const cur = prev[tabId];
            if (!cur) return prev;
            return {
              ...prev,
              [tabId]: {
                ...cur,
                title: host,
                isLoading: false,
                progress: 100,
                error: ev.message ? `${t('error_page_load_failed')} (${ev.message})` : t('error_page_load_failed'),
              },
            };
          });
          logEvent('navigation', host, `load_failed:${ev.message || 'network'}`);
          break;
        }
        case 'media': {
          reportDetectedMediaFromBridge(tabId, ev.pageUrl || tab.url, ev.items);
          break;
        }
        case 'visible-media': {
          reportVisibleMediaFromBridge(tabId, ev.pageUrl || tab.url, ev.mediaUrl);
          break;
        }
        case 'find-result': {
          setFindInPage((prev) => (prev ? { ...prev, matches: ev.matches, activeMatch: ev.active } : null));
          break;
        }
        case 'download-request': {
          const lower = (ev.mimeType || '').toLowerCase();
          const ext = (ev.filename.split('.').pop() || '').toLowerCase();
          const looksLikeMedia =
            lower.startsWith('video/') || lower.startsWith('audio/') || lower.includes('mpegurl') || lower.includes('dash');
          if (!looksLikeMedia) {
            // General files (PDF, APK, ZIP, documents): straight into the native download queue.
            const filename = ev.filename || `file.${ext || 'bin'}`;
            const record: DownloadItemRecord = {
              id: makeId('dl'),
              url: ev.url,
              mediaTitle: filename,
              filename,
              mimeType: ev.mimeType || 'application/octet-stream',
              thumbnail: null,
              status: 'QUEUED',
              downloadedBytes: 0,
              totalBytes: ev.length > 0 ? ev.length : null,
              bytesPerSecond: 0,
              etaSeconds: null,
              isLocked: false,
              exportedToGallery: false,
              isHls: false,
              isDash: false,
              audioTrackUrl: null,
              createdAt: Date.now(),
              pageUrl: tab.url,
              isGeneralFile: true,
            };
            const isDuplicate = downloadsRef.current.some(
              (d) => d.url === ev.url && (d.status === 'DOWNLOADING' || d.status === 'QUEUED' || d.status === 'PAUSED')
            );
            if (!isDuplicate) {
              setDownloads((prev) => [record, ...prev]);
              showToast(t('download_enqueued'));
            }
            break;
          }
          openQualitySheetForCandidate({
            id: `dl-${ev.url}`,
            pageUrl: tab.url,
            mediaUrl: ev.url,
            title: ev.filename || null,
            mimeType: ev.mimeType || null,
            extension: ext || null,
            source: 'FETCH',
            confidence: 0.95,
            estimatedSize: ev.length > 0 ? ev.length : null,
          });
          break;
        }
        default:
          break;
      }
    },
    [
      t,
      logEvent,
      showToast,
      openQualitySheetForCandidate,
      reportDetectedMediaFromBridge,
      reportVisibleMediaFromBridge,
      createTab,
      updateSettings,
    ]
  );

  useEffect(() => {
    if (!isNativeBrowserAvailable()) return undefined;
    return registerNativeBrowserListener(handleNativeBrowserEvent);
  }, [handleNativeBrowserEvent]);

  // Downloads the native engine drives without registered callbacks: downloads it resumed on
  // its own after an app kill / reboot / Wi-Fi hold. Keep their UI state in sync.
  useEffect(() => {
    if (!isNativeAndroid()) return undefined;
    setUnmatchedNativeDownloadHandler((ev) => {
      setDownloads((prev) => {
        const known = prev.some((d) => d.id === ev.id);
        if (!known) return prev;
        switch (ev.type) {
          case 'restored':
          case 'progress':
            return prev.map((d) =>
              d.id === ev.id
                ? {
                    ...d,
                    status: 'DOWNLOADING',
                    error: null,
                    downloadedBytes: ev.type === 'progress' ? ev.downloadedBytes : d.downloadedBytes,
                    totalBytes: ev.type === 'progress' ? (ev.totalBytes ?? d.totalBytes) : d.totalBytes,
                    bytesPerSecond: ev.type === 'progress' ? ev.bytesPerSecond : d.bytesPerSecond,
                    etaSeconds: ev.type === 'progress' ? ev.etaSeconds : d.etaSeconds,
                  }
                : d
            );
          case 'complete':
            return prev.map((d) =>
              d.id === ev.id
                ? {
                    ...d,
                    status: 'COMPLETED',
                    downloadedBytes: ev.finalSize,
                    totalBytes: ev.finalSize,
                    bytesPerSecond: 0,
                    etaSeconds: null,
                    mimeType: ev.mimeType,
                    blobUrl: ev.blobUrl,
                    nativeUri: ev.nativeUri ?? d.nativeUri ?? null,
                    completedAt: Date.now(),
                  }
                : d
            );
          case 'paused':
            return prev.map((d) =>
              d.id === ev.id
                ? {
                    ...d,
                    status: 'PAUSED',
                    bytesPerSecond: 0,
                    etaSeconds: null,
                    error: ev.reason === 'wifi' ? WIFI_HOLD_MARKER : d.error,
                  }
                : d
            );
          case 'cancelled':
            return prev.map((d) => (d.id === ev.id ? { ...d, status: 'CANCELLED', bytesPerSecond: 0, etaSeconds: null } : d));
          case 'error':
            return prev.map((d) =>
              d.id === ev.id
                ? {
                    ...d,
                    status: 'FAILED',
                    error: ev.code === 'INSUFFICIENT_SPACE' ? t('download_insufficient_space') : ev.message,
                    bytesPerSecond: 0,
                    etaSeconds: null,
                  }
                : d
            );
          default:
            return prev;
        }
      });
    });
    return () => setUnmatchedNativeDownloadHandler(null);
  }, [t]);

  const [pageZoom, setPageZoom] = useState<number>(100);

  // Bookmarks & History helpers
  const isCurrentTabBookmarked = useMemo(
    () => Boolean(currentTab?.url && bookmarks.some((b) => b.url === currentTab.url)),
    [bookmarks, currentTab?.url]
  );

  const addBookmarkForCurrentTab = useCallback(() => {
    if (!currentTab?.url) return;
    setBookmarks((prev) => {
      if (prev.some((b) => b.url === currentTab.url)) return prev;
      return [{ id: makeId('bm'), title: currentTab.title || currentTab.url, url: currentTab.url, createdAt: Date.now() }, ...prev];
    });
    showToast(t('bookmark_added'));
  }, [currentTab, showToast, t]);

  const toggleBookmarkForCurrentTab = useCallback(() => {
    if (!currentTab?.url) return;
    const existing = bookmarks.find((b) => b.url === currentTab.url);
    if (existing) {
      setBookmarks((prev) => prev.filter((b) => b.id !== existing.id));
      showToast(t('bookmark_removed'));
    } else {
      addBookmarkForCurrentTab();
    }
  }, [currentTab?.url, bookmarks, addBookmarkForCurrentTab, showToast, t]);

  const deleteBookmark = useCallback((id: string) => {
    setBookmarks((prev) => prev.filter((b) => b.id !== id));
  }, []);

  const duplicateTab = useCallback(
    (tabId: string) => {
      const source = tabs.find((item) => item.id === tabId);
      if (!source) return;
      createTab({ isPrivate: source.isPrivate, url: source.url || undefined });
    },
    [tabs, createTab]
  );

  const closeOtherTabs = useCallback(
    (tabId: string) => {
      const target = tabsRef.current.find((item) => item.id === tabId);
      if (!target) return;
      const removedIds = tabsRef.current.filter((item) => item.id !== tabId).map((item) => item.id);
      for (const otherId of removedIds) {
        getSalviaNative()?.browserDestroy?.(otherId);
      }
      pruneTabRecords(removedIds);
      setTabs([target]);
      setCurrentTabId(target.id);
    },
    [pruneTabRecords]
  );

  const deleteHistoryEntry = useCallback((id: string) => {
    setHistory((prev) => prev.filter((h) => h.id !== id));
  }, []);

  const clearHistory = useCallback(() => {
    setHistory([]);
    showToast(t('settings_history_cleared'));
  }, [showToast, t]);

  const clearCookiesAndStorage = useCallback(() => {
    getSalviaNative()?.clearBrowsingData?.();
    showToast(t('settings_cookies_cleared'));
  }, [showToast, t]);

  const clearAllBrowsingData = useCallback(() => {
    getSalviaNative()?.clearBrowsingData?.();
    setHistory([]);
    showToast(t('settings_browsing_data_cleared'));
  }, [showToast, t]);

  const openUrlFromLibrary = useCallback(
    (url: string) => {
      setRoute('browser');
      loadUrlIntoTab(currentTabId, url, true);
    },
    [currentTabId, loadUrlIntoTab]
  );

  const getAddressSuggestions = useCallback(
    (query: string): AddressSuggestion[] => {
      const q = query.trim().toLowerCase();
      if (!q) return [];
      const results: AddressSuggestion[] = [];
      const seen = new Set<string>();
      for (const b of bookmarks) {
        if (b.title.toLowerCase().includes(q) || b.url.toLowerCase().includes(q)) {
          seen.add(b.url);
          results.push({ title: b.title, url: b.url, source: 'BOOKMARK' });
        }
      }
      for (const h of history) {
        if (!seen.has(h.url) && (h.title.toLowerCase().includes(q) || h.url.toLowerCase().includes(q))) {
          seen.add(h.url);
          results.push({ title: h.title, url: h.url, source: 'HISTORY' });
        }
      }
      return results.slice(0, 6);
    },
    [bookmarks, history]
  );

  const isAdBlockOffForCurrentSite = useCallback(() => {
    const host = hostOfUrl(currentTab?.url);
    return host ? settings.adBlockDisabledSites.includes(host) : false;
  }, [currentTab?.url, settings.adBlockDisabledSites]);

  const toggleAdBlockForCurrentSite = useCallback(() => {
    const host = hostOfUrl(currentTab?.url);
    if (!host) return;
    setSettings((prev) => {
      const isOff = prev.adBlockDisabledSites.includes(host);
      const nextList = isOff
        ? prev.adBlockDisabledSites.filter((h) => h !== host)
        : [...prev.adBlockDisabledSites, host];
      showToast(t(isOff ? 'ad_block_on_here' : 'ad_block_off_here', { host }));
      return { ...prev, adBlockDisabledSites: nextList };
    });
  }, [currentTab?.url, showToast, t]);

  const allowCleartextAndRetry = useCallback(() => {
    const target = blockedCleartextUrl;
    if (!target) return;
    setSettings((prev) => ({ ...prev, isCleartextAllowed: true }));
    setBlockedCleartextUrl(null);
    loadUrlIntoTab(currentTabId, target, true, true);
  }, [blockedCleartextUrl, currentTabId, loadUrlIntoTab]);

  const dismissCleartextBlock = useCallback(() => setBlockedCleartextUrl(null), []);

  // Update check
  const [updateCheck, setUpdateCheck] = useState<UpdateCheckState>({
    isChecking: false,
    availableVersion: null,
    availableUrl: null,
    availableNotes: null,
    availableApkUrl: null,
  });

  interface GithubReleaseInfo {
    tag_name?: string;
    html_url?: string;
    body?: string;
    assets?: Array<{ name?: string; browser_download_url?: string }>;
  }

  /**
   * Runs once per 24h on startup, silently: the app is distributed outside any store, so this
   * is the only channel through which users learn a new version exists. Manual checks from
   * Settings bypass the throttle and show toasts as before.
   */
  const runUpdateCheck = useCallback(async (silent: boolean) => {
    try {
      const res = await fetch(RELEASES_API_URL, { headers: { Accept: 'application/vnd.github+json' } });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const json = (await res.json()) as GithubReleaseInfo;
      const remoteVersion = (json.tag_name || '').replace(/^v/i, '').trim();
      const apkUrl =
        json.assets?.find((a) => (a.name || '').toLowerCase().endsWith('release.apk'))
          ?.browser_download_url ?? null;
      if (remoteVersion && isNewerVersion(remoteVersion, APP_VERSION)) {
        setUpdateCheck({
          isChecking: false,
          availableVersion: remoteVersion,
          availableUrl: json.html_url || null,
          availableNotes: json.body || null,
          availableApkUrl: apkUrl,
        });
      } else {
        setUpdateCheck({
          isChecking: false, availableVersion: null, availableUrl: null, availableNotes: null, availableApkUrl: null,
        });
        if (!silent) showToast(t('update_up_to_date'));
      }
    } catch {
      if (!silent) showToast(t('update_check_failed'));
    }
  }, [showToast, t]);

  // Silent auto-check on startup, at most once every 24 hours.
  useEffect(() => {
    const THROTTLE_MS = 24 * 60 * 60 * 1000;
    const KEY = 'salvia_last_update_check';
    try {
      const last = Number(window.localStorage.getItem(KEY) || '0');
      if (Number.isFinite(last) && Date.now() - last < THROTTLE_MS) return;
      window.localStorage.setItem(KEY, String(Date.now()));
    } catch {
      return; // storage unavailable → skip silently
    }
    void runUpdateCheck(true);
  }, [runUpdateCheck]);

  const checkForUpdates = useCallback(async () => {
    if (updateCheck.isChecking) return;
    setUpdateCheck((prev) => ({ ...prev, isChecking: true }));
    await runUpdateCheck(false);
  }, [updateCheck.isChecking, runUpdateCheck]);

  /**
   * Queues the signed release APK through the normal download pipeline. When it finishes, the
   * user taps it in Downloads and the system installer opens (with an "unknown sources"
   * prompt on Android 8+ the first time).
   */
  const downloadUpdate = useCallback(() => {
    const version = updateCheck.availableVersion;
    const apkUrl = updateCheck.availableApkUrl || updateCheck.availableUrl;
    if (!version || !apkUrl) return;
    const existing = downloads.find(
      (d) => d.url === apkUrl && (d.status === 'DOWNLOADING' || d.status === 'QUEUED' || d.status === 'PAUSED')
    );
    if (existing) {
      showToast(t('download_already_downloading', { name: existing.filename }));
      return;
    }
    const record: DownloadItemRecord = {
      id: makeId('dl'),
      url: apkUrl,
      mediaTitle: `SalviaBrowxer ${version}`,
      filename: `SalviaBrowxer-${version}.apk`,
      mimeType: 'application/vnd.android.package-archive',
      thumbnail: null,
      status: 'QUEUED',
      downloadedBytes: 0,
      totalBytes: null,
      bytesPerSecond: 0,
      etaSeconds: null,
      isLocked: false,
      exportedToGallery: false,
      isHls: false,
      isDash: false,
      audioTrackUrl: null,
      createdAt: Date.now(),
      pageUrl: null,
    };
    setDownloads((prev) => [record, ...prev]);
    setUpdateCheck((prev) => ({ ...prev, availableVersion: null }));
    showToast(t('update_downloading'));
  }, [updateCheck.availableVersion, updateCheck.availableApkUrl, updateCheck.availableUrl, downloads, showToast, t]);

  const dismissUpdateDialog = useCallback(() => {
    setUpdateCheck((prev) => ({ ...prev, availableVersion: null }));
  }, []);

  const exportDiagnostics = useCallback(() => {
    const payload = {
      app: 'SalviaBrowxer',
      version: APP_VERSION,
      generatedAt: new Date().toISOString(),
      adBlockStats: {
        enabled: settings.isAdBlockEnabled,
        blockedRequests: settings.adBlockBlockedRequests,
        exemptSites: settings.adBlockDisabledSites.length,
      },
      events: diagnostics,
    };
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    triggerBrowserFileSave(url, 'salviabrowxer-diagnostics.json');
    window.setTimeout(() => URL.revokeObjectURL(url), 5000);
  }, [settings.isAdBlockEnabled, settings.adBlockBlockedRequests, settings.adBlockDisabledSites.length, diagnostics]);

  const activeDownloadsCount = useMemo(
    () =>
      downloads.filter(
        (d) =>
          d.status === 'DOWNLOADING' ||
          d.status === 'QUEUED' ||
          d.status === 'PREPARING' ||
          d.status === 'RESOLVING' ||
          d.status === 'PAUSED'
      ).length,
    [downloads]
  );

  const completedDownloadsCount = useMemo(
    () => downloads.filter((d) => d.status === 'COMPLETED' && (!d.isLocked || isLockedFolderUnlocked)).length,
    [downloads, isLockedFolderUnlocked]
  );

  // Ignore unused reference check for isBlockedAdHost
  void isBlockedAdHost;

  const value: AppContextValue = {
    route,
    downloadsInitialTab,
    playerTarget,
    navigate,
    locale,
    isRtl,
    t,
    settings,
    updateSettings,
    tabs,
    currentTabId,
    currentTab,
    hibernatedTabIds,
    tabPages,
    tabMedia,
    tabVisibleMedia,
    isTabSwitcherVisible,
    openTabSwitcher: () => setIsTabSwitcherVisible(true),
    closeTabSwitcher: () => setIsTabSwitcherVisible(false),
    createTab,
    switchTab,
    closeTab,
    navigateCurrentTab,
    openPastedLink,
    isExtractingLink,
    extractFromPastedPage,
    reloadCurrentTab,
    stopLoadingCurrentTab,
    goBackCurrentTab,
    goForwardCurrentTab,
    goHomeInCurrentTab,
    blockedCleartextUrl,
    allowCleartextAndRetry,
    dismissCleartextBlock,
    isAdBlockOffForCurrentSite,
    toggleAdBlockForCurrentSite,
    bookmarks,
    isCurrentTabBookmarked,
    addBookmarkForCurrentTab,
    toggleBookmarkForCurrentTab,
    deleteBookmark,
    openUrlFromLibrary,
    duplicateTab,
    closeOtherTabs,
    pageZoom,
    setPageZoom,
    history,
    deleteHistoryEntry,
    clearHistory,
    clearCookiesAndStorage,
    clearAllBrowsingData,
    getAddressSuggestions,
    currentTabCandidates,
    rankedTabCandidates,
    currentMainCandidate,
    isMediaTrayVisible,
    openMediaTray: () => setIsMediaTrayVisible(true),
    closeMediaTray: () => setIsMediaTrayVisible(false),
    qualitySheet,
    openQualitySheetForCandidate,
    closeQualitySheet: () => setQualitySheet(null),
    startDownloadFromFormat,
    startBatchDownloadFromCandidates,
    downloadMainMedia,
    recording,
    startRecordingPlayback,
    stopRecordingPlayback,
    downloads,
    activeDownloadsCount,
    completedDownloadsCount,
    pauseDownload,
    resumeDownload,
    cancelDownload,
    retryDownload,
    deleteDownload,
    clearDownloadsByBucket,
    exportDownloadToDevice,
    playDownloadInApp,
    shareDownload,
    setDownloadLocked,
    isLockedFolderConfigured,
    isLockedFolderUnlocked,
    isUnlockingFolder,
    unlockLockedFolder,
    lockLockedFolder,
    saveLockedFolderPassword,
    removeLockedFolderPasswordAction,
    findInPage,
    openFindInPage: () => setFindInPage({ query: '', matches: 0, activeMatch: 0 }),
    closeFindInPage: () => setFindInPage(null),
    updateFindQuery: (query: string) => setFindInPage((prev) => (prev ? { ...prev, query } : { query, matches: 0, activeMatch: 0 })),
    setFindResult: (matches: number, activeMatch: number) =>
      setFindInPage((prev) => (prev ? { ...prev, matches, activeMatch } : null)),
    reportDetectedMediaFromBridge,
    reportVisibleMediaFromBridge,
    connectionKind,
    updateCheck,
    checkForUpdates,
    downloadUpdate,
    dismissUpdateDialog,
    diagnostics,
    exportDiagnostics,
    toastMessage,
    showToast,
  };

  useEffect(() => {
    return registerNativeBackHandler(() => {
      if (qualitySheet) {
        setQualitySheet(null);
        return true;
      }
      if (isMediaTrayVisible) {
        setIsMediaTrayVisible(false);
        return true;
      }
      if (isTabSwitcherVisible) {
        setIsTabSwitcherVisible(false);
        return true;
      }
      if (route === 'player') {
        setRoute('downloads');
        return true;
      }
      if (route === 'browser' && currentTab.historyIndex > 0) {
        goBackCurrentTab();
        return true;
      }
      if (route !== 'home') {
        setRoute('home');
        return true;
      }
      return false;
    });
  }, [
    qualitySheet,
    isMediaTrayVisible,
    isTabSwitcherVisible,
    route,
    currentTab.historyIndex,
    goBackCurrentTab,
  ]);

  // window.__salviaOpenUrl is registered ONCE by the host (links arriving from outside the
  // app), so it must read the latest state through refs instead of the first-render closure.
  const loadUrlIntoTabRef = useRef(loadUrlIntoTab);
  loadUrlIntoTabRef.current = loadUrlIntoTab;

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const openUrl = params.get('open');
    if (openUrl && /^https?:\/\//i.test(openUrl)) {
      loadUrlIntoTabRef.current(currentTabIdRef.current, openUrl, true);
    }
    const win = window as unknown as { __salviaOpenUrl?: (url: string) => void };
    win.__salviaOpenUrl = (nextUrl: string) => {
      if (nextUrl && /^https?:\/\//i.test(nextUrl)) {
        loadUrlIntoTabRef.current(currentTabIdRef.current, nextUrl, true);
      }
    };
    return () => {
      delete win.__salviaOpenUrl;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
};

export function useApp(): AppContextValue {
  const ctx = useContext(AppContext);
  if (!ctx) throw new Error('useApp must be used inside AppProvider');
  return ctx;
}
