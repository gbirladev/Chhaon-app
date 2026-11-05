import { useEffect, useState } from "react";

/** Colour theme. "gurukul" (Smart Gurukul violet, theme.css) is the default and means NO data-palette attribute;
 *  the others are generated into src/palettes.css by scripts/build-palettes.mjs. Remembered on this device only,
 *  like light/dark. public/theme.js applies the saved choice before the first paint: keep the key in step. */
export const PALETTES = ["gurukul", "teal", "blue", "green", "saffron", "rose"] as const;
export type Palette = (typeof PALETTES)[number];
export const DEFAULT_PALETTE: Palette = "gurukul";

/** The swatch in the picker: each theme's primary button colour. */
export const PALETTE_SWATCH: Record<Palette, string> = {
  gurukul: "#6d28d9", teal: "#19625b", blue: "#24589d", green: "#1f643c", saffron: "#824b1a", rose: "#a12951",
};

const KEY = "chhaon-palette";
const EVENT = "chhaon-palette";

export function readPalette(): Palette {
  try {
    const saved = localStorage.getItem(KEY);
    return (PALETTES as readonly string[]).includes(saved ?? "") ? (saved as Palette) : DEFAULT_PALETTE;
  } catch { return DEFAULT_PALETTE; }
}

function paint(palette: Palette) {
  const root = document.documentElement;
  if (palette === DEFAULT_PALETTE) root.removeAttribute("data-palette");
  else root.setAttribute("data-palette", palette);
}

export function applyPalette(palette: Palette) {
  try { if (palette === DEFAULT_PALETTE) localStorage.removeItem(KEY); else localStorage.setItem(KEY, palette); } catch { /* storage blocked */ }
  paint(palette);
  window.dispatchEvent(new Event(EVENT));
}

export function initPalette() {
  paint(readPalette());
  window.addEventListener("storage", (event) => {
    if (event.key === KEY || event.key === null) { paint(readPalette()); window.dispatchEvent(new Event(EVENT)); }
  });
}

export function usePalette(): [Palette, (next: Palette) => void] {
  const [palette, setPalette] = useState<Palette>(readPalette);
  useEffect(() => {
    const sync = () => setPalette(readPalette());
    window.addEventListener(EVENT, sync);
    return () => window.removeEventListener(EVENT, sync);
  }, []);
  return [palette, (next) => { setPalette(next); applyPalette(next); }];
}
