# Deploying Chhaon: hosting and HTTP security headers

Replace `<project>` with the Supabase project ref and `<api-host>` with the API host (e.g. `api.carelink.example`).
Dev servers only send the basic headers (see `web/vite.config.ts`); a strict CSP would break Vite hot reload, so do not apply this block in development.

## Static host for `web/` (Vercel / Netlify / Cloudflare Pages / nginx)

Serve `dist/` with SPA fallback to `index.html`. `sw.js` and `index.html` must not be cached long (`Cache-Control: no-cache`); hashed files under `/assets/` can be `public, max-age=31536000, immutable`.

```
Strict-Transport-Security: max-age=63072000; includeSubDomains
X-Content-Type-Options: nosniff
Referrer-Policy: strict-origin-when-cross-origin
X-Frame-Options: DENY
Permissions-Policy: camera=(self), microphone=(), geolocation=(), payment=(), usb=()
Cross-Origin-Opener-Policy: same-origin
Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: https://<project>.supabase.co; font-src 'self'; connect-src 'self' https://<project>.supabase.co wss://<project>.supabase.co https://<api-host>; worker-src 'self'; manifest-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'; object-src 'none'; upgrade-insecure-requests
```

Notes
- `style-src 'unsafe-inline'` is needed because the app uses inline `style` attributes. Remove it once they are gone.
- Report and medicine photos load from `https://<project>.supabase.co` (signed URLs), hence `img-src`. `blob:` is used for photo previews and the data-export download.
- The emergency page is the same app, so it carries the same headers; add `X-Robots-Tag: noindex` for the `/e/*` path (link pages must not be indexed).
- Test with `https://securityheaders.com` and the browser console (CSP violations are logged there) after every deploy.

nginx example (static host):
```
add_header Strict-Transport-Security "max-age=63072000; includeSubDomains" always;
add_header X-Content-Type-Options "nosniff" always;
add_header Referrer-Policy "strict-origin-when-cross-origin" always;
add_header X-Frame-Options "DENY" always;
add_header Permissions-Policy "camera=(self), microphone=(), geolocation=(), payment=(), usb=()" always;
add_header Content-Security-Policy "<the CSP line above>" always;
```

## API (`api/`, FastAPI)

It returns JSON only, so its CSP can be locked down completely. Set these at the reverse proxy (or as middleware):

```
Strict-Transport-Security: max-age=63072000; includeSubDomains
X-Content-Type-Options: nosniff
Referrer-Policy: no-referrer
Cache-Control: no-store            (every authenticated response, including /privacy/export)
Content-Security-Policy: default-src 'none'; frame-ancestors 'none'
```

Environment: `ENVIRONMENT=production` (closes `/docs`, narrows CORS to `WEB_ORIGIN`), real `WHATSAPP_APP_SECRET`, `CRON_SECRET`, `SUPABASE_SERVICE_ROLE_KEY` (server only). CORS allows only `GET, POST` and the `Authorization` / `Content-Type` headers from `WEB_ORIGIN`.

### Logs, proxies and rate limits (public emergency card)
- **Access logs must not contain the card token.** The link is `/public/emergency/<64 hex>` and uvicorn's access log prints the full path. Run uvicorn with `--no-access-log`, or redact the path before it is stored (nginx: log `$uri` through a `map` that replaces `^/public/emergency/.*` with `/public/emergency/[redacted]`; same for any load balancer / CDN log). The app itself never logs the token, phone numbers or health data, and background jobs log only ids and exception class names.
- **Client IP for the rate limit.** By default the limiter uses only the socket peer (`request.client.host`) and ignores `X-Forwarded-For`, so a caller cannot dodge the limit by inventing that header. Behind a reverse proxy start uvicorn with `--proxy-headers --forwarded-allow-ips=<proxy ip>` (never `*`): uvicorn then trusts the header only when the connection comes from that address. Alternatively set `TRUSTED_PROXY_HOPS=N` (number of proxies in front of the API); the app then takes the Nth address from the right of `X-Forwarded-For`, which is the one your own outermost proxy appended. Leave it at 0 when nothing sits in front.
- **The limit is in-process, per worker.** With `--workers 4` a caller can make 4x the limit, and a restart resets it. That is fine against token guessing (256-bit tokens); for hard limits add rate limiting at the proxy/CDN as well.

## Supabase
- Region: Mumbai (health data, DPDP). Turn email confirmation on and raise the minimum password length before launch (see TODO).
- Add the web origin to Auth redirect URLs; keep the service role key out of the web build.
