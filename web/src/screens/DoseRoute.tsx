import { useMemo, type ReactNode } from "react";
import "./route.css";

export type RouteStop = {
  id: string;
  time: string;       // "8:00"
  suffix: string;     // "AM"
  name: string;
  dose: string;
  state: "done" | "missed" | "current" | "skipped" | "todo";
  pill: ReactNode;
  icon: ReactNode;
  tod?: number;       // minutes since midnight on the parent's clock
  at?: number;        // scheduled time in ms: the distance on the road follows the time between doses
};

const ROW = 76;               // height of one stop, px
const MIN_GAP = 76, MAX_GAP = 118;  // road length between two doses, px
const TOP = 48, BOTTOM = 60;  // room for the start and finish caps
const MID = 50;               // the road starts and ends in the middle (% of the width)

/** Small seeded random generator (mulberry32). */
function rng(seed: string) {
  let h = 1779033703 ^ seed.length;
  for (let i = 0; i < seed.length; i++) { h = Math.imul(h ^ seed.charCodeAt(i), 3432918353); h = (h << 13) | (h >>> 19); }
  let a = h >>> 0;
  return () => { a = (a + 0x6d2b79f5) >>> 0; let t = Math.imul(a ^ (a >>> 15), 1 | a); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
}

const Sun = () => <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><circle cx="12" cy="12" r="4.2" fill="currentColor" /><path d="M12 2v2.5M12 19.5V22M2 12h2.5M19.5 12H22M4.9 4.9l1.8 1.8M17.3 17.3l1.8 1.8M4.9 19.1l1.8-1.8M17.3 6.7l1.8-1.8" /></svg>;
const Flag = () => <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M5 21V4" /><path d="M5 4h11l-2 4 2 4H5" fill="currentColor" /></svg>;


/**
 * Today's doses drawn as a winding route from the start of the day to the end of it.
 * Solid glowing green where doses were taken, dotted ahead, and the parent's avatar marks the next stop.
 */
export function DoseRoute({ stops, avatar, startLabel, endLabel, nextLabel, curvy }: { stops: RouteStop[]; avatar: string; startLabel: string; endLabel: string; nextLabel: string; curvy?: boolean }) {
  const n = stops.length;
  // A different winding road every day (and per set of doses), like a route on a map: seeded, so it never jumps while you look at it.
  const atKey = stops.map((st) => st.at ?? 0).join(",");
  const seed = `${new Date().toDateString()}|${stops.map((st) => st.id).join(",")}`;
  const layout = useMemo(() => {
    const rand = rng(seed);
    type Node = { x: number; y: number; stop?: number };
    const nodes: Node[] = [{ x: MID, y: TOP / 2 }];
    const stopNode: number[] = [];
    let y = TOP + ROW / 2, side = 0;
    for (let i = 0; i < n; i++) {
      if (i > 0) {
        // the road is longer between doses that are far apart in time, and it meanders between stops
        const minutes = Math.max(0, ((stops[i].at ?? 0) - (stops[i - 1].at ?? 0)) / 60000);
        const gap = Math.min(MAX_GAP, Math.max(MIN_GAP, MIN_GAP + minutes * 0.2));
        const nextSide = 1 - side;
        y += gap;
        side = nextSide;   // always alternate left, right, left, right: never two medicines on the same side in a row
      }
      nodes.push({ x: curvy ? (side === 0 ? 20 + rand() * 14 : 66 + rand() * 14) : side === 0 ? 32 + rand() * 7 : 61 + rand() * 7, y, stop: i });
      stopNode.push(nodes.length - 1);
    }
    const height = y + ROW / 2 + BOTTOM;
    nodes.push({ x: MID, y: height - BOTTOM / 2 });
    return { nodes, stopNode, height };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [seed, n, atKey, curvy]);
  const { nodes, stopNode, height } = layout;
  // a smooth curve through the nodes (Catmull-Rom turned into Bezier curves): no sharp S-bends, it flows like a real road
  const k = curvy ? 4.2 : 6;
  const curve = (from: number, to: number) => {
    let d = `M ${nodes[from].x.toFixed(1)} ${nodes[from].y.toFixed(1)}`;
    for (let i = from; i < to; i++) {
      const p0 = nodes[i - 1] ?? nodes[i], p1 = nodes[i], p2 = nodes[i + 1], p3 = nodes[i + 2] ?? nodes[i + 1];
      d += ` C ${(p1.x + (p2.x - p0.x) / k).toFixed(1)} ${(p1.y + (p2.y - p0.y) / k).toFixed(1)}, ${(p2.x - (p3.x - p1.x) / k).toFixed(1)} ${(p2.y - (p3.y - p1.y) / k).toFixed(1)}, ${p2.x.toFixed(1)} ${p2.y.toFixed(1)}`;
    }
    return d;
  };
  const endNode = nodes.length - 1;

  let lastDone = -1;
  stops.forEach((s, i) => { if (s.state === "done") lastDone = i; });
  const allDone = n > 0 && stops.every((s) => s.state === "done" || s.state === "skipped") && lastDone >= 0;
  const doneNode = allDone ? endNode : lastDone >= 0 ? stopNode[lastDone] : 0;
  const nextIndex = stops.findIndex((s) => s.state === "current" || s.state === "todo");

  return (
    <div className="dose-route" style={{ height }}>
      <svg viewBox={`0 0 100 ${height}`} preserveAspectRatio="none" aria-hidden="true">
        <path className="route-casing" d={curve(0, endNode)} vectorEffect="non-scaling-stroke" />
        <path className="route-ahead" d={curve(0, endNode)} vectorEffect="non-scaling-stroke" />
        {doneNode > 0 && <path className="route-done" d={curve(0, doneNode)} vectorEffect="non-scaling-stroke" />}
        {doneNode > 0 && doneNode < endNode && lastDone >= 0 && lastDone < n - 1 && <path className="route-next" d={curve(doneNode, stopNode[lastDone + 1])} vectorEffect="non-scaling-stroke" />}
      </svg>

      <div className="route-cap start" style={{ top: 0, height: TOP }}>
        <span className="route-cap-dot sun" style={{ left: `${MID}%` }}><Sun /></span>
        <span className="route-cap-label" style={{ left: `calc(${MID}% + 26px)` }}>{startLabel}</span>
      </div>

      <ol>
        {stops.map((s, i) => {
          const p = nodes[stopNode[i]];
          const onLeft = p.x < MID;
          const isNext = i === nextIndex;
          return (
            <li key={s.id} className={`route-stop ${s.state}${isNext ? " next" : ""}`} style={{ top: p.y - ROW / 2, height: ROW }}>
              <span className="route-marker" style={{ left: `${p.x}%` }} aria-hidden="true"><span className="pin-icon">{isNext ? <b>{avatar}</b> : s.icon}</span></span>
              {isNext && <span className="route-flag" style={{ left: `${p.x}%` }} aria-hidden="true">{nextLabel}</span>}
              <div className={`route-card ${onLeft ? "right" : "left"}`}>
                <h3><span className="route-name">{s.name}</span>{s.dose && <small className="route-dose">{s.dose}</small>}</h3>
                <p className="route-meta"><strong>{s.time}</strong> <small>{s.suffix}</small><span className="sr-only"> {s.pill}</span></p>
              </div>
            </li>
          );
        })}
      </ol>

      <div className={`route-cap end${allDone ? " won" : ""}`} style={{ bottom: 0, height: BOTTOM }}>
        <span className="route-cap-dot flag" style={{ left: `${MID}%` }}><Flag /></span>
        <span className="route-cap-label" style={{ left: `calc(${MID}% + 26px)` }}>{endLabel}</span>
      </div>
    </div>
  );
}
