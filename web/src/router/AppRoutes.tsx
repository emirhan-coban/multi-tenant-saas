import { Link, Navigate, Outlet, Route, Routes, useLocation } from 'react-router';
import { AuthScreen } from '../screens/AuthScreen';
import { StaffScreen } from '../screens/StaffScreen';
import { ServicesScreen } from '../screens/ServicesScreen';
import { AppScreen } from '../screens/AppScreen';
import { useAuth } from '../store/auth';
import { TenantProvider, useTenant } from '../store/tenant';
import { PanelLayout } from '../layouts/PanelLayout';
import { AccountScreen } from '../screens/AccountScreen';
import { WorkingHoursScreen } from '../screens/WorkingHoursScreen';
import { AppointmentsScreen } from '../screens/AppointmentsScreen';
import { AuditScreen, ReportsScreen, PlanScreen, NotificationsScreen } from '../screens/OperationsScreens';
import { canAccess, managementRoles, memberRoles } from './navigation';
import type { Role } from '../api/types';
import { useCurrentUser } from '../query/management';
import { PageSkeleton } from '../components/PageSkeleton';
import { Button, Card } from '../components/ui';

function RequireSession() {
  const { ready, session } = useAuth();
  const location = useLocation();
  if (!ready) return <PageSkeleton />;
  if (!session) return <Navigate to="/login" state={{ from: location.pathname + location.search + location.hash }} replace />;
  return <VerifiedSession />;
}

function VerifiedSession() {
  const { logout } = useAuth();
  const me = useCurrentUser();
  if (me.isPending) return <PageSkeleton />;
  if (me.isError) return <main className="app-shell"><Card>
    <h2>Çalışma alanı yüklenemedi</h2>
    <p role="alert">Oturum bilgileri alınamadı. Lütfen tekrar deneyin.</p>
    <Button onClick={() => void me.refetch()} disabled={me.isFetching}>{me.isFetching ? 'Yükleniyor…' : 'Tekrar dene'}</Button>{' '}
    <Button variant="quiet" onClick={logout}>Oturumu kapat</Button>
  </Card></main>;
  return <TenantProvider user={me.data}><Outlet /></TenantProvider>;
}

function LoginRoute() {
  const { ready, session } = useAuth();
  const location = useLocation();
  if (!ready) return <PageSkeleton />;
  const from: unknown = location.state?.from;
  const destination = typeof from === 'string' && (from === '/app' || from.startsWith('/app/') || from.startsWith('/app?') || from.startsWith('/app#')) ? from : '/app';
  return session ? <Navigate to={destination} replace /> : <AuthScreen />;
}

function RequireRole({ allowed }: { allowed: readonly Role[] }) {
  const { role } = useTenant();
  const { logout } = useAuth();
  if (canAccess(role, allowed)) return <Outlet />;
  return <section className="access-denied"><Card><p className="eyebrow">ERİŞİM KISITLI</p><h1>Bu sayfaya erişiminiz yok</h1>
    <p>Bu alanı görüntülemek için yönetim yetkisi gerekiyor.</p>
    {canAccess(role, memberRoles) ? <Link className="text-link" to="/app">Genel bakışa dön</Link> : <Button variant="quiet" onClick={logout}>Oturumu kapat</Button>}
  </Card></section>;
}

function NotFound({ insidePanel = false }: { insidePanel?: boolean }) {
  return <section className={insidePanel ? undefined : "app-shell"}><Card><h2>Sayfa bulunamadı</h2>
    <p className="muted">Bu adres mevcut bir sayfaya ait değil.</p>
    <Link to="/app">Çalışma alanına dön</Link>
  </Card></section>;
}

export function AppRoutes() {
  return <Routes>
    <Route path="/" element={<Navigate to="/app" replace />} />
    <Route path="/login" element={<LoginRoute />} />
    <Route path="/app" element={<RequireSession />}>
      <Route element={<RequireRole allowed={memberRoles} />}>
        <Route element={<PanelLayout />}>
          <Route index element={<AppScreen />} />
          <Route path="account" element={<AccountScreen />} />
          <Route path="notifications" element={<NotificationsScreen />} />
          <Route element={<RequireRole allowed={managementRoles} />}>
            <Route path="services" element={<ServicesScreen />} />
            <Route path="staff" element={<StaffScreen />} />
            <Route path="working-hours" element={<WorkingHoursScreen />} />
            <Route path="appointments" element={<AppointmentsScreen />} />
            <Route path="reports" element={<ReportsScreen />} />
            <Route path="audit-log" element={<AuditScreen />} />
            <Route path="plan" element={<PlanScreen />} />
          </Route>
          <Route path="*" element={<NotFound insidePanel />} />
        </Route>
      </Route>
    </Route>
    <Route path="*" element={<NotFound />} />
  </Routes>;
}
