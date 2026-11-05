import { Component, createContext, useContext, useEffect, useRef, useState, type ChangeEvent, type FormEvent, type ErrorInfo, type ReactNode } from "react";
import { createTranslator, useLocale, useTranslations } from "use-intl";
import { supabase } from "./lib/supabase";
import { requestOtp, verifyOtp, clearToken, authToken, authUserId, authExpired } from "./lib/auth";
import { ApiError } from "./lib/http";
import { compressImage } from "./lib/image";
import {
  isLowStock, loadCheckin, loadMyAlerts, requestAlerts, turnOffAlerts, loadMedicineNames, loadMedicines, loadMembers, loadNotifications, loadRecipients, loadTodayDoses, loadRecentActivity, type Activity, markNotificationsRead, deleteNotificationRow, sessionIsGone, isAuthError, isNotFound, normalizePhone, requestConsent,
  createRecipientApi, updateRecipientApi, createMedicineApi, updateMedicineApi, adjustStockApi, setMedicineFlags, deleteMedicineApi,
  loadReports, loadReport, loadTrend, uploadReport, analyzeReport, deleteReport, correctReportValue, signedReportUrl,
  type ReportSummary, type ReportDetailData, type ReportValue, type ReportFlag, type TrendPoint,
  type AlertsState, type AppNotification, type CircleMember, type Medicine, type Recipient, type TodayCheckin, type TodayDose,
} from "./lib/api";
import { clockLabel, clockParts, describeTimes, formatClock, formatDate, localDate, localizeDose, localMinutes, type Language, type T } from "./lib/format";
import { TIMEZONES } from "./lib/timezones";
import { CITIES } from "./lib/cities";
import { AppIntl, MESSAGES } from "./i18n";
import { ConsentGate, Goodbye, LegalLinks, LegalPage, OfflineBanner, PrivacyData } from "./screens/Privacy";
import { EmergencyLinkPanel } from "./screens/EmergencyLink";
import { ThemeButton } from "./screens/Appearance";
import { clearPendingInvite, leaveCircle, pendingInvite, removeMember } from "./lib/invites";
import { ConfirmSheet, InviteSheet, JoinScreen, PendingInvites } from "./screens/Invites";
import { AppointmentsScreen, NextAppointment } from "./screens/Appointments";
import { ChildDetailsFields, NextVaccine, VaccinesLink, VaccinesScreen } from "./screens/Vaccines";
import { childDetailsErrors, isChild, loadChild, saveChildDetails, vaccineDueText, type ChildDetails } from "./lib/vaccines";
import { ChildHome } from "./screens/ChildHome";
import { DoctorSummaryScreen } from "./screens/DoctorSummary";
import { PlansScreen } from "./screens/Plans";
import { StockField } from "./screens/StockField";
import { StatsScreen } from "./screens/Stats";
import { FeedbackScreen } from "./screens/Feedback";
import { LoveCard, LoveLink, LoveScreen } from "./screens/Love";
import { InstallBanner, InstallLink, PullIndicator } from "./screens/Install";
import { EscalationProgress, SafetyNetLink, SafetyNetScreen } from "./screens/SafetyNet";
import { usePullToRefresh } from "./lib/pullToRefresh";
import { WalletDetail, WalletScreen } from "./screens/Wallet";
import { loadIsAdmin } from "./lib/stats";
import { daysLeft, loadPlanOverview, loadPlanPauses, PLANS_ENABLED, type PlanOverview, type PlanPauses } from "./lib/plans";
import { loadSchedulerAge, SCHEDULER_STALE_SECONDS } from "./lib/scheduler";
import { type RouteStop } from "./screens/DoseRoute";
import { DoseLayouts, LayoutSheet, useTodayLayout } from "./screens/DoseLayouts";
import { alertsHidden, hideAlerts } from "./lib/alertsHidden";
import { forgetDoctorAddresses } from "./lib/doctorAddress";
import { PillGlyph, PillLookPicker, usePillLookText } from "./screens/PillLook";
import type { PillColour, PillShape } from "./lib/pillLook";
import { careTypeOf, dueDateOk, hindiOrdinal, isPregnancy, pregnancyStatus, todayIn, type CareType } from "./lib/pregnancy";
import { CareTypePicker, SupplementChips } from "./screens/Pregnancy";
import { PregnancyHome } from "./screens/PregnancyHome";
import { ReceiverChoice, ReceiverNote } from "./screens/ReminderReceiver";
import { WeeklyCard } from "./screens/WeeklySummary";
import { initialReceiverMode, receiverName, receiverOf, type ReceiverMode } from "./lib/reminderReceiver";
import { loadMe, type MeDto } from "./lib/api";
import { ActivityLink, ActivityScreen, SeeAllActivity } from "./screens/Activity";

type Screen =
  | "activity"
  | "welcome"
  | "otp"
  | "profile"
  | "addParent"
  | "today"
  | "medicines"
  | "addMedicine"
  | "editMedicine"
  | "medicineDetail"
  | "reports"
  | "reportDetail"
  | "documentDetail"
  | "family"
  | "emergency"
  | "whatsapp"
  | "privacyData"
  | "policy"
  | "terms"
  | "goodbye"
  | "join"
  | "appointments"
  | "plans"
  | "stats"
  | "safety"
  | "feedback"
  | "love"
  | "doctor"
  | "addSelf"
  | "vaccines";

const ToastContext = createContext<(message: string) => void>(() => {});
export const useToast = () => useContext(ToastContext);
const SessionContext = createContext<() => void>(() => {});
/** Call when the backend says our login no longer works: signs out and explains. */
export const useSessionExpired = () => useContext(SessionContext);

/** The privacy pages, the first-login consent sheet, data export and account deletion are built but switched off for v1. Set VITE_PRIVACY_ENABLED=true to turn them on. */
const PRIVACY_ENABLED = import.meta.env.VITE_PRIVACY_ENABLED === "true";

const MAX_NUMBER = 100000;  // stock, dose and refill numbers: far above anything real, far below database limits

/** A form field whose label is tied to its control and whose error is announced to screen readers. */
export function Field({ id, label, error, hint, children }: { id: string; label: string; error?: string; hint?: string; children: ReactNode }) {
  return (
    <div className={`field${error ? " invalid" : ""}`}>
      <label htmlFor={id}>{label}</label>
      {children}
      {hint && <p className="hint">{hint}</p>}
      {error && <p className="field-error" id={`${id}-error`} role="alert">{error}</p>}
    </div>
  );
}

/** id + accessibility attributes for an input that may show an error. Error keys are the field ids. */
const fieldProps = (id: string, errors: Record<string, string>) => ({
  id,
  "aria-invalid": errors[id] ? (true as const) : undefined,
  "aria-describedby": errors[id] ? `${id}-error` : undefined,
});
const focusFirstError = (errors: Record<string, string>) => {
  if (Object.keys(errors).length) window.setTimeout(() => document.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus(), 0);
};

/** Users never see raw backend text: it is English, technical and can echo what they typed.
 *  Works for the old Supabase error shape (hint) and the Java API's error detail (message). */
const friendlyError = (err: { code?: string; hint?: string; message?: string } | null | undefined, t: T) => {
  const tag = `${err?.hint ?? ""} ${err?.message ?? ""}`;
  return err?.code === "22003" ? t("add.errStock")
  : /too_many_medicines|maximum number of medicines/i.test(tag) ? t("add.errTooManyMedicines")
  : /plan_parents|limit of parent profiles/i.test(tag) ? t("plans.errParents")
  : /self_number/i.test(tag) ? t("profile.errSelfNumber")
  : /too_many_times|times per medicine/i.test(tag) ? t("add.errTooManyTimes")
  : /bad_name|invalid name/i.test(tag) ? t("add.errName")
  : /bad_dosage|invalid dosage/i.test(tag) ? t("add.errDose")
  : t("common.unexpected");
};

/** Dialog behaviour: focus moves in, Tab stays inside, Escape closes, focus returns to the button that opened it. */
export function useDialog(onClose: () => void) {
  const ref = useRef<HTMLElement | null>(null);
  const close = useRef(onClose);
  close.current = onClose;
  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    const node = ref.current;
    node?.focus({ preventScroll: true });
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") { event.stopPropagation(); close.current(); return; }
      if (event.key !== "Tab" || !node) return;
      const focusable = Array.from(node.querySelectorAll<HTMLElement>('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])')).filter((el) => !el.hasAttribute("disabled"));
      if (!focusable.length) { event.preventDefault(); return; }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const active = document.activeElement;
      if (event.shiftKey && (active === first || active === node)) { event.preventDefault(); last.focus({ preventScroll: true }); }
      else if (!event.shiftKey && active === last) { event.preventDefault(); first.focus({ preventScroll: true }); }
    };
    document.addEventListener("keydown", onKey);
    // A phone keyboard can make the browser scroll the (overflow:hidden) frame to reveal a field; put it back.
    const frame = node?.closest<HTMLElement>(".phone-frame");
    const reset = () => { if (frame) { frame.scrollTop = 0; frame.scrollLeft = 0; } };
    document.addEventListener("focusout", reset);
    window.visualViewport?.addEventListener("resize", reset);
    return () => { document.removeEventListener("keydown", onKey); document.removeEventListener("focusout", reset); window.visualViewport?.removeEventListener("resize", reset); reset(); opener?.focus?.({ preventScroll: true }); };
  }, []);
  return ref;
}


/** Makes a card tappable (mouse, touch and keyboard) without changing its look. */
const tappable = (onActivate: () => void) => ({
  role: "button" as const,
  tabIndex: 0,
  onClick: onActivate,
  onKeyDown: (event: { key: string; preventDefault: () => void }) => {
    if (event.key === "Enter" || event.key === " ") { event.preventDefault(); onActivate(); }
  },
});

/** What to call the parent: Papa / Mummy (in the given language) or their name. */
export function parentCall(recipient: Recipient | null, language: Language) {
  const chosen = recipient?.call_name?.trim();
  if (chosen) return chosen;  // what the caregiver calls them: "Mom", "Appa", "Dadi"
  if (recipient) return recipient.name;  // older records: the name that was typed, never a word we picked for them
  return MESSAGES[language].call.father;  // no parent loaded yet (login screens)
}

/** Relations offered in the form, and a friendly default for "What do you call them?" (the caregiver can change it). */
const RELATIONS = ["father", "mother", "grandfather", "grandmother", "father_in_law", "mother_in_law", "spouse", "uncle", "aunt", "brother", "sister", "other"] as const;
const defaultCallName = (relation: string, language: Language): string =>
  (MESSAGES[language].callDefaults as Record<string, string>)[relation] ?? "";

function usePersistentState<T>(key: string, initial: T) {
  const [value, setValue] = useState<T>(() => {
    try {
      const saved = localStorage.getItem(key);
      return saved ? JSON.parse(saved) : initial;
    } catch {
      return initial;
    }
  });
  useEffect(() => {
    try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* storage full or blocked: keep working without it */ }
  }, [key, value]);
  return [value, setValue] as const;
}

export function shareContent(title: string, text: string, t: T) {
  if (navigator.share) {
    return navigator.share({ title, text }).catch(() => undefined);
  }
  if (navigator.clipboard) {
    return navigator.clipboard.writeText(text).then(() => window.alert(t("common.copied")));
  }
  window.prompt(t("common.copyPrompt"), text);
  return Promise.resolve();
}

export function Icon({
  name,
  size = 22,
  strokeWidth = 1.8,
}: {
  name: string;
  size?: number;
  strokeWidth?: number;
}) {
  const paths: Record<string, ReactNode> = {
    trash: <><path d="M4 7h16M10 11v6M14 11v6" /><path d="M6 7l1 13h10l1-13M9 7V4h6v3" /></>,
    edit: <><path d="M4 20h4L19 9l-4-4L4 16z" /><path d="m13 7 4 4" /></>,
    sliders: <><path d="M4 7h9M17 7h3M4 17h3M11 17h9" /><circle cx="15" cy="7" r="2" /><circle cx="9" cy="17" r="2" /></>,
    home: <><path d="m3 10 9-7 9 7" /><path d="M5 9v11h14V9M9 20v-6h6v6" /></>,
    pill: <><path d="M10.5 4.5 4.7 10.3a5 5 0 0 0 7 7l5.8-5.8a5 5 0 0 0-7-7Z" /><path d="m8 7 9 9" /></>,
    report: <><path d="M6 3h9l3 3v15H6z" /><path d="M14 3v4h4M9 12h6M9 16h6" /></>,
    users: <><path d="M16 20v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" /><circle cx="9" cy="7" r="4" /><path d="M22 20v-2a4 4 0 0 0-3-3.9M16 3.1a4 4 0 0 1 0 7.8" /></>,
    bell: <><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9" /><path d="M10 21h4" /></>,
    chart: <><path d="M4 20V10M10 20V4M16 20v-7M22 20H2" /></>,
    check: <path d="m5 12 5 5 9-10" />,
    refresh: <><path d="M20 11a8 8 0 0 0-14.3-4.6L4 8" /><path d="M4 4v4h4" /><path d="M4 13a8 8 0 0 0 14.3 4.6L20 16" /><path d="M20 20v-4h-4" /></>,
    chevron: <path d="m9 18 6-6-6-6" />,
    back: <><path d="m15 18-6-6 6-6" /><path d="M9 12h10" /></>,
    plus: <><path d="M12 5v14M5 12h14" /></>,
    calendar: <><rect x="3" y="5" width="18" height="16" rx="2" /><path d="M16 3v4M8 3v4M3 10h18" /></>,
    upload: <><path d="M12 16V4M7 9l5-5 5 5" /><path d="M5 14v6h14v-6" /></>,
    share: <><circle cx="18" cy="5" r="3" /><circle cx="6" cy="12" r="3" /><circle cx="18" cy="19" r="3" /><path d="m8.6 10.5 6.8-4M8.6 13.5l6.8 4" /></>,
    heart: <path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8l1.1 1.1L12 21l7.8-7.5a5.5 5.5 0 0 0 1-8.9Z" />,
    shield: <><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10" /><path d="m9 12 2 2 4-4" /></>,
    camera: <><path d="M4 7h4l2-3h4l2 3h4v13H4z" /><circle cx="12" cy="13" r="4" /></>,
    down: <path d="m6 9 6 6 6-6" />,
    send: <><path d="M22 2 11 13" /><path d="M22 2 15 22l-4-9-9-4Z" /></>,
    phone: <path d="M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3.1 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2.1 4.2 2 2 0 0 1 4.1 2h3a2 2 0 0 1 2 1.7c.1 1 .4 2 .7 2.8a2 2 0 0 1-.4 2.1L8.1 9.9a16 16 0 0 0 6 6l1.3-1.3a2 2 0 0 1 2.1-.4c.9.3 1.8.6 2.8.7a2 2 0 0 1 1.7 2Z" />,
  };
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {paths[name]}
    </svg>
  );
}

export function AppHeader({
  title,
  subtitle,
  back,
  action,
}: {
  title: string;
  subtitle?: string;
  back?: () => void;
  action?: ReactNode;   // shown at the right end (e.g. the theme button on the Family page)
}) {
  const t = useTranslations();
  return (
    <header className="page-header">
      {back && <button className="icon-button" onClick={back} aria-label={t("common.goBack")}><Icon name="back" /></button>}
      <div>
        <h1>{title}</h1>
        {subtitle && <p>{subtitle}</p>}
      </div>
      {action}
    </header>
  );
}

function BottomNav({ screen, go, unread }: { screen: Screen; go: (screen: Screen) => void; unread: number }) {
  const t = useTranslations("nav");
  const tc = useTranslations("common");
  const tabs = [
    ["today", "home"],
    ["medicines", "pill"],
    ["reports", "report"],
    ["family", "users"],
  ] as const;
  return (
    <nav className="bottom-nav" aria-label={tc("mainNav")}>
      {tabs.map(([target, icon]) => {
        const active = screen === target || (screen === "appointments" && target === "today") || ((screen === "addMedicine" || screen === "editMedicine" || screen === "medicineDetail") && target === "medicines") || ((screen === "reportDetail" || screen === "documentDetail") && target === "reports") || (["emergency", "whatsapp", "plans", "stats", "safety", "feedback", "love", "doctor", "activity"].includes(screen) && target === "family");
        return (
          <button key={target} className={active ? "active" : ""} aria-current={active ? "page" : undefined} onClick={() => go(target)}>
            <Icon name={icon} size={21} />
            {target === "today" && unread > 0 && <i className="nav-dot" aria-hidden="true" />}
            <span>{t(target)}{target === "today" && unread > 0 && <span className="sr-only"> ({tc("unreadCount", { count: unread })})</span>}</span>
          </button>
        );
      })}
    </nav>
  );
}

class ErrorBoundary extends Component<{ fallback: ReactNode; children: ReactNode }, { crashed: boolean }> {
  state = { crashed: false };
  static getDerivedStateFromError() { return { crashed: true }; }
  componentDidCatch(error: Error, info: ErrorInfo) { console.error("screen crashed", error.message, info.componentStack); }
  render() { return this.state.crashed ? this.props.fallback : this.props.children; }
}

/** Shown instead of a blank page if a screen ever crashes, or when the first load fails. */
function Notice({ title, body, action, onAction }: { title: string; body: string; action: string; onAction: () => void }) {
  return (
    <main className="onboarding" role="alert">
      <div className="empty-state"><strong>{title}</strong><p>{body}</p></div>
      <button className="primary-button" onClick={onAction}>{action}</button>
    </main>
  );
}

function CrashNotice() {
  const t = useTranslations("common");
  return <Notice title={t("crashTitle")} body={t("crashBody")} action={t("reload")} onAction={() => window.location.reload()} />;
}

function LoadFailedNotice({ onRetry }: { onRetry: () => void }) {
  const t = useTranslations("common");
  const tPrivacy = useTranslations("privacy");
  return <Notice title={navigator.onLine ? t("crashTitle") : tPrivacy("offlineTitle")} body={t("loadFailed")} action={t("retry")} onAction={onRetry} />;
}

function Welcome({ go }: { go: (screen: Screen) => void }) {
  const t = useTranslations("welcome");
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const continueLogin = async () => {
    setError("");
    const phone = normalizePhone(value);
    if (!phone) { document.getElementById("login-input")?.focus(); return setError(t("invalidPhone")); }
    setBusy(true);
    const res = await requestOtp(phone);
    setBusy(false);
    if (!res.ok) {
      return setError(t(res.error === "network" ? "networkError" : res.error === "too_many" ? "tooManyCodes" : res.error === "invalid_phone" ? "invalidPhone" : "sendFailed"));
    }
    sessionStorage.setItem("carelink-login", JSON.stringify({ value: phone }));
    go("otp");
  };
  return (
    <main className="onboarding">
      <div className="logo-hero">
        <div className="brand-mark"><Icon name="heart" size={31} strokeWidth={2.2} /></div>
        <div className="big-heart">
          <Icon name="heart" size={64} strokeWidth={1.6} />
          {/* Optional photo: drop web/public/welcome-parent.jpg in. Without the file the heart above stays. */}
          <img className="welcome-photo" src="/welcome-parent.jpg" alt={t("photoAlt")} onError={(event) => { event.currentTarget.style.display = "none"; }} />
          <span className="floating-chip chip-one">{t("chipTaken")}</span>
          <span className="floating-chip chip-two">{t("chipOkay")}</span>
        </div>
      </div>
      <div className="welcome-copy">
        <p className="eyebrow">{t("eyebrow")}</p>
        <h1>{t("headline")}</h1>
        <p>{t("body")}</p>
        {pendingInvite() && <p className="invite-banner" role="status">{t("inviteBanner")}</p>}
      </div>
      <div className="login-card">
        <label htmlFor="login-input">{t("phoneLabel")}</label>
        <div className="phone-input">
          {!/^\s*(\+|00)/.test(value) && <span>+91</span>}
          <input id="login-input" aria-invalid={error ? true : undefined} value={value} onChange={(event) => setValue(event.target.value)} placeholder={t("phonePlaceholder")} inputMode="tel" />
        </div>
        {error && <p role="alert" className="form-error">{error}</p>}
        <button className="primary-button" onClick={continueLogin} disabled={busy}>{busy ? t("sending") : t("continue")}</button>
        {PRIVACY_ENABLED && <LegalLinks go={go} />}
      </div>
    </main>
  );
}

