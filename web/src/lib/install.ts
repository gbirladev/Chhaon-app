import { useSyncExternalStore } from "react";

/** "Add to home screen" help. Android Chrome can install with one tap (it sends an event we keep); iPhone has no such event, so it gets steps. */
export type InstallPlatform = "ios" | "android" | "other";

type InstallEvent = Event & { prompt: () => Promise<void>; userChoice: Promise<{ outcome: "accepted" | "dismissed" }> };

const DISMISS_KEY = "chhaon-install-dismissed";
const HIDE_DAYS = 14;

let deferred: InstallEvent | null = null;
let installed = false;
const listeners = new Set<() => void>();
const notify = () => listeners.forEach((l) => l());

if (typeof window !== "undefined") {
  window.addEventListener("beforeinstallprompt", (event) => {
    event.preventDefault();               // keep Chrome's own small bar away: we show our own banner
    deferred = event as InstallEvent;
    notify();
  });
  window.addEventListener("appinstalled", () => { installed = true; deferred = null; notify(); });
}

export function platformOf(ua = navigator.userAgent, touchPoints = navigator.maxTouchPoints): InstallPlatform {
  if (/iphone|ipad|ipod/i.test(ua) || (/macintosh/i.test(ua) && touchPoints > 1)) return "ios";   // iPadOS pretends to be a Mac
  if (/android/i.test(ua)) return "android";
  return "other";
}

/** Already opened from the home screen, so there is nothing to explain. */
export const isStandalone = () =>
  (typeof window.matchMedia === "function" && window.matchMedia("(display-mode: standalone)").matches) || (navigator as Navigator & { standalone?: boolean }).standalone === true;

const dismissedRecently = () => {
  try {
    const at = Number(localStorage.getItem(DISMISS_KEY));
    return Number.isFinite(at) && at > 0 && Date.now() - at < HIDE_DAYS * 86_400_000;
  } catch { return false; }
};
export const dismissInstallHelp = () => { try { localStorage.setItem(DISMISS_KEY, String(Date.now())); } catch { /* storage blocked */ } notify(); };

export type InstallState = { platform: InstallPlatform; canPrompt: boolean; show: boolean };
let snapshot: InstallState = { platform: "other", canPrompt: false, show: false };
const compute = (): InstallState => {
  const platform = platformOf();
  const next: InstallState = { platform, canPrompt: !!deferred, show: platform !== "other" && !installed && !isStandalone() && !dismissedRecently() };
  // useSyncExternalStore needs the same object back when nothing changed
  if (next.platform === snapshot.platform && next.canPrompt === snapshot.canPrompt && next.show === snapshot.show) return snapshot;
  snapshot = next;
  return snapshot;
};

export function useInstallState(): InstallState {
  return useSyncExternalStore((cb) => { listeners.add(cb); return () => listeners.delete(cb); }, compute, () => snapshot);
}

/** Asks the browser to install (Android Chrome). Returns true if the person accepted. */
export async function promptInstall(): Promise<boolean> {
  if (!deferred) return false;
  const event = deferred;
  deferred = null;
  try {
    await event.prompt();
    const choice = await event.userChoice;
    if (choice.outcome === "accepted") installed = true;
    notify();
    return choice.outcome === "accepted";
  } catch { notify(); return false; }
}
