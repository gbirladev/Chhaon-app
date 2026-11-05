import { useEffect, useState } from "react";
import { updateTabIcon } from "./logo";

/** Appearance: System (default, follows the phone), Light or Dark. Remembered on this device only.
 *  public/theme.js applies the saved choice before the first paint; this file keeps it in step afterwards.
 *  The CSS reads <html data-theme="light|dark">; with no attribute (System) prefers-color-scheme decides. */
export type ThemeChoice = "system" | "light" | "dark";
export const THEME_CHOICES: ThemeChoice[] = ["system", "light", "dark"];

const KEY = "chhaon-theme";
/** Same colours as public/theme.js. The browser's own top bar: brand teal in light, the page colour in dark. */
const BAR_LIGHT = "#faf8fd";
const BAR_DARK = "#14101f";

export function readThemeChoice(): ThemeChoice {
  try {
    const saved = localStorage.getItem(KEY);
    return saved === "light" || saved === "dark" ? saved : "system";
  } catch { return "system"; }
}

const systemIsDark = () => typeof window.matchMedia === "function" && window.matchMedia("(prefers-color-scheme: dark)").matches;

function paint(choice: ThemeChoice) {
  const root = document.documentElement;
  if (choice === "system") { root.removeAttribute("data-theme"); root.style.removeProperty("color-scheme"); }
  else { root.setAttribute("data-theme", choice); root.style.colorScheme = choice; }
  const dark = choice === "dark" || (choice === "system" && systemIsDark());
  document.querySelector('meta[name="theme-color"]')?.setAttribute("content", dark ? BAR_DARK : BAR_LIGHT);
  updateTabIcon();   // dark mode has its own primary colour
}

export function applyThemeChoice(choice: ThemeChoice) {
  try { if (choice === "system") localStorage.removeItem(KEY); else localStorage.setItem(KEY, choice); } catch { /* storage blocked: it still applies until the app is closed */ }
  paint(choice);
  window.dispatchEvent(new Event("chhaon-theme"));
}

/** Call once at start: follows the phone's setting live while on System, and another open tab changing the choice. */
export function initTheme() {
  paint(readThemeChoice());
  if (typeof window.matchMedia === "function") window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => paint(readThemeChoice()));
  window.addEventListener("storage", (event) => {
    if (event.key === KEY || event.key === null) { paint(readThemeChoice()); window.dispatchEvent(new Event("chhaon-theme")); }
  });
}

export function useThemeChoice(): [ThemeChoice, (next: ThemeChoice) => void] {
  const [choice, setChoice] = useState<ThemeChoice>(readThemeChoice);
  useEffect(() => {
    const sync = () => setChoice(readThemeChoice());
    window.addEventListener("chhaon-theme", sync);
    return () => window.removeEventListener("chhaon-theme", sync);
  }, []);
  return [choice, (next) => { setChoice(next); applyThemeChoice(next); }];
}
