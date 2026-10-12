/**
 * In local development the configured URLs point at 127.0.0.1, which on a phone is the phone itself
 * ("Failed to fetch"). When the page was opened through the machine's network address, use that
 * same address for the backend services too.
 */
export function resolveLocalUrl(configured: string): string {
  const url = new URL(configured);
  const isLoopback = ["127.0.0.1", "localhost", "0.0.0.0"].includes(url.hostname);
  if (isLoopback && !["127.0.0.1", "localhost"].includes(window.location.hostname)) {
    url.hostname = window.location.hostname;
  }
  return url.toString().replace(/\/$/, "");
}
