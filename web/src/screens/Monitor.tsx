import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useLocale, useTranslations } from "use-intl";
import { AppHeader, Icon } from "../App";
import {
  BADGES, loadActivity, loadAlerts, loadFamilies, loadFamily, loadFeedbackInbox, loadHealth, loadOverview, loadPilotCap, loadUpcoming,
  movePilotUp, type PilotCap,
  type ActivityItem, type Alerts, type FamilyDetail, type FamilyRow, type FeedbackItem, type Health, type HealthCheck, type Level,
  type Overview, type UpcomingItem,
} from "../lib/admin";
import { deviceZone, formatClock, formatDate } from "../lib/format";
import "./monitor.css";

/**
 * The owner's Monitor (admin only; the backend refuses everyone else): is everything working, for every family?
 * Health checks, open problems, today's totals, what is due next, every family with warning badges, one family in full (each
 * opening is recorded in the admin audit table), the latest events, feedback and the pilot stats. Refreshes every 30 seconds.
 * See docs/admin-monitor.md for what each check means and what to do when it is red.
 */

const IST = "Asia/Kolkata";
const REFRESH_MS = 30_000;
type Tab = "overview" | "families" | "activity" | "feedback" | "pilot";
const TABS: Tab[] = ["overview", "families", "activity", "feedback", "pilot"];

type Data = {
  health?: Health; alerts?: Alerts; overview?: Overview; families?: FamilyRow[];
  activity?: ActivityItem[]; upcoming?: UpcomingItem[]; feedback?: FeedbackItem[];
};

/** "14:31 IST" plus the viewer's own clock when they are not on India time ("· 10:01 your time"). */
function useTimes() {
  const locale = useLocale();
  const t = useTranslations("monitor");
  const zone = deviceZone();
  const [sameAsIst] = useState(() => {
    const now = new Date();
    return formatClock(now, zone, "en") === formatClock(now, IST, "en");
  });
  return useCallback((iso: string | null | undefined, withDate = true) => {
    if (!iso) return "–";
    const d = new Date(iso);
    const istDay = formatDate(d, locale, { day: "numeric", month: "short", timeZone: IST });
    const todayIst = formatDate(new Date(), locale, { day: "numeric", month: "short", timeZone: IST });
    const ist = `${withDate && istDay !== todayIst ? `${istDay}, ` : ""}${formatClock(d, IST, locale)} ${t("ist")}`;
    return sameAsIst ? ist : `${ist} · ${t("yourTime", { time: formatClock(d, zone, locale) })}`;
  }, [locale, sameAsIst, t, zone]);
}

/** "12 s ago", re-rendered every second on its own (the page itself does not re-render). */
function Ago({ since }: { since: number | null }) {
  const t = useTranslations("monitor");
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => { const id = window.setInterval(() => setNow(Date.now()), 1000); return () => window.clearInterval(id); }, []);
  if (since === null) return <>{t("loading")}</>;
  const s = Math.max(0, Math.round((now - since) / 1000));
  return <>{s < 90 ? t("updatedSeconds", { count: s }) : t("updatedMinutes", { count: Math.round(s / 60) })}</>;
}

/** A fact for an ICU message: null / missing becomes a dash so a message never breaks. */
function factValues(facts: Record<string, unknown>) {
  const out: Record<string, string | number> = {};
  for (const [k, v] of Object.entries(facts)) out[k] = typeof v === "number" ? v : v === null || v === undefined ? "–" : String(v);
  return out;
}