type PendingLogin = { value: string } | null;
const readPendingLogin = (): PendingLogin => {
  try { return JSON.parse(sessionStorage.getItem("carelink-login") ?? "null"); } catch { return null; }
};

function OtpScreen({ go, onVerified }: { go: (screen: Screen, options?: { replace?: boolean }) => void; onVerified: () => Promise<void> }) {
  const t = useTranslations("otp");
  const [otp, setOtp] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const pending = readPendingLogin();
  const destination = pending?.value || t("yourPhone");
  const verifying = useRef(false);  // synchronous: rapid taps must send one verify call
  useEffect(() => { if (!pending) go("welcome", { replace: true }); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, []);
  const verify = async () => {
    if (verifying.current) return;
    setError("");
    if (!pending) return go("welcome", { replace: true });
    if (otp.length < 6 || otp.length > 8) return setError(t("invalidCode"));  // Supabase sends 6 or 8 digits depending on the project setting
    verifying.current = true;
    setBusy(true);
    try {
      const res = await verifyOtp(pending.value, otp);
      if (!res.ok) { setBusy(false); return setError(t(res.error === "network" ? "networkError" : res.error === "too_many" ? "tooManyTries" : "wrongCode")); }
      sessionStorage.removeItem("carelink-login");
      await onVerified();
    } finally {
      verifying.current = false;
      setBusy(false);
    }
  };
  const resend = async () => {
    if (!pending) return go("welcome", { replace: true });
    setError(""); setNotice("");
    const res = await requestOtp(pending.value);
    if (!res.ok) return setError(t(res.error === "network" ? "networkError" : res.error === "too_many" ? "tooManyTries" : "resendFailed"));
    setNotice(t("resent"));
  };
  if (!pending) return null;
  return (
    <main className="onboarding otp-screen">
      <AppHeader title={t("title")} subtitle={t("subtitle", { destination })} back={() => go("welcome")} />
      <div className="otp-icon"><Icon name="shield" size={30} /></div>
      <h2>{t("heading")}</h2>
      {import.meta.env.DEV && <p>{t("devHint")}</p>}
      <input className="otp-input" aria-label={t("heading")} value={otp} onChange={(event) => setOtp(event.target.value.replace(/\D/g, "").slice(0, 8))} inputMode="numeric" autoComplete="one-time-code" placeholder="• • • • • •" autoFocus />
      {error && <p role="alert" className="form-error">{error}</p>}
      {notice && <p role="status" style={{ textAlign: "center", color: "var(--primary-text)" }}>{notice}</p>}
      <button className="primary-button" onClick={verify} disabled={busy}>{busy ? t("checking") : t("verify")}</button>
      <button className="text-button" onClick={resend}>{t("resend")}</button>
    </main>
  );
}

/** A sensible starting time zone for a number outside India (the caregiver can change it). */
const ZONE_BY_DIAL: Record<string, string> = {
  "+1": "America/New_York", "+44": "Europe/London", "+971": "Asia/Dubai", "+61": "Australia/Sydney", "+65": "Asia/Singapore", "+64": "Pacific/Auckland",
  "+49": "Europe/Berlin", "+31": "Europe/Berlin", "+353": "Europe/London", "+966": "Asia/Riyadh", "+974": "Asia/Qatar", "+965": "Asia/Kuwait",
  "+968": "Asia/Dubai", "+973": "Asia/Riyadh",
};
const defaultZone = (e164: string) => {
  const dial = Object.keys(ZONE_BY_DIAL).filter((d) => e164.startsWith(d)).sort((x, y) => y.length - x.length)[0];
  return dial ? ZONE_BY_DIAL[dial] : "Asia/Kolkata";
};
const splitList = (value: string) => value.split(/[,\n]/).map((item) => item.trim()).filter(Boolean);
const phoneForForm = (e164: string | null) => (e164?.startsWith("+91") ? `+91 ${e164.slice(3, 8)} ${e164.slice(8)}` : e164 ?? "");

function ProfileForm({ go, recipient, adding = false, self = false, onSaved, onSignOut }: { go: (screen: Screen) => void; recipient: Recipient | null; adding?: boolean; self?: boolean; onSaved: (newId?: string) => Promise<void>; onSignOut?: () => void }) {
  const t = useTranslations("profile");
  const isSelf = self || !!recipient?.is_self;   // the caregiver's own profile ("Me"): reminders for their own medicines
  const submitting = useRef(false);  // synchronous guard: two quick submits must create only one parent
  const [zone, setZone] = useState("");
  const [zoneTouched, setZoneTouched] = useState(false);
  const tAll = useTranslations();
  const expireSession = useSessionExpired();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const language = useLocale() as Language;
  const [phoneText, setPhoneText] = useState(phoneForForm(recipient?.whatsapp_phone ?? null));
  const [relation, setRelation] = useState<string>(recipient && (RELATIONS as readonly string[]).includes(recipient.relation) ? recipient.relation : "father");
  // "What do you call them?" starts from a friendly default for the relation until the caregiver types their own.
  const [callName, setCallName] = useState(recipient ? parentCall(recipient, language) : defaultCallName("father", language));
  const [callTouched, setCallTouched] = useState(!!recipient);
  // Who they care for (migration 0043): an older parent (default, every existing profile) or a pregnancy. Not for "Me".
  const [careType, setCareType] = useState<CareType>(careTypeOf(recipient));
  const pregnant = !isSelf && careType === "pregnant";
  const [dueDate, setDueDate] = useState(recipient?.due_date ?? "");
  // A child (migration 0045): date of birth + "I am the parent or legal guardian", saved through the child endpoint after the profile.
  const child = !isSelf && careType === "child";
  const [childDetails, setChildDetails] = useState<ChildDetails>({ dob: recipient?.dob ?? "", guardian: false });
  const [guardianDone, setGuardianDone] = useState(false);
  useEffect(() => {
    if (!recipient || careTypeOf(recipient) !== "child") return;
    loadChild(recipient.id).then((info) => {
      if (info.guardianConfirmedAt) setGuardianDone(true);
      if (info.dob) setChildDetails((d) => ({ ...d, dob: d.dob || (info.dob as string) }));
    }).catch(() => { /* the form still works: the guardian box simply asks again */ });
  }, [recipient]);
  const pk = (key: string) => (pregnant ? `${key}Pregnant` : key);   // the pregnancy wording of a label ("Her name")
  const chooseCareType = (next: CareType) => {
    setCareType(next);
    // A new profile follows the choice until the caregiver picks a relation or a name by hand.
    if (!recipient && relation === (next === "pregnant" ? "father" : "spouse")) {
      const nextRelation = next === "pregnant" ? "spouse" : "father";
      setRelation(nextRelation);
      if (!callTouched) setCallName(defaultCallName(nextRelation, language));
    }
  };
  const typedPhone = normalizePhone(phoneText);
  // Who gets the WhatsApp reminders (docs/reminder-receiver.md): the person, me, someone else (a helper), or ("other") another family member.
  const myId = authUserId();
  const storedReceiver = receiverOf(recipient);
  const [receiver, setReceiver] = useState<ReceiverMode>(isSelf ? "person" : initialReceiverMode(recipient, myId));
  const [helperName, setHelperName] = useState(storedReceiver.reminder_to === "helper" ? storedReceiver.reminder_name ?? "" : "");
  const [helperRelation, setHelperRelation] = useState(storedReceiver.reminder_to === "helper" ? storedReceiver.reminder_relation ?? "" : "");
  const [me, setMe] = useState<MeDto | null>(null);
  useEffect(() => {
    if (isSelf) return;
    let cancelled = false;
    loadMe().then((m) => { if (!cancelled) setMe(m); }).catch(() => {});
    return () => { cancelled = true; };
  }, [isSelf]);
  const chooseReceiver = (next: ReceiverMode) => {
    if (next === receiver) return;
    setReceiver(next);
    // The number belongs to whoever gets the reminders: the stored one when going back, my own for "Me", empty for someone new.
    if (recipient && next === initialReceiverMode(recipient, myId)) setPhoneText(phoneForForm(recipient.whatsapp_phone));
    else if (next === "caregiver") setPhoneText(phoneForForm(me?.phone ?? me?.alertPhone ?? null));
    else setPhoneText("");
  };
  const reminderTo = receiver === "other" ? "caregiver" : receiver;
  // Another profile of yours that already uses this number: a warning only (a couple may share one phone); the database refuses it for your own profile.
  const [sharedWith, setSharedWith] = useState<{ name: string; isSelf: boolean } | null>(null);
  useEffect(() => {
    let cancelled = false;
    const run = async () => {
      if (!typedPhone) { setSharedWith(null); return; }
      const list = await loadRecipients().catch(() => [] as Recipient[]);
      const hit = list.find((r) => r.whatsapp_phone === typedPhone && (!recipient || r.id !== recipient.id));
      if (!cancelled) setSharedWith(hit ? { name: hit.name, isSelf: hit.is_self } : null);
    };
    const id = window.setTimeout(() => void run(), 400);
    return () => { cancelled = true; window.clearTimeout(id); };
  }, [typedPhone, recipient?.id]);
    // The time zone follows the phone number's country until the caregiver picks one by hand. It is always shown: reminders follow THE PARENT's clock.
  const zoneValue = zoneTouched ? zone : recipient && typedPhone === recipient.whatsapp_phone ? recipient.timezone : defaultZone(typedPhone ?? "");
  const zoneOptions: string[] = (TIMEZONES as readonly string[]).includes(zoneValue) ? [...TIMEZONES] : [...TIMEZONES, zoneValue];
  const saveProfile = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (submitting.current) return;
    submitting.current = true;
    try {
      await saveProfileInner(event);
    } catch {
      setError(tAll("common.unexpected"));
      setBusy(false);
    } finally {
      submitting.current = false;
    }
  };
  const saveProfileInner = async (event: FormEvent<HTMLFormElement>) => {
    setError("");
    const data = Object.fromEntries(new FormData(event.currentTarget)) as Record<string, string>;
    const found: Record<string, string> = {};
    const name = (data.name ?? "").trim();
    if (!name) found["f-name"] = t(pk("errName"));
    const whatsapp = normalizePhone(data.whatsapp ?? "");
    if (!whatsapp) found["f-whatsapp"] = t("invalidPhone");
    if (!isSelf && receiver === "helper" && !helperName.trim()) found["f-helperName"] = t("errHelperName");
    const timezone = data.timezone || zoneValue;   // always the box: it starts at the stored zone, or the number's country (India: Asia/Kolkata)
    const ageText = (data.age ?? "").trim();
    const age = ageText ? Number(ageText) : null;
    if (age !== null && !(/^\d{1,3}$/.test(ageText) && age >= 0 && age <= 130)) found["f-age"] = t("invalidAge");
    const conditions = splitList(data.conditions ?? "");
    const allergies = splitList(data.allergies ?? "");
    const badTags = (list: string[]) => list.length > 30 || list.some((item) => item.length > 80);
    if (badTags(conditions)) found["f-conditions"] = t("errTags");
    if (badTags(allergies)) found["f-allergies"] = t("errTags");
    // The due date is optional; when typed it must be plausible on HER calendar (the backend checks the same window).
    if (pregnant && dueDate && !dueDateOk(dueDate, todayIn(timezone))) found["f-dueDate"] = t("errDueDate");
    if (child) Object.assign(found, childDetailsErrors(childDetails, todayIn(timezone), guardianDone, { errDob: tAll("vaccines.errDob"), errGuardian: tAll("vaccines.errGuardian") }));
    setErrors(found);
    if (Object.keys(found).length || !whatsapp) return focusFirstError(found);

    const body = {
      name,
      relation: isSelf ? "self" : (RELATIONS as readonly string[]).includes(data.relation) ? data.relation : "other",
      callName: isSelf ? null : (data.callName ?? "").trim() || null,
      age,
      dob: recipient?.dob ?? null,                                 // the form does not edit these: pass them through
      doctorContacts: recipient?.doctor_contacts ?? null,
      emergencyContacts: recipient?.emergency_contacts ?? null,
      city: data.city?.trim() || null,
      whatsappPhone: whatsapp,
      timezone,
      language: data.language === "en" ? "en" : "hi",
      conditions,
      allergies,
      bloodGroup: data.bloodGroup || null,
      ...(isSelf && !recipient ? { isSelf: true } : {}),
      // "" clears the due date; the backend also clears it whenever the profile is not a pregnancy.
      ...(isSelf ? {} : { careType: pregnant ? "pregnant" : child ? "child" : "older", dueDate: pregnant ? dueDate : "",
        reminderTo, reminderName: receiver === "helper" ? helperName.trim() : null, reminderRelation: receiver === "helper" ? helperRelation.trim() || null : null }),
    };
    setBusy(true);
    let recipientId = recipient?.id;
    try {
      if (recipient) {
        await updateRecipientApi(recipient.id, body);
      } else {
        recipientId = (await createRecipientApi(body)).id;
      }
    } catch (e) {
      setBusy(false);
      const err = e as ApiError;
      if (recipient && err.status === 404) { void onSaved(); return setError(tAll("common.unexpected")); }
      if (await sessionIsGone(err)) return expireSession();
      return setError(friendlyError(err, tAll));
    }
    // A child's date of birth and the guardian's confirmation (needed before any of the child's health data is kept).
    if (child && recipientId) {
      try {
        await saveChildDetails(recipientId, childDetails.dob);
      } catch {
        setBusy(false);
        await onSaved(recipientId);
        return setError(tAll("common.unexpected"));
      }
    }
    // A new parent, or a changed number, needs a fresh WhatsApp opt-in (the database resets consent on a number change).
    // A new receiver (helper / me / the person) is a new agreement too (the database resets consent then).
    if (recipientId && (!recipient || recipient.whatsapp_phone !== whatsapp || (!isSelf && storedReceiver.reminder_to !== reminderTo))) void requestConsent(recipientId);
    await onSaved(recipientId);
    setBusy(false);
  };
  return (
    <main className="scroll-page no-nav">
      <AppHeader title={isSelf ? t(recipient ? "titleSelfEdit" : "titleSelfAdd") : pregnant ? t(recipient ? "titleEditPregnant" : "titleNewPregnant") : adding ? t("titleAdd") : recipient ? t("titleEdit") : t("titleNew")} subtitle={isSelf ? t(recipient ? "subtitleSelfEdit" : "subtitleSelfAdd") : pregnant ? t("subtitlePregnant") : adding ? t("subtitleAdd") : recipient ? t("subtitleEdit") : t("subtitleNew")} back={recipient || adding ? () => go("today") : undefined} />
      <form id="parent-form" className="form-card" onSubmit={saveProfile} noValidate>
        {!isSelf && <CareTypePicker value={careType} onChange={chooseCareType} />}
        {pregnant && <Field id="f-dueDate" label={t("dueDate")} error={errors["f-dueDate"]} hint={t("dueDateHint")}><input {...fieldProps("f-dueDate", errors)} name="dueDate" type="date" value={dueDate} onChange={(event) => setDueDate(event.target.value)} /></Field>}
        {child && <ChildDetailsFields value={childDetails} onChange={setChildDetails} today={todayIn(zoneValue)} errors={errors} guardianLocked={guardianDone} />}
        <Field id="f-name" label={t(isSelf ? "nameSelf" : pk("name"))} error={errors["f-name"]}><input {...fieldProps("f-name", errors)} name="name" autoComplete="off" maxLength={100} defaultValue={recipient?.name ?? ""} /></Field>
        {!isSelf && <div className="two-col">
          <Field id="f-age" label={t("age")} error={errors["f-age"]}><input {...fieldProps("f-age", errors)} name="age" inputMode="numeric" defaultValue={recipient?.age ?? ""} /></Field>
          <Field id="f-relation" label={t("relation")}><select id="f-relation" name="relation" value={relation} onChange={(event) => { setRelation(event.target.value); if (!callTouched) setCallName(defaultCallName(event.target.value, language)); }}>{RELATIONS.map((r) => <option key={r} value={r}>{t(`relations.${r}`)}</option>)}</select></Field>
        </div>}
        {!isSelf && <Field id="f-callName" label={t("callName")} hint={t(pk("callNameHint"))}><input id="f-callName" name="callName" autoComplete="off" maxLength={40} value={callName} onChange={(event) => { setCallName(event.target.value); setCallTouched(true); }} /></Field>}
        {!isSelf && <ReceiverChoice mode={receiver} onMode={chooseReceiver} call={callName.trim() || t("receiverPersonFallback")} otherName={receiverName(storedReceiver) || t("receiverFamilyMember")}
          helperName={helperName} helperRelation={helperRelation} onHelperName={setHelperName} onHelperRelation={setHelperRelation} nameError={errors["f-helperName"]} />}
        <Field id="f-whatsapp" label={isSelf ? t("whatsappSelf") : receiver === "person" ? t("whatsapp") : receiver === "caregiver" ? t("whatsappMe") : receiver === "helper" ? t("whatsappHelper") : t("whatsappOther", { name: receiverName(storedReceiver) })} error={errors["f-whatsapp"]} hint={isSelf ? t("whatsappHintSelf") : receiver === "person" ? t(pk("whatsappHint")) : receiver === "caregiver" ? t("whatsappHintMe", { call: callName.trim() || t("receiverPersonFallback") }) : receiver === "helper" ? t("whatsappHintHelper") : t("whatsappHintOther")}><input {...fieldProps("f-whatsapp", errors)} name="whatsapp" inputMode="tel" autoComplete="off" placeholder="+91 98100 12345" value={phoneText} readOnly={receiver === "other"} onChange={(event) => setPhoneText(event.target.value)} /></Field>
        {sharedWith && <p className="form-note" role="status">{receiver === "caregiver" && sharedWith.isSelf ? t("sharedNumberMe") : t(isSelf || sharedWith.isSelf ? "sharedNumberSelf" : "sharedNumber", { name: sharedWith.name })}</p>}
        <Field id="f-timezone" label={t(pk("timezone"))} hint={t(pk("timezoneHint"))}><select id="f-timezone" name="timezone" value={zoneValue} onChange={(event) => { setZone(event.target.value); setZoneTouched(true); }}>{zoneOptions.map((z) => <option key={z} value={z}>{z.replace("_", " ")}</option>)}</select></Field>
        {!isSelf && <Field id="f-city" label={t("city")}><input id="f-city" name="city" list="city-options" autoComplete="off" maxLength={100} placeholder={t("cityPlaceholder")} defaultValue={recipient?.city ?? ""} /><datalist id="city-options">{CITIES.map((city) => <option key={city} value={city} />)}</datalist></Field>}
        <Field id="f-conditions" label={t("conditions")} error={errors["f-conditions"]}><input {...fieldProps("f-conditions", errors)} name="conditions" placeholder={t("conditionsPlaceholder")} defaultValue={recipient?.conditions.join(", ") ?? ""} /></Field>
        <div className="two-col">
          <Field id="f-bloodGroup" label={t("bloodGroup")}><select id="f-bloodGroup" name="bloodGroup" defaultValue={recipient?.blood_group ?? ""}><option value="">{t("notSure")}</option>{["A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"].map((group) => <option key={group}>{group}</option>)}</select></Field>
          <Field id="f-language" label={t("language")}><select id="f-language" name="language" defaultValue={recipient ? (recipient.language === "en" ? "en" : "hi") : isSelf ? (language === "en" ? "en" : "hi") : "hi"}><option value="hi">{t("langHindi")}</option><option value="en">{t("langEnglish")}</option></select></Field>
        </div>
        <Field id="f-allergies" label={t("allergies")} error={errors["f-allergies"]}><input {...fieldProps("f-allergies", errors)} name="allergies" placeholder={t("allergiesPlaceholder")} defaultValue={recipient?.allergies.join(", ") ?? ""} /></Field>
        {!recipient && <p className="form-note">{t(isSelf ? "noteSelf" : pk("note"))}</p>}
      </form>
      {error && <p role="alert" className="form-error">{error}</p>}
      <button className="primary-button sticky-action" type="submit" form="parent-form" disabled={busy}>{busy ? t("saving") : recipient ? t("saveEdit") : t("save")}</button>
      {!recipient && !adding && onSignOut && (
        <div className="first-run-extras">
          <button type="button" className="text-button" onClick={onSignOut}>{tAll("privacy.consentLogout")}</button>
          {PRIVACY_ENABLED && <LegalLinks go={go} />}
        </div>
      )}
    </main>
  );
}

