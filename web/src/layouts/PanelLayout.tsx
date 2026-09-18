import { useEffect, useRef, useState } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router';
import { Button } from '../components/ui';
import { OperationalFeedback } from '../screens/OperationsScreens';
import { useAuth } from '../store/auth';
import { useTenant } from '../store/tenant';
import { canAccess, navigation, roleLabels } from '../router/navigation';
import { OfflineBanner } from '../components/OfflineBanner';
import { disablePush } from '../pwa/push';

export function PanelLayout() {
  const { session, logout } = useAuth();
  const { tenantId, role } = useTenant();
  const tenantName = session?.memberships.find(item => item.tenantId === tenantId)?.tenantName ?? 'Çalışma alanı';
  const location = useLocation();
  const [menuOpen, setMenuOpen] = useState(false);
  const toggle = useRef<HTMLButtonElement>(null);
  const content = useRef<HTMLElement>(null);
  const previousPath = useRef(location.pathname);
  useEffect(() => {
    setMenuOpen(false);
    if (previousPath.current !== location.pathname) content.current?.focus();
    previousPath.current = location.pathname;
  }, [location.pathname]);
  return <div className="panel-layout" onKeyDown={event => {
    if (event.key === 'Escape' && menuOpen) { setMenuOpen(false); toggle.current?.focus(); }
  }}>
    <a className="skip-link" href="#panel-content">İçeriğe geç</a>
    <OperationalFeedback />
    <aside className="panel-sidebar">
      <div className="panel-brand"><span className="brand-symbol" aria-hidden="true">T</span><span>TENANT OS<small>Çalışma alanınız</small></span></div>
      <button ref={toggle} className="button button-quiet menu-toggle" aria-expanded={menuOpen} aria-controls="panel-menu" onClick={() => setMenuOpen(open => !open)}>{menuOpen ? 'Menüyü kapat' : 'Menüyü aç'}</button>
      <div id="panel-menu" className={`panel-menu${menuOpen ? ' is-open' : ''}`}>
        <p className="eyebrow nav-caption">ÇALIŞMA ALANI</p>
        <nav aria-label="Ana menü">
          {navigation.filter(item => canAccess(role, item.roles)).map(item => <NavLink key={item.path} to={item.path} end={item.path === '/app'} onClick={() => setMenuOpen(false)} className={({ isActive }) => `nav-link${isActive ? ' is-active' : ''}`}>
            <span className="nav-mark" aria-hidden="true">{item.mark}</span>{item.label}
          </NavLink>)}
        </nav>
        <div className="sidebar-footer"><span className="role-badge">{roleLabels[role]}</span><p>{tenantName}</p><Button variant="quiet" onClick={() => { void disablePush('staff').catch(() => undefined).finally(logout); }}>Oturumu kapat</Button></div>
      </div>
    </aside>
    <div className="panel-workspace">
      <header className="panel-topbar"><span>{tenantName}</span><span className="workspace-status"><span aria-hidden="true" />Çalışma alanı</span></header>
      <main id="panel-content" className="panel-content" ref={content} tabIndex={-1}><OfflineBanner message="İnternet bağlantısı yok. Gösterilen bilgiler güncel olmayabilir; değişiklikler kaydedilmez." /><Outlet /></main>
      <footer className="panel-footer">TENANT OS <span>İşinize odaklanmanız için.</span></footer>
    </div>
  </div>;
}
