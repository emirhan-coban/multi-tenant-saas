export function PageSkeleton() {
  return <main className="app-shell" aria-busy="true" aria-label="Çalışma alanı yükleniyor">
    <div className="skeleton skeleton-title" aria-hidden="true" />
    <div className="dashboard-grid" aria-hidden="true">
      <div className="skeleton skeleton-card" />
      <div className="skeleton skeleton-card" />
    </div>
    <span className="sr-only" role="status">Çalışma alanı yükleniyor…</span>
  </main>;
}