const StatusPill = ({ type, children }: { type: "taken" | "pending" | "missed"; children: ReactNode }) => (
  <span className={`status ${type}`}>{children}</span>
);

function parentGreeting(recipient: Recipient | null, language: Language, t: T) {
  const tz = recipient?.timezone ?? "Asia/Kolkata";
  const now = new Date();
  const hour = Number(new Intl.DateTimeFormat("en-GB", { hour: "numeric", hour12: false, timeZone: tz }).format(now)) % 24;
  return {
    call: parentCall(recipient, language),
    hello: hour < 12 ? t("today.morning") : hour < 17 ? t("today.afternoon") : t("today.evening"),
    where: `${recipient?.city ? recipient.city + " • " : ""}${formatClock(now, tz, language)}${tz !== Intl.DateTimeFormat().resolvedOptions().timeZone && !recipient?.is_self ? " · " + t("today.theirTime") : ""}`,
  };
}

/** A list row: swipe it sideways to reveal a dustbin, tap the dustbin to remove it. */
const SWIPE_OPEN = 76;
function SwipeRow({ onRemove, label, className, children }: { onRemove: () => void; label: string; className?: string; children: ReactNode }) {
  const [open, setOpen] = useState(0);   // 0 closed, -76 opened to the left, 76 opened to the right
  const [drag, setDrag] = useState<number | null>(null);
  const [gone, setGone] = useState(false);
  const start = useRef<{ x: number; y: number; base: number; locked: boolean } | null>(null);
  const x = drag ?? open;
  const remove = () => { setGone(true); window.setTimeout(onRemove, 180); };
  return (
    <li className={`swipe-li${gone ? " gone" : ""}`}>
      {x > 0 && <button className="swipe-trash left" aria-label={label} onClick={remove}><Icon name="trash" size={22} /></button>}
      {x < 0 && <button className="swipe-trash right" aria-label={label} onClick={remove}><Icon name="trash" size={22} /></button>}
      <div
        className={`swipe-row ${className ?? ""}`}
        tabIndex={0}
        style={{ transform: `translateX(${x}px)`, transition: drag === null ? "transform .18s" : "none" }}
        onKeyDown={(e) => { if (e.target === e.currentTarget && (e.key === "Delete" || e.key === "Backspace")) { e.preventDefault(); remove(); } else if (e.key === "Escape" && open) { e.stopPropagation(); setOpen(0); } }}
        onPointerDown={(e) => { start.current = { x: e.clientX, y: e.clientY, base: open, locked: false }; }}
        onPointerMove={(e) => {
          const s = start.current; if (!s) return;
          const mx = e.clientX - s.x;
          if (!s.locked && Math.abs(mx) > 10 && Math.abs(mx) > Math.abs(e.clientY - s.y)) { s.locked = true; (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId); }
          if (s.locked) setDrag(Math.max(-SWIPE_OPEN * 1.4, Math.min(SWIPE_OPEN * 1.4, s.base + mx)));
        }}
        onPointerUp={() => {
          const s = start.current; start.current = null;
          if (s?.locked && drag !== null) setOpen(drag < -SWIPE_OPEN / 2 ? -SWIPE_OPEN : drag > SWIPE_OPEN / 2 ? SWIPE_OPEN : 0);
          else if (open && !s?.locked) setOpen(0);   // a tap on an opened row closes it
          setDrag(null);
        }}
        onPointerCancel={() => { start.current = null; setDrag(null); }}
      >
        {children}
      </div>
    </li>
  );
}

function NotificationSheet({ notifications, medicineNames, callFor, onDelete, onClose }: { notifications: AppNotification[]; medicineNames: Record<string, string>; callFor: (recipientId?: string) => string; onDelete: (id: string) => void; onClose: () => void }) {
  const t = useTranslations("notif");
  const tv = useTranslations("vaccines");
  const language = useLocale() as Language;
  const dialog = useDialog(onClose);
  const text = (n: AppNotification) => {
    const payload = (n.payload ?? {}) as { medicine_id?: string; recipient_id?: string; count?: number; days?: number; reason?: string };
    const call = callFor(payload.recipient_id);  // the parent THIS alert is about, not necessarily the one on screen
    const name = payload.medicine_id ? medicineNames[payload.medicine_id] : undefined;
    if (n.type === "refill_needed") return name ? t("refill_needed", { name }) : t("refill_needed_generic");
    if (n.type === "out_of_stock") return name ? t("out_of_stock", { name }) : t("out_of_stock_generic");
    if (n.type === "dose_missed" && typeof payload.count === "number" && payload.count > 1) return t("dose_missed_many", { call, count: payload.count });
    if (n.type === "vaccine_due") return vaccineDueText(n.payload, language, call, tv);
    if (n.type === "parent_silent") return t("parent_silent", { call, days: payload.days ?? 3 });
    if (n.type === "love_not_delivered") return t(`love_not_delivered_${payload.reason === "expired" || payload.reason === "parent_opted_out" || payload.reason === "whatsapp_failed" ? payload.reason : "other"}`, { call });
    if (n.type === "dose_missed") return name ? t("dose_missed_named", { call, name }) : t("dose_missed", { call });
    if (n.type === "reminder_not_sent") return name ? t("reminder_not_sent_named", { call, name }) : t("reminder_not_sent", { call });
    return t.has(n.type) ? t(n.type, { call }) : t("unknown");
  };
  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <section className="sheet" ref={dialog} tabIndex={-1} role="dialog" aria-modal="true" aria-label={t("title")} onClick={(event) => event.stopPropagation()}>
        <div className="sheet-head"><h3>{t("title")}</h3><button className="sheet-x" aria-label={t("close")} onClick={onClose}>×</button></div>
        {notifications.length > 0 && <p className="sheet-hint">{t("swipeHint")}</p>}
        {notifications.length === 0 ? <p className="sheet-empty">{t("empty")}</p> : (
          <ul className="notif-list">
            {notifications.map((n) => (
              <SwipeRow key={n.id} className={n.read_at ? "" : "unread"} label={t("delete")} onRemove={() => onDelete(n.id)}>
                <span>{text(n)}</span>
                <small>{formatDate(new Date(n.created_at), language, { day: "numeric", month: "short" })}, {formatClock(new Date(n.created_at), Intl.DateTimeFormat().resolvedOptions().timeZone, language)}</small>
              </SwipeRow>
            ))}
          </ul>
        )}
        <button className="primary-button" onClick={onClose}>{t("close")}</button>
      </section>
    </div>
  );
}

/** Who am I looking after right now? Switch parent, edit the current one, or add another. */
function ParentSheet({ recipients, selectedId, language, onSelect, onEdit, onAdd, onAddSelf, onClose }: { recipients: Recipient[]; selectedId: string | null; language: Language; onSelect: (id: string) => void; onEdit: () => void; onAdd: () => void; onAddSelf: () => void; onClose: () => void }) {
  const t = useTranslations("parents");
  const dialog = useDialog(onClose);
  const current = recipients.find((r) => r.id === selectedId);
  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <section className="sheet" ref={dialog} tabIndex={-1} role="dialog" aria-modal="true" aria-label={t("title")} onClick={(event) => event.stopPropagation()}>
        <h3>{t("title")}</h3>
        <ul className="parent-list">
          {recipients.map((r) => (
            <li key={r.id}>
              <button className={`parent-option${r.id === selectedId ? " selected" : ""}`} aria-pressed={r.id === selectedId} onClick={() => onSelect(r.id)}>
                <span className="initials-avatar" aria-hidden="true">{([...r.name][0] ?? "?").toUpperCase()}</span>
                <span><strong>{parentCall(r, language)}</strong><small>{r.is_self ? t("selfTag") : [r.name, r.city].filter(Boolean).join(" • ")}</small></span>
                {r.id === selectedId && <span className="parent-check" aria-hidden="true">✓</span>}
              </button>
            </li>
          ))}
        </ul>
        <div className="stack">
          {current && <button className="secondary-button" onClick={onEdit}>{t("edit", { name: parentCall(current, language) })}</button>}
          <button className="primary-button" onClick={onAdd}><Icon name="plus" size={18} /> {t("add")}</button>
          {!recipients.some((r) => r.is_self) && <button className="secondary-button" onClick={onAddSelf}>{t("addSelf")}</button>}
        </div>
      </section>
    </div>
  );
}

/** What is paused because of the plan limits. Refreshed when the screen opens and when the app comes back to the front. */
export function usePlanPauses(): PlanPauses | null {
  const [pauses, setPauses] = useState<PlanPauses | null>(null);
  useEffect(() => {
    let cancelled = false;
    const load = () => { loadPlanPauses().then((p) => { if (!cancelled) setPauses(p); }).catch(() => {}); };
    load();
    const onReturn = () => { if (document.visibilityState === "visible") load(); };
    document.addEventListener("visibilitychange", onReturn);
    return () => { cancelled = true; document.removeEventListener("visibilitychange", onReturn); };
  }, []);
  return pauses;
}

/** A loud, plain warning: reminders that are NOT being sent because of the plan limits. Visible to the whole family circle. */
function PauseWarning({ recipient, call, go }: { recipient: Recipient; call: string; go: (screen: Screen) => void }) {
  const t = useTranslations("today");
  const pauses = usePlanPauses();
  if (!pauses) return null;
  const parentPaused = pauses.parentIds.includes(recipient.id);
  const names = pauses.medicines.filter((m) => m.recipientId === recipient.id).map((m) => m.name);
  if (!parentPaused && names.length === 0) return null;
  const shown = names.slice(0, 5).join(", ") + (names.length > 5 ? ` +${names.length - 5}` : "");
  return (
    <section className="pause-card" role="alert">
      <div className="pause-icon" aria-hidden="true">!</div>
      <div>
        <strong>{parentPaused ? t("pauseParentTitle", { call }) : t("pauseMedicinesTitle")}</strong>
        <p>{parentPaused ? t("pauseParentBody", { call }) : t("pauseMedicinesBody", { call, names: shown })}</p>
        <button onClick={() => go("plans")}>{t("pauseButton")}</button>
      </div>
    </section>
  );
}

/** Tells the caregiver when the free trial is about to end (last 7 days) or has ended. Quiet otherwise. */
function TrialBanner({ go }: { go: (screen: Screen) => void }) {
  const t = useTranslations("today");
  const [plan, setPlan] = useState<PlanOverview | null>(null);
  const pauses = usePlanPauses();
  useEffect(() => {
    let cancelled = false;
    loadPlanOverview().then((p) => { if (!cancelled) setPlan(p); }).catch(() => {});
    return () => { cancelled = true; };
  }, []);
  if (!plan) return null;
  const days = daysLeft(plan.trialEndsAt);
  const items = [
    plan.wouldPauseParents > 0 ? t("pauseItemParents", { count: plan.wouldPauseParents }) : "",
    plan.wouldPauseMedicines > 0 ? t("pauseItemMedicines", { count: plan.wouldPauseMedicines }) : "",
  ].filter(Boolean).join(` ${t("and")} `);
  const soon = items ? t("bannerSoonPause", { days, items }) : days <= 1 ? t("bannerSoonOne") : t("bannerSoon", { days });
  const text = plan.onTrial ? (days <= 7 ? soon : null) : plan.plan === "free" && !(pauses && (pauses.parentIds.length > 0 || pauses.medicines.length > 0)) ? t("bannerEnded") : null;   // the red warning already says it
  if (!text) return null;
  return <button className="trial-banner" onClick={() => go("plans")}>{text}</button>;
}

function ConsentBanner({ recipient, call }: { recipient: Recipient; call: string }) {
  const t = useTranslations("today");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  // When the server says to wait, the button counts down to the moment it may be pressed again.
  const [waitUntil, setWaitUntil] = useState<number | null>(null);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (waitUntil === null) return;
    const id = window.setInterval(() => {
      setNow(Date.now());
      if (Date.now() >= waitUntil) { setWaitUntil(null); setNote(""); }
    }, 1000);
    return () => window.clearInterval(id);
  }, [waitUntil]);
  if (recipient.consent_at && !recipient.opted_out_at) return null;
  const stopped = !!recipient.opted_out_at;
  // The agreement belongs to whoever gets the reminders: the person, me, or a helper / another family member (docs/reminder-receiver.md).
  const rcv = receiverOf(recipient);
  const toMe = !recipient.is_self && rcv.reminder_to === "caregiver" && rcv.reminder_user_id === authUserId();
  const toOther = !recipient.is_self && rcv.reminder_to !== "person" && !toMe;
  const who = toOther ? receiverName(rcv) || t("receiverSomeone") : call;
  const title = recipient.is_self ? t("consentPendingTitleSelf") : toMe ? t("consentPendingTitleMe") : toOther ? t("consentPendingTitleReceiver", { name: who }) : t("consentPendingTitle", { call });
  const body = stopped
    ? recipient.is_self ? t("consentStoppedSelf") : toMe ? t("consentStoppedMe", { call }) : toOther ? t("consentStoppedReceiver", { name: who, call }) : t("consentStopped", { call })
    : recipient.is_self ? t("consentPendingSelf") : toMe ? t("consentPendingMe", { call }) : toOther ? t("consentPendingReceiver", { name: who, call }) : t("consentPending", { call });
  const secondsLeft = waitUntil === null ? 0 : Math.max(0, Math.ceil((waitUntil - now) / 1000));
  const clock = `${Math.floor(secondsLeft / 60)}:${String(secondsLeft % 60).padStart(2, "0")}`;
  const resend = async () => {
    setBusy(true);
    const { result, retryAfter } = await requestConsent(recipient.id);
    setBusy(false);
    if (result === "too_soon" && retryAfter) {
      setNow(Date.now());
      setWaitUntil(Date.now() + retryAfter * 1000);
      setNote("");
      return;
    }
    if (result === "sent") {
      // The server allows the next message after its wait window; the first refusal tells us the exact time.
      setNote(t("consentSent", { call: toMe ? t("consentYourWhatsApp") : who }));
      return;
    }
    setNote(t(result === "too_soon" ? "consentTooSoon" : "consentFailed", { call }));
  };
  return (
    <section className={`consent-card${stopped ? " stopped" : ""}`} role="status">
      <strong>{stopped ? t("consentStoppedTitle") : title}</strong>
      <p>{body}</p>
      {!stopped && <button onClick={resend} disabled={busy || secondsLeft > 0}>{t("consentResend")}</button>}
      {secondsLeft > 0 && <p className="consent-note" aria-live="off">{t("consentWait", { time: clock })}</p>}
      {note && secondsLeft === 0 && <p className="consent-note">{note}</p>}
    </section>
  );
}

/** WhatsApp alerts to the caregiver: off -> waiting for the number to tap Yes -> on. `compact` hides itself once on. */
function AlertsCard({ compact }: { compact?: boolean }) {
  const t = useTranslations("alerts");
  const toast = useToast();
  const [state, setState] = useState<AlertsState | null>(null);
  const [open, setOpen] = useState(false);
  const [hiddenTick, setHiddenTick] = useState(0);
  const hidden = hiddenTick >= 0 && !!state && alertsHidden(state.userId);
  const refresh = async () => { try { setState(await loadMyAlerts()); } catch { /* keep what we had */ } };
  useEffect(() => { void refresh(); }, []);
  useEffect(() => {
    if (!state?.waiting) return;
    const id = window.setInterval(refresh, 15000);
    return () => window.clearInterval(id);
  }, [state?.waiting]);
  if (!state || (compact && (state.on || (hidden && !state.waiting)))) return null;
  const off = async () => { if (await turnOffAlerts()) { toast(t("turnedOff")); void refresh(); } else toast(t("failed")); };
  const shown = state.phone ? plusPhone(state.phone) : "";
  const notNow = () => { hideAlerts(state.userId); setHiddenTick((n) => n + 1); };
  if (compact) {
    return (
      <>
        <section className="alerts-slim" role="status">
          <Icon name="bell" size={18} />
          <span>{state.waiting ? t("waitingTitle") : t("slimTitle")}</span>
          <button className="slim-action" onClick={() => setOpen(true)}>{state.waiting ? t("resend") : t("turnOn")}</button>
          {!state.waiting && <button className="slim-x" aria-label={t("notNow")} onClick={notNow}>×</button>}
        </section>
        {open && <AlertsSheet initial={state.phone ?? state.loginPhone ?? ""} onClose={() => setOpen(false)} onDone={() => { setOpen(false); void refresh(); }} />}
      </>
    );
  }
  return (
    <>
      <section className={`alerts-card${state.on ? " on" : ""}`} role="status">
        <div>
          <strong>{state.on ? t("onTitle") : state.waiting ? t("waitingTitle") : t("offTitle")}</strong>
          <p>{state.on ? t("onBody", { phone: shown }) : state.waiting ? t("waitingBody", { phone: shown }) : t("offBody")}</p>
        </div>
        {state.on ? <button onClick={off}>{t("turnOff")}</button> : <button onClick={() => setOpen(true)}>{state.waiting ? t("resend") : t("turnOn")}</button>}
      </section>
      {open && <AlertsSheet initial={state.phone ?? state.loginPhone ?? ""} onClose={() => setOpen(false)} onDone={() => { setOpen(false); void refresh(); }} />}
    </>
  );
}

