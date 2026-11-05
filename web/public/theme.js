/* Sets the colour theme before the first paint, so a dark phone never flashes white.
   Loaded synchronously from index.html (a separate file because the production CSP is script-src 'self': no inline scripts).
   Keep the key and the two colours in step with src/lib/theme.ts. Storage may be blocked: then it is simply System. */
(function () {
  var choice = null;
  try { choice = localStorage.getItem("chhaon-theme"); } catch (e) { /* storage blocked */ }
  var root = document.documentElement;
  if (choice === "light" || choice === "dark") { root.setAttribute("data-theme", choice); root.style.colorScheme = choice; }  // colorScheme: dark canvas even before the CSS arrives
  var dark = choice === "dark" || (choice !== "light" && !!window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches);
  // Colour theme (src/lib/palette.ts): no attribute = the Smart Gurukul default.
  var palette = null;
  try { palette = localStorage.getItem("chhaon-palette"); } catch (e) { /* storage blocked */ }
  if (palette && /^(teal|blue|green|saffron|rose)$/.test(palette)) root.setAttribute("data-palette", palette);
  if (palette === "custom") {
    // the user's own colour: the CSS was made by src/lib/recolour.ts when they picked it
    var css = null;
    try { css = localStorage.getItem("chhaon-palette-css"); } catch (e) { /* storage blocked */ }
    if (css) {
      var style = document.createElement("style");
      style.id = "chhaon-custom-palette";
      style.textContent = css;
      document.head.appendChild(style);
      root.setAttribute("data-palette", "custom");
    }
  }
  var meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute("content", dark ? "#14101f" : "#faf8fd");
})();
