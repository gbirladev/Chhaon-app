import { useId, useState, type ReactNode } from "react";
import { useTranslations } from "use-intl";
import { Icon } from "../App";
import "./todayTidy.css";

export type NoticeTone = "warn" | "danger" | "info";

/**
 * A warning or notice on Today as ONE slim line: a small flat icon, the key words, and "More". "More" opens the full text and its
 * buttons in place (a real button with aria-expanded / aria-controls), so the warnings never push Today's medicines off the screen.
 */
export function SlimNotice({ tone = "warn", title, role = "status", label, defaultOpen = false, children }: {
  tone?: NoticeTone;
  title: ReactNode;
  role?: "alert" | "status" | "note";
  /** Accessible name of the region when the title is not plain text. */
  label?: string;
  /** Start opened: for the one notice the family must act on now (waiting for the parent's yes). */
  defaultOpen?: boolean;
  /** The full text and the buttons, shown under the line after "More". */
  children?: ReactNode;
}) {
  const t = useTranslations("slimNotice");
  const [open, setOpen] = useState(defaultOpen);
  const id = useId();
  return (
    <section className={`slim-notice sn-${tone}${open ? " open" : ""}`} role={role} aria-label={label}>
      <div className="sn-line">
        <span className="sn-icon" aria-hidden="true"><Icon name={tone === "info" ? "info" : "warning"} size={18} strokeWidth={2} /></span>
        <strong className="sn-title">{title}</strong>
        {children && (
          <button type="button" className="sn-more" aria-expanded={open} aria-controls={id} onClick={() => setOpen((v) => !v)}>
            {t(open ? "less" : "more")}<Icon name="down" size={14} strokeWidth={2.2} />
          </button>
        )}
      </div>
      {children && <div id={id} className="sn-body" hidden={!open}>{children}</div>}
    </section>
  );
}
