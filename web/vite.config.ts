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
 *     img-src 'self' data: blob: https://<project>.supabase.co;
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
import path from "node:path";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";
import { VitePWA } from "vite-plugin-pwa";

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
    VitePWA({
      registerType: "autoUpdate",
      includeAssets: ["favicon.svg", "apple-touch-icon.png"],
      manifest: {
        name: "Chhaon",
        short_name: "Chhaon",
        description: "Look after your parents' health from anywhere.",
        id: "/",
        start_url: "/",
        display: "standalone",
        orientation: "portrait",
        background_color: "#f8faf8",
        theme_color: "#247c72",
        icons: [
          { src: "icon-192.png", sizes: "192x192", type: "image/png" },
          { src: "icon-512.png", sizes: "512x512", type: "image/png" },
          { src: "icon-maskable-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
        ],
      },
      workbox: {
        // App shell is cached; Supabase / API calls always go to the network (health data is never cached offline).
        navigateFallback: "/index.html",
        navigateFallbackDenylist: [/^\/emergency\//, /^\/about\.html$/, /^\/privacy\.html$/, /^\/legal\//, /^\/home\.html$/, /^\/landing\//],
      },
    }),
  ],
});
