type InstallPromptEvent = Event & { prompt: () => Promise<void>; userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }> };

let deferred: InstallPromptEvent | null = null;
const listeners = new Set<() => void>();
const DISMISS_KEY = 'berber.install.dismissedAt';
const DISMISS_DAYS = 30;

/** `beforeinstallprompt` sayfa yüklenirken erken gelir; bileşenler monte olmadan önce yakalanır. */
export function captureInstallPrompt() {
  if (typeof window === 'undefined') return;
  window.addEventListener('beforeinstallprompt', event => {
    event.preventDefault();
    deferred = event as InstallPromptEvent;
    listeners.forEach(listener => listener());
  });
  window.addEventListener('appinstalled', () => {
    deferred = null;
    listeners.forEach(listener => listener());
  });
}

export function subscribeInstall(listener: () => void) {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

export function canPromptInstall() { return deferred !== null; }

export async function promptInstall() {
  const event = deferred;
  if (!event) return 'unavailable' as const;
  deferred = null;
  await event.prompt();
  const choice = await event.userChoice;
  listeners.forEach(listener => listener());
  return choice.outcome;
}

export function installDismissed(now = Date.now()) {
  try {
    const value = Number(localStorage.getItem(DISMISS_KEY));
    return Number.isFinite(value) && value > 0 && now - value < DISMISS_DAYS * 86_400_000;
  } catch {
    return false;
  }
}

export function dismissInstall(now = Date.now()) {
  try { localStorage.setItem(DISMISS_KEY, String(now)); } catch { /* özel pencere: yalnızca bu oturumda gizlenir */ }
}
