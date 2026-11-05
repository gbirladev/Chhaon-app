import { useEffect, useState } from "react";
import type { Recipient } from "./api";
import { isPregnancy } from "./pregnancy";
import { isChild, loadChild, type ChildInfo } from "./vaccines";
import { keyFactLines, pregnancyWeek, todayOn, type FactsT, type KeyFacts } from "./keyFacts";

/*
 * Key facts at the top of the emergency card and the doctor summary (round 2, R3), for the signed-in app: a pregnancy's week and due
 * date, a child's date of birth, age and last recorded weight. Facts the family entered, shown as they are: no judgement, no advice.
 */

/** The child's details from the backend (date of birth, guardian confirmation, weight); null while loading or for anyone else. */
export function useChildInfo(recipient: Recipient | null) {
  const child = isChild(recipient);
  const id = recipient?.id;
  const [state, setState] = useState<{ id: string; info: ChildInfo | null } | null>(null);
  useEffect(() => {
    if (!id || !child) return;
    let alive = true;
    loadChild(id).then((info) => { if (alive) setState({ id, info }); }).catch(() => { if (alive) setState({ id, info: null }); });
    return () => { alive = false; };
  }, [id, child]);
  const info = child && state && state.id === id ? state.info : null;
  return { info, set: (next: ChildInfo) => { if (id) setState({ id, info: next }); } };
}

/** The facts for this person, in the person's own time zone. */
export function keyFactsOf(recipient: Recipient | null, info: ChildInfo | null): { facts: KeyFacts; today: string } {
  const today = todayOn(recipient?.timezone);
  const due = (recipient as unknown as { due_date?: string | null } | null)?.due_date ?? null;
  return {
    today,
    facts: {
      pregnancy: isPregnancy(recipient) ? { week: pregnancyWeek(due, today), dueDate: due } : null,
      child: isChild(recipient) ? { born: info?.dob ?? recipient?.dob ?? null, weightKg: info?.weightKg ?? null, weightOn: info?.weightOn ?? null } : null,
    },
  };
}

/** The lines in a given language (a translator for that language's "facts" messages). */
export function factLines(recipient: Recipient | null, info: ChildInfo | null, tf: FactsT, language: string): string[] {
  const { facts, today } = keyFactsOf(recipient, info);
  return keyFactLines(facts, tf, language, today);
}
