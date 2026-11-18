import { getSalviaNative } from './nativeBridge';

/**
 * The native page is drawn *under* the transparent React UI. Android has to know when a touch
 * belongs to the UI instead of the page:
 *  - "shield": any overlay (dialog, menu, suggestions) is open -> the UI gets every touch;
 *  - "hot rects": small UI elements that float above the page (the download button).
 * Elements opt in with data-salvia-overlay / data-salvia-hot; role="dialog" counts as an overlay.
 */
type Rect = [number, number, number, number];

function collect(): { shield: boolean; hot: Rect[] } {
  const shield = document.querySelector('[data-salvia-overlay], [role="dialog"]') !== null;
  const hot: Rect[] = [];
  document.querySelectorAll('[data-salvia-hot]').forEach((el) => {
    const r = el.getBoundingClientRect();
    if (r.width > 0 && r.height > 0) {
      hot.push([Math.floor(r.left), Math.floor(r.top), Math.ceil(r.right), Math.ceil(r.bottom)]);
    }
  });
  return { shield, hot: hot.slice(0, 16) };
}

export function startOverlayTracker(): () => void {
  const native = getSalviaNative();
  if (!native?.browserSetInteraction) return () => {};

  let frame = 0;
  let lastKey = '';

  const flush = () => {
    frame = 0;
    const { shield, hot } = collect();
    const json = JSON.stringify(hot);
    const key = `${shield ? 1 : 0}|${json}`;
    if (key === lastKey) return;
    lastKey = key;
    native.browserSetInteraction?.(shield, json);
  };
  const schedule = () => {
    if (frame === 0) frame = window.requestAnimationFrame(flush);
  };

  const observer = new MutationObserver(schedule);
  observer.observe(document.body, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ['style', 'class', 'data-salvia-overlay', 'data-salvia-hot'],
  });
  window.addEventListener('resize', schedule);
  schedule();

  // CSS transitions (the floating button's magnetic snap) and transforms change a hot
  // element's rect without any DOM mutation firing; resample a few times a second so the
  // native layer is never left routing touches through a stale rectangle.
  const sampler = window.setInterval(() => {
    if (frame === 0) schedule();
  }, 300);

  return () => {
    observer.disconnect();
    window.removeEventListener('resize', schedule);
    window.clearInterval(sampler);
    if (frame !== 0) window.cancelAnimationFrame(frame);
    // Back to "UI gets everything" so no stale rectangle can swallow touches.
    native.browserSetInteraction?.(true, '[]');
  };
}
