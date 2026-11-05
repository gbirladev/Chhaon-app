import { useEffect, useState } from "react";
import { customPaletteCss, customPrimary } from "./recolour";

/** Colour theme. "chhaon" (the violet of theme.css) is the default and means NO data-palette attribute;
 *  the ready-made ones are generated into src/palettes.css by scripts/build-palettes.mjs; "custom" is any colour the
 *  user picks, turned into CSS in the browser (src/lib/recolour.ts) and kept in storage so public/theme.js can put it
 *  back before the first paint. Remembered on this device only, like light/dark. Keep the keys in step with theme.js. */
export const PALETTES = ["chhaon", "teal", "blue", "green", "saffron", "rose"] as const;
export type Palette = (typeof PALETTES)[number] | "custom";
export const DEFAULT_PALETTE: Palette = "chhaon";
export const DEFAULT_CUSTOM = "#e0457b";

/** The swatch in the picker: each theme's primary button colour. */
export const PALETTE_SWATCH: Record<(typeof PALETTES)[number], string> = {
  chhaon: "#6d28d9", teal: "#19625b", blue: "#24589d", green: "#1f643c", saffron: "#824b1a", rose: "#a12951",
};

const KEY = "chhaon-palette";
const CUSTOM_KEY = "chhaon-palette-custom";   // the colour the user picked, #rrggbb
const CSS_KEY = "chhaon-palette-css";          // the CSS made from it
const STYLE_ID = "chhaon-custom-palette";      // same id as in public/theme.js
const EVENT = "chhaon-palette";

const read = (key: string) => { try { return localStorage.getItem(key); } catch { return null; } };
const write = (key: string, value: string | null) => {
  try { if (value === null) localStorage.removeItem(key); else localStorage.setItem(key, value); } catch { /* storage blocked */ }
};

export function readPalette(): Palette {
  const saved = read(KEY);
  if (saved === "custom") return "custom";
  return (PALETTES as readonly string[]).includes(saved ?? "") ? (saved as Palette) : DEFAULT_PALETTE;
}
export const readCustomColour = () => { const c = read(CUSTOM_KEY); return c && /^#[0-9a-f]{6}$/i.test(c) ? c : DEFAULT_CUSTOM; };
export const swatchOf = (palette: Palette) => (palette === "custom" ? customPrimary(readCustomColour()) : PALETTE_SWATCH[palette]);

function setCustomStyle(css: string | null) {
  let el = document.getElementById(STYLE_ID);
  if (!css) { el?.remove(); return; }
  if (!el) { el = document.createElement("style"); el.id = STYLE_ID; document.head.appendChild(el); }
  el.textContent = css;
}

async function paint(palette: Palette) {
  const root = document.documentElement;
  if (palette === "custom") {
    let css = read(CSS_KEY);
    if (!css) { css = await customPaletteCss(readCustomColour()); write(CSS_KEY, css); }
    setCustomStyle(css);
  }
  if (palette === DEFAULT_PALETTE) root.removeAttribute("data-palette");
  else root.setAttribute("data-palette", palette);
}

export async function applyPalette(palette: Palette, customColour?: string) {
  if (palette === "custom" && customColour) {
    write(CUSTOM_KEY, customColour);
    write(CSS_KEY, await customPaletteCss(customColour));
  }
  write(KEY, palette === DEFAULT_PALETTE ? null : palette);
  await paint(palette);
  window.dispatchEvent(new Event(EVENT));
}

export function initPalette() {
  void paint(readPalette());
  window.addEventListener("storage", (event) => {
    if (event.key === KEY || event.key === CSS_KEY || event.key === null) { void paint(readPalette()); window.dispatchEvent(new Event(EVENT)); }
  });
}

export function usePalette(): [Palette, string, (next: Palette, customColour?: string) => void] {
  const [palette, setPalette] = useState<Palette>(readPalette);
  const [custom, setCustom] = useState(readCustomColour);
  useEffect(() => {
    const sync = () => { setPalette(readPalette()); setCustom(readCustomColour()); };
    window.addEventListener(EVENT, sync);
    return () => window.removeEventListener(EVENT, sync);
  }, []);
  return [palette, custom, (next, customColour) => {
    setPalette(next);
    if (customColour) setCustom(customColour);
    void applyPalette(next, customColour);
  }];
}
