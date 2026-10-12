import { useState, type FormEvent } from "react";
import { Navigate, useNavigate } from "react-router";
import { useTranslations } from "use-intl";
import { z } from "zod";
import { supabase } from "@/lib/supabase";
import { useAuth } from "@/lib/auth";
import { E164 } from "@/lib/countries";
import { Icon } from "@/components/Icon";
import { LanguageButton } from "@/components/LanguageButton";
import { PhoneField } from "@/components/PhoneField";

type Method = "phone" | "email";

export default function LoginPage() {
  const t = useTranslations("login");
  const navigate = useNavigate();
  const { user, loading } = useAuth();
  const [method, setMethod] = useState<Method>("phone");
  const [phone, setPhone] = useState("");
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [codeSent, setCodeSent] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (!loading && user) return <Navigate to="/dashboard" replace />;

  const destination = method === "phone" ? phone : email.trim();

  async function sendCode(e?: FormEvent) {
    e?.preventDefault();
    setError(null);
    setNotice(null);
    if (method === "phone" && !E164.test(phone)) return setError(t("invalidPhone"));
    if (method === "email" && !z.string().email().safeParse(email.trim()).success) return setError(t("invalidEmail"));

    setBusy(true);
    const { error } = await supabase.auth.signInWithOtp(method === "phone" ? { phone } : { email: email.trim() });
    setBusy(false);
    if (error) return setError(error.message);
    if (codeSent) setNotice(t("resent"));
    setCodeSent(true);
  }

  async function verifyCode(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!/^\d{6,8}$/.test(code.trim())) return setError(t("invalidCode"));
    setBusy(true);
    const { error } = await supabase.auth.verifyOtp(
      method === "phone"
        ? { phone, token: code.trim(), type: "sms" }
        : { email: email.trim(), token: code.trim(), type: "email" },
    );
    setBusy(false);
    if (error) return setError(error.message);
    navigate("/dashboard", { replace: true });
  }

  if (codeSent) {
    return (
      <main className="onboarding otp-screen">
        <header className="page-header">
          <button className="icon-button" type="button" onClick={() => { setCodeSent(false); setCode(""); setError(null); }} aria-label={t("back")}>
            <Icon name="back" />
          </button>
          <div>
            <h1>{t("verifyTitle")}</h1>
            <p>{t("verifySubtitle", { destination })}</p>
          </div>
        </header>
        <div className="otp-icon"><Icon name="shield" size={30} /></div>
        <h2>{t("enterCode")}</h2>
        <form onSubmit={verifyCode}>
          <input
            className="otp-input"
            aria-label={t("codeLabel")}
            value={code}
            onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, 8))}
            inputMode="numeric"
            autoComplete="one-time-code"
            autoFocus
          />
          <button className="primary-button" disabled={busy}>{busy ? t("checking") : t("verify")}</button>
        </form>
        <button className="text-button" type="button" disabled={busy} onClick={() => void sendCode()}>{t("resend")}</button>
        {notice && <p role="status" style={{ textAlign: "center", color: "#247c72" }}>{notice}</p>}
        {error && <p role="alert" className="form-error">{error}</p>}
      </main>
    );
  }

  return (
    <main className="onboarding">
      <div style={{ display: "flex", justifyContent: "flex-end" }}><LanguageButton /></div>
      <div className="logo-hero">
        <div className="brand-mark"><Icon name="heart" size={31} strokeWidth={2.2} /></div>
        <div className="big-heart">
          <Icon name="heart" size={64} strokeWidth={1.6} />
          <span className="floating-chip chip-one">{t("chipTaken")}</span>
          <span className="floating-chip chip-two">{t("chipOkay")}</span>
        </div>
      </div>
      <div className="welcome-copy">
        <p className="eyebrow">{t("eyebrow")}</p>
        <h1>{t("headline")}</h1>
        <p>{t("body")}</p>
      </div>
      <form className="login-card" onSubmit={sendCode}>
        <label htmlFor={method === "phone" ? "login-phone" : "login-email"}>
          {method === "phone" ? t("phoneLabel") : t("emailLabel")}
        </label>
        {method === "phone" ? (
          <PhoneField id="login-phone" value={phone} onChange={setPhone} countryLabel={t("countryLabel")} placeholder={t("phonePlaceholder")} required />
        ) : (
          <div className="phone-input">
            <input id="login-email" type="email" autoComplete="email" inputMode="email" placeholder={t("emailPlaceholder")} value={email} onChange={(e) => setEmail(e.target.value)} required />
          </div>
        )}
        <button className="primary-button" disabled={busy}>{busy ? t("sending") : t("continue")}</button>
        <button className="text-button" type="button" onClick={() => { setMethod(method === "phone" ? "email" : "phone"); setError(null); }}>
          {method === "phone" ? t("useEmail") : t("usePhone")}
        </button>
        {error && <p role="alert" className="form-error">{error}</p>}
      </form>
    </main>
  );
}
