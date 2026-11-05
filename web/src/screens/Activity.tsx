import { useCallback, useEffect, useRef, useState } from "react";
import { useLocale, useTranslations } from "use-intl";
import { AppHeader, Icon, useToast } from "../App";
import { formatClock, formatDate, type Language } from "../lib/format";
import { groupByDay, helperLabel, loadFeed, setHeart, type FeedItem, type FeedPerson } from "../lib/activity";
import "./activity.css";
import { Icon3D } from "./Icon3D";

/**
 * Family activity feed (feature 4): a read-only list of what happened for everyone the caregiver cares for, last 7 days, newest
 * first, grouped Today / Yesterday / earlier, with one filter chip per person. One ♥ per item, seen by the family only.
 * No comments, no chat.
 */

type Tone = "ok" | "warn" | "info";
const TONE: Record<FeedItem["kind"], Tone> = {
  dose_taken: "ok", dose_missed: "warn", checkin_answered: "ok", checkin_missed: "warn", vaccine_given: "ok",
  appointment_added: "info", medicine_added: "info", medicine_paused: "info", consent_given: "ok", consent_stopped: "warn",
};
const ICON: Record<FeedItem["kind"], string> = {
  dose_taken: "check", dose_missed: "bell", checkin_answered: "check", checkin_missed: "bell", vaccine_given: "check",
  appointment_added: "calendar", medicine_added: "pill", medicine_paused: "pill", consent_given: "phone", consent_stopped: "phone",
};

const initialOf = (name: string) => ([...name.trim()][0] ?? "?").toUpperCase();

/** One sentence per item, following who received the reminder (the person, a helper, a family member) or who marked it. */
function useSentence() {
  const t = useTranslations("activity");
  const language = useLocale() as Language;
  return (item: FeedItem, person: FeedPerson | undefined): string => {
    const call = person?.call ?? t("someone");
    const a = item.actor;
    const by = !a ? "" : a.kind === "helper" ? helperLabel(person, t("helper")) : (a.name ?? t("familyMember"));
    const thing = (language === "hi" && item.titleHi) || item.title || "";
    const who = !a ? "none" : a.kind === "me" ? "you" : a.kind === "person" ? "person" : a.kind === "app" ? "app" : "by";
    switch (item.kind) {
      case "dose_taken": return t(`doseTaken.${who === "none" ? "person" : who}`, { call, by, medicine: thing });
      case "dose_missed": return t("doseMissed", { call, medicine: thing });
      case "checkin_answered": return t(item.answer === "call" ? "checkinCall" : "checkinFine", { call });
      case "checkin_missed": return t("checkinMissed", { call });
      case "vaccine_given": return t("vaccineGiven", { call, vaccine: thing });
      case "appointment_added": return t(`appointmentAdded.${who === "you" || who === "by" ? who : "none"}`, { call, by, doctor: thing });
      case "medicine_added": return t("medicineAdded", { call, medicine: thing });
      case "medicine_paused": return t("medicinePaused", { call, medicine: thing });
      case "consent_given": return t(`consentGiven.${who === "you" || who === "by" ? who : "person"}`, { call, by });
      case "consent_stopped": return t(`consentStopped.${who === "you" || who === "by" ? who : "person"}`, { call, by });
    }
  };
}

function HeartButton({ item, onToggle }: { item: FeedItem; onToggle: (item: FeedItem) => void }) {
  const t = useTranslations("activity");
  return (
    <button type="button" className={`feed-heart${item.heartedByMe ? " on" : ""}`} aria-pressed={item.heartedByMe}
      aria-label={item.heartedByMe ? t("heartUndo", { count: item.hearts }) : t("heartGive", { count: item.hearts })} onClick={() => onToggle(item)}>
      <span aria-hidden="true">{item.heartedByMe ? "♥" : "♡"}</span>{item.hearts > 0 && <small>{item.hearts}</small>}
    </button>
  );
}

