import { useState } from "react";
import { useTranslations } from "use-intl";
import { Icon, useDialog } from "../App";
import { dismissInstallHelp, isStandalone, promptInstall, useInstallState, type InstallPlatform } from "../lib/install";
import "./install.css";
import { Icon3D } from "./Icon3D";
import { SlimNotice } from "./SlimNotice";

const Steps = ({ platform }: { platform: InstallPlatform }) => {
  const t = useTranslations("install");
  const key = platform === "ios" ? "ios" : platform === "android" ? "android" : "other";
  return <ol className="install-steps">{[1, 2, 3].map((n) => <li key={n}>{t(`${key}Step${n}`)}</li>)}</ol>;
};

/** How to add Chhaon to the home screen. Opened from the banner and from the Family tab. */
export function InstallSheet({ onClose }: { onClose: () => void }) {
  const t = useTranslations("install");
  const dialog = useDialog(onClose);
  const { platform, canPrompt } = useInstallState();
  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <section className="sheet" ref={dialog} tabIndex={-1} role="dialog" aria-modal="true" aria-label={t("sheetTitle")} onClick={(event) => event.stopPropagation()}>
        <h3>{t("sheetTitle")}</h3>
        <p className="sheet-hint">{t("sheetBody")}</p>
        {canPrompt && <button className="primary-button" onClick={() => void promptInstall().then((ok) => ok && onClose())}>{t("add")}</button>}
        <Steps platform={platform} />
        <div className="stack"><button className="secondary-button" onClick={onClose}>{t("done")}</button></div>
      </section>
    </div>
  );
}

/** A small, dismissible card on Today for phones that have not installed the app yet. Hidden once installed, and for 14 days after "Not now". */
export function InstallBanner() {
  const t = useTranslations("install");
  const { canPrompt, show } = useInstallState();
  const [help, setHelp] = useState(false);
  if (!show) return help ? <InstallSheet onClose={() => setHelp(false)} /> : null;
  return (
    <>
      <SlimNotice tone="info" role="note" title={t("bannerTitle")}>
        <p>{t("bannerBody")}</p>
        <div className="sn-actions">
          {canPrompt
            ? <button className="sn-primary" onClick={() => void promptInstall()}>{t("add")}</button>
            : <button className="sn-primary" onClick={() => setHelp(true)}>{t("how")}</button>}
          <button onClick={dismissInstallHelp}>{t("notNow")}</button>
        </div>
      </SlimNotice>
      {help && <InstallSheet onClose={() => setHelp(false)} />}
    </>
  );
}

/** The Family tab entry, so the help is still there after "Not now". Hidden on desktop and once installed. */
export function InstallLink() {
  const t = useTranslations("install");
  const { platform } = useInstallState();
  const [open, setOpen] = useState(false);
  if (platform === "other" || isStandalone()) return null;
  return (
    <>
      <button className="feature-link" onClick={() => setOpen(true)}>
        <Icon3D name="install" />
        <div><strong>{t("linkTitle")}</strong><p>{t("linkSub")}</p></div>
        <Icon name="chevron" />
      </button>
      {open && <InstallSheet onClose={() => setOpen(false)} />}
    </>
  );
}

/** The round indicator that follows the finger while pulling to refresh. */
export function PullIndicator({ pull, refreshing }: { pull: number; refreshing: boolean }) {
  const t = useTranslations("install");
  if (pull <= 0 && !refreshing) return null;
  return (
    <div className="pull-indicator" role="status" aria-label={refreshing ? t("refreshing") : undefined} style={{ transform: `translate(-50%, ${pull - 44}px)`, opacity: Math.min(1, pull / 40) }}>
      <span className={refreshing ? "spin" : ""} style={refreshing ? undefined : { transform: `rotate(${pull * 4}deg)` }}><Icon name="refresh" size={20} /></span>
    </div>
  );
}
