import type { ReactNode } from "react";
import { Icon } from "../App";
import { Icon3D, type Icon3DName } from "./Icon3D";
import "./featureEntry.css";

/** How a Family-page entry is drawn: a full card with a line of explanation, a square tile, or a slim row in the "More" list. */
export type EntryLook = "card" | "tile" | "row";

export function FeatureEntry({ look = "card", icon, title, sub, onClick, className = "" }: {
  look?: EntryLook;
  icon: Icon3DName;
  title: ReactNode;
  /** Shown on the card only; a tile or row is the title alone (the screen itself explains). */
  sub?: ReactNode;
  onClick: () => void;
  className?: string;
}) {
  if (look === "tile") {
    return (
      <button type="button" className={`feature-tile ${className}`.trim()} onClick={onClick}>
        <Icon3D name={icon} size={36} />
        <span>{title}</span>
      </button>
    );
  }
  if (look === "row") {
    return (
      <button type="button" className={`more-row ${className}`.trim()} onClick={onClick}>
        <Icon3D name={icon} size={24} />
        <span>{title}</span>
        <Icon name="chevron" size={18} />
      </button>
    );
  }
  return (
    <button type="button" className={`feature-link ${className}`.trim()} onClick={onClick}>
      <Icon3D name={icon} />
      <div><strong>{title}</strong>{sub && <p>{sub}</p>}</div>
      <Icon name="chevron" />
    </button>
  );
}
