import { useEffect, useRef, useState } from "react";

/** Pull down at the top of a screen to refresh it. The phone's own pull-to-refresh is switched off in the styles (it reloaded the whole page), so this refreshes the data instead. */
export const PULL_THRESHOLD = 56;
const MAX_PULL = 96;
const FACTOR = 0.6;   // the finger moves further than the indicator, like a native pull

/** The nearest scrolling parent of what was touched, or null when the page itself scrolls. */
const nearestScroller = (target: EventTarget | null): HTMLElement | null => {
  let node = target instanceof HTMLElement ? target : null;
  while (node && node !== document.body && node !== document.documentElement) {
    const { overflowY } = getComputedStyle(node);
    if ((overflowY === "auto" || overflowY === "scroll") && node.scrollHeight > node.clientHeight) return node;
    node = node.parentElement;
  }
  return null;
};

export function usePullToRefresh(enabled: boolean, onRefresh: () => Promise<void>) {
  const [pull, setPull] = useState(0);
  const [refreshing, setRefreshing] = useState(false);
  const gesture = useRef({ startY: 0, active: false, pull: 0 });
  const busy = useRef(false);
  const refreshRef = useRef(onRefresh);
  useEffect(() => { refreshRef.current = onRefresh; });   // always call the newest version, without reading a ref while rendering

  useEffect(() => {
    if (!enabled) return;
    // Only from the very top, never inside a scrolled list, and never while a sheet or dialog is open.
    const atTop = (target: EventTarget | null) =>
      window.scrollY <= 0 && (nearestScroller(target)?.scrollTop ?? 0) <= 0 && !document.querySelector(".sheet-backdrop, [role='dialog']");
    const start = (event: TouchEvent) => {
      if (busy.current || event.touches.length !== 1 || !atTop(event.target)) return;
      gesture.current = { startY: event.touches[0].clientY, active: true, pull: 0 };
    };
    const move = (event: TouchEvent) => {
      const g = gesture.current;
      if (!g.active) return;
      if (event.touches.length !== 1 || window.scrollY > 0) { g.active = false; setPull(0); return; }
      const dy = event.touches[0].clientY - g.startY;
      if (dy <= 0) { g.pull = 0; setPull(0); return; }
      g.pull = Math.min(MAX_PULL, dy * FACTOR);
      setPull(g.pull);
    };
    const end = async () => {
      const g = gesture.current;
      if (!g.active) return;
      g.active = false;
      if (g.pull < PULL_THRESHOLD) { setPull(0); return; }
      busy.current = true;
      setRefreshing(true);
      setPull(PULL_THRESHOLD);
      try { await Promise.all([refreshRef.current(), new Promise((resolve) => window.setTimeout(resolve, 700))]); }
      catch { /* a failed refresh keeps what is on screen */ }
      busy.current = false;
      setRefreshing(false);
      setPull(0);
    };
    document.addEventListener("touchstart", start, { passive: true });
    document.addEventListener("touchmove", move, { passive: true });
    document.addEventListener("touchend", end);
    document.addEventListener("touchcancel", end);
    return () => {
      document.removeEventListener("touchstart", start);
      document.removeEventListener("touchmove", move);
      document.removeEventListener("touchend", end);
      document.removeEventListener("touchcancel", end);
    };
  }, [enabled]);

  return { pull, refreshing };
}
