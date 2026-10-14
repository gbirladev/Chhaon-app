import { useEffect, useState } from "react";
import { useLocale, useTranslations } from "use-intl";
import { AppHeader, usePlanPauses } from "../App";
import { daysLeft, loadPlanOverview, loadPlans, type PlanInfo, type PlanOverview } from "../lib/plans";
import "./plans.css";

const CONTACT_EMAIL = "chhaon.app.mail@gmail.com";

/** What each plan allows, and which one this account is on. Plans are switched by hand during the pilot: nothing is charged. */
export function PlansScreen({ go }: { go: (screen: "family") => void }) {
  const t = useTranslations("plans");
  const language = useLocale();
  const tt = useTranslations("today");
  const pauses = usePlanPauses();
  const [state, setState] = useState<{ plans: PlanInfo[]; mine: PlanOverview } | "error" | null>(null);
  useEffect(() => {
    let cancelled = false;
    Promise.all([loadPlans(), loadPlanOverview()])
      .then(([plans, mine]) => { if (!cancelled) setState({ plans, mine }); })
      .catch(() => { if (!cancelled) setState("error"); });
    return () => { cancelled = true; };
  }, []);
  const planName = (p: { key: string; name: string }) => (["free", "plus", "family", "ultra"].includes(p.key) ? t(`names.${p.key}`) : p.name);
  const mailto = `mailto:${CONTACT_EMAIL}?subject=${encodeURIComponent(t("mailSubject"))}`;
  return (
    <main className="scroll-page no-nav plans-page">
      <AppHeader title={t("title")} subtitle={t("subtitle")} back={() => go("family")} />
      {state === null && <p className="plan-usage" role="status">{t("loading")}</p>}
      {state === "error" && <p className="plan-usage" role="alert">{t("loadError")}</p>}
      {state && state !== "error" && (
        <>
          {state.mine.onTrial && (
            <div className="plan-trial" role="status">
              <strong>{t("trialTitle", { days: daysLeft(state.mine.trialEndsAt) })}</strong>
              <p>{state.mine.wouldPauseMedicines + state.mine.wouldPauseParents > 0
                ? t("trialBodyPause", { date: new Intl.DateTimeFormat(language === "hi" ? "hi-IN" : "en-IN", { day: "numeric", month: "long" }).format(new Date(state.mine.trialEndsAt)), items: [state.mine.wouldPauseParents > 0 ? tt("pauseItemParents", { count: state.mine.wouldPauseParents }) : "", state.mine.wouldPauseMedicines > 0 ? tt("pauseItemMedicines", { count: state.mine.wouldPauseMedicines }) : ""].filter(Boolean).join(` ${tt("and")} `) })
                : t("trialBody", { date: new Intl.DateTimeFormat(language === "hi" ? "hi-IN" : "en-IN", { day: "numeric", month: "long" }).format(new Date(state.mine.trialEndsAt)) })}</p>
            </div>
          )}
          {!state.mine.onTrial && state.mine.plan === "free" && (
            <div className="plan-trial ended" role="status"><strong>{t("trialEndedTitle")}</strong><p>{t("trialEndedBody")}</p></div>
          )}
          {pauses && (pauses.parentIds.length > 0 || pauses.medicines.length > 0) && (
            <div className="plan-trial ended" role="alert"><strong>{t("pausedNow", { items: [pauses.parentIds.length > 0 ? tt("pauseItemParents", { count: pauses.parentIds.length }) : "", pauses.medicines.length > 0 ? tt("pauseItemMedicines", { count: pauses.medicines.length }) : ""].filter(Boolean).join(` ${tt("and")} `) })}</strong></div>
          )}
          {state.plans.map((p) => {
            const current = p.key === state.mine.plan;
            const trialPlan = state.mine.onTrial && p.key === state.mine.effective;
            return (
              <section key={p.key} className={`plan-card${current || trialPlan ? " current" : ""}`} aria-label={planName(p)}>
                <div className="plan-head"><h2>{planName(p)}</h2>{(current && !trialPlan) && <span className="plan-tag">{t("yourPlan")}</span>}{trialPlan && <span className="plan-tag">{t("trialTag")}</span>}</div>
                <ul className="plan-list">
                  <li><span>{t("parents")}</span><strong>{p.maxParents ?? t("unlimited")}</strong></li>
                  <li><span>{t("members")}</span><strong>{p.maxMembers ?? t("unlimited")}</strong></li>
                  <li><span>{t("medicines")}</span><strong>{p.maxMedicines ?? t("unlimited")}</strong></li>
                </ul>
                {(current || trialPlan) && <p className="plan-usage">{state.mine.maxParents === null ? t("usageUnlimited", { used: state.mine.parentsUsed }) : t("usage", { used: state.mine.parentsUsed, max: state.mine.maxParents })}</p>}
              </section>
            );
          })}
          <div className="plan-note">
            <strong>{t("noteTitle")}</strong>
            <p style={{ margin: "6px 0 0" }}>{t("noteBody")}</p>
            <a href={mailto}>{t("contact")}</a>
          </div>
        </>
      )}
    </main>
  );
}
