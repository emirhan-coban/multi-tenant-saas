import { useEffect, useId, useRef, type ReactNode } from 'react';

export function Modal({ title, children, onClose, busy = false }: { title: string; children: ReactNode; onClose: () => void; busy?: boolean }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  useEffect(() => {
    const element = dialog.current!;
    const previous = document.activeElement as HTMLElement | null;
    if (typeof element.showModal === 'function') element.showModal();
    else element.setAttribute('open', '');
    element.querySelector<HTMLElement>('[data-autofocus="true"]')?.focus();
    return () => { if (typeof element.close === 'function') element.close(); else element.removeAttribute('open'); if (previous?.isConnected) previous.focus(); };
  }, []);
  return <dialog ref={dialog} className="modal" aria-labelledby={titleId} onCancel={event => { event.preventDefault(); if (!busy) onClose(); }}>
    <div className="modal-heading"><h2 id={titleId}>{title}</h2><button type="button" className="button button-quiet" aria-label="Pencereyi kapat" disabled={busy} onClick={onClose}>×</button></div>
    {children}
  </dialog>;
}