function AlertsSheet({ initial, onClose, onDone }: { initial: string; onClose: () => void; onDone: () => void }) {
  const t = useTranslations("alerts");
  const toast = useToast();
  const dialog = useDialog(onClose);
  const [value, setValue] = useState(initial ? plusPhone(initial) : "");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const phone = normalizePhone(value);
    if (!phone) { setError(t("badPhone")); return; }
    setError(""); setBusy(true);
    const result = await requestAlerts(phone);
    setBusy(false);
    if (result === "sent") { toast(t("sent")); onDone(); }
    else setError(t(result === "opted_out" ? "optedOut" : result === "too_soon" ? "tooSoon" : result === "too_many" ? "tooMany" : "failed"));
  };
  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <section className="sheet" ref={dialog} tabIndex={-1} role="dialog" aria-modal="true" aria-label={t("sheetTitle")} onClick={(event) => event.stopPropagation()}>
        <form onSubmit={submit} noValidate>
        <h3>{t("sheetTitle")}</h3>
        <p className="sheet-hint">{t("sheetBody")}</p>
        <Field id="a-phone" label={t("phoneLabel")} error={error} hint={t("phoneHint")}>
          <input id="a-phone" name="phone" type="tel" inputMode="tel" autoComplete="tel" value={value} onChange={(event) => setValue(event.target.value)} aria-invalid={!!error} aria-describedby={error ? "a-phone-error" : undefined} />
        </Field>
        <div className="stack">
          <button className="primary-button" type="submit" disabled={busy}>{t("send")}</button>
          <button className="secondary-button" type="button" onClick={onClose}>{t("cancel")}</button>
        </div>
        </form>
      </section>
    </div>
  );
}

/** How long a due reminder may wait before the family is told that reminders are delayed. */
const DELAYED_AFTER_MS = 20 * 60_000;

function Today({ go, language, setLanguage, recipient, recipients, notifications, medicineNames, medicines, onNotificationsSeen, onDeleteNotification, onSelectParent }: { go: (screen: Screen) => void; language: Language; setLanguage: (language: Language) => void; recipient: Recipient | null; recipients: Recipient[]; notifications: AppNotification[]; medicineNames: Record<string, string>; medicines: Medicine[]; onNotificationsSeen: (ids: string[]) => void; onDeleteNotification: (id: string) => void; onSelectParent: (id: string) => void }) {
  const t = useTranslations();
  const header = parentGreeting(recipient, language, t);
  const { call } = header;
  const tz = recipient?.timezone ?? "Asia/Kolkata";
  const [sheetOpen, setSheetOpen] = useState(false);
  const [parentsOpen, setParentsOpen] = useState(false);
  // The loaded data is tagged with the parent it belongs to, so after switching parent the old one's doses are never shown.
  const [loaded, setLoaded] = useState<{ id: string; doses: TodayDose[]; checkin: TodayCheckin; at: number; age: number | null } | null>(null);
  const [errorFor, setErrorFor] = useState<string | null>(null);
  const mine = loaded && loaded.id === recipient?.id ? loaded : null;
  const doses = mine ? mine.doses : null;
  const checkin: TodayCheckin | undefined = mine ? mine.checkin : undefined;
  const dataError = !!recipient && errorFor === recipient.id && !mine;
  const unread = notifications.filter((n) => !n.read_at).length;
  const [layout, saveLayout] = useTodayLayout();
  const [styleOpen, setStyleOpen] = useState(false);
  const styleToast = useToast();

  // Today's doses and the check-in come from the database (written by the WhatsApp replies and the scheduler).
  useEffect(() => {
    if (!recipient) return;
    let cancelled = false;
    const load = async () => {
      try {
        const [d, c, age] = await Promise.all([loadTodayDoses(recipient), loadCheckin(recipient), loadSchedulerAge().catch(() => null)]);
        if (!cancelled) { setLoaded({ id: recipient.id, doses: d, checkin: c, at: Date.now(), age }); setErrorFor(null); }
      } catch {
        if (!cancelled) setErrorFor(recipient.id);
      }
    };
    void load();
    const id = window.setInterval(load, 30000);
    // Phone browsers pause timers while the app is in the background (e.g. while on WhatsApp): refresh on return.
    const onReturn = () => { if (document.visibilityState === "visible") void load(); };
    document.addEventListener("visibilitychange", onReturn);
    window.addEventListener("focus", onReturn);
    window.addEventListener("online", onReturn);
    return () => {
      cancelled = true;
      window.clearInterval(id);
      document.removeEventListener("visibilitychange", onReturn);
      window.removeEventListener("focus", onReturn);
      window.removeEventListener("online", onReturn);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [recipient?.id, recipient?.timezone]);

  // The "Call" buttons dial the person: when the reminders go to a helper or the family, whatsapp_phone is not the person's number.
  const phone = receiverOf(recipient).reminder_to === "person" ? recipient?.whatsapp_phone ?? null : null;
  const callParent = () => { if (phone) window.location.href = `tel:${phone}`; };
  const time = (iso: string | null) => (iso ? formatClock(new Date(iso), tz, language) : "");
  const consented = !!recipient?.consent_at && !recipient.opted_out_at;
  // A paused medicine's doses that were already created are not shown as coming up (the scheduler removes them).
  const visibleDoses = (doses ?? []).filter((d) => d.active || d.status !== "pending");
  const missed = visibleDoses.filter((d) => d.status === "missed");
  // A working reminder system sends a due reminder within minutes. A dose that is still "pending" long after its time means reminders are NOT being sent
  // (the scheduler is down): say so, instead of showing it as "next" and letting the family believe the parent was reminded.
  const stuck = !mine || !consented || recipient?.is_self ? [] : visibleDoses.filter((d) => d.status === "pending" && mine.at - new Date(d.scheduledAt).getTime() > DELAYED_AFTER_MS);
  const stuckNames = [...new Set(stuck.map((d) => d.name))];
  // The scheduler's own heartbeat (no heartbeat ever recorded = nothing to compare with = no alarm): if it last ran long ago, reminders and tomorrow's doses are not being made.
  const schedulerAge = mine && consented && !recipient?.is_self ? mine.age : null;
  const schedulerStale = schedulerAge !== null && schedulerAge > SCHEDULER_STALE_SECONDS;
  const ago = (seconds: number) => seconds < 5400 ? t("today.agoMinutes", { count: Math.max(1, Math.round(seconds / 60)) }) : seconds < 129600 ? t("today.agoHours", { count: Math.round(seconds / 3600) }) : t("today.agoDays", { count: Math.round(seconds / 86400) });
  const stuckShown = stuckNames.slice(0, 5).join(", ") + (stuckNames.length > 5 ? ` +${stuckNames.length - 5}` : "");
  const lastMissed = missed[missed.length - 1];

  const checkinCard = () => {
    if (!consented || recipient?.is_self) return null;   // no "are you okay" check-in for yourself
    if (receiverOf(recipient).reminder_to !== "person") return null;   // nor when a helper / the family gets the reminders (ReceiverNote says so)
    if (checkin?.status === "missed") {
      return (
        <section className="alert-card">
          <div className="alert-icon">!</div>
          <div><strong>{t("today.checkinMissedText", { call })}</strong>{checkin.sent_at && <p>{t("today.checkinSentAt", { time: time(checkin.sent_at) })}</p>}</div>
          {phone && <button onClick={callParent}>{t("today.callButton")}</button>}
        </section>
      );
    }
    const state = checkin?.status === "replied" ? "replied" : checkin?.status === "sent" ? "sent" : "none";
    return (
      <button className={`checkin-line ${state}`} onClick={() => go("whatsapp")} aria-label={t("today.seeMessages")}>
        <span className="okay-icon" aria-hidden="true">{state === "replied" ? "✓" : "…"}</span>
        <span className="checkin-text">
          <strong>{t(state === "replied" ? "today.checkinOkay" : state === "sent" ? "today.checkinWaiting" : "today.checkinNone", { call })}</strong>
          <small>{state === "replied" ? t("today.checkinReplied", { time: time(checkin?.responded_at ?? checkin?.sent_at ?? null) }) : state === "sent" ? (checkin?.sent_at ? t("today.checkinSentAt", { time: time(checkin.sent_at) }) : "") : t("today.checkinNoneHint")}</small>
        </span>
        <Icon name="chevron" size={18} />
      </button>
    );
  };

  const routeStop = (d: TodayDose): RouteStop => {
    const parts = clockParts(new Date(d.scheduledAt), tz, language);
    const future = new Date(d.scheduledAt).getTime() > Date.now();
    const pill =
      d.status === "taken" ? (["taken", "today.taken"] as const)
      : d.status === "taken_late" ? (["taken", "today.takenLate"] as const)
      : d.status === "missed" ? (["missed", "today.missed"] as const)
      : d.status === "skipped" ? (["notsent", "today.notSent"] as const)
      : d.status === "sent" ? (["pending", "today.pending"] as const)
      : future ? (["upcoming", "today.upcoming"] as const) : (["pending", "today.due"] as const);
    return {
      id: d.id, at: new Date(d.scheduledAt).getTime(), tod: localMinutes(new Date(d.scheduledAt), tz), time: parts.time, suffix: parts.suffix, name: d.name, dose: d.dosage ? localizeDose(d.dosage, t) : "",
      state: d.status === "taken" || d.status === "taken_late" ? "done" : d.status === "missed" ? "missed" : d.status === "sent" ? "current" : d.status === "skipped" ? "skipped" : "todo",
      icon: d.status === "taken" || d.status === "taken_late" ? "✓" : d.status === "missed" ? "!" : d.status === "sent" ? <Icon name="pill" size={16} /> : <Icon name="pill" size={15} />,
      pill: <StatusPill type={pill[0] as "taken"}>{t(pill[1])}</StatusPill>,
    };
  };

  return (
    <main className="scroll-page">
      <h1 className="sr-only">{t("nav.today")}</h1>
      {recipient && isChild(recipient) ? <ChildHome recipient={recipient} go={go} unread={unread} onLanguage={() => setLanguage(language === "en" ? "hi" : "en")} onBell={() => setSheetOpen(true)} onPeople={() => setParentsOpen(true)} /> : (
      <header className="home-header">
        <button className="parent-profile" aria-haspopup="dialog" onClick={() => setParentsOpen(true)}>
          <span className="initials-avatar" aria-hidden="true">{([...(recipient?.name ?? "P")][0] ?? "P").toUpperCase()}</span>
          <span><small>{recipient && isPregnancy(recipient) ? t("pregnancyHome.label") : header.hello}</small><strong>{header.call} <Icon name="down" size={16} /></strong><em>{header.where}</em></span>
        </button>
        <div className="header-actions">
          <button className="language-button" aria-label={t("common.switchLanguageLabel")} onClick={() => setLanguage(language === "en" ? "hi" : "en")}>{t("common.switchLanguage")}</button>
          <button className="icon-button notification" aria-label={t("common.notifications")} onClick={() => setSheetOpen(true)}><Icon name="bell" />{unread > 0 && <i />}</button>
        </div>
      </header>)}

      <TrialBanner go={go} />
      {recipient && <PauseWarning recipient={recipient} call={call} go={go} />}
      {schedulerStale && schedulerAge !== null && (
        <section className="alert-card" role="alert">
          <div className="alert-icon">!</div>
          <div><strong>{t("today.staleTitle")}</strong><p>{t("today.staleBody", { call, ago: ago(schedulerAge) })}</p></div>
          {phone && <button onClick={callParent}>{t("today.callButton")}</button>}
        </section>
      )}
      {!schedulerStale && stuck.length > 0 && (
        <section className="alert-card" role="alert">
          <div className="alert-icon">!</div>
          <div><strong>{t("today.delayedTitle")}</strong><p>{t("today.delayedBody", { call, names: stuckShown })}</p></div>
          {phone && <button onClick={callParent}>{t("today.callButton")}</button>}
        </section>
      )}
      <InstallBanner />
      {recipient && <EscalationProgress recipient={recipient} call={call} />}
      {recipient && <ConsentBanner recipient={recipient} call={call} />}
      {!isChild(recipient) && <ReceiverNote recipient={recipient} userId={authUserId()} call={call} />}
      {styleOpen && <LayoutSheet current={layout} onClose={() => setStyleOpen(false)} onSave={async (next) => { setStyleOpen(false); const ok = await saveLayout(next); styleToast(t(ok ? "layoutPicker.saved" : "layoutPicker.failed")); }} />}
      {sheetOpen && <NotificationSheet notifications={notifications} medicineNames={medicineNames} callFor={(rid) => { const found = rid ? recipients.find((r) => r.id === rid) : recipient; return found ? parentCall(found, language) : rid ? t("notif.unknownParent") : parentCall(recipient, language); }} onDelete={onDeleteNotification} onClose={() => { setSheetOpen(false); onNotificationsSeen(notifications.map((n) => n.id)); }} />}
      {parentsOpen && <ParentSheet recipients={recipients} selectedId={recipient?.id ?? null} language={language} onSelect={(id) => { onSelectParent(id); setParentsOpen(false); }} onEdit={() => { setParentsOpen(false); go("profile"); }} onAdd={() => { setParentsOpen(false); go("addParent"); }} onAddSelf={() => { setParentsOpen(false); go("addSelf"); }} onClose={() => setParentsOpen(false)} />}

      {lastMissed && !recipient?.is_self && (
        <section className="alert-card">
          <div className="alert-icon">!</div>
          <div><strong>{missed.length > 1 ? t("today.doseMissedMany", { count: missed.length, call }) : t("today.doseMissedText", { name: lastMissed.name, time: time(lastMissed.scheduledAt), call })}</strong></div>
          {phone && <button onClick={callParent}>{t("today.callButton")}</button>}
        </section>
      )}

      {checkinCard()}

      {/* A pregnancy gets her own home (feature 7); everyone else keeps the Today below unchanged. */}
      {recipient && isPregnancy(recipient) ? <PregnancyHome recipient={recipient} language={language} doses={doses === null ? null : visibleDoses} dosesError={dataError} medicineCount={medicines.length} go={go} alerts={<AlertsCard compact />} /> : <>

      {recipient && <LoveCard recipient={recipient} call={call} />}

      {recipient && <NextAppointment recipient={recipient} go={() => go("appointments")} />}
      {!isChild(recipient) && <NextVaccine go={go} recipient={recipient} />}

      <AlertsCard compact />
      <SeeAllActivity go={go} />

      <section className="section">
        <div className="section-title"><div><h2>{t("today.medicinesTitle")}</h2>{visibleDoses.length > 0 && <p>{t("today.progress", { done: visibleDoses.filter((d) => d.status === "taken" || d.status === "taken_late").length, total: visibleDoses.length })}</p>}</div><button onClick={() => go("medicines")}>{t("today.viewAll")}</button></div>
        {visibleDoses.length > 0 && <div className="dose-progress" role="progressbar" aria-valuemin={0} aria-valuemax={visibleDoses.length} aria-valuenow={visibleDoses.filter((d) => d.status === "taken" || d.status === "taken_late").length} aria-label={t("today.medicinesTitle")}><i style={{ width: `${(visibleDoses.filter((d) => d.status === "taken" || d.status === "taken_late").length / visibleDoses.length) * 100}%` }} /></div>}
        {dataError && <p role="alert" className="form-error">{t("today.loadFailed")}</p>}
        {doses === null && !dataError && <div className="skeleton card" aria-busy="true" />}
        {doses !== null && visibleDoses.length > 0 && (
          <div className="layouts-wrap">
            <button className="style-badge" onClick={() => setStyleOpen(true)} aria-label={t("layoutPicker.title")}><Icon name="sliders" size={17} /></button>
            <DoseLayouts layout={layout} stops={visibleDoses.map(routeStop)} avatar={([...(recipient?.name ?? "P")][0] ?? "P").toUpperCase()} startLabel={t("today.routeStart")} endLabel={t("today.routeEnd")} nextLabel={t("today.routeNext")} />
          </div>
        )}
        {doses !== null && visibleDoses.length === 0 && (
          <div className="empty-note">
            <strong>{medicines.length === 0 ? t("today.noMedicinesTitle") : t("today.dosesEmpty")}</strong>
            {medicines.length === 0 && <p>{t("today.noMedicinesBody")}</p>}
            {medicines.length === 0 && <button className="primary-button" onClick={() => go("addMedicine")}>{t("medicines.add")}</button>}
          </div>
        )}
      </section>
      </>}
    </main>
  );
}

function Medicines({ go, medicines, loading, error, onOpen }: { go: (screen: Screen) => void; medicines: Medicine[]; loading: boolean; error: boolean; onOpen: (id: string) => void }) {
  const t = useTranslations();
  const pausedByPlan = new Set((usePlanPauses()?.medicines ?? []).map((m) => m.id));
  const lookText = usePillLookText();
  const language = useLocale() as Language;
  const active = medicines.filter((medicine) => medicine.active);
  const outCount = active.filter((m) => m.stock === 0).length;
  const lowCount = active.filter((m) => isLowStock(m) && m.stock > 0).length;
  return (
    <main className="scroll-page">
      <AppHeader title={t("medicines.title")} subtitle={t("medicines.subtitle", { count: active.length })} />
      <div className="stock-summary"><Icon name="pill" /><div>
        <strong>{outCount ? t("medicines.summaryOut") : lowCount ? t("medicines.summaryLow") : active.length ? t("medicines.summaryTracked") : medicines.length ? t("medicines.summaryPaused") : t("medicines.summaryNone")}</strong>
        <p>{outCount || lowCount ? [outCount ? t("medicines.outCount", { count: outCount }) : "", lowCount ? t("medicines.refillNeeded", { count: lowCount }) : ""].filter(Boolean).join(" • ") : active.length ? t("medicines.stockGood") : medicines.length ? t("medicines.summaryPausedHint") : t("medicines.addFirst")}</p>
      </div></div>
      {error && <p role="alert" className="form-error">{t("medicines.loadFailed")}</p>}
      {loading && !medicines.length && <p className="form-note">{t("common.loading")}</p>}
      <section className="medicine-list">
        {medicines.map((medicine, index) => {
          const low = isLowStock(medicine);
          const color = low ? "orange" : index % 2 ? "blue" : "green";
          return (
          <article className={`med-card${medicine.active ? "" : " paused"}`} key={medicine.id} {...tappable(() => onOpen(medicine.id))} aria-label={t("medicines.open", { name: medicine.name })}>
            <div className={`med-visual ${color}`}><span /><span /><span /></div>
            <div className="med-copy"><h3>{medicine.name}{!medicine.active && <span className="tag-low paused-tag">{t("medicines.paused")}</span>}{medicine.active && pausedByPlan.has(medicine.id) && <span className="tag-low paused-tag">{t("medicines.pausedByPlan")}</span>}{medicine.critical && <span className="tag-critical">{t("medicines.critical")}</span>}</h3><p>{[medicine.dose && localizeDose(medicine.dose, t), lookText(medicine.pillColour, medicine.pillShape), describeTimes(medicine.times, t, language)].filter(Boolean).join(" • ")}</p><small className={low || medicine.stock === 0 ? "low-stock" : ""}>{medicine.stock === 0 ?t("medicines.outOfStock") : t(low ? "medicines.stockLeft" : "medicines.stock", { count: medicine.stock })}</small></div>
            <span className="chevron-hint" aria-hidden="true"><Icon name="chevron" size={19} /></span>
          </article>
        )})}
      </section>
      <button className="fab" onClick={() => go("addMedicine")}><Icon name="plus" /><span>{t("medicines.add")}</span></button>
    </main>
  );
}

// The generated RPC types mark optional arguments as required; SQL accepts NULL for them.
const orNull = <T,>(value: T | null | undefined) => (value ?? null) as T;

const DOSE_UNITS = ["mg", "mcg", "g", "ml", "tablet", "capsule", "drops", "units", "puffs", "tsp"] as const;
const COUNTABLE_UNITS = new Set(["tablet", "capsule"]);

/** 500 + "mg" -> "500 mg"; 2 + "tablet" -> "2 tablets"; 0.5 + "tablet" -> "0.5 tablet". Stored as canonical English. */
function formatDose(amount: number, unit: string) {
  const label = COUNTABLE_UNITS.has(unit) && amount > 1 ? `${unit}s` : unit;
  return `${Number.isInteger(amount) ? amount : Number(amount.toFixed(2))} ${label}`;
}

const INSTRUCTION_OPTIONS = [["", "noInstructions"], ["after_food", "afterFood"], ["before_food", "beforeFood"], ["with_food", "withFood"]] as const;
const DECIMAL_RE = /^\d{1,6}([.,]\d{1,2})?$/;
const INTEGER_RE = /^\d{1,6}$/;
const MAX_TIMES = 8;   // reminder times for one medicine

/** "500 mg" -> 500 + "mg"; "2 tablets" -> 2 + "tablet". Doses are always stored by formatDose(), so this reverses it. */
function parseDose(dose: string): { amount: string; unit: string; unreadable: boolean } {
  const text = dose.trim();
  const match = text.match(/^(\d{1,6}(?:[.,]\d{1,2})?)\s*([a-z]+)$/i);  // "500 mg" and legacy "500mg"
  const unit = match?.[2].toLowerCase().replace(/^(tablet|capsule)s$/, "$1") ?? "";
  if (!match || !(DOSE_UNITS as readonly string[]).includes(unit)) return { amount: "", unit: "mg", unreadable: !!text };
  return { amount: match[1], unit, unreadable: false };
}
const hhmm = (time: string) => time.slice(0, 5);
/** The times already used by this parent's medicines (most used first, at most 4), so the same time is one tap and medicines due together share one WhatsApp message. */
const usualTimesOf = (medicines: Medicine[]): string[] => {
  const count = new Map<string, number>();
  for (const m of medicines) for (const time of m.times) count.set(hhmm(time), (count.get(hhmm(time)) ?? 0) + 1);
  return [...count.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])).slice(0, 4).map(([time]) => time).sort();
};

