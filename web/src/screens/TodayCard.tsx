import type { ReactNode } from "react";
import { Icon } from "../App";
import "./todayTidy.css";

export type CardTone = "plain" | "ok" | "warn" | "danger" | "love";

/**
 * The one icon style of the Today cards (check-in, next visit, next vaccine, alerts, love, activity): a small FLAT icon in a soft
 * tinted square. The tint comes from the theme tokens, so every colour theme and dark mode work. (3D icons stay elsewhere.)
 */
export function CardIcon({ name, tone = "plain", badge }: { name: string; tone?: CardTone; badge?: ReactNode }) {
  return (
    <span className={`tc-icon tc-${tone}`} aria-hidden="true">
      <Icon name={name} size={20} strokeWidth={2} />
      {badge !== undefined && <span className="tc-badge">{badge}</span>}
    </span>
  );
}