export function ActivityScreen({ go }: { go: (screen: "family") => void }) {
  const t = useTranslations("activity");
  const language = useLocale() as Language;
  const toast = useToast();
  const sentence = useSentence();
  const [people, setPeople] = useState<FeedPerson[]>([]);
  const [items, setItems] = useState<FeedItem[] | null>(null);
  const [next, setNext] = useState<string | null>(null);
  const [filter, setFilter] = useState<string | null>(null);
  const [error, setError] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const request = useRef(0);
  const viewerZone = Intl.DateTimeFormat().resolvedOptions().timeZone;

  const fetchFeed = useCallback((recipientId: string | null) => {
    const mine = ++request.current;
    loadFeed({ recipientId })
      .then((feed) => {
        if (mine !== request.current) return;
        setPeople(feed.people);
        setItems(feed.items);
        setNext(feed.next);
      })
      .catch(() => { if (mine === request.current) setError(true); });
  }, []);
  useEffect(() => { fetchFeed(null); }, [fetchFeed]);
  /** A chip or "Try again": show the skeleton, then that person's (or everyone's) feed. */
  const load = (recipientId: string | null) => {
    setFilter(recipientId);
    setItems(null);
    setNext(null);
    setError(false);
    fetchFeed(recipientId);
  };

  const more = async () => {
    if (!next || loadingMore) return;
    const mine = request.current;
    setLoadingMore(true);
    try {
      const feed = await loadFeed({ recipientId: filter, cursor: next });
      if (mine !== request.current) return;
      setItems((list) => [...(list ?? []), ...feed.items.filter((i) => !(list ?? []).some((x) => x.key === i.key && x.recipientId === i.recipientId))]);
      setNext(feed.next);
    } catch {
      toast(t("loadFailed"));
    } finally {
      setLoadingMore(false);
    }
  };

  const toggleHeart = async (item: FeedItem) => {
    const on = !item.heartedByMe;
    const patch = (hearts: number, heartedByMe: boolean) =>
      setItems((list) => (list ?? []).map((x) => (x.key === item.key && x.recipientId === item.recipientId ? { ...x, hearts, heartedByMe } : x)));
    patch(Math.max(0, item.hearts + (on ? 1 : -1)), on);   // feels instant on a slow network
    try {
      const result = await setHeart(item.recipientId, item.key, on);
      patch(result.hearts, result.heartedByMe);
    } catch {
      patch(item.hearts, item.heartedByMe);
      toast(t("heartFailed"));
    }
  };

  const personOf = (id: string) => people.find((p) => p.id === id);
  // Times on the person's clock (reminders follow their time zone); when the viewer lives elsewhere the main time also shows on
  // the viewer's clock. Days are grouped on the viewer's calendar.
  const zone = (item: FeedItem) => personOf(item.recipientId)?.timezone || "Asia/Kolkata";
  const clock = (iso: string, item: FeedItem) => formatClock(new Date(iso), zone(item), language);
  const both = (iso: string, item: FeedItem) => {
    const theirs = clock(iso, item);
    const mine = formatClock(new Date(iso), viewerZone, language);
    return mine === theirs ? theirs : t("bothTimes", { time: theirs, mine });
  };
  const dayTitle = (daysAgo: number, iso: string) =>
    daysAgo <= 0 ? t("today") : daysAgo === 1 ? t("yesterday") : formatDate(new Date(iso), language, { weekday: "long", day: "numeric", month: "short" });
  const meta = (item: FeedItem): string => {
    // A vaccine shows the day it was given, not when someone ticked it in the app.
    const parts = item.kind === "vaccine_given" ? [] : [both(item.at, item)];
    if ((item.kind === "dose_taken" || item.kind === "dose_missed" || item.kind === "medicine_added") && item.detail) parts.push(item.detail);
    if (item.kind === "dose_missed" && item.dueAt) parts[0] = t("dueAt", { time: clock(item.dueAt, item) });
    if (item.kind === "vaccine_given" && item.dueAt) parts.push(t("givenOn", { date: formatDate(new Date(item.dueAt), language, { day: "numeric", month: "short", timeZone: "UTC" }) }));
    if (item.kind === "vaccine_given" && item.detail) parts.push(t("doseLabel", { dose: item.detail }));
    if (item.kind === "appointment_added" && item.dueAt) parts.push(t("onDate", { date: formatDate(new Date(item.dueAt), language, { day: "numeric", month: "short", hour: "numeric", minute: "2-digit", timeZone: zone(item) }) }));
    return parts.join(" · ");
  };

  const groups = items ? groupByDay(items) : [];
  const chipLabel = (p: FeedPerson) => (p.isSelf ? t("me") : p.call);

  return (
    <main className="scroll-page no-nav activity-page">
      <AppHeader title={t("title")} subtitle={t("subtitle")} back={() => go("family")} />
      {people.length > 1 && (
        <div className="feed-chips" role="group" aria-label={t("filterLabel")}>
          <button type="button" className={filter === null ? "selected" : ""} aria-pressed={filter === null} onClick={() => load(null)}>{t("all")}</button>
          {people.map((p) => (
            <button type="button" key={p.id} className={filter === p.id ? "selected" : ""} aria-pressed={filter === p.id} onClick={() => load(p.id)}>
              <span className={`feed-initial ${p.careType ?? "older"}`} aria-hidden="true">{initialOf(chipLabel(p))}</span>{chipLabel(p)}
            </button>
          ))}
        </div>
      )}
      {error && (
        <div className="feed-error" role="alert">
          <p>{t("loadFailed")}</p>
          <button type="button" className="secondary-button" onClick={() => load(filter)}>{t("retry")}</button>
        </div>
      )}
      {items === null && !error && <div className="skeleton card" aria-busy="true" />}
      {items !== null && items.length === 0 && <p className="feed-empty">{t("empty")}</p>}
      {groups.map((g) => (
        <section className="feed-day" key={g.key} aria-label={dayTitle(g.daysAgo, g.items[0].at)}>
          <h2>{dayTitle(g.daysAgo, g.items[0].at)}</h2>
          <ul>
            {g.items.map((item) => {
              const person = personOf(item.recipientId);
              return (
                <li key={`${item.recipientId}:${item.key}`} className={`feed-item ${TONE[item.kind]}`}>
                  <span className="feed-icon" aria-hidden="true"><Icon name={ICON[item.kind]} size={18} /></span>
                  <div className="feed-text">
                    <p>{sentence(item, person)}</p>
                    <small>{meta(item)}</small>
                  </div>
                  <HeartButton item={item} onToggle={(i) => void toggleHeart(i)} />
                </li>
              );
            })}
          </ul>
        </section>
      ))}
      {next && items && <button type="button" className="secondary-button feed-more" disabled={loadingMore} onClick={() => void more()}>{loadingMore ? t("loading") : t("showMore")}</button>}
      {items !== null && items.length > 0 && <p className="feed-note">{t("heartNote")}</p>}
    </main>
  );
}

/** On the Family tab. */
export function ActivityLink({ go }: { go: (screen: "activity") => void }) {
  const t = useTranslations("activity");
  return (
    <button className="feature-link" onClick={() => go("activity")}>
      <Icon3D name="people" />
      <div><strong>{t("linkTitle")}</strong><p>{t("linkSub")}</p></div>
      <Icon name="chevron" />
    </button>
  );
}

/** A small text link on Today. */
export function SeeAllActivity({ go }: { go: (screen: "activity") => void }) {
  const t = useTranslations("activity");
  return <button type="button" className="feed-see-all" onClick={() => go("activity")}>{t("seeAll")} <Icon name="chevron" size={16} /></button>;
}