/** Shown instead of the edit form when the medicine was deleted meanwhile: say so and go back to the list. */
function GoneRedirect({ onGone }: { onGone: () => void }) {
  const done = useRef(false);
  useEffect(() => { if (!done.current) { done.current = true; onGone(); } }, [onGone]);
  return null;
}

/** The add form, also used to edit a medicine that already exists (`medicine` given). */
function AddMedicine({ go, recipient, medicine, usualTimes = [], onSaved, onMissing = () => {} }: { go: (screen: Screen) => void; recipient: Recipient; medicine?: Medicine; usualTimes?: string[]; onSaved: () => Promise<void>; onMissing?: () => void }) {
  const editing = !!medicine;
  const initialTimes = (medicine?.times ?? []).map(hhmm);
  const initialDose = parseDose(medicine?.dose ?? "");
  const t = useTranslations("add");
  const tAll = useTranslations();
  const language = useLocale() as Language;
  // Every person takes their medicine at their own times: no fixed "morning" or "night", just the times typed (any number, up to MAX_TIMES).
  const [times, setTimes] = useState<string[]>(editing ? [...new Set(initialTimes)].sort() : [""]);
  const setTimeAt = (index: number, value: string) => setTimes((list) => list.map((time, i) => (i === index ? value : time)));
  const [photo, setPhoto] = useState<File | null>(null);
  const [refill, setRefill] = useState(medicine ? medicine.threshold !== null : true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [doseUnit, setDoseUnit] = useState<string>(initialDose.unit);
  const [pillColour, setPillColour] = useState<PillColour | null>(medicine?.pillColour ?? null);
  const [pillShape, setPillShape] = useState<PillShape | null>(medicine?.pillShape ?? null);
  const nameInput = useRef<HTMLInputElement>(null);
  // Pregnancy: a supplement chip fills ONLY the name; the dose and times are typed from the prescription.
  const pickSupplement = (name: string) => {
    if (!nameInput.current) return;
    nameInput.current.value = name;
    document.getElementById("f-dose")?.focus();
  };
  const expireSession = useSessionExpired();
  const [photoError, setPhotoError] = useState(false);
  /** Only a readable, non-empty image is accepted; anything else shows the photo error right away. */
  const pickPhoto = async (event: ChangeEvent<HTMLInputElement>) => {
    const input = event.target;
    const file = input.files?.[0] ?? null;
    if (!file) return;  // picker cancelled: keep what we had
    let ok = file.size > 0 && file.type.startsWith("image/");
    if (ok) {
      try { (await createImageBitmap(file)).close(); } catch { ok = false; }
    }
    setPhotoError(!ok);
    setPhoto(ok ? file : null);
    if (!ok) input.value = "";
  };
  const toggleUsual = (time: string) => setTimes((list) => {
    if (list.includes(time)) return list.length > 1 ? list.filter((x) => x !== time) : [""];
    const blank = list.indexOf("");
    return blank >= 0 ? list.map((x, i) => (i === blank ? time : x)) : list.length < MAX_TIMES ? [...list, time] : list;
  });
  const save = async (event: FormEvent<HTMLFormElement>) => {
    try {
      await saveMedicine(event);
    } catch {
      setError(tAll("common.unexpected"));  // never leave the button stuck on "Saving…"
      setBusy(false);
    }
  };
  const saveMedicine = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError("");
    const data = new FormData(event.currentTarget);
    const found: Record<string, string> = {};
    const name = String(data.get("name") ?? "").trim();
    if (!name) found["f-name"] = t("errName");
    const doseText = String(data.get("doseAmount") ?? "").trim();
    const keepOldDose = !!medicine && initialDose.unreadable && !doseText;  // old free-text dose we could not read: keep as is
    const doseAmount = Math.round(Number(doseText.replace(",", ".")) * 100) / 100;
    if (!keepOldDose && (!DECIMAL_RE.test(doseText) || !(doseAmount > 0) || doseAmount > MAX_NUMBER)) found["f-dose"] = t("errDose");
    const stockText = String(data.get("stock") ?? "").trim();
    const stock = Number(stockText);
    if (!editing && (!INTEGER_RE.test(stockText) || stock > MAX_NUMBER)) found["f-stock"] = t("errStock");
    const remindText = String(data.get("remind") ?? "").trim();
    const threshold = Number(remindText);
    if (refill && (!INTEGER_RE.test(remindText) || threshold > MAX_NUMBER)) found["f-remind"] = t("errRemind");
    const chosen = [...new Set(times.filter(Boolean))].sort();   // a blank extra row is simply ignored
    if (!chosen.length) found["f-time-0"] = t("errTimes");
    setErrors(found);
    if (Object.keys(found).length) return focusFirstError(found);

    setBusy(true);
    let photoPath: string | undefined;
    if (photo) {
      // crypto.randomUUID() does not exist on plain http:// pages (e.g. a phone opening the dev server by IP).
      const fileName = Array.from(crypto.getRandomValues(new Uint8Array(16)), (b) => b.toString(16).padStart(2, "0")).join("");
      photoPath = `${recipient.id}/${fileName}.jpg`;
      const shrunk = await compressImage(photo);
      const { error: uploadError } = await supabase.storage.from("medicine-photos").upload(photoPath, shrunk, { contentType: shrunk.type || "image/jpeg" });
      if (uploadError) { setBusy(false); return setError(t("errPhoto")); }
    }
    try {
      if (medicine) {
        await updateMedicineApi(medicine.id, {
          name,
          dosage: keepOldDose ? medicine.dose : formatDose(doseAmount, doseUnit),
          instructions: orNull(String(data.get("instructions") ?? "") || null),
          photoPath: orNull(photoPath),
          refillThreshold: orNull(refill ? threshold : null),
          times: chosen,
          // "" clears; the backend keeps the saved value only when the field is left out (older app versions).
          pillColour: pillColour ?? "",
          pillShape: pillShape ?? "",
        });
      } else {
        await createMedicineApi(recipient.id, {
          name,
          dosage: formatDose(doseAmount, doseUnit),
          instructions: orNull(String(data.get("instructions") ?? "") || null),
          photoPath: orNull(photoPath),
          stock,
          refillThreshold: orNull(refill ? threshold : null),
          times: chosen,
          pillColour,
          pillShape,
        });
      }
    } catch (e) {
      if (photoPath) await supabase.storage.from("medicine-photos").remove([photoPath]);
      setBusy(false);
      const err = e as ApiError;
      if (medicine && err.status === 404) return onMissing();
      if (await sessionIsGone(err)) return expireSession();
      return setError(friendlyError(err, tAll));
    }
    if (photoPath && medicine?.photoUrl) await supabase.storage.from("medicine-photos").remove([medicine.photoUrl]);
    await onSaved();
    setBusy(false);
  };
  const clock = (time: string) => formatClock(new Date(`2000-01-01T${time}:00`), Intl.DateTimeFormat().resolvedOptions().timeZone, language);
  const removedTimes = initialTimes.some((time) => !times.includes(time));
  return (
    <main className="scroll-page no-nav">
      <AppHeader title={t(editing ? "editTitle" : "title")} back={() => go(editing ? "medicineDetail" : "medicines")} />
      <label className="photo-upload large-upload"><input type="file" accept="image/*" onChange={(event) => void pickPhoto(event)} /><span className="camera-disc"><Icon name="camera" size={30} /></span><span><strong>{photo?.name || t(medicine?.photoUrl ? "photoKept" : "photoTitle")}</strong><small>{t("photoHint", { call: parentCall(recipient, language) })}</small></span><em>{photo || medicine?.photoUrl ? t("changePhoto") : t("openCamera")}</em></label>
      {photoError && <p role="alert" className="form-error">{t("errPhoto")}</p>}
      <form id="medicine-form" className="form-card" onSubmit={save} noValidate>
        {!editing && isPregnancy(recipient) && <SupplementChips personLanguage={recipient.language === "en" ? "en" : "hi"} onPick={pickSupplement} />}
        <Field id="f-name" label={t("name")} error={errors["f-name"]}><input {...fieldProps("f-name", errors)} ref={nameInput} name="name" autoComplete="off" maxLength={100} placeholder={t("namePlaceholder")} defaultValue={medicine?.name ?? ""} /></Field>
        <Field id="f-dose" label={t("dosage")} error={errors["f-dose"]}>
          <div className="two-col dose-row">
            <input {...fieldProps("f-dose", errors)} name="doseAmount" inputMode="decimal" defaultValue={initialDose.amount} placeholder={isPregnancy(recipient) ? undefined : COUNTABLE_UNITS.has(doseUnit) ? "1" : "500"} />
            <select aria-label={t("doseUnit")} value={doseUnit} onChange={(event) => setDoseUnit(event.target.value)}>
              {DOSE_UNITS.map((unit) => <option key={unit} value={unit}>{tAll(`units.${unit}`, { count: 1 })}</option>)}
            </select>
          </div>
          {editing && initialDose.unreadable && <small className="form-note">{t("doseUnreadable", { dose: medicine?.dose ?? "" })}</small>}
        </Field>
        <PillLookPicker colour={pillColour} shape={pillShape} call={parentCall(recipient, language)} onColour={setPillColour} onShape={setPillShape} />
        <div className="field" role="group" aria-labelledby="f-times-label">
          <p className="field-label" id="f-times-label">{t("when")}</p>
          {usualTimes.length > 0 && (
            <>
              <p className="hint">{t("usualTimes", { call: parentCall(recipient, language) })}</p>
              <div className="choice-chips wrap">{usualTimes.map((time) => <button type="button" key={time} className={times.includes(time) ? "selected" : ""} aria-pressed={times.includes(time)} onClick={() => toggleUsual(time)}>{clock(time)}</button>)}</div>
            </>
          )}
          {times.map((time, index) => (
            <div className="time-row" key={index}>
              <input {...(index === 0 ? fieldProps("f-time-0", errors) : { id: `f-time-${index}` })} type="time" value={time} aria-label={t("timeN", { n: index + 1 })} onChange={(event) => setTimeAt(index, event.target.value)} />
              {times.length > 1 && <button type="button" className="time-remove" aria-label={t("removeTime", { n: index + 1 })} onClick={() => setTimes((list) => list.filter((_, i) => i !== index))}>×</button>}
            </div>
          ))}
          {errors["f-time-0"] && !times.some(Boolean) && <p className="field-error" id="f-time-0-error" role="alert">{errors["f-time-0"]}</p>}
          {times.length < MAX_TIMES && <button type="button" className="secondary-button add-time" onClick={() => setTimes((list) => [...list, ""])}><Icon name="plus" size={16} /> {t("addTime")}</button>}
        </div>
        <Field id="f-instructions" label={t("instructions")}><select id="f-instructions" name="instructions" defaultValue={editing ? medicine?.instructions ?? "" : "after_food"}>{INSTRUCTION_OPTIONS.map(([value, key]) => <option key={value} value={value}>{t(key)}</option>)}</select></Field>
        <div className="two-col">
          {!editing && <Field id="f-stock" label={t("stockLabel")} error={errors["f-stock"]}><input {...fieldProps("f-stock", errors)} name="stock" placeholder="30" inputMode="numeric" /></Field>}
          <Field id="f-remind" label={t("remindAt")} error={errors["f-remind"]}><input {...fieldProps("f-remind", errors)} name="remind" inputMode="numeric" defaultValue={medicine?.threshold ?? 5} disabled={!refill} /></Field>
        </div>
        <label className="toggle-row" style={{ minHeight: 48 }}><span><strong>{t("refillTitle")}</strong><small>{t("refillHint")}</small></span><input type="checkbox" role="switch" checked={refill} onChange={(event) => setRefill(event.target.checked)} /><i className={refill ? "" : "off"} /></label>
        {editing && removedTimes && <p className="form-note" role="status">{t("timeRemoved")}</p>}
      </form>
      {error && <p role="alert" className="form-error">{error}</p>}
      <button className="primary-button sticky-action" type="submit" form="medicine-form" disabled={busy}>{busy ? t("saving") : t("save")}</button>
    </main>
  );
}

function DeleteSheet({ name, busy, onConfirm, onCancel }: { name: string; busy: boolean; onConfirm: () => void; onCancel: () => void }) {
  const t = useTranslations("medicine");
  const dialog = useDialog(onCancel);
  return (
    <div className="sheet-backdrop" onClick={onCancel}>
      <section className="sheet" ref={dialog} tabIndex={-1} role="alertdialog" aria-modal="true" aria-label={t("confirmTitle", { name })} onClick={(event) => event.stopPropagation()}>
        <h3>{t("confirmTitle", { name })}</h3>
        <p className="sheet-empty">{t("confirmBody")}</p>
        <div className="stack">
          <button className="danger-button" onClick={onConfirm} disabled={busy}>{t("confirmYes")}</button>
          <button className="secondary-button" onClick={onCancel}>{t("cancel")}</button>
        </div>
      </section>
    </div>
  );
}

function MedicineDetail({ go, medicine, onChanged }: { go: (screen: Screen, options?: { replace?: boolean }) => void; medicine: Medicine | undefined; onChanged: () => Promise<void> }) {
  const t = useTranslations();
  const language = useLocale() as Language;
  const toast = useToast();
  const expireSession = useSessionExpired();
  const [stock, setStock] = useState(medicine?.stock ?? 0);
  const [active, setActive] = useState(medicine?.active ?? true);
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [photoUrl, setPhotoUrl] = useState<string | null>(null);
  const lookText = usePillLookText();

  useEffect(() => { if (!medicine) go("medicines", { replace: true }); }, [medicine, go]);
  useEffect(() => { if (medicine) { setStock(medicine.stock); setActive(medicine.active); } }, [medicine?.stock, medicine?.active]);
  useEffect(() => {
    if (!medicine?.photoUrl) return;
    void supabase.storage.from("medicine-photos").createSignedUrl(medicine.photoUrl, 600).then(({ data }) => setPhotoUrl(data?.signedUrl ?? null));
  }, [medicine?.photoUrl]);
  if (!medicine) return null;

  const low = medicine.threshold !== null && stock <= medicine.threshold;
  const instructionKey = { after_food: "afterFood", before_food: "beforeFood", with_food: "withFood" }[medicine.instructions ?? ""];

  /** A write that changed nothing (the record is gone) or failed is never presented as success. */
  const failed = async (err?: { code?: string; message?: string; status?: number } | null, missing = false) => {
    if (!missing && await sessionIsGone(err)) return expireSession();
    toast(t(missing || isNotFound(err) || err?.status === 404 ? "medicine.gone" : "medicine.errSave"));
    void onChanged();  // refresh: if the medicine no longer exists we are taken back to the list
  };
  const changeStock = async (delta: number) => {
    if (busy || stock + delta < 0 || stock + delta > MAX_NUMBER) return;
    const previous = stock;
    setStock(previous + delta);  // instant feedback; undone if saving fails
    setBusy(true);
    // A delta, not an absolute number: a dose the parent confirmed a moment ago must not be overwritten.
    let data: number;
    try {
      data = await adjustStockApi(medicine.id, delta);
    } catch (e) {
      setBusy(false); setStock(previous); return failed(e as ApiError);
    }
    setBusy(false);
    setStock(data);
    // The database alerts the whole family (bell) when stock reaches the refill level and again when it runs out.
    // Say so here, because the bell is on another screen.
    if (medicine.threshold !== null) {
      if (data === 0 && previous > 0) toast(t("medicine.outAlertSent"));
      else if (data <= medicine.threshold && previous > medicine.threshold) toast(t("medicine.lowAlertSent"));
    }
    void onChanged();
  };
  const togglePause = async () => {
    setBusy(true);
    try {
      await setMedicineFlags(medicine.id, { active: !active });
    } catch (e) {
      setBusy(false); return failed(e as ApiError);
    }
    setBusy(false);
    setActive(!active);
    void onChanged();
  };
  const remove = async () => {
    setBusy(true);
    try {
      await deleteMedicineApi(medicine.id);
    } catch (e) {
      setBusy(false); setConfirming(false); return failed(e as ApiError);
    }
    if (medicine.photoUrl) await supabase.storage.from("medicine-photos").remove([medicine.photoUrl]);
    await onChanged();
    setBusy(false);
    go("medicines", { replace: true });
  };

  return (
    <main className="scroll-page no-nav">
      <AppHeader title={medicine.name} subtitle={t("medicine.title")} back={() => go("medicines")} />
      {photoUrl && <img className="photo-thumb" src={photoUrl} alt={medicine.name} />}
      {!active && <p className="consent-card stopped" role="status">{t("medicine.pausedNote")}</p>}
      <section className="form-card">
        <div className="meta-row"><span>{t("medicine.dose")}</span><strong>{localizeDose(medicine.dose, t) || "-"}</strong></div>
        <div className="meta-row"><span>{t("medicine.look")}</span><strong>{medicine.pillColour || medicine.pillShape ? <span className="pill-look-line"><PillGlyph colour={medicine.pillColour} shape={medicine.pillShape} />{lookText(medicine.pillColour, medicine.pillShape)}</span> : t("medicine.noLook")}</strong></div>
        <div className="meta-row"><span>{t("medicine.times")}</span><strong>{describeTimes(medicine.times, t, language)}</strong></div>
        <div className="meta-row"><span>{t("medicine.instructions")}</span><strong>{instructionKey ? t(`add.${instructionKey}`) : t("medicine.noInstructions")}</strong></div>
        <div className="meta-row"><span>{t("medicine.stock")}</span><strong>{stock === 0 ? <span className="tag-low">{t("medicine.outOfStock")}</span> : low && <span className="tag-low">{t("medicine.lowStock")}</span>}</strong></div>
        <div className="stepper" role="group" aria-label={t("medicine.stock")}>
          <button type="button" onClick={() => changeStock(-1)} disabled={busy || stock <= 0} aria-label={t("medicine.decrease")}>−</button>
          <StockField value={stock} max={MAX_NUMBER} disabled={busy} onCommit={(next) => changeStock(next - stock)} />
          <button type="button" onClick={() => changeStock(1)} disabled={busy || stock >= MAX_NUMBER} aria-label={t("medicine.increase")}>+</button>
        </div>
        <p className="form-note" style={{ textAlign: "center" }}>{medicine.threshold === null ? t("medicine.refillOff") : t("medicine.refillAt", { count: medicine.threshold })}</p>
      </section>
      <div className="stack" style={{ marginTop: 18 }}>
        <button className="primary-button" onClick={() => go("editMedicine")} disabled={busy}><Icon name="edit" size={18} /> {t("medicine.edit")}</button>
        <button className="secondary-button" onClick={togglePause} disabled={busy}>{active ? t("medicine.pause") : t("medicine.resume")}</button>
        <button className="danger-button" onClick={() => setConfirming(true)} disabled={busy}>{t("medicine.delete")}</button>
      </div>
      {confirming && <DeleteSheet name={medicine.name} busy={busy} onConfirm={remove} onCancel={() => setConfirming(false)} />}
    </main>
  );
}

