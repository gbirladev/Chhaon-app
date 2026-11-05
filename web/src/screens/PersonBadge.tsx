import { useState } from "react";
import { useTranslations } from "use-intl";
import { initialOf, type PersonKind } from "../lib/person";
import "./personBadge.css";

/**
 * One avatar for a person the family cares for, used wherever that person appears (switcher, Today header, Family, activity,
 * notifications): the initial of the name actually shown (the call name: "Mummy" -> M), a colour per care type, and a small 3D
 * icon at the corner (Microsoft Fluent Emoji 3D, MIT, web/public/icons/3d/) with the plain emoji as a fallback.
 */
const ICONS: Record<PersonKind, { src: string; emoji: string }> = {
  older: { src: "/icons/3d/old-man.webp", emoji: "👴" },
  pregnant: { src: "/icons/3d/pregnant-woman.webp", emoji: "🤰" },
  child: { src: "/icons/3d/baby.webp", emoji: "👶" },
  self: { src: "/icons/3d/person-raising-hand.webp", emoji: "🙋" },
};

export function PersonBadge({ name, kind, size = "md", lazy = false, className = "" }: { name: string; kind: PersonKind; size?: "sm" | "md" | "lg"; lazy?: boolean; className?: string }) {
  const t = useTranslations("person");
  const [failed, setFailed] = useState(false);
  const icon = ICONS[kind];
  return (
    <span className={`person-badge ${kind} ${size} ${className}`.trim()}>
      <span className="person-initial" aria-hidden="true">{initialOf(name)}</span>
      <span className="person-type" role="img" aria-label={t(kind)}>
        {failed ? <span className="person-emoji" aria-hidden="true">{icon.emoji}</span>
          : <img src={icon.src} alt="" width={64} height={64} loading={lazy ? "lazy" : undefined} decoding="async" onError={() => setFailed(true)} />}
      </span>
    </span>
  );
}
