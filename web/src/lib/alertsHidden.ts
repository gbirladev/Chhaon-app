/** "Not now" on the slim WhatsApp-alerts row: remembered per user for 14 days, then the row comes back. */
const DAYS = 14;
const key = (userId: string) => `carelink-alerts-hidden-${userId}`;

export function alertsHidden(userId: string): boolean {
  try {
    const at = Number(localStorage.getItem(key(userId)));
    return at > 0 && Date.now() - at < DAYS * 86400_000 && at <= Date.now() + 60_000;
  } catch { return false; }
}

export function hideAlerts(userId: string): void {
  try { localStorage.setItem(key(userId), String(Date.now())); } catch { /* storage blocked: it will show again next time */ }
}
