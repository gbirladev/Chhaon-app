import { useEffect, useState } from "react";
import { useTranslations } from "use-intl";
import "./updateBanner.css";

/**
 * "A new version is ready" with a Refresh button. The service worker (vite-plugin-pwa, autoUpdate) installs a new version in the
 * background and takes over the page, but the open page keeps running the old code until it reloads. Never reloads by itself:
 * that could throw away a half-filled form.
 */
export function UpdateBanner() {
  const t = useTranslations("update");
  const [ready, setReady] = useState(false);
  const [hidden, setHidden] = useState(false);
  useEffect(() => {
    const sw = "serviceWorker" in navigator ? navigator.serviceWorker : null;
    if (!sw) return;
    const hadController = !!sw.controller;   // the very first install also changes the controller: nothing to replace then
    const onChange = () => { if (hadController) setReady(true); };
    const check = () => {
      if (document.visibilityState === "visible") sw.getRegistration().then((r) => r?.update()).catch(() => {});
    };
    sw.addEventListener("controllerchange", onChange);
    document.addEventListener("visibilitychange", check);
    return () => { sw.removeEventListener("controllerchange", onChange); document.removeEventListener("visibilitychange", check); };
  }, []);
  if (!ready || hidden) return null;
  return (
    <div className="update-banner" role="status">
      <span className="update-text">{t("ready")}</span>
      <button type="button" className="update-later" onClick={() => setHidden(true)}>{t("later")}</button>
      <button type="button" className="update-go" onClick={() => window.location.reload()}>{t("refresh")}</button>
    </div>
  );
}