const reportDate = (iso: string | null, language: Language) => (iso ? formatDate(new Date(`${iso.slice(0, 10)}T00:00:00`), language, { day: "2-digit", month: "short", year: "numeric" }) : "");

/** Report reading is built but switched off for v1 (AI cost + medical-safety review). Set VITE_REPORTS_ENABLED=true to turn it on. */
const REPORTS_ENABLED = import.meta.env.VITE_REPORTS_ENABLED === "true";

function ReportsSoon({ recipient }: { recipient: Recipient | null }) {
  const t = useTranslations("reports");
  const language = useLocale() as Language;
  return (
    <main className="scroll-page">
      <AppHeader title={t("title")} subtitle={t("subtitle")} />
      <section className="empty-note">
        <span className="camera-disc" aria-hidden="true"><Icon name="report" size={30} /></span>
        <strong>{t("soonTitle")}</strong>
        <p>{t("soonBody", { call: parentCall(recipient, language) })}</p>
        <p>{t("soonNote")}</p>
      </section>
    </main>
  );
}

function Reports(props: { recipient: Recipient | null; onOpen: (id: string) => void }) {
  return REPORTS_ENABLED ? <ReportsLive {...props} /> : <ReportsSoon recipient={props.recipient} />;
}

function ReportsLive({ recipient, onOpen }: { recipient: Recipient | null; onOpen: (id: string) => void }) {
  const t = useTranslations("reports");
  const language = useLocale() as Language;
  const toast = useToast();
  const expireSession = useSessionExpired();
  const [reports, setReports] = useState<ReportSummary[] | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [reading, setReading] = useState(false);
  const recipientId = recipient?.id;

  const refresh = async () => {
    if (!recipientId) return;
    try { setReports(await loadReports(recipientId)); setLoadError(false); } catch { setLoadError(true); }
  };
  useEffect(() => { setReports(null); void refresh(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [recipientId]);
  const working = (reports ?? []).some((r) => r.status === "pending" || r.status === "processing");
  useEffect(() => {
    if (!working) return;
    const id = window.setInterval(refresh, 4000);
    return () => window.clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [working, recipientId]);

  const upload = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file || !recipientId || reading) return;
    setReading(true);
    const result = await uploadReport(recipientId, file, language, (f) => compressImage(f, 2000, 0.85));
    setReading(false);
    if (result.ok) { await refresh(); return onOpen(result.id); }
    if (result.reason === "auth") return expireSession();
    toast(t(result.reason === "type" ? "errType" : result.reason === "size" ? "errSize" : result.reason === "ai_off" ? "errAiOff" : result.reason === "failed" ? "errRead" : "errUpload"));
    void refresh();
  };

  return (
    <main className="scroll-page">
      <AppHeader title={t("title")} subtitle={t("subtitle")} />
      <label className={`upload-card${reading ? " busy" : ""}`}><input type="file" accept="image/*,.pdf,application/pdf" disabled={reading} onChange={upload} /><span><Icon name="upload" /></span><div><strong>{reading ? t("reading") : t("uploadTitle")}</strong><p>{reading ? t("readingHint") : t("uploadHint")}</p></div><Icon name="chevron" size={19} /></label>
      <div className="section-title reports-title"><div><h2>{t("recent")}</h2><p>{t("sorted")}</p></div></div>
      {loadError && <p role="alert" className="form-error">{t("loadFailed")}</p>}
      {reports === null && !loadError && <div className="skeleton card" aria-busy="true" />}
      {reports !== null && reports.length === 0 && <div className="empty-note"><strong>{t("emptyTitle")}</strong><p>{t("emptyBody")}</p></div>}
      <section className="reports-list">
        {(reports ?? []).map((r) => (
          <button className="report-card" key={r.id} onClick={() => onOpen(r.id)}>
            <div className={`file-icon${r.fileUrl.endsWith(".pdf") ? "" : " image"}`}>{r.fileUrl.endsWith(".pdf") ? "PDF" : "IMG"}</div>
            <div>
              <h3>{r.type || t("untitled")}</h3>
              <p>{[reportDate(r.date ?? r.createdAt, language)].filter(Boolean).join(" • ")}</p>
              {r.status === "pending" || r.status === "processing" ? <span className="reading-dot">{t("readingShort")}</span>
                : r.status === "failed" ? <span className="attention-dot">{t("failedShort")}</span>
                : r.attention > 0 ? <span className="attention-dot">{t("needAttention", { count: r.attention })}</span>
                : <span className="normal-dot">{t("allInRange")}</span>}
            </div>
            <Icon name="chevron" size={19} />
          </button>
        ))}
      </section>
    </main>
  );
}

function TrendChart({ points }: { points: TrendPoint[] }) {
  const t = useTranslations("report");
  const language = useLocale() as Language;
  if (points.length < 2) return <p className="form-note">{t("trendOne")}</p>;
  const values = points.map((p) => p.value);
  const min = Math.min(...values), max = Math.max(...values);
  const span = max - min || 1;
  const x = (i: number) => 8 + (i * 284) / (points.length - 1);
  const y = (v: number) => 14 + (1 - (v - min) / span) * 76;
  const line = points.map((p, i) => `${i ? "L" : "M"}${x(i).toFixed(1)} ${y(p.value).toFixed(1)}`).join(" ");
  const label = (iso: string) => formatDate(new Date(`${iso}T00:00:00`), language, { day: "numeric", month: "short", year: "2-digit" });
  return (
    <div className="chart" role="img" aria-label={t("trendAria", { from: label(points[0].date), to: label(points[points.length - 1].date), first: values[0], last: values[values.length - 1] })}>
      <svg viewBox="0 0 300 110" preserveAspectRatio="none">
        <path className="chart-area" d={`${line} L${x(points.length - 1)} 110 L${x(0)} 110Z`} />
        <path className="chart-line" d={line} />
        <g>{points.map((p, i) => <circle key={`${p.date}-${i}`} cx={x(i)} cy={y(p.value)} r={i === points.length - 1 ? 5 : 4} />)}</g>
      </svg>
      <div className="chart-labels"><span>{label(points[0].date)}</span><span>{label(points[points.length - 1].date)}</span></div>
    </div>
  );
}

function ValueSheet({ item, onClose, onSaved }: { item: ReportValue; onClose: () => void; onSaved: () => void }) {
  const t = useTranslations("report");
  const toast = useToast();
  const dialog = useDialog(onClose);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const value = Number(String(data.get("value") ?? "").replace(",", "."));
    if (!String(data.get("value") ?? "").trim() || !Number.isFinite(value) || Math.abs(value) > 1e7) return setError(t("errValue"));
    setBusy(true);
    const ok = await correctReportValue(item.id, value, String(data.get("unit") ?? "").trim().slice(0, 20) || null);
    setBusy(false);
    if (!ok) return setError(t("errSave"));
    toast(t("saved"));
    onSaved();
  };
  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <section className="sheet" ref={dialog} tabIndex={-1} role="dialog" aria-modal="true" aria-label={t("editTitle", { name: item.name })} onClick={(event) => event.stopPropagation()}>
        <h3>{t("editTitle", { name: item.name })}</h3>
        <p className="sheet-hint">{t("editHint")}</p>
        <form onSubmit={save} noValidate>
          <div className="two-col">
            <Field id="v-value" label={t("valueLabel")} error={error}><input id="v-value" name="value" inputMode="decimal" defaultValue={item.value} aria-invalid={!!error} aria-describedby={error ? "v-value-error" : undefined} /></Field>
            <Field id="v-unit" label={t("unitLabel")}><input id="v-unit" name="unit" maxLength={20} defaultValue={item.unit ?? ""} /></Field>
          </div>
          <div className="stack">
            <button className="primary-button" type="submit" disabled={busy}>{t("saveValue")}</button>
            <button className="secondary-button" type="button" onClick={onClose}>{t("cancel")}</button>
          </div>
        </form>
      </section>
    </div>
  );
}

