export interface SalviaNativeApi {
  isAndroidApp: () => boolean;
  getApiBase: () => string;
  /** Unlocks the state-changing methods (one-time token delivered by the host to the main frame). */
  armUiBridge?: (token: string) => void;
  startDownload: (
    id: string,
    url: string,
    filename: string,
    mimeType: string,
    isHls: boolean,
    parallelConnections: number,
    autoSaveToGallery: boolean,
    audioTrackUrl?: string | null,
    headersJson?: string | null,
    wifiOnly?: boolean | null
  ) => void;
  pauseDownload: (id: string) => void;
  cancelDownload: (id: string) => void;
  resumeDownload?: (id: string) => void;
  hasPersistedDownload?: (id: string) => boolean;
  openNativeFile: (uriString: string, mimeType: string) => boolean;
  shareNativeFile: (uriString: string, title: string, mimeType: string) => boolean;
  minimizeApp: () => void;
  /** Optional: only present in app builds that include the matching native feature. */
  setDownloadReferer?: (id: string, referer: string) => void;
  hasNativeBrowser?: () => boolean;
  browserShow?: (tabId: string, url: string, desktop: boolean, adBlock: boolean, isPrivate?: boolean) => void;
  browserLoad?: (tabId: string, url: string, desktop: boolean, adBlock: boolean, isPrivate?: boolean) => void;
  browserReload?: (tabId: string) => void;
  browserStop?: (tabId: string) => void;
  browserDestroy?: (tabId: string) => void;
  browserHide?: () => void;
  browserSetBounds?: (left: number, top: number, width: number, height: number) => void;
  browserSetInteraction?: (shield: boolean, hotRectsJson: string) => void;
  browserFind?: (tabId: string, query: string, backwards: boolean) => void;
  browserApplySettings?: (javaScript: boolean, cookies: boolean, textZoom: number, darkPages?: boolean) => void;
  browserSetDesktop?: (tabId: string, desktop: boolean) => void;
  browserPrint?: (tabId: string) => void;
  clearBrowsingData?: () => void;
  openInExternalBrowser?: (url: string) => boolean;
  pickDownloadFolder?: () => void;
  getDownloadFolderName?: () => string;
  resetDownloadFolder?: () => void;
  /** Number of native crash reports stored on this device (self-update / diagnostics build). */
  getCrashReportCount?: () => number;
  /** Shares the stored crash reports as a zip via the Android share sheet. */
  shareCrashReports?: () => boolean;
  /** Opens the system installer for an APK the app downloaded; false = missing "unknown sources" grant. */
  installApk?: (uriString: string) => boolean;
}

export interface NativeBrowserMediaItem {
  url: string;
  tag: string;
  type: string;
  visible: boolean;
  /** Quality label like "1080p · 2.4 Mbps" (HLS variants), a language name (renditions)… */
  label?: string;
  /** 'variant' | 'subtitle' | 'audio-track' | 'mse-feed' when the item is a labeled stream part. */
  kind?: string;
  language?: string;
  width?: number | null;
  height?: number | null;
  durationMs?: number | null;
  muted?: boolean;
  hasControls?: boolean;
  isAd?: boolean;
  isSuggested?: boolean;
}

/** Events sent by the native browser engine (BrowserHost.kt). */
export type NativeBrowserEvent =
  | { type: 'loading'; tabId: string; url: string; progress: number; started?: boolean }
  | { type: 'finished'; tabId: string; url: string; title: string; blocked: number }
  | { type: 'title'; tabId: string; title: string }
  | { type: 'url'; tabId: string; url: string }
  | { type: 'error'; tabId: string; url: string; message: string }
  | { type: 'media'; tabId: string; pageUrl: string; items: NativeBrowserMediaItem[] }
  | { type: 'visible-media'; tabId: string; pageUrl: string; mediaUrl: string }
  | { type: 'find-result'; tabId: string; matches: number; active: number }
  | { type: 'new-tab'; url: string }
  | { type: 'download-folder-changed'; uri: string }
  | { type: 'browsing-data-cleared' }
  | {
      type: 'download-request';
      tabId: string;
      url: string;
      mimeType: string;
      filename: string;
      length: number;
    };

export type NativeDownloadEvent =
  | {
      type: 'progress';
      id: string;
      downloadedBytes: number;
      totalBytes: number | null;
      bytesPerSecond: number;
      etaSeconds: number | null;
    }
  | {
      type: 'complete';
      id: string;
      blobUrl: string;
      finalSize: number;
      mimeType: string;
      nativeUri?: string | null;
    }
  | { type: 'paused'; id: string; reason?: string }
  | { type: 'cancelled'; id: string }
  | { type: 'restored'; id: string }
  | { type: 'error'; id: string; message: string; code?: string | null };

declare global {
  interface Window {
    SalviaNative?: SalviaNativeApi;
    __salviaNativeDownloadEvent?: (event: NativeDownloadEvent) => void;
    __salviaNativeBack?: () => boolean;
    __salviaNativeBrowserEvent?: (event: NativeBrowserEvent) => void;
  }
}

export function getSalviaNative(): SalviaNativeApi | null {
  if (typeof window === 'undefined') return null;
  return window.SalviaNative && typeof window.SalviaNative.isAndroidApp === 'function'
    ? window.SalviaNative
    : null;
}

export function isNativeAndroid(): boolean {
  try {
    return Boolean(getSalviaNative()?.isAndroidApp());
  } catch {
    return false;
  }
}

export function apiUrl(path: string): string {
  const native = getSalviaNative();
  if (!native) return path;
  const base = native.getApiBase?.() || '';
  return `${base}${path}`;
}

export function registerNativeDownloadListener(
  handler: (event: NativeDownloadEvent) => void
): () => void {
  if (typeof window === 'undefined') return () => {};
  window.__salviaNativeDownloadEvent = handler;
  return () => {
    if (window.__salviaNativeDownloadEvent === handler) {
      delete window.__salviaNativeDownloadEvent;
    }
  };
}

export function registerNativeBackHandler(handler: () => boolean): () => void {
  if (typeof window === 'undefined') return () => {};
  window.__salviaNativeBack = handler;
  return () => {
    if (window.__salviaNativeBack === handler) {
      delete window.__salviaNativeBack;
    }
  };
}

/** True when this build of the Android app can browse with a real native WebView. */
export function isNativeBrowserAvailable(): boolean {
  try {
    const native = getSalviaNative();
    return Boolean(native && typeof native.browserShow === 'function' && native.hasNativeBrowser?.());
  } catch {
    return false;
  }
}

export function registerNativeBrowserListener(
  handler: (event: NativeBrowserEvent) => void
): () => void {
  if (typeof window === 'undefined') return () => {};
  window.__salviaNativeBrowserEvent = handler;
  return () => {
    if (window.__salviaNativeBrowserEvent === handler) {
      delete window.__salviaNativeBrowserEvent;
    }
  };
}
