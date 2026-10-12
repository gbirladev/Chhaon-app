export function ListSkeleton({ rows = 3 }: { rows?: number }) {
  return (
    <div className="stack" aria-busy="true" aria-live="polite">
      {Array.from({ length: rows }, (_, i) => <div key={i} className="skeleton card" />)}
    </div>
  );
}

export function PageSkeleton() {
  return (
    <main className="scroll-page no-nav" aria-busy="true">
      <div className="skeleton line" style={{ width: "55%", height: 28, marginBottom: 24 }} />
      <ListSkeleton />
    </main>
  );
}
