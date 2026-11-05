/** The Chhaon logo ("C": a roof over a heart), on a 64x64 rounded tile. The one source of its shapes:
 *  screens/Logo.tsx draws it in the app, updateTabIcon() makes the browser-tab icon. The static files in public/
 *  (favicon.svg, favicon.ico, the PNG icons) are drawn from the same shapes in the default violet. */
export const LOGO_ROOF = "M13 30 32 14l19 16";
export const LOGO_HEART = "M32 50 21.5 40a6.6 6.6 0 0 1 9.3-9.4l1.2 1.1 1.2-1.1a6.6 6.6 0 0 1 9.3 9.4Z";

/** The logo as SVG markup. Colours must be plain CSS colours (no var()). */
export const logoSvg = (tile: string, ink = "#ffffff") =>
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="14" fill="${tile}"/>` +
  `<path fill="none" stroke="${ink}" stroke-width="5.5" stroke-linecap="round" stroke-linejoin="round" d="${LOGO_ROOF}"/>` +
  `<path fill="${ink}" d="${LOGO_HEART}"/></svg>`;

const plainColour = (c: string) => /^(#[0-9a-f]{3,8}|rgba?\([\d\s.,%/]+\))$/i.test(c);

/** Browser-tab icon in the current colour theme: reads the page's --primary / --on-fill and puts the logo in those
 *  colours into the SVG <link rel="icon"> as a data URL. Called after every colour or light/dark change.
 *  Only the tab icon follows the theme: an installed home-screen icon is fixed at install time (the violet files in public/). */
export function updateTabIcon() {
  if (typeof document === "undefined" || typeof getComputedStyle !== "function") return;
  const style = getComputedStyle(document.documentElement);
  const tile = style.getPropertyValue("--primary").trim();
  const ink = style.getPropertyValue("--on-fill").trim() || "#ffffff";
  let link = document.querySelector<HTMLLinkElement>('link[rel="icon"][type="image/svg+xml"]');
  if (!link) {
    link = document.createElement("link");
    link.rel = "icon";
    link.type = "image/svg+xml";
    link.setAttribute("sizes", "any");
    document.head.appendChild(link);
  }
  // Anything that is not a plain colour (cannot be read, or unsafe in markup): the static violet file.
  link.href = plainColour(tile) && plainColour(ink) ? `data:image/svg+xml,${encodeURIComponent(logoSvg(tile, ink))}` : "/favicon.svg";
}
