/**
 * The "Custom" colour theme: any colour the user picks. The violet tokens of the default theme (exported by
 * scripts/build-palettes.mjs into src/paletteTokens.json) are turned to the picked hue, and each one keeps its own
 * brightness (relative luminance). So text keeps the same contrast as in the Smart Gurukul theme, whatever the colour:
 * a very light or very dark pick is simply drawn at the right shade. Same maths as scripts/build-palettes.mjs: keep in step.
 */
type Rgb = [number, number, number];

const hexToRgb = (hex: string): Rgb => [0, 2, 4].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255) as Rgb;
const rgbToHex = (rgb: Rgb) => rgb.map((v) => Math.round(Math.min(1, Math.max(0, v)) * 255).toString(16).padStart(2, "0")).join("");

function rgbToHsl([r, g, b]: Rgb): [number, number, number] {
  const max = Math.max(r, g, b), min = Math.min(r, g, b), l = (max + min) / 2;
  if (max === min) return [0, 0, l];
  const d = max - min, s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
  const h = max === r ? (g - b) / d + (g < b ? 6 : 0) : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
  return [h * 60, s, l];
}
function hslToRgb([h, s, l]: [number, number, number]): Rgb {
  const k = (n: number) => (n + h / 30) % 12, a = s * Math.min(l, 1 - l);
  return [0, 8, 4].map((n) => l - a * Math.max(-1, Math.min(k(n) - 3, 9 - k(n), 1))) as Rgb;
}
const lin = (v: number) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4);
const luminance = (rgb: Rgb) => 0.2126 * lin(rgb[0]) + 0.7152 * lin(rgb[1]) + 0.0722 * lin(rgb[2]);

type Target = { hue: number; sat: number };
const VIOLET = (h: number, s: number) => h >= 235 && h <= 305 && s >= 0.06;

function shift(hex: string, { hue, sat }: Target): string | null {
  const rgb = hexToRgb(hex);
  const [h, s] = rgbToHsl(rgb);
  if (!VIOLET(h, s)) return null;
  const goal = luminance(rgb), ns = Math.min(1, s * sat);
  let lo = 0, hi = 1;
  for (let i = 0; i < 30; i++) {
    const mid = (lo + hi) / 2;
    if (luminance(hslToRgb([hue, ns, mid])) < goal) lo = mid; else hi = mid;
  }
  return rgbToHex(hslToRgb([hue, ns, (lo + hi) / 2]));
}

function recolour(value: string, target: Target) {
  return value
    .replace(/#([0-9a-fA-F]{6})\b/g, (m, hex: string) => { const out = shift(hex, target); return out ? `#${out}` : m; })
    .replace(/%23([0-9a-fA-F]{6})/g, (m, hex: string) => { const out = shift(hex, target); return out ? `%23${out}` : m; })
    .replace(/rgba?\((\d+),\s*(\d+),\s*(\d+)((?:,\s*[\d.]+)?)\)/g, (m, r: string, g: string, b: string, alpha: string) => {
      const out = shift([r, g, b].map((v) => Number(v).toString(16).padStart(2, "0")).join(""), target);
      if (!out) return m;
      const [nr, ng, nb] = hexToRgb(out).map((v) => Math.round(v * 255));
      return `${alpha ? "rgba" : "rgb"}(${nr},${ng},${nb}${alpha.replace(/\s/g, "")})`;
    });
}

/** The picked colour as a hue and a saturation factor (the default violet button is about 70% saturated). */
function targetOf(picked: string): Target {
  const [h, s] = rgbToHsl(hexToRgb(picked.replace("#", "")));
  return { hue: h, sat: Math.min(1.3, s / 0.7) };
}

/** The button colour this custom theme will really use (for the swatch in the picker). */
export function customPrimary(picked: string) {
  return `#${shift("6d28d9", targetOf(picked)) ?? "6d28d9"}`;
}

/** The CSS for data-palette="custom", built from the default theme's violet tokens. */
export async function customPaletteCss(picked: string) {
  const tokens = (await import("../paletteTokens.json")).default as { light: [string, string][]; dark: [string, string][]; systemDark: [string, string][] };
  const target = targetOf(picked);
  const lines = (list: [string, string][]) => list.map(([name, value]) => `${name}:${recolour(value, target)};`).join("");
  return `:root[data-palette="custom"]{${lines(tokens.light)}}`
    + `@media screen{:root[data-palette="custom"][data-theme="dark"]{${lines(tokens.dark)}}`
    + `@media (prefers-color-scheme: dark){:root[data-palette="custom"]:not([data-theme="light"]):not([data-theme="dark"]){${lines(tokens.systemDark)}}}}`;
}
