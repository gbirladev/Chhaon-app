// Builds the public Privacy policy and Terms pages (plain HTML, English + Hindi) from src/content/legal.ts.
// Run: node scripts/build-legal-pages.mjs   (also runs before every `npm run build`)
// The pages are served at /legal/privacy.html and /legal/terms.html and can be used as the URLs Meta asks for.
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";

mkdirSync("public/legal", { recursive: true });
// legal.ts is plain data with a few type lines: strip the types so Node can read it without a compiler.
const source = readFileSync("src/content/legal.ts", "utf8")
  .replace(/^export type .*$/gm, "")
  .replace(/^type .*$/gm, "")
  .replace(/: Both = /g, " = ")
  .replace(/ as const;/g, ";");
const { LEGAL } = await import("data:text/javascript;base64," + Buffer.from(source).toString("base64"));

const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
const section = (s) => `<section><h2>${esc(s.h)}</h2>${(s.p ?? []).map((p) => `<p>${esc(p)}</p>`).join("")}${s.ul ? `<ul>${s.ul.map((x) => `<li>${esc(x)}</li>`).join("")}</ul>` : ""}</section>`;
const page = (kind, other) => {
  const en = LEGAL[kind].en, hi = LEGAL[kind].hi;
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<meta name="robots" content="index,follow" />
<title>${esc(en.title)} | Chhaon</title>
<meta name="description" content="${kind === "privacy" ? "What Chhaon keeps about your family, why, who can see it, and your choices, in plain words." : "The terms for using Chhaon, in plain words."}" />
<link rel="canonical" href="https://chhaon.site/legal/${kind}.html" />
<meta property="og:site_name" content="Chhaon" />
<meta property="og:type" content="website" />
<meta property="og:url" content="https://chhaon.site/legal/${kind}.html" />
<meta property="og:title" content="${esc(en.title)} | Chhaon" />
<meta property="og:image" content="https://chhaon.site/og-image.jpg" />
<link rel="icon" href="/favicon.ico" sizes="48x48" />
<link rel="icon" href="/favicon.svg" type="image/svg+xml" sizes="any" />
<link rel="icon" href="/favicon-96.png" type="image/png" sizes="96x96" />
<style>
  :root { --teal:#247c72; --ink:#173d3a; --muted:#566966; --bg:#f8faf8; }
  * { box-sizing: border-box; }
  body { margin:0; font:16px/1.6 system-ui,-apple-system,"Segoe UI",Roboto,"Noto Sans","Noto Sans Devanagari",sans-serif; color:var(--ink); background:var(--bg); }
  main { max-width: 720px; margin: 0 auto; padding: 28px 18px 64px; }
  header { display:flex; justify-content:space-between; align-items:center; gap:12px; flex-wrap:wrap; margin-bottom: 8px; }
  .brand { font-weight:800; color:var(--teal); letter-spacing:.2px; }
  nav a { color:var(--teal); font-weight:600; margin-left:14px; text-decoration:none; }
  h1 { font-size: 1.9rem; margin: 18px 0 2px; } h2 { font-size: 1.15rem; margin: 26px 0 4px; }
  .version { color:var(--muted); margin:0 0 12px; }
  p, li { color:#243f3c; } ul { padding-left: 1.2rem; } li { margin: 6px 0; }
  hr { border:0; border-top:1px solid #dce7e4; margin: 44px 0 8px; }
  #hindi h1, #hindi h2, #hindi p, #hindi li { font-family: "Noto Sans Devanagari", system-ui, sans-serif; }
  footer { margin-top: 40px; color:var(--muted); font-size:.9rem; }
</style>
</head>
<body>
<main>
<header><span class="brand">छाँव</span><nav><a href="privacy.html">Privacy policy</a><a href="terms.html">Terms of use</a><a href="#hindi">हिंदी</a></nav></header>
<div id="english"><h1>${esc(en.title)}</h1><p class="version">${esc(en.updated)}</p>${en.sections.map(section).join("")}</div>
<hr />
<div id="hindi" lang="hi"><h1>${esc(hi.title)}</h1><p class="version">${esc(hi.updated)}</p>${hi.sections.map(section).join("")}</div>
<footer><a href="${other}.html">${kind === "privacy" ? "Terms of use / उपयोग की शर्तें" : "Privacy policy / गोपनीयता नीति"}</a></footer>
</main>
</body>
</html>
`;
};
writeFileSync("public/legal/privacy.html", page("privacy", "terms"));
writeFileSync("public/legal/terms.html", page("terms", "privacy"));
console.log("legal pages written: public/legal/privacy.html, terms.html");
