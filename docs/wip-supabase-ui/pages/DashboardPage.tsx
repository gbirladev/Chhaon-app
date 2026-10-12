import { Link } from "react-router";
import { useTranslations } from "use-intl";
import { Icon } from "@/components/Icon";
import { LanguageButton } from "@/components/LanguageButton";
import { LiveTimes } from "@/components/LiveTimes";
import { callName, useRecipient } from "@/lib/recipient";

export default function DashboardPage() {
  const t = useTranslations("dashboard");
  const tCall = useTranslations("parent.relationCall");
  const { recipient } = useRecipient();
  if (!recipient) return null;

  const name = callName(recipient, (k) => tCall(k));
  const hour = Number(new Intl.DateTimeFormat("en-GB", { hour: "numeric", hour12: false, timeZone: recipient.timezone }).format(new Date())) % 24;
  const greeting = hour < 12 ? t("goodMorning") : hour < 17 ? t("goodAfternoon") : t("goodEvening");

  return (
    <main className="scroll-page">
      <header className="home-header">
        <Link to="/parent/edit" className="parent-profile" aria-label={t("editParent")}>
          <span className="initials-avatar">{recipient.name.charAt(0).toUpperCase()}</span>
          <span>
            <small>{greeting}</small>
            <strong>{name}</strong>
            <LiveTimes parentTz={recipient.timezone} city={recipient.city} />
          </span>
        </Link>
        <div className="header-actions"><LanguageButton /></div>
      </header>

      <section className="hero-card">
        <span className="hero-eyebrow">{t("heroEyebrow")}</span>
        <h2>{t("heroTitle", { name })}</h2>
        <p>{t("placeholder")}</p>
        <Link to="/medicines/new" className="hero-cta"><Icon name="plus" size={18} /> {t("addMedicine")}</Link>
      </section>

      <div className="quick-grid">
        <Link to="/medicines" className="quick-tile">
          <span className="ico"><Icon name="pill" /></span>
          {t("tileMedicines")}
          <small>{t("tileMedicinesHint")}</small>
        </Link>
        <Link to="/parent/edit" className="quick-tile">
          <span className="ico"><Icon name="edit" /></span>
          {t("editParent")}
          <small>{t("tileProfileHint")}</small>
        </Link>
      </div>
    </main>
  );
}
