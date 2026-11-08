/*
 * PRODUCTION HOSTING (set these on the real host: Vercel/Netlify/nginx; Vite's own servers only add the basic ones below)
 *
 *   X-Content-Type-Options: nosniff
 *   Referrer-Policy: strict-origin-when-cross-origin
 *   X-Frame-Options: DENY
 *   Permissions-Policy: camera=(self), microphone=(), geolocation=()
 *   Strict-Transport-Security: max-age=63072000; includeSubDomains
 *   Content-Security-Policy:
 *     default-src 'self';
 *     script-src 'self';
 *     style-src 'self' 'unsafe-inline';
 *     img-src 'self' data: blob: https://<project>.supabase.co https://chhaon.goatcounter.com;   (count.js: visit counting)
 *     font-src 'self';
 *     connect-src 'self' https://<project>.supabase.co wss://<project>.supabase.co https://<api-host>;
 *     worker-src 'self';
 *     manifest-src 'self';
 *     frame-ancestors 'none';
 *     base-uri 'self';
 *     form-action 'self';
 *     object-src 'none'
 *
 * No CSP is set on the dev server because it would break Vite HMR (inline scripts + websocket).
 * Fonts are self-hosted via @fontsource, so no third-party font origins are needed.
 */
import { readFileSync } from "node:fs";
import path from "node:path";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig, type Plugin } from "vite";
import { VitePWA } from "vite-plugin-pwa";

/**
 * Production layout: the marketing home page at "/", the app at "/app/".
 * Done in the build (not as a copy step in CI) so the service worker's precache list and `vite preview`
 * match exactly what is deployed. The app's asset links are absolute ("/assets/..."), so moving its
 * index.html into app/ needs no other change. The dev server is unaffected (the app stays at "/").
 */
function siteLayout(): Plugin {
  return {
    name: "chhaon-site-layout",
    apply: "build",
    enforce: "post",
    generateBundle(_options, bundle) {
      const app = bundle["index.html"];
      if (!app || app.type !== "asset") this.error("chhaon-site-layout: index.html missing from the bundle");
      delete bundle["index.html"];
      this.emitFile({ type: "asset", fileName: "app/index.html", source: app.source });
      this.emitFile({ type: "asset", fileName: "index.html", source: readFileSync(path.resolve(__dirname, "public/home.html")) });
    },
  };
}

const securityHeaders = {
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "strict-origin-when-cross-origin",
  "X-Frame-Options": "DENY",
  "Permissions-Policy": "camera=(self), microphone=(), geolocation=()",
};

export default defineConfig({
  resolve: { alias: { "@": path.resolve(__dirname, "src") } },
  server: { port: 5173, headers: securityHeaders },
  preview: { headers: securityHeaders },
  plugins: [
    react(),
    tailwindcss(),
    siteLayout(),
    VitePWA({
      registerType: "autoUpdate",
      // Scoped to /app/: the marketing site (home.html, about.html, legal/) sits at the domain root,
      // outside the PWA entirely, and is never touched by the service worker.
      scope: "/app/",
      includeAssets: ["favicon.svg", "apple-touch-icon.png"],
      manifest: {
        name: "Chhaon",
        short_name: "Chhaon",
        description: "Look after your parents' health from anywhere.",
        id: "/app/",
        start_url: "/app/",
        scope: "/app/",
        display: "standalone",
        orientation: "portrait",
        background_color: "#faf8fd",
        theme_color: "#faf8fd",
        icons: [
          { src: "icon-192.png", sizes: "192x192", type: "image/png" },
          { src: "icon-512.png", sizes: "512x512", type: "image/png" },
          { src: "icon-maskable-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
        ],
      },
      workbox: {
        // App shell is cached; Supabase / API calls always go to the network (health data is never cached offline).
        // Only addresses under /app/ ever fall back to the app shell. Browsers that still hold the old site-wide
        // registration (scope "/", from before /app existed) pick up this worker too; the allowlist keeps them showing
        // the real home, doctors, legal and any future pages instead of the app.
        navigateFallback: "/app/index.html",
        navigateFallbackAllowlist: [/^\/app\//],
        // Only the app is precached. The marketing site ("index.html" here is the home page) and the doctors page
        // with its QR library are plain web pages that families never need offline.
        globIgnores: ["**/node_modules/**/*", "index.html", "home.html", "about.html", "privacy.html", "doctors.html", "demo.html", "videos/**", "legal/**", "vendor/**", "og-image.jpg", "robots.txt", "sitemap.xml", "hi/**", "site-theme.css", "site-theme.js", "site-home.js", "doctors-page.js", "about-page.js", "ref-carry.js", "count.js"],
      },
    }),
  ],
});