function ReportDetail({ go, recipient, reportId }: { go: (screen: Screen, options?: { replace?: boolean }) => void; recipient: Recipient | null; reportId: string | null }) {
  const t = useTranslations();
  const language = useLocale() as Language;
  const toast = useToast();
  const expireSession = useSessionExpired();
  const call = parentCall(recipient, language);
  const [report, setReport] = useState<ReportDetailData | null | undefined>(undefined);
  const [error, setError] = useState(false);
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [editing, setEditing] = useState<ReportValue | null>(null);
  const [trendName, setTrendName] = useState<string | null>(null);
  const [trend, setTrend] = useState<TrendPoint[] | null>(null);

  const load = async () => {
    if (!reportId) return setReport(null);
    try { setReport(await loadReport(reportId)); setError(false); } catch { setError(true); }
  };
  useEffect(() => { void load(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [reportId]);
  useEffect(() => { if (report === null) go("reports", { replace: true }); }, [report, go]);
  const working = report?.status === "pending" || report?.status === "processing";
  useEffect(() => {
    if (!working) return;
    const id = window.setInterval(load, 4000);
    return () => window.clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [working, reportId]);

  const items = report?.items ?? [];
  const names = [...new Set(items.map((i) => i.name))];
  const flagged = items.find((i) => i.flag && i.flag !== "normal");
  const activeTrend = trendName && names.includes(trendName) ? trendName : (flagged ?? items[0])?.name ?? null;
  useEffect(() => {
    if (!activeTrend || !recipient) return setTrend(null);
    let cancelled = false;
    setTrend(null);
    void loadTrend(recipient.id, activeTrend).then((p) => { if (!cancelled) setTrend(p); }).catch(() => { if (!cancelled) setTrend([]); });
    return () => { cancelled = true; };
  }, [activeTrend, recipient?.id, report?.items.length, items.map((i) => i.value).join(",")]);

  if (report === undefined) return <main className="scroll-page no-nav"><AppHeader title={t("reports.title")} back={() => go("reports")} />{error ? <p role="alert" className="form-error">{t("reports.loadFailed")}</p> : <div className="skeleton card" aria-busy="true" />}</main>;
  if (report === null) return null;

  const title = report.type || t("reports.untitled");
  const retry = async () => {
    setBusy(true);
    const result = await analyzeReport(report.id, language);
    setBusy(false);
    if (result === "auth") return expireSession();
    if (result === "ai_off") toast(t("reports.errAiOff"));
    else if (result === "failed") toast(t("reports.errRead"));
    void load();
  };
  const remove = async () => {
    setBusy(true);
    const ok = await deleteReport(report);
    setBusy(false);
    setConfirming(false);
    if (!ok) return toast(t("reports.errDelete"));
    go("reports", { replace: true });
  };
  const openFile = async () => {
    const url = await signedReportUrl(report.fileUrl);
    if (url) window.open(url, "_blank", "noopener"); else toast(t("report.errFile"));
  };
  const shown = (i: ReportValue) => `${Number.isInteger(i.value) ? i.value : Number(i.value.toFixed(3))}${i.unit ? ` ${i.unit}` : ""}`;
  const tag = (flag: ReportFlag | null) => (flag ? <em className={`tag ${flag}`}>{t(`report.${flag}`)}</em> : null);

  return (
    <main className="scroll-page no-nav">
      <AppHeader title={title} subtitle={t("report.subtitle", { date: reportDate(report.date ?? report.createdAt, language) })} back={() => go("reports")} />
      {working && <p className="consent-card" role="status">{t("report.workingBody")}</p>}
      {report.status === "failed" && (
        <section className="alert-card">
          <div className="alert-icon">!</div>
          <div><strong>{t("report.failedTitle")}</strong><p>{t("report.failedBody")}</p></div>
          <button onClick={retry} disabled={busy}>{t("report.retry")}</button>
        </section>
      )}
      {report.status === "done" && items.length === 0 && <p className="empty-note">{t("report.noValues")}</p>}
      {report.status === "done" && items.length > 0 && (
        <>
          <section className={`report-summary${report.attention ? "" : " calm"}`}>
            <span>{report.attention || "✓"}</span>
            <div><strong>{report.attention ? t("report.attentionTitle") : t("report.allGoodTitle")}</strong><p>{report.attention ? t("report.attentionBody", { call }) : t("report.allGoodBody", { call })}</p></div>
          </section>
          <section className="values-card">
            {items.map((i) => (
              <button type="button" className="value-row" key={i.id} onClick={() => setEditing(i)} aria-label={t("report.editValue", { name: i.name })}>
                <div><strong>{i.name}</strong>{i.range && <small>{t("report.normalRange", { range: i.range })}</small>}{i.verified && <small className="verified">{t("report.checked")}</small>}</div>
                <span><b>{shown(i)}</b>{tag(i.flag)}</span>
              </button>
            ))}
          </section>
          <p className="form-note">{t("report.tapToCorrect")}</p>
          {report.summary && <section className="explanation-card"><div className="bulb">i</div><div><strong>{t("report.meansTitle")}</strong><p className="summary-text">{report.summary}</p></div></section>}
          {names.length > 0 && (
            <section className="chart-card">
              <div className="section-title"><div><h2>{t("report.trendTitle")}</h2><p>{activeTrend}</p></div></div>
              {names.length > 1 && <div className="choice-chips wrap" role="group" aria-label={t("report.trendPick")}>{names.map((n) => <button type="button" key={n} className={n === activeTrend ? "selected" : ""} aria-pressed={n === activeTrend} onClick={() => setTrendName(n)}>{n}</button>)}</div>}
              {trend === null ? <div className="skeleton card" aria-busy="true" /> : <TrendChart points={trend} />}
            </section>
          )}
        </>
      )}
      <div className="disclaimer-card"><Icon name="shield" size={19} /><p><strong>{t("report.disclaimerTitle")}</strong><br />{t("report.disclaimerBody")}</p></div>
      <div className="stack" style={{ marginTop: 14 }}>
        {report.status === "done" && <button className="primary-button doctor-share" onClick={() => shareContent(title, t("report.shareText"), t)}><Icon name="share" size={19} /> {t("report.share")}</button>}
        <button className="secondary-button" onClick={openFile}>{t("report.viewOriginal")}</button>
        <button className="danger-button" onClick={() => setConfirming(true)} disabled={busy}>{t("report.delete")}</button>
      </div>
      {editing && <ValueSheet item={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); void load(); }} />}
      {confirming && <DeleteSheet name={title} busy={busy} onConfirm={remove} onCancel={() => setConfirming(false)} />}
    </main>
  );
}

const plusPhone = (phone: string) => (phone.startsWith("+") ? phone : `+${phone}`);

function Family({ go, members, userId, recipient, onSignOut, onMembersChanged, onLeft }: { go: (screen: Screen) => void; members: CircleMember[]; userId: string | null; recipient: Recipient | null; onSignOut: () => void; onMembersChanged: () => void; onLeft: () => void }) {
  const t = useTranslations("family");
  const td = useTranslations("doctorSummary");
  const ti = useTranslations("invites");
  const [isAdmin, setIsAdmin] = useState(false);
  useEffect(() => { let cancelled = false; loadIsAdmin().then((v) => { if (!cancelled) setIsAdmin(v); }).catch(() => {}); return () => { cancelled = true; }; }, []);
  const language = useLocale() as Language;
  const toast = useToast();
  const call = parentCall(recipient, language);
  const circleId = recipient?.circle_id ?? null;
  const [inviteOpen, setInviteOpen] = useState(false);
  const [invitesVersion, setInvitesVersion] = useState(0);
  const [confirming, setConfirming] = useState<{ kind: "remove"; member: CircleMember } | { kind: "leave" } | null>(null);
  const [actionBusy, setActionBusy] = useState(false);
  const me = members.find((m) => m.userId === userId);
  const confirmAction = async () => {
    if (!circleId || !confirming || actionBusy) return;
    setActionBusy(true);
    const ok = confirming.kind === "remove" ? await removeMember(circleId, confirming.member.userId) : await leaveCircle(circleId);
    setActionBusy(false);
    if (!ok) { toast(ti("errGeneric")); onMembersChanged(); return setConfirming(null); }
    toast(ti(confirming.kind === "remove" ? "removed" : "left"));
    setConfirming(null);
    if (confirming.kind === "leave") onLeft(); else onMembersChanged();
  };
  const name = (m: CircleMember) => m.name || m.email || (m.phone ? plusPhone(m.phone) : t("unnamed"));
  const initials = (m: CircleMember) => name(m).split(/\s+/).map((word) => [...word][0] ?? "").join("").slice(0, 2).toUpperCase();
  return (
    <main className="scroll-page">
      <AppHeader title={t("title")} subtitle={t("subtitle")} action={<ThemeButton />} />
      {!recipient?.is_self && <section className="family-hero">
        <div className="avatar-stack">{members.slice(0, 3).map((m) => <span key={m.userId}>{initials(m)}</span>)}</div>
        <h2>{t("heroTitle", { count: members.length, call })}</h2>
        <p>{t("heroBody")}</p>
        <button onClick={() => setInviteOpen(true)} disabled={!circleId}><Icon name="plus" size={18} /> {t("invite")}</button>
      </section>}
      {recipient?.is_self && <p className="form-note">{t("selfNote")}</p>}
      <ReceiverNote recipient={recipient} userId={userId} call={call} />
      <WeeklyCard recipient={recipient} />
      {circleId && !recipient?.is_self && <PendingInvites circleId={circleId} version={invitesVersion} onChanged={() => setInvitesVersion((v) => v + 1)} />}
      <div className="section-title members-title"><div><h2>{t("membersTitle")}</h2><p>{t("membersSub")}</p></div></div>
      <AlertsCard />
      <section className="member-list">
        {members.map((m, index) => (
          <div className="member" key={m.userId}>
            <span className={`member-avatar ${index % 3 === 0 ? "teal" : index % 3 === 1 ? "orange" : "plum"}`}>{initials(m)}</span>
            <div><h3>{name(m)} {m.userId === userId && <em>{t("you")}</em>}</h3><p>{t(m.role === "owner" ? "roleOwner" : "roleMember")}</p></div>
            {me?.role === "owner" && m.role === "member" && <button className="member-action" onClick={() => setConfirming({ kind: "remove", member: m })} aria-label={ti("removeLabel", { name: name(m) })}>{ti("remove")}</button>}
          </div>
        ))}
      </section>
      {me?.role === "member" && <button className="account-link leave-link" onClick={() => setConfirming({ kind: "leave" })}>{ti("leave")}</button>}
      {inviteOpen && circleId && <InviteSheet circleId={circleId} call={call} onClose={() => setInviteOpen(false)} onCreated={() => setInvitesVersion((v) => v + 1)} />}
      {confirming && <ConfirmSheet busy={actionBusy} onCancel={() => setConfirming(null)} onConfirm={() => void confirmAction()}
        title={confirming.kind === "remove" ? ti("removeTitle", { name: name(confirming.member) }) : ti("leaveTitle", { call })}
        body={confirming.kind === "remove" ? ti("removeBody", { call }) : ti("leaveBody", { call })}
        confirm={confirming.kind === "remove" ? ti("removeConfirm") : ti("leaveConfirm")} />}
      <button className="feature-link emergency-link" onClick={() => go("emergency")}><span><Icon name="heart" /></span><div><strong>{t("emergencyTitle")}</strong><p>{t("emergencySub")}</p></div><Icon name="chevron" /></button>
      <ActivityLink go={go} />
      <VaccinesLink go={go} recipient={recipient} />
      <button className="feature-link" onClick={() => go("doctor")}><span style={{ background: "var(--s-e3f3ef)", color: "var(--primary-text)" }}><Icon name="report" /></span><div><strong>{td("linkTitle")}</strong><p>{td("linkSub")}</p></div><Icon name="chevron" /></button>
      <button className="feature-link whatsapp-link" onClick={() => go("whatsapp")}><span><Icon name="phone" /></span><div><strong>{t("whatsappTitle", { call })}</strong><p>{t("whatsappSub")}</p></div><Icon name="chevron" /></button>
      {isAdmin && <button className="feature-link" onClick={() => go("stats")}><span style={{ background: "var(--s-e3f3ef)", color: "var(--primary-text)" }}><Icon name="chart" /></span><div><strong>{t("statsLink")}</strong><p>{t("statsSub")}</p></div><Icon name="chevron" /></button>}
      <InstallLink />
      <button className="feature-link" onClick={() => go("feedback")}><span style={{ background: "var(--s-e3f3ef)", color: "var(--primary-text)" }}><Icon name="send" /></span><div><strong>{t("feedbackLink")}</strong><p>{t("feedbackSub")}</p></div><Icon name="chevron" /></button>
      <SafetyNetLink go={go} hidden={!!recipient?.is_self} />
      <LoveLink go={go} hidden={!!recipient?.is_self} />
      <button className="feature-link" onClick={() => go("plans")}><span style={{ background: "var(--s-e3f3ef)", color: "var(--primary-text)" }}><Icon name="heart" /></span><div><strong>{t(PLANS_ENABLED ? "plansLink" : "planPilotLink")}</strong><p>{t(PLANS_ENABLED ? "plansSub" : "planPilotSub")}</p></div><Icon name="chevron" /></button>
      {PRIVACY_ENABLED && (<button className="feature-link" onClick={() => go("privacyData")}><span style={{ background: "var(--s-e3f3ef)", color: "var(--primary-text)" }}><Icon name="shield" /></span><div><strong>{t("privacyLink")}</strong><p>{t("privacyLinkSub")}</p></div><Icon name="chevron" /></button>)}
      <button className="account-link" onClick={onSignOut}>{t("signOut")}</button>
    </main>
  );
}

/** Built only from the parent's own records. Anything not filled in says "Not added" instead of guessing. */
function Emergency({ go, recipient, medicines, members }: { go: (screen: Screen) => void; recipient: Recipient | null; medicines: Medicine[]; members: CircleMember[] }) {
  const t = useTranslations();
  const language = useLocale() as Language;
  const toast = useToast();
  if (!recipient) return null;
  const e = (key: string, values?: Record<string, string | number>) => t(`emergency.${key}`, values);
  const notAdded = e("notAdded");
  const unknown = e("unknown");  // "not recorded" must never read as "none": staff may act on it
  const active = medicines.filter((m) => m.active);
  const family = members.filter((m) => m.phone).slice(0, 3);
  const ageCity = [recipient.age ? e("years", { count: recipient.age }) : null, recipient.city].filter(Boolean).join(" • ");
  const conditions = recipient.conditions;
  const allergies = recipient.allergies;
  const medicineLine = (m: Medicine) => [m.name, m.dose && localizeDose(m.dose, t)].filter(Boolean).join(" ");
  const shareText = [
    `${e("title")} — ${recipient.name}`,
    ageCity,
    `${e("bloodGroup")}: ${recipient.blood_group ?? unknown}`,
    `${e("allergies")}: ${allergies.join(", ") || unknown}`,
    `${e("conditions")}: ${conditions.join(", ") || unknown}`,
    `${e("medicines")}: ${active.map((m) => `${medicineLine(m)} (${describeTimes(m.times, t, language)})`).join("; ") || notAdded}`,
    recipient.whatsapp_phone && receiverOf(recipient).reminder_to === "person" ? `${e("parentPhone")}: ${recipient.whatsapp_phone}` : "",
    recipient.whatsapp_phone && receiverOf(recipient).reminder_to === "helper" ? `${e("helperPhone")}: ${receiverName(receiverOf(recipient))} ${recipient.whatsapp_phone}` : "",
    ...family.map((m) => `${e("family")}: ${m.name || m.email || ""} ${plusPhone(m.phone ?? "")}`.trim()),
    `${e("timezoneLabel")}: ${recipient.timezone}`,
    `${e("updated")}: ${formatDate(new Date(), language, { day: "numeric", month: "short", year: "numeric" })}`,
  ].filter(Boolean).join("\n");
  const download = () => {
    const url = URL.createObjectURL(new Blob([shareText], { type: "text/plain" }));
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `${recipient.name.replace(/[^A-Za-z0-9]+/g, "-").replace(/^-|-$/g, "") || "Emergency"}-Emergency-Card.txt`;
    anchor.click();
    URL.revokeObjectURL(url);
  };
  const contactRow = (label: string, who: string, phone: string, aria: string) => (
    <div className="contact-row" key={label + phone}>
      <div><span>{label}</span><strong>{who}</strong><p>{phone}</p></div>
      <button aria-label={aria} onClick={() => { window.location.href = `tel:${phone}`; }}><Icon name="phone" /></button>
    </div>
  );
  return (
    <main className="scroll-page no-nav emergency-page">
      <header className="emergency-title">
        <button onClick={() => go("family")} aria-label={t("common.goBack")}><Icon name="back" /></button>
        <div><h1 style={{ margin: 0, font: "inherit", fontWeight: 700, fontSize: 17, letterSpacing: ".6px" }}>{e("title")}</h1><small>{e("subtitle")}</small></div>
        <Icon name="heart" />
      </header>
      <section className="emergency-id">
        <div className="emergency-head"><span className="initials-avatar large" aria-hidden="true">{([...recipient.name][0] ?? "?").toUpperCase()}</span><div><h2>{recipient.name}</h2><p>{ageCity || notAdded}</p></div><span>{recipient.blood_group ?? "?"}</span></div>
        <div className="emergency-grid"><div><small>{e("bloodGroup")}</small><strong>{recipient.blood_group ?? unknown}</strong></div><div><small>{e("allergies")}</small><strong>{allergies.join(", ") || unknown}</strong></div></div>
        <div className="emergency-section"><small>{e("conditions")}</small>{conditions.length ? <div>{conditions.map((c) => <span key={c}>{c}</span>)}</div> : <strong>{unknown}</strong>}</div>
        <div className="emergency-section"><small>{e("medicines")}</small>{active.length ? <ul>{active.map((m) => <li key={m.id}>{medicineLine(m)} <span>{describeTimes(m.times, t, language)}</span></li>)}</ul> : <strong>{notAdded}</strong>}</div>
        {recipient.whatsapp_phone && receiverOf(recipient).reminder_to === "person" && contactRow(e("parentPhone"), recipient.name, recipient.whatsapp_phone, e("callParent"))}
        {recipient.whatsapp_phone && receiverOf(recipient).reminder_to === "helper" && contactRow(e("helperPhone"), receiverName(receiverOf(recipient)), recipient.whatsapp_phone, e("callHelper"))}
        {family.map((m) => contactRow(e("family"), m.name || m.email || "", plusPhone(m.phone ?? ""), e("callFamily")))}
      </section>
      <button className="secondary-button" style={{ marginTop: 14 }} onClick={() => go("profile")}>{e("editDetails")}</button>
      <div className="emergency-actions">
        <button className="primary-button share-button" onClick={() => shareContent(`${recipient.name} — ${e("title")}`, shareText, t)}><Icon name="share" size={19} /> {e("share")}</button>
        <button className="download-button" onClick={download}><Icon name="report" size={19} /> {e("download")}</button>
      </div>
      <p className="privacy-note"><Icon name="shield" size={16} /> {e("privacy")}</p>
      <EmergencyLinkPanel recipientId={recipient.id} name={recipient.name} share={(title, text) => shareContent(title, text, t)} toast={toast} />
    </main>
  );
}

/** The chat bubbles are what the PARENT receives, so they use the parent's language, not the caregiver's. */
function WhatsApp({ go, recipient, medicines }: { go: (screen: Screen) => void; recipient: Recipient | null; medicines: Medicine[] }) {
  const t = useTranslations("whatsapp");
  const tProfile = useTranslations("profile");
  const uiLanguage = useLocale() as Language;
  const parentLanguage: Language = recipient ? (recipient.language === "en" ? "en" : "hi") : uiLanguage;
  const msg = createTranslator({ locale: parentLanguage, messages: MESSAGES[parentLanguage], namespace: "whatsapp.msg" });
  const parentName = parentCall(recipient, parentLanguage);
  const callUi = parentCall(recipient, uiLanguage);
  // The picture of "what your parent receives" uses the parent's real first medicine and its real time.
  const tParent = createTranslator({ locale: parentLanguage, messages: MESSAGES[parentLanguage] }) as unknown as T;
  const sample = medicines.find((m) => m.active);
  const sampleName = sample ? sample.name : "Metformin";
  const sampleDose = sample?.dose ? localizeDose(sample.dose, tParent) : "500 mg";
  const sampleTime = sample?.times[0] ? clockLabel(sample.times[0], parentLanguage) : clockLabel("08:30", parentLanguage);
  const tz = recipient?.timezone ?? "Asia/Kolkata";
  // A pregnancy gets the gentler wording with the week (MessageTexts.pregnancyDoseReminder / pregnancyCheckin).
  const pregnant = isPregnancy(recipient);
  const pregWeek = pregnant && recipient?.due_date ? pregnancyStatus(recipient.due_date, todayIn(tz))?.week ?? null : null;
  const weekLine = pregWeek !== null ? <p>{msg("pregnancyWeek", { week: pregWeek, ordinal: hindiOrdinal(pregWeek) })}</p> : null;
  const hello = pregnant ? <><p>{msg("goodMorning", { call: parentName })}</p>{weekLine}</> : <p>{msg("hello", { call: parentName })}</p>;
  const howAreYou = pregnant ? <>{weekLine}<p>{msg("feelingToday")}</p></> : <p>{msg("areYouOkay")}</p>;

  // The real history: what was sent in the last 3 days and how the parent answered (null = still loading).
  const [activity, setActivity] = useState<Activity[] | null>(null);
  useEffect(() => {
    if (!recipient) return;
    let cancelled = false;
    const load = () => { loadRecentActivity(recipient).then((a) => { if (!cancelled) setActivity(a); }).catch(() => { if (!cancelled) setActivity([]); }); };
    load();
    const onReturn = () => { if (document.visibilityState === "visible") load(); };
    document.addEventListener("visibilitychange", onReturn);
    return () => { cancelled = true; document.removeEventListener("visibilitychange", onReturn); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [recipient?.id]);

  const time = (iso: string) => formatClock(new Date(iso), tz, parentLanguage);
  const dayLabel = (iso: string) => {
    const day = localDate(new Date(iso), tz);
    if (day === localDate(new Date(), tz)) return t("today");
    if (day === localDate(new Date(Date.now() - 24 * 3600_000), tz)) return t("yesterday");
    return new Intl.DateTimeFormat(uiLanguage === "hi" ? "hi-IN" : "en-IN", { day: "numeric", month: "short", timeZone: tz }).format(new Date(iso));
  };
  const real = activity && activity.length > 0 ? activity : null;
  const waitingForData = recipient && activity === null;

  const realItems = (items: Activity[]) => {
    let lastDay = "";
    return items.map((a) => {
      const day = localDate(new Date(a.at), tz);
      const heading = day !== lastDay ? <div key={`d-${a.id}`} className="wa-date next-day">{dayLabel(a.at)}</div> : null;
      lastDay = day;
      if (a.kind === "dose") {
        return (
          <div key={a.id}>
            {heading}
            <div className="wa-message medicine-message">
              {hello}<p>{msg("medicineTimeNow")}</p>
              <div className="wa-med-photo"><div className="blister"><i /><i /><i /><i /><i /><i /></div><span><strong>{a.name}{a.dosage ? ` ${localizeDose(a.dosage, tParent)}` : ""}</strong></span></div>
              <small>{time(a.at)} ✓✓</small>
            </div>
            {(a.status === "taken" || a.status === "taken_late") && <div className="wa-reply"><p>{msg("taken")}</p><small>{a.respondedAt ? time(a.respondedAt) : ""}</small></div>}
            {a.status === "missed" && <div className="wa-helper" style={{ marginTop: 0, marginBottom: 10 }}>{t("noReply", { call: callUi })}</div>}
            {a.status === "sent" && <div className="wa-helper" style={{ marginTop: 0, marginBottom: 10 }}>{t("waiting", { call: callUi })}</div>}
          </div>
        );
      }
      return (
        <div key={a.id}>
          {heading}
          <div className="wa-message">
            <p>{msg("goodMorning", { call: parentName })}</p>{howAreYou}<small>{time(a.at)} ✓✓</small>
            <div className="wa-buttons"><button type="button" disabled>{msg("okay")}</button><button type="button" disabled>{msg("callMe")}</button></div>
          </div>
          {a.status === "replied" && <div className="wa-reply"><p>{t("replied", { call: callUi })}</p><small>{a.respondedAt ? time(a.respondedAt) : ""}</small></div>}
          {a.status === "missed" && <div className="wa-helper" style={{ marginTop: 0, marginBottom: 10 }}>{t("noReply", { call: callUi })}</div>}
          {a.status === "sent" && <div className="wa-helper" style={{ marginTop: 0, marginBottom: 10 }}>{t("waiting", { call: callUi })}</div>}
        </div>
      );
    });
  };

  return (
    <main className="whatsapp-page">
      <h1 className="sr-only">{t("title")}</h1>
      <header className="wa-header"><button onClick={() => go("family")} aria-label={t("back")}><Icon name="back" /></button><span className="wa-logo"><Icon name="heart" size={19} /></span><div><strong>Chhaon</strong><small>{t("title")}</small></div></header>
      <div className="wa-chat">
        {!waitingForData && <div className={`wa-helper${real ? "" : " preview-note"}`} style={{ marginTop: 0, marginBottom: 8 }}>{real ? t("realNote", { call: callUi }) : t("sampleNote", { call: callUi })}</div>}
        <div className="wa-helper" style={{ marginTop: 0, marginBottom: 14 }}>{t(pregnant ? "sentInPregnant" : "sentIn", { call: callUi, language: tProfile(parentLanguage === "en" ? "langEnglish" : "langHindi") })}</div>
        {real && realItems(real)}
        {!real && !waitingForData && (
          <>
            <div className="wa-date">{t("today")}</div>
            <div className="wa-message medicine-message">
              {hello}<p>{msg("medicineTime")}</p>
              <div className="wa-med-photo"><div className="blister"><i /><i /><i /><i /><i /><i /></div><span><strong>{sampleName} {sampleDose}</strong><small>{sample ? "" : msg("medicineDose")}</small></span></div>
              <small>{sampleTime} ✓✓</small>
              <button type="button" disabled>{msg("taken")}</button>
            </div>
            <div className="wa-date next-day">{t("tomorrow")}</div>
            <div className="wa-message">
              <p>{msg("goodMorning", { call: parentName })}</p>{howAreYou}<small>{clockLabel("08:00", parentLanguage)} ✓✓</small>
              <div className="wa-buttons"><button type="button" disabled>{msg("okay")}</button><button type="button" disabled>{msg("callMe")}</button></div>
            </div>
          </>
        )}
        <div className="wa-helper">{t("helper", { call: callUi })}</div>
      </div>
      <div className="wa-input" aria-hidden="true"><span>{t("inputPlaceholder")}</span><button type="button" tabIndex={-1} disabled aria-hidden="true" aria-label={t("inputPlaceholder")}><Icon name="send" /></button></div>
    </main>
  );
}

const NEEDS_PARENT: Screen[] = ["today", "appointments", "medicines", "addMedicine", "editMedicine", "medicineDetail", "reports", "reportDetail", "documentDetail", "family", "emergency", "whatsapp", "doctor", "vaccines"];
const TAB_SCREENS: Screen[] = ["today", "medicines", "reports", "family"];
const ALL_SCREENS: Screen[] = ["welcome", "otp", "profile", "addParent", "today", "appointments", "medicines", "addMedicine", "editMedicine", "medicineDetail", "reports", "reportDetail", "documentDetail", "family", "emergency", "whatsapp", "plans", "stats", "safety", "feedback", "love", "doctor", "addSelf", "privacyData", "policy", "terms", "goodbye", "vaccines", "activity"];

function screenFromHash(): Screen {
  const name = window.location.hash.replace(/^#\/?/, "").split(/[?#]/)[0] as Screen;  // "#/medicines?x=1" is still Medicines
  return ALL_SCREENS.includes(name) ? name : "today";
}

/** Keeps the current screen in the URL hash so the Android Back button and iOS swipe-back move between screens
 *  instead of leaving the app. Switching between the four tabs replaces the entry (Back from a tab exits). */
function useScreenHistory() {
  const [screen, setScreenState] = useState<Screen>(screenFromHash);
  useEffect(() => {
    history.replaceState({ screen }, "", `#/${screen}`);
    const onPop = () => {
      const next = screenFromHash();
      if (window.location.hash !== `#/${next}`) history.replaceState({ screen: next }, "", `#/${next}`);  // never leave "#/garbage" showing
      setScreenState(next);
    };
    window.addEventListener("popstate", onPop);
    return () => window.removeEventListener("popstate", onPop);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  const go = (next: Screen, options?: { replace?: boolean }) => {
    const bothTabs = TAB_SCREENS.includes(screen) && TAB_SCREENS.includes(next);
    history[bothTabs || options?.replace ? "replaceState" : "pushState"]({ screen: next }, "", `#/${next}`);
    setScreenState(next);
    document.querySelector(".scroll-page, .onboarding")?.scrollTo?.({ top: 0 });
  };
  return [screen, go] as const;
}

export default function App() {
  const [screen, go] = useScreenHistory();
  const [storedLanguage, setLanguage] = usePersistentState<Language>("carelink-language", "en");
  const language: Language = storedLanguage === "hi" ? "hi" : "en";
  const [members, setMembers] = useState<CircleMember[]>([]);
  const [userId, setUserId] = useState<string | null>(null);
  const [ready, setReady] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);
  const [signedIn, setSignedIn] = useState(false);
  const recipientRef = useRef<Recipient | null>(null);
  const selectedIdRef = useRef<string | null>(null);
  const [recipient, setRecipient] = useState<Recipient | null>(null);
  const [recipients, setRecipients] = useState<Recipient[]>([]);
  const [medicineNames, setMedicineNames] = useState<Record<string, string>>({});
  const [medicines, setMedicines] = useState<Medicine[]>([]);
  const [medicinesLoading, setMedicinesLoading] = useState(false);
  const [medicinesError, setMedicinesError] = useState(false);
  const [notifications, setNotifications] = useState<AppNotification[]>([]);
  const [selectedMedicineId, setSelectedMedicineId] = useState<string | null>(null);
  const [selectedReportId, setSelectedReportId] = useState<string | null>(null);
  const [selectedDocumentId, setSelectedDocumentId] = useState<string | null>(null);
  const [toastMessage, setToastMessage] = useState("");
  const [toastLeaving, setToastLeaving] = useState(false);
  const toastTimer = useRef<number | undefined>(undefined);
  /** Fades the toast out first, then removes it. */
  const hideToast = () => {
    window.clearTimeout(toastTimer.current);
    setToastLeaving(true);
    toastTimer.current = window.setTimeout(() => { setToastMessage(""); setToastLeaving(false); }, 320);
  };
  const showToast = (message: string) => {
    window.clearTimeout(toastTimer.current);
    setToastMessage(message);
    setToastLeaving(false);
    toastTimer.current = window.setTimeout(() => hideToast(), 3000);
  };

  /** The selected parent, null if there are none, or "error" if the lookup failed. A failure never clears what is on screen.
   *  `selectId` makes a just-created parent the selected one. */
  const refreshRecipient = async (selectId?: string, notifyIfRemoved = false): Promise<Recipient | null | "error" | "auth"> => {
    try {
      const previousId = recipientRef.current?.id;
      const uid = authUserId();
      setUserId(uid);
      if (uid && !selectedIdRef.current) {
        try { selectedIdRef.current = localStorage.getItem(`carelink-parent-${uid}`); } catch { /* storage blocked */ }
      }
      const list = await loadRecipients();
      if (list.length === 0 && recipientRef.current) {
        // We had a parent a moment ago and now there are none. Either our login has expired (send them to log in)
        // or the parent was removed (the guard below sends them to "Add your parent").
        if (authExpired()) { void expireSession(); return null; }
      }
      if (selectId) selectedIdRef.current = selectId;
      const chosen = list.find((r) => r.id === selectedIdRef.current) ?? list[0] ?? null;
      selectedIdRef.current = chosen?.id ?? null;
      if (uid && chosen) { try { localStorage.setItem(`carelink-parent-${uid}`, chosen.id); } catch { /* storage blocked */ } }
      recipientRef.current = chosen;
      setRecipients(list);
      setRecipient(chosen);
      // The selected parent vanished while the caregiver was looking (removed from the family by the owner, or parent deleted).
      if (notifyIfRemoved && previousId && !list.some((r) => r.id === previousId)) {
        if (!chosen) { setMedicines([]); setMembers([]); }
        showToast(MESSAGES[language].common.removedFromFamily);
      }
      return chosen;
    } catch (e) {
      return isAuthError(e as { code?: string; message?: string; hint?: string; status?: number }) ? "auth" : "error";
    }
  };
  const selectParent = (id: string) => {
    const next = recipients.find((r) => r.id === id);
    if (!next || next.id === recipient?.id) return;
    selectedIdRef.current = id;
    recipientRef.current = next;
    setRecipient(next);
    setMedicines([]);  // never show the previous parent's medicines under the new name
    setMembers([]);
    setSelectedMedicineId(null);
    try { if (userId) localStorage.setItem(`carelink-parent-${userId}`, id); } catch { /* storage blocked */ }
    showToast(MESSAGES[language].parents.switched.replace("{call}", parentCall(next, language)));
  };
  const refreshMedicines = async (recipientId = recipient?.id, quiet = false) => {
    if (!recipientId) return setMedicines([]);
    if (!quiet) setMedicinesLoading(true);
    try { setMedicines(await loadMedicines(recipientId)); setMedicinesError(false); }
    catch { if (!quiet) setMedicinesError(true); }  // a failed background refresh keeps what is on screen
    setMedicinesLoading(false);
  };

  // First load: decide where the caregiver belongs. If we cannot tell (offline), ask them to retry instead of
  // guessing "no parent yet", which would send them to the add-parent form and risk a duplicate parent.
  const boot = async () => {
    setLoadFailed(false);
    let session = authToken();
    let expired = false;
    if (session && authExpired()) {
      // A stored token that has expired: clear it and ask them to log in again.
      clearToken();
      session = null;
      expired = true;
    }
    if (!session) {
      setSignedIn(false);
      // A reload on the code screen (or a legal page) keeps the screen; the OTP screen itself bounces to welcome if nothing is pending.
      if (!(screen === "otp" || screen === "policy" || screen === "terms")) go("welcome", { replace: true });
      if (expired) showToast(MESSAGES[language].common.sessionExpired);
    } else if (!navigator.onLine) {
      setLoadFailed(true);  // say so straight away instead of waiting for requests that cannot succeed
    } else {
      const next = await refreshRecipient();
      if (next === "auth") rejectLogin();   // a stored token Supabase refuses: clear it so we don't loop on the error screen
      else if (next === "error") setLoadFailed(true);
      else {
        setSignedIn(true);
        if (!next) go("profile", { replace: true });
        else if (screen === "welcome" || screen === "otp" || screen === "profile") go("today", { replace: true });
      }
    }
    setReady(true);
  };
  useEffect(() => {
    void boot();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  // When the network comes back: retry the failed first lookup by itself, and refresh a signed-in caregiver's view.
  const onlineRef = useRef<() => void>(() => {});
  onlineRef.current = () => {
    if (loadFailed) void boot();
    else if (signedIn && recipientRef.current) { void refreshRecipient(undefined, true); void refreshMedicines(undefined, true); }
  };
  useEffect(() => {
    const on = () => onlineRef.current();
    window.addEventListener("online", on);
    return () => window.removeEventListener("online", on);
  }, []);

  // Central guard: nobody sees a screen that does not fit their situation (no login, no parent yet, or parent gone).
  useEffect(() => {
    if (!ready || loadFailed) return;
    if (!signedIn) { if (!["welcome", "otp", "policy", "terms", "goodbye"].includes(screen)) go("welcome", { replace: true }); }
    else if (pendingInvite() && screen !== "join") go("join", { replace: true });  // opened a family invite link: ask before anything else
    else if (screen === "join" && !pendingInvite()) go(recipient ? "today" : "profile", { replace: true });
    else if (screen === "welcome" || screen === "otp" || screen === "goodbye") go(recipient ? "today" : "profile", { replace: true });
    else if (!recipient && (NEEDS_PARENT.includes(screen) || screen === "addParent" || screen === "addSelf")) go("profile", { replace: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready, loadFailed, signedIn, recipient, screen]);

  useEffect(() => { void refreshMedicines(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [recipient?.id]);
  useEffect(() => {
    if (!recipient?.circle_id) { setMembers([]); return; }
    void loadMembers(recipient.circle_id).then(setMembers).catch(() => {});
    try { localStorage.removeItem("carelink-family-v2"); } catch { /* old demo data from earlier versions */ }
  }, [recipient?.circle_id]);

  // Keep the parent's WhatsApp status fresh: quickly while we wait for them to agree, slowly otherwise (they may reply STOP).
  const waitingForParent = !!recipient && (!recipient.consent_at || !!recipient.opted_out_at);
  const hasRecipient = !!recipient;
  useEffect(() => {
    if (!hasRecipient) return;
    const id = window.setInterval(() => void refreshRecipient(undefined, true), waitingForParent ? 15000 : 60000);
    return () => window.clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hasRecipient, waitingForParent]);

  // Alerts (missed dose, no check-in reply...) arrive from the backend scheduler: refresh them every 30 seconds.
  useEffect(() => {
    if (!hasRecipient) return;
    const load = () => {
      void loadNotifications().then((list) => { if (list) setNotifications(list); });
      void loadMedicineNames().then(setMedicineNames);
      void refreshMedicines(undefined, true);  // stock changes when the parent confirms a dose on WhatsApp, or a sibling edits
    };
    load();
    const id = window.setInterval(load, 30000);
    const onVisible = () => { if (document.visibilityState === "visible") load(); };
    document.addEventListener("visibilitychange", onVisible);
    return () => { window.clearInterval(id); document.removeEventListener("visibilitychange", onVisible); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hasRecipient, recipient?.id]);
  useEffect(() => {
    if (!recipient?.circle_id) return;
    const circleId = recipient.circle_id;
    const id = window.setInterval(() => void loadMembers(circleId).then((list) => {
      setMembers(list);
      // The member list no longer includes me: I was removed. Re-check which parents I still have.
      if (userId && !list.some((m) => m.userId === userId)) void refreshRecipient(undefined, true);
    }).catch(() => {}), 60000);
    return () => window.clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [recipient?.circle_id, userId]);
  const notificationsSeen = async (ids: string[]) => {
    await markNotificationsRead(ids);  // only what was actually shown: the list holds the 30 newest
    const list = await loadNotifications();
    if (list) setNotifications(list);
  };

  const deleteNotification = async (id: string) => {
    setNotifications((list) => list.filter((n) => n.id !== id));
    await deleteNotificationRow(id);
    const list = await loadNotifications();
    if (list) setNotifications(list);
  };

  const reloadMembers = () => { if (recipient?.circle_id) void loadMembers(recipient.circle_id).then(setMembers).catch(() => {}); };
  /** After accepting or declining an invite: pick up the new parent (it becomes the selected one) and go home. */
  const afterInvite = async (recipientId?: string) => {
    clearPendingInvite();
    const next = await refreshRecipient(recipientId);
    if (next === "error") return setLoadFailed(true);
    go(next ? "today" : "profile", { replace: true });
  };
  const afterLeft = async () => {
    selectedIdRef.current = null;
    setMembers([]);
    const next = await refreshRecipient();
    if (next === "error") return setLoadFailed(true);
    go(next ? "today" : "profile", { replace: true });
  };
  // The login token was refused (e.g. Supabase does not accept it): clear it and send them back to sign in with a
  // clear message, instead of a blank screen or a misleading "check your connection".
  const rejectLogin = () => {
    clearToken();
    setSignedIn(false);
    setLoadFailed(false);
    recipientRef.current = null;
    selectedIdRef.current = null;
    setRecipient(null);
    setRecipients([]);
    go("welcome", { replace: true });
    showToast(MESSAGES[language].common.signInFailed);
  };
  const afterLogin = async () => {
    const next = await refreshRecipient();
    if (next === "auth") return rejectLogin();          // token not accepted: don't strand on the code screen
    if (next === "error") return setLoadFailed(true);   // network/server: retryable
    setSignedIn(true);
    go(next ? "today" : "profile", { replace: true });
  };
  const afterProfileSaved = async (newId?: string) => {
    if ((await refreshRecipient(newId)) === "error") return setLoadFailed(true);
    setSignedIn(true);
    go("today", { replace: true });
  };
  const refreshNotifications = () => void loadNotifications().then((list) => { if (list) setNotifications(list); });
  const afterMedicineSaved = async () => {
    await refreshMedicines();
    refreshNotifications();  // a medicine added while already low raises a refill alert straight away
    go("medicines");
  };
  const afterMedicineEdited = async () => {
    await refreshMedicines();
    refreshNotifications();
    go("medicineDetail", { replace: true });
  };
  const expireSession = async () => {
    clearToken();
    setSignedIn(false);
    recipientRef.current = null;
    selectedIdRef.current = null;
    setRecipient(null);
    setRecipients([]);
    setMembers([]);
    setMedicines([]);
    setNotifications([]);
    go("welcome", { replace: true });
    showToast(MESSAGES[language].common.sessionExpired);
  };
  const signOut = async () => {
    clearToken();
    forgetDoctorAddresses();
    setSignedIn(false);
    recipientRef.current = null;
    selectedIdRef.current = null;
    setRecipient(null);
    setRecipients([]);
    setMembers([]);
    setMedicines([]);
    setNotifications([]);
    go("welcome", { replace: true });
  };

  useEffect(() => { document.documentElement.lang = language; }, [language]);

  // Pull down at the top of a screen to refresh it: reload the shared data, then reopen the screen so it fetches its own.
  const [pullTick, setPullTick] = useState(0);
  const PULL_SCREENS: Screen[] = ["today", "appointments", "medicines", "medicineDetail", "reports", "reportDetail", "documentDetail", "family", "emergency", "whatsapp", "plans", "stats", "feedback", "love", "doctor"];
  const { pull, refreshing: pullRefreshing } = usePullToRefresh(ready && !loadFailed && PULL_SCREENS.includes(screen), async () => {
    await Promise.allSettled([
      refreshRecipient(recipientRef.current?.id),
      refreshMedicines(recipientRef.current?.id, true),
      loadNotifications().then((list) => { if (list) setNotifications(list); }),
      recipientRef.current?.circle_id ? loadMembers(recipientRef.current.circle_id).then(setMembers) : Promise.resolve(),
    ]);
    setPullTick((n) => n + 1);
  });

  const hasNav = ["today", "medicines", "reports", "family"].includes(screen);
  return (
    <AppIntl language={language}>
    <ToastContext.Provider value={showToast}>
    <SessionContext.Provider value={() => void expireSession()}>
    <div className="app-shell">
      <PullIndicator pull={pull} refreshing={pullRefreshing} />
      <div className="phone-frame">
        {!ready && <main className="onboarding"><p className="form-note" role="status">{MESSAGES[language].common.loading}</p></main>}
        {ready && loadFailed && <LoadFailedNotice onRetry={() => void boot()} />}
        {ready && !loadFailed && (
          <ErrorBoundary key={`${screen}-${pullTick}`} fallback={<CrashNotice />}>
          {screen === "welcome" && <Welcome go={go} />}
          {screen === "otp" && <OtpScreen go={go} onVerified={afterLogin} />}
          {screen === "profile" && <ProfileForm go={go} recipient={recipient} onSaved={afterProfileSaved} onSignOut={() => void signOut()} />}
          {screen === "addParent" && <ProfileForm go={go} recipient={null} adding onSaved={afterProfileSaved} />}
          {screen === "addSelf" && <ProfileForm go={go} recipient={null} adding self onSaved={afterProfileSaved} />}
          {screen === "today" && <Today go={go} language={language} setLanguage={setLanguage} recipient={recipient} recipients={recipients} notifications={notifications} medicineNames={medicineNames} medicines={medicines} onNotificationsSeen={notificationsSeen} onDeleteNotification={deleteNotification} onSelectParent={selectParent} />}
          {screen === "medicines" && <Medicines go={go} medicines={medicines} loading={medicinesLoading} error={medicinesError} onOpen={(id) => { setSelectedMedicineId(id); go("medicineDetail"); }} />}
          {screen === "addMedicine" && recipient && <AddMedicine go={go} recipient={recipient} usualTimes={usualTimesOf(medicines)} onSaved={afterMedicineSaved} />}
          {screen === "editMedicine" && recipient && (() => {
            const editing = medicines.find((m) => m.id === selectedMedicineId);
            const medicineGone = () => { showToast(MESSAGES[language].medicine.gone); void refreshMedicines(); go("medicines", { replace: true }); };
            return editing
              ? <AddMedicine key={selectedMedicineId} go={go} recipient={recipient} medicine={editing} usualTimes={usualTimesOf(medicines)} onSaved={afterMedicineEdited} onMissing={medicineGone} />
              : medicinesLoading ? null : <GoneRedirect onGone={medicineGone} />;
          })()}
          {screen === "medicineDetail" && <MedicineDetail go={go} medicine={medicines.find((m) => m.id === selectedMedicineId)} onChanged={async () => { await refreshMedicines(); refreshNotifications(); }} />}
          {screen === "reports" && (REPORTS_ENABLED
            ? <Reports recipient={recipient} onOpen={(id) => { setSelectedReportId(id); go("reportDetail"); }} />
            : <WalletScreen recipient={recipient} onOpen={(id) => { setSelectedDocumentId(id); go("documentDetail"); }} onUpgrade={() => go("plans")} />)}
          {screen === "documentDetail" && <WalletDetail go={go} documentId={selectedDocumentId} />}
          {screen === "appointments" && <AppointmentsScreen go={go} recipient={recipient} />}
          {screen === "vaccines" && <VaccinesScreen go={go} recipient={recipient} />}
          {screen === "reportDetail" && <ReportDetail go={go} recipient={recipient} reportId={selectedReportId} />}
          {screen === "join" && pendingInvite() && <JoinScreen token={pendingInvite() as string} onDone={afterInvite} />}
          {screen === "family" && <Family go={go} members={members} userId={userId} recipient={recipient} onSignOut={signOut} onMembersChanged={reloadMembers} onLeft={afterLeft} />}
          {screen === "emergency" && <Emergency go={go} recipient={recipient} medicines={medicines} members={members} />}
          {screen === "whatsapp" && <WhatsApp go={go} recipient={recipient} medicines={medicines} />}
          {screen === "doctor" && <DoctorSummaryScreen go={go} recipient={recipient} medicines={medicines} />}
          {screen === "plans" && <PlansScreen go={go} />}
          {screen === "stats" && <StatsScreen go={go} />}
          {screen === "safety" && <SafetyNetScreen go={go} recipient={recipient} medicines={medicines} onMedicinesChanged={() => void refreshMedicines(undefined, true)} />}
          {screen === "feedback" && <FeedbackScreen go={go} />}
          {screen === "love" && <LoveScreen go={go} recipient={recipient} />}
          {screen === "activity" && <ActivityScreen go={go} />}
          {screen === "privacyData" && <PrivacyData go={go} recipient={recipient} members={members} userId={userId} onParentDeleted={() => afterProfileSaved()} onAccountDeleted={async () => { await signOut(); go("goodbye", { replace: true }); }} />}
          {(screen === "policy" || screen === "terms") && <LegalPage key={screen} kind={screen} go={go} signedIn={signedIn} />}
          {screen === "goodbye" && <Goodbye go={go} />}
          </ErrorBoundary>
        )}
        {ready && !loadFailed && hasNav && <BottomNav screen={screen} go={go} unread={notifications.filter((n) => !n.read_at).length} />}
        {PRIVACY_ENABLED && ready && !loadFailed && <ConsentGate signedIn={signedIn} screen={screen} go={go} onSignOut={() => void signOut()} />}
        <OfflineBanner />
        {toastMessage && <div className={`toast${toastLeaving ? " leaving" : ""}`} key={toastMessage} role="status" onClick={hideToast}><span className="toast-text">{toastMessage}</span></div>}
      </div>
    </div>
    </SessionContext.Provider>
    </ToastContext.Provider>
    </AppIntl>
  );
}