export function MonitorScreen({ go }: { go: (screen: "family" | "stats") => void }) {
  const t = useTranslations("monitor");
  const locale = useLocale();
  const time = useTimes();
  const [tab, setTab] = useState<Tab>("overview");
  const [data, setData] = useState<Data>({});
  const [updatedAt, setUpdatedAt] = useState<number | null>(null);
  const [failed, setFailed] = useState<"forbidden" | "error" | null>(null);
  const [busy, setBusy] = useState(false);
  const [openCheck, setOpenCheck] = useState<string | null>(null);
  const [familyId, setFamilyId] = useState<string | null>(null);
  const loading = useRef(false);

  const refresh = useCallback(async () => {
    if (loading.current) return;
    loading.current = true;
    setBusy(true);
    const r = await Promise.allSettled([loadHealth(), loadAlerts(), loadOverview(), loadFamilies(), loadActivity(50), loadUpcoming(2), loadFeedbackInbox(50)]);
    loading.current = false;
    setBusy(false);
    const forbidden = r.some((x) => x.status === "rejected" && (x.reason as { status?: number })?.status === 403);
    if (forbidden) { setFailed("forbidden"); return; }
    const v = <T,>(i: number) => (r[i].status === "fulfilled" ? (r[i] as PromiseFulfilledResult<T>).value : undefined);
    setData((prev) => ({
      health: v<Health>(0) ?? prev.health, alerts: v<Alerts>(1) ?? prev.alerts, overview: v<Overview>(2) ?? prev.overview,
      families: v<FamilyRow[]>(3) ?? prev.families, activity: v<ActivityItem[]>(4) ?? prev.activity,
      upcoming: v<UpcomingItem[]>(5) ?? prev.upcoming, feedback: v<FeedbackItem[]>(6) ?? prev.feedback,
    }));
    if (r.every((x) => x.status === "rejected")) setFailed("error");
    else { setFailed(null); setUpdatedAt(Date.now()); }
  }, []);

  useEffect(() => {
    const first = window.setTimeout(() => void refresh(), 0);
    const id = window.setInterval(() => { if (document.visibilityState === "visible") void refresh(); }, REFRESH_MS);
    return () => { window.clearTimeout(first); window.clearInterval(id); };
  }, [refresh]);

  const number = (n: number | null | undefined) => (n ?? 0).toLocaleString(locale === "hi" ? "hi-IN" : "en-IN");
  const checkText = (c: HealthCheck) => {
    const key = `reason.${c.key}.${c.reason}`;
    return t.has(key) ? t(key, factValues(c.facts)) : t.has(`reason.any.${c.reason}`) ? t(`reason.any.${c.reason}`) : c.detail;
  };

  const action = (
    <button className="icon-button monitor-refresh" onClick={() => void refresh()} aria-label={t("refresh")} disabled={busy}>
      <Icon name="refresh" />
    </button>
  );

  if (familyId) {
    return <FamilyView id={familyId} back={() => setFamilyId(null)} time={time} />;
  }

  const h = data.health;
  return (
    <main className="scroll-page no-nav monitor-page">
      <AppHeader title={t("title")} subtitle={undefined} back={() => go("family")} action={action} />
      <p className="monitor-updated" role="status" aria-live="polite"><Ago since={updatedAt} />{busy && updatedAt !== null ? ` · ${t("refreshing")}` : ""}</p>
      {failed === "forbidden" && <p className="note monitor-error" role="alert">{t("forbidden")}</p>}
      {failed === "error" && <p className="note monitor-error" role="alert">{t("error")}</p>}

      <nav className="monitor-tabs" aria-label={t("tabsLabel")}>
        {TABS.map((x) => (
          <button key={x} type="button" className={tab === x ? "on" : ""} aria-pressed={tab === x} onClick={() => setTab(x)}>
            {t(`tab.${x}`)}
            {x === "overview" && h && h.overall !== "green" && <span className={`tab-dot ${h.overall}`} aria-hidden="true" />}
            {x === "families" && data.families && <span className="tab-count">{data.families.filter((f) => !f.is_canary).length}</span>}
          </button>
        ))}
      </nav>

      {tab === "overview" && (
        <div className="monitor-grid">
          <section className="monitor-card span-2" aria-label={t("healthTitle")}>
            <div className="card-head">
              <h2>{t("healthTitle")}</h2>
              {h && <span className={`level-pill ${h.overall}`}>{t(`level.${h.overall}`)}</span>}
            </div>
            {!h && <p className="muted">{t("loading")}</p>}
            {h && (
              <>
                <div className="health-strip" role="list">
                  {h.checks.map((c) => (
                    <button key={c.key} role="listitem" type="button" className={`health-chip ${c.status}${openCheck === c.key ? " open" : ""}`}
                      aria-expanded={openCheck === c.key} onClick={() => setOpenCheck(openCheck === c.key ? null : c.key)}>
                      <span className="dot" aria-hidden="true" />
                      <span className="chip-label">{t(`check.${c.key}`)}</span>
                      <span className="sr-only">{t(`level.${c.status}`)}</span>
                    </button>
                  ))}
                </div>
                {h.checks.filter((c) => c.key === openCheck || (openCheck === null && c.status !== "green" && c.status !== "grey")).map((c) => (
                  <div key={c.key} className={`check-detail ${c.status}`}>
                    <strong>{t(`check.${c.key}`)} · {t(`level.${c.status}`)}</strong>
                    <p>{checkText(c)}</p>
                    {c.since && <p className="muted">{t("since", { time: time(c.since) })}</p>}
                    {(c.status === "amber" || c.status === "red") && t.has(`todo.${c.key}`) && <p className="todo">{t(`todo.${c.key}`)}</p>}
                  </div>
                ))}
                {openCheck === null && h.checks.every((c) => c.status === "green" || c.status === "grey") && <p className="all-good">{t("allGood")}</p>}
              </>
            )}
          </section>

          <section className="monitor-card" aria-label={t("alertsTitle")}>
            <h2>{t("alertsTitle")}</h2>
            {data.alerts && data.alerts.open.length === 0 && <p className="muted">{t("noOpenAlerts")}</p>}
            {data.alerts?.open.map((a) => (
              <div key={a.id} className={`alert-row ${a.status}`}>
                <span className="dot" aria-hidden="true" />
                <div><strong>{t(`check.${a.check_key}`)}</strong><p>{a.detail}</p><p className="muted">{t("openedAt", { time: time(a.opened_at) })}</p></div>
              </div>
            ))}
            {data.alerts && data.alerts.recent.some((a) => a.resolved_at) && (
              <details className="monitor-details">
                <summary>{t("alertHistory")}</summary>
                <ul className="plain-list">
                  {data.alerts.recent.filter((a) => a.resolved_at).map((a) => (
                    <li key={a.id}><span className={`dot ${a.worst_status}`} aria-hidden="true" /> {t(`check.${a.check_key}`)}: {time(a.opened_at)} → {time(a.resolved_at)}</li>
                  ))}
                </ul>
              </details>
            )}
          </section>

          {h && <GatewayCard health={h} time={time} />}
          {h && <CanaryCard health={h} time={time} />}

          {data.overview && (
            <section className="monitor-card span-2" aria-label={t("todayTitle")}>
              <h2>{t("todayTitle")} <small>{formatDate(new Date(`${data.overview.date}T12:00:00Z`), locale, { day: "numeric", month: "short" })} · {t("ist")}</small></h2>
              <div className="tile-grid">
                <Tile label={t("t.sent")} value={number(data.overview.doses.sent)} sub={t("t.ofScheduled", { count: data.overview.doses.scheduled })} />
                <Tile label={t("t.taken")} value={number(data.overview.doses.taken)} sub={t("t.takenLate", { count: data.overview.doses.taken_late })} />
                <Tile label={t("t.missed")} value={number(data.overview.doses.missed)} tone={data.overview.doses.missed > 0 ? "amber" : undefined} />
                <Tile label={t("t.waiting")} value={number(data.overview.doses.waiting)} sub={t("t.notSentYet", { count: data.overview.doses.not_sent_yet })} />
                <Tile label={t("t.sentLate")} value={number(data.overview.doses.sent_late)} tone={data.overview.doses.sent_late > 0 ? "amber" : undefined} />
                <Tile label={t("t.checkins")} value={`${number(data.overview.checkins.answered)} / ${number(data.overview.checkins.sent)}`} sub={t("t.callMe", { count: data.overview.checkins.call_me })} />
                <Tile label={t("t.alerts")} value={number(data.overview.alerts.total)} sub={t("t.alertsWhatsapp", { count: data.overview.alerts.whatsapp_sent })} />
                <Tile label={t("t.messages")} value={number(data.overview.volume.today)} sub={t("t.messagesAvg", { avg: data.overview.volume.daily_average_7d })}
                  tone={data.overview.volume.today > data.overview.volume.amber_per_day ? "amber" : undefined} />
              </div>
              <VolumeBars days={data.overview.volume.daily} limit={data.overview.volume.amber_per_day} />
            </section>
          )}

          <section className="monitor-card" aria-label={t("upcomingTitle")}>
            <h2>{t("upcomingTitle")}</h2>
            {data.upcoming && data.upcoming.length === 0 && <p className="muted">{t("nothingUpcoming")}</p>}
            <ul className="plain-list upcoming">
              {data.upcoming?.map((u) => (
                <li key={`${u.kind}-${u.recipient_id}-${u.due_at}`}>
                  <span className="when">{time(u.due_at, false)}</span>
                  <span className="what">
                    <strong>{u.recipient_name}</strong> · {u.kind === "dose" ? u.medicines.join(", ") : t("checkin")}
                    <small> · {t(`receiver.${u.reminder_to}`, { name: u.reminder_name ?? "" })}{!u.will_send && <> · <em className="warn-text">{t("willNotSend")}</em></>}</small>
                  </span>
                </li>
              ))}
            </ul>
          </section>
        </div>
      )}

      {tab === "families" && <FamiliesList rows={data.families} open={setFamilyId} time={time} />}

      {tab === "activity" && (
        <section className="monitor-card" aria-label={t("activityTitle")}>
          <h2>{t("activityTitle")}</h2>
          {data.activity && data.activity.length === 0 && <p className="muted">{t("nothingYet")}</p>}
          <ul className="plain-list activity">
            {data.activity?.map((a, i) => (
              <li key={`${a.kind}-${a.at_time}-${i}`} className={`kind-${a.kind}`}>
                <span className="when">{time(a.at_time)}</span>
                <span className="what">
                  <strong>{t.has(`kind.${a.kind}`) ? t(`kind.${a.kind}`) : a.kind}</strong>
                  {a.recipient_name && <> · {a.recipient_id ? <button type="button" className="link-button" onClick={() => setFamilyId(a.recipient_id)}>{a.recipient_name}</button> : a.recipient_name}</>}
                  {a.detail && <> · {a.kind === "alert" && t.has(`alertType.${a.detail}`) ? t(`alertType.${a.detail}`) : a.detail}</>}
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}

      {tab === "feedback" && (
        <section className="monitor-card" aria-label={t("feedbackTitle")}>
          <h2>{t("feedbackTitle")}</h2>
          {data.feedback && data.feedback.length === 0 && <p className="muted">{t("nothingYet")}</p>}
          {data.feedback?.map((f) => (
            <article key={f.id} className="feedback-item">
              <p className="muted">{t.has(`fbKind.${f.kind}`) ? t(`fbKind.${f.kind}`) : f.kind}{f.rating ? ` · ${"★".repeat(f.rating)}` : ""} · {time(f.created_at)}</p>
              <p className="message">{f.message}</p>
              <p className="muted">{[f.name, f.email, f.phone].filter(Boolean).join(" · ") || t("unknownSender")}{f.screen ? ` · ${f.screen}` : ""}{f.images > 0 ? ` · ${t("pictures", { count: f.images })}` : ""}</p>
            </article>
          ))}
        </section>
      )}

      {tab === "pilot" && <PilotCapCard time={time} />}
      {tab === "pilot" && (
        <section className="monitor-card" aria-label={t("pilotTitle")}>
          <h2>{t("pilotTitle")}</h2>
          {data.overview?.pilot ? (
            <div className="tile-grid">
              <Tile label={t("p.caregivers")} value={number(data.overview.pilot.caregivers.total)} sub={t("p.active", { count: data.overview.pilot.caregivers.active_7d })} />
              <Tile label={t("p.parents")} value={`${number(data.overview.pilot.parents.agreed)} / ${number(data.overview.pilot.parents.total)}`} sub={t("p.parentsSub", { waiting: data.overview.pilot.parents.waiting, stopped: data.overview.pilot.parents.stopped })} />
              <Tile label={t("p.reminders")} value={`${number(data.overview.pilot.reminders_7d.taken)} / ${number(data.overview.pilot.reminders_7d.sent)}`} sub={t("p.missed", { count: data.overview.pilot.reminders_7d.missed })} />
              <Tile label={t("p.checkins")} value={`${number(data.overview.pilot.checkins_7d.replied)} / ${number(data.overview.pilot.checkins_7d.sent)}`} />
            </div>
          ) : <p className="muted">{t("loading")}</p>}
          <button type="button" className="feature-link monitor-stats-link" onClick={() => go("stats")}>
            <div><strong>{t("openStats")}</strong><p>{t("openStatsSub")}</p></div><Icon name="chevron" />
          </button>
        </section>
      )}
    </main>
  );
}

function Tile({ label, value, sub, tone }: { label: string; value: string; sub?: string; tone?: Level }) {
  return (
    <div className={`m-tile${tone ? ` ${tone}` : ""}`}>
      <span className="label">{label}</span>
      <span className="value">{value}</span>
      {sub && <span className="sub">{sub}</span>}
    </div>
  );
}

function VolumeBars({ days, limit }: { days: { date: string; count: number }[]; limit: number }) {
  const t = useTranslations("monitor");
  const locale = useLocale();
  const most = Math.max(1, ...days.map((d) => d.count));
  return (
    <div className="volume">
      <p className="muted">{t("volumeTitle", { limit })}</p>
      <div className="vbars" role="img" aria-label={days.map((d) => `${d.date}: ${d.count}`).join(", ")}>
        {days.map((d, i) => (
          <div key={d.date} className="vbar-slot" title={`${d.date}: ${d.count}`}>
            <span className="vbar-value">{d.count}</span>
            <span className={`vbar${d.count > limit ? " over" : ""}${i === days.length - 1 ? " today" : ""}`} style={{ height: `${Math.max(3, (d.count / most) * 100)}%` }} />
            <span className="vbar-day">{formatDate(new Date(`${d.date}T12:00:00Z`), locale, { day: "numeric" })}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function GatewayCard({ health, time }: { health: Health; time: (iso: string | null | undefined, withDate?: boolean) => string }) {
  const t = useTranslations("monitor");
  const gw = health.checks.find((c) => c.key === "gateway");
  const f = (gw?.facts ?? {}) as Record<string, unknown>;
  const s = health.server;
  const uptimeH = Math.floor(s.uptime_seconds / 3600);
  const str = (v: unknown) => (v === null || v === undefined || v === "" ? "–" : String(v));
  return (
    <section className="monitor-card" aria-label={t("gatewayTitle")}>
      <h2>{t("gatewayTitle")}</h2>
      <dl className="facts">
        <dt>{t("g.session")}</dt><dd>{str(f.session_name)}</dd>
        <dt>{t("g.number")}</dt><dd>{str(f.number)}</dd>
        <dt>{t("g.status")}</dt><dd className={f.configured === false ? "lvl-amber" : f.session_status === "CONNECTED" ? "lvl-green" : "lvl-red"}>{f.configured === false ? t("g.notConfigured") : str(f.session_status ?? f.error)}</dd>
        <dt>{t("g.webhooks")}</dt><dd className={f.webhooks === 0 ? "lvl-red" : ""}>{str(f.webhooks)}</dd>
        <dt>{t("g.lastOut")}</dt><dd>{time(f.last_outbound_at as string | null)}</dd>
        <dt>{t("g.rejected")}</dt>
        <dd>{health.webhook_rejections_24h.length === 0 ? t("g.none") : health.webhook_rejections_24h.map((r) => `${t(`rejection.${r.reason}`)} ${r.n}× (${time(r.last_at)})`).join("; ")}</dd>
        <dt>{t("g.version")}</dt><dd><code>{s.git_sha === "unknown" ? t("g.unknown") : s.git_sha.slice(0, 7)}</code></dd>
        <dt>{t("g.uptime")}</dt><dd>{t("g.uptimeValue", { hours: uptimeH, minutes: Math.floor((s.uptime_seconds % 3600) / 60) })} · {time(s.started_at)}</dd>
      </dl>
    </section>
  );
}

/** The pilot cap (docs/pilot-cap.md): how many WhatsApp numbers get messages, new ones today, and the waiting line. */
function PilotCapCard({ time }: { time: (iso: string | null | undefined, withDate?: boolean) => string }) {
  const t = useTranslations("monitor");
  const [cap, setCap] = useState<PilotCap | null | undefined>(undefined);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState(false);
  const load = useCallback(async () => {
    try { setCap(await loadPilotCap()); } catch { setCap((prev) => prev ?? undefined); }
  }, []);
  useEffect(() => {
    const first = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(first);
  }, [load]);
  const moveUp = async (id: string) => {
    setBusy(id); setError(false);
    try { await movePilotUp(id); await load(); } catch { setError(true); }
    setBusy(null);
  };
  return (
    <section className="monitor-card" aria-label={t("pilotCapTitle")}>
      <h2>{t("pilotCapTitle")}</h2>
      {cap === undefined ? <p className="muted">{t("loading")}</p> : cap === null ? <p className="muted">{t("pc.notInstalled")}</p> : (
        <>
          {!cap.enabled && <p className="muted">{t("pc.off")}</p>}
          <div className="tile-grid">
            <Tile label={t("pc.seats")} value={`${cap.seats_used}`} sub={t("pc.seatsSub", { max: cap.max_active })} tone={cap.enabled && cap.seats_used >= cap.max_active ? "amber" : undefined} />
            <Tile label={t("pc.new")} value={`${cap.new_24h}`} sub={t("pc.newSub", { max: cap.max_new_per_day })} />
            <Tile label={t("pc.waiting")} value={`${cap.waiting.length}`} />
          </div>
          {cap.waiting.length === 0 ? <p className="muted">{t("pc.none")}</p> : (
            <ul className="plain-list">
              {cap.waiting.map((w) => (
                <li key={w.recipient_id} className="pilot-row">
                  <span><strong>#{w.position} {w.name}</strong>{w.caregiver ? ` · ${t("pc.by", { name: w.caregiver })}` : ""}<br />
                    <small>{t("pc.since", { time: time(w.queued_at, true) })}</small></span>
                  {w.position > 1 && (
                    <button type="button" className="slim-action" onClick={() => void moveUp(w.recipient_id)} disabled={busy !== null}>{t("pc.moveUp")}</button>
                  )}
                </li>
              ))}
            </ul>
          )}
          {error && <p className="note monitor-error" role="alert">{t("pc.moveFailed")}</p>}
        </>
      )}
    </section>
  );
}

/** The canary family (docs/canary.md): a spare number that answers its own reminder, proving the whole chain every 4 hours. */
function CanaryCard({ health, time }: { health: Health; time: (iso: string | null | undefined, withDate?: boolean) => string }) {
  const t = useTranslations("monitor");
  const c = health.checks.find((x) => x.key === "canary");
  if (!c) return null;
  const f = c.facts as Record<string, unknown>;
  const num = (v: unknown) => (typeof v === "number" ? v : 0);
  return (
    <section className="monitor-card" aria-label={t("canaryTitle")}>
      <div className="card-head">
        <h2>{t("canaryTitle")}</h2>
        <span className={`level-pill ${c.status}`}>{t(`level.${c.status}`)}</span>
      </div>
      {c.reason === "not_set_up" ? <p className="muted">{t("c.notSetUp")}</p> : (
        <dl className="facts">
          <dt>{t("c.lastSent")}</dt><dd>{time(f.last_sent_at as string | null)}</dd>
          <dt>{t("c.answered")}</dt><dd className={c.status === "red" ? "lvl-red" : ""}>{f.answered_at ? time(f.answered_at as string) : t("c.noAnswer")}</dd>
          <dt>{t("c.roundTrip")}</dt><dd>{typeof f.round_trip_seconds === "number" ? t("c.seconds", { count: f.round_trip_seconds }) : "–"}</dd>
          <dt>{t("c.day")}</dt><dd className={num(f.ok_24h) < num(f.sent_24h) ? "lvl-amber" : ""}>{t("c.dayValue", { ok: num(f.ok_24h), sent: num(f.sent_24h) })}</dd>
          <dt>{t("c.messagesToday")}</dt><dd>{health.volume.canary_today ?? 0}</dd>
        </dl>
      )}
    </section>
  );
}

function FamiliesList({ rows, open, time }: { rows?: FamilyRow[]; open: (id: string) => void; time: (iso: string | null | undefined, withDate?: boolean) => string }) {
  const t = useTranslations("monitor");
  const [query, setQuery] = useState("");
  const [badge, setBadge] = useState<string | null>(null);
  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (rows ?? []).filter((r) => (!badge || r.badges.includes(badge)) && (!q
      || [r.name, r.call_name, r.whatsapp_phone, ...r.caregivers.flatMap((c) => [c.name, c.email, c.phone])].some((x) => x?.toLowerCase().includes(q))));
  }, [rows, query, badge]);
  const counts = (code: string) => (rows ?? []).filter((r) => r.badges.includes(code)).length;
  return (
    <section className="monitor-card" aria-label={t("familiesTitle")}>
      <h2>{t("familiesTitle")}</h2>
      <input className="monitor-search" type="search" value={query} onChange={(e) => setQuery(e.target.value)} placeholder={t("search")} aria-label={t("search")} />
      <div className="badge-filter" role="group" aria-label={t("filterLabel")}>
        <button type="button" className={badge === null ? "on" : ""} aria-pressed={badge === null} onClick={() => setBadge(null)}>{t("filterAll")} {rows?.length ?? 0}</button>
        {BADGES.filter((b) => counts(b) > 0).map((b) => (
          <button key={b} type="button" className={badge === b ? "on" : ""} aria-pressed={badge === b} onClick={() => setBadge(badge === b ? null : b)}>{t(`badge.${b}`)} {counts(b)}</button>
        ))}
      </div>
      {rows === undefined && <p className="muted">{t("loading")}</p>}
      {rows && shown.length === 0 && <p className="muted">{t("noMatch")}</p>}
      <ul className="family-list">
        {shown.map((r) => {
          const owner = r.caregivers.find((c) => c.role === "owner") ?? r.caregivers[0];
          const pct = r.today_total > 0 ? Math.round((r.today_confirmed / r.today_total) * 100) : null;
          return (
            <li key={r.recipient_id}>
              <button type="button" className="family-row" onClick={() => open(r.recipient_id)}>
                <span className="fr-top">
                  <strong>{r.name}</strong>
                  <span className="care">{t(`care.${r.care_type}`)}{r.is_self ? ` · ${t("self")}` : ""}</span>
                  <span className={`today ${pct === null ? "" : pct >= 80 ? "green" : pct >= 50 ? "amber" : "red"}`}>{t("todayOf", { done: r.today_confirmed, total: r.today_total })}</span>
                </span>
                <span className="fr-line">{owner ? `${owner.name ?? "–"} · ${owner.phone ?? owner.email ?? "–"}` : t("noCaregiver")}{r.caregivers.length > 1 ? ` +${r.caregivers.length - 1}` : ""}</span>
                <span className="fr-line">{t("wa")}: {r.whatsapp_phone ?? "–"} · {t(`receiver.${r.reminder_to}`, { name: r.reminder_name ?? r.reminder_user_name ?? "" })} · {t(r.consent_at ? "consentYes" : "consentNo")} · {r.plan ?? "–"}</span>
                <span className="fr-line muted">
                  {t("lastReply", { time: time(r.last_reply_at) })}{r.median_reply_minutes_7d !== null ? ` · ${t("replySpeed", { minutes: r.median_reply_minutes_7d })}` : ""} · {t("medsCount", { count: r.active_medicines })} · {t("msgs", { today: r.messages_today, week: r.messages_7d })}
                </span>
                <span className="fr-line muted">{t("caregiverSeen", { time: time(r.last_caregiver_activity_at) })}</span>
                {r.badges.length > 0 && (
                  <span className="badges">{r.badges.map((b) => <span key={b} className={`badge b-${b}`}>{t(`badge.${b}`)}</span>)}</span>
                )}
              </button>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

function FamilyView({ id, back, time }: { id: string; back: () => void; time: (iso: string | null | undefined, withDate?: boolean) => string }) {
  const t = useTranslations("monitor");
  const locale = useLocale();
  const [d, setD] = useState<FamilyDetail | "error" | null>(null);
  useEffect(() => {
    let cancelled = false;
    loadFamily(id).then((x) => { if (!cancelled) setD(x); }).catch(() => { if (!cancelled) setD("error"); });
    return () => { cancelled = true; };
  }, [id]);
  const utc = (iso: string | null) => (iso ? `${new Date(iso).toISOString().slice(11, 16)} UTC` : "");
  const r = d && d !== "error" ? d.recipient : null;
  return (
    <main className="scroll-page no-nav monitor-page">
      <AppHeader title={r?.name ?? t("familyTitle")} subtitle={t("auditNote")} back={back} />
      {d === null && <p className="note">{t("loading")}</p>}
      {d === "error" && <p className="note" role="alert">{t("error")}</p>}
      {d && d !== "error" && r && (
        <div className="monitor-grid">
          <section className="monitor-card">
            <h2>{t("f.profile")}</h2>
            <dl className="facts">
              <dt>{t("f.whatsapp")}</dt><dd>{String(r.whatsapp_phone ?? "–")}</dd>
              <dt>{t("f.care")}</dt><dd>{t(`care.${String(r.care_type)}`)} · {String(r.relation ?? "")}</dd>
              <dt>{t("f.timezone")}</dt><dd>{String(r.timezone)} · {String(r.language)}</dd>
              <dt>{t("f.receiver")}</dt><dd>{t(`receiver.${String(r.reminder_to)}`, { name: String(r.reminder_name ?? "") })}</dd>
              <dt>{t("f.consent")}</dt><dd>{r.consent_at ? time(String(r.consent_at)) : t("consentNo")}{r.opted_out_at ? ` · ${t("badge.opted_out")} ${time(String(r.opted_out_at))}` : ""}</dd>
              <dt>{t("f.checkin")}</dt><dd>{r.checkin_enabled ? String(r.checkin_time ?? "").slice(0, 5) : t("off")}</dd>
              <dt>{t("f.conditions")}</dt><dd>{(r.conditions ?? []).join(", ") || "–"}</dd>
              <dt>{t("f.allergies")}</dt><dd>{(r.allergies ?? []).join(", ") || "–"}</dd>
              <dt>{t("f.blood")}</dt><dd>{String(r.blood_group ?? "–")}</dd>
            </dl>
          </section>
          <section className="monitor-card">
            <h2>{t("f.members")}</h2>
            <ul className="plain-list">
              {d.members.map((m) => (
                <li key={m.user_id}><strong>{m.name ?? "–"}</strong> · {m.role} · {m.phone ?? "–"} · {m.email ?? "–"} · {m.plan}
                  <small className="muted"> · {t("f.alerts")}: {m.alert_consent_at ? m.alert_phone ?? "✓" : t("off")}</small></li>
              ))}
            </ul>
          </section>
          <section className="monitor-card span-2">
            <h2>{t("f.medicines")}</h2>
            <ul className="plain-list">
              {d.medicines.map((m) => (
                <li key={m.id} className={m.active ? "" : "muted"}>
                  <strong>{m.name}</strong>{m.dosage ? ` ${m.dosage}` : ""} · {m.schedules.map((s) => s.time_of_day.slice(0, 5)).join(", ") || "–"}
                  {" · "}{t("f.stock", { stock: m.stock_count ?? "–", refill: m.refill_threshold ?? "–" })}
                  {!m.active && ` · ${t("off")}`}{m.paused_by_plan && ` · ${t("badge.paused_by_plan")}`}{m.critical && ` · ${t("f.critical")}`}
                </li>
              ))}
            </ul>
          </section>
          <section className="monitor-card span-2">
            <h2>{t("f.doses")}</h2>
            <div className="table-wrap">
              <table className="dose-table">
                <thead><tr><th>{t("f.due")}</th><th>{t("f.medicine")}</th><th>{t("f.status")}</th><th>{t("f.sent")}</th><th>{t("f.replied")}</th></tr></thead>
                <tbody>
                  {d.doses.map((x) => (
                    <tr key={x.id}>
                      <td>{formatDate(new Date(x.scheduled_at), locale, { day: "numeric", month: "short", timeZone: "Asia/Kolkata" })} {formatClock(new Date(x.scheduled_at), "Asia/Kolkata", locale)}<small>{utc(x.scheduled_at)}</small></td>
                      <td>{x.medicine}</td>
                      <td><span className={`status s-${x.status}`}>{t.has(`doseStatus.${x.status}`) ? t(`doseStatus.${x.status}`) : x.status}</span>{x.confirm_source && x.confirm_source !== "whatsapp" ? <small>{x.confirm_source}</small> : null}</td>
                      <td>{x.sent_at ? formatClock(new Date(x.sent_at), "Asia/Kolkata", locale) : "–"}{x.send_delay_minutes !== null && x.send_delay_minutes > 15 && <small className="warn-text">{t("f.late", { minutes: x.send_delay_minutes })}</small>}<small>{utc(x.sent_at)}</small></td>
                      <td>{x.responded_at ? formatClock(new Date(x.responded_at), "Asia/Kolkata", locale) : "–"}<small>{utc(x.responded_at)}</small></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="muted">{t("f.timesNote")}</p>
          </section>
          <section className="monitor-card">
            <h2>{t("f.checkins")}</h2>
            <ul className="plain-list">
              {d.checkins.length === 0 && <li className="muted">{t("nothingYet")}</li>}
              {d.checkins.map((c) => (
                <li key={c.id}>{c.date} · <span className={`status s-${c.status}`}>{t.has(`checkinStatus.${c.status}`) ? t(`checkinStatus.${c.status}`) : c.status}</span> · {t("f.sentReplied", { sent: time(c.sent_at, false), replied: time(c.responded_at, false) })}</li>
              ))}
            </ul>
          </section>
          <section className="monitor-card">
            <h2>{t("f.alertsSent")}</h2>
            <ul className="plain-list">
              {d.alerts.length === 0 && <li className="muted">{t("nothingYet")}</li>}
              {d.alerts.map((a, i) => (
                <li key={`${a.type}-${a.created_at}-${i}`}>{t.has(`alertType.${a.type}`) ? t(`alertType.${a.type}`) : a.type} · {time(a.created_at)}{a.whatsapp_sent_at ? ` · ${t("f.viaWhatsapp")}` : ""}</li>
              ))}
            </ul>
            {d.consent_requests.length > 0 && <p className="muted">{t("f.consentAsked", { count: d.consent_requests.length, time: time(d.consent_requests[0].requested_at) })}</p>}
          </section>
        </div>
      )}
    </main>
  );
}
