import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button } from './ui';
import { Modal } from './Modal';
import { useTenant } from '../store/tenant';
import { errorMessage } from '../api/errors';
import { addDays, clock, localDate } from '../features/calendar';
import * as api from '../api/appointments';
import type { Staff, Service } from '../api/types';
export type BookingDraft=api.CreateAppointment & {date:string;editing?:api.Appointment};
export function AppointmentBooking({initial,staff,services,onClose,onSave,onDate,busy,error}:{
  initial:BookingDraft;staff:Staff[];services:Service[];onClose:()=>void;onSave:(draft:BookingDraft)=>void;onDate:(date:string)=>void;busy:boolean;error:string;
}) {
  const {tenantId}=useTenant();
  const [draft,setDraft]=useState(initial);
  const [searching,setSearching]=useState(false), [searchMessage,setSearchMessage]=useState('');
  const controller=useRef<AbortController|null>(null);
  const identity=draft.staffId+draft.serviceId+draft.date;
  useEffect(()=>{controller.current?.abort();setSearching(false);setSearchMessage('');return()=>controller.current?.abort();},[identity]);
  const available=useQuery({
    queryKey:['tenant',tenantId,'availability',draft.staffId,draft.serviceId,draft.date,draft.editing?.id],
    queryFn:({signal})=>api.availability(draft.staffId,draft.serviceId,draft.date,signal,draft.editing?.id),
    enabled:!!draft.staffId&&!!draft.serviceId&&!!draft.date,
  });
  function update(patch:Partial<BookingDraft>) {setDraft(d=>({...d,...patch,startsAt:''}));if(patch.date)onDate(patch.date);}
  async function nearest() {
    controller.current?.abort();const search=new AbortController();controller.current=search;
    setSearching(true);setSearchMessage('');
    try {
      for(let i=1;i<=30;i++){
        const date=addDays(draft.date,i);
        const result=await api.availability(draft.staffId,draft.serviceId,date,search.signal,draft.editing?.id);
        if(search.signal.aborted)return;
        if(result.slots.length){setDraft(d=>({...d,date,startsAt:''}));onDate(date);return;}
      }
      setSearchMessage('Önümüzdeki 30 gün içinde müsait saat bulunamadı.');
    } catch(e){if(!search.signal.aborted)setSearchMessage(errorMessage(e,'Müsait gün aranamadı. Tekrar deneyin.'));}
    finally{if(!search.signal.aborted)setSearching(false);}
  }
  function submit(e:FormEvent) {e.preventDefault();if(!busy&&draft.startsAt&&available.data?.slots.some(s=>s.startsAt===draft.startsAt))onSave(draft);}
  function selectSlot(startsAt:string) {
    setDraft(d=>({...d,startsAt}));
    onDate(localDate(new Date(startsAt)));
  }
  return <Modal title={draft.editing?'Randevuyu taşı':'Yeni randevu'} onClose={onClose} busy={busy}>
    <p className="muted">Hizmet → personel → tarih → müsait saat</p>
    <form onSubmit={submit}><fieldset disabled={busy||searching} className="booking-fields">
      <label className="field">1. Hizmet<select required value={draft.serviceId} disabled={!!draft.editing} onChange={e=>update({serviceId:e.target.value})}><option value="">Hizmet seçin</option>{services.map(s=><option key={s.id} value={s.id}>{s.name} · {s.durationMin} dk</option>)}</select></label>
      <label className="field">2. Personel<select required value={draft.staffId} disabled={!!draft.editing||!draft.serviceId} onChange={e=>update({staffId:e.target.value})}><option value="">Personel seçin</option>{staff.map(p=><option key={p.id} value={p.id}>{p.displayName}</option>)}</select></label>
      <label className="field">3. Tarih<input type="date" required value={draft.date} onChange={e=>{if(e.target.value)update({date:e.target.value});}}/></label>
      {!!draft.staffId&&!!draft.serviceId&&<section className="booking-slots" aria-label="Müsait saatler">
        <p className="eyebrow">4. MÜSAİT SAAT</p>
        {available.isFetching&&<p role="status">Müsait saatler güncelleniyor…</p>}
        {available.isError&&<p role="alert">{errorMessage(available.error,'Müsaitlik yüklenemedi.')} <Button type="button" onClick={()=>void available.refetch()}>Saatleri yenile</Button></p>}
        {available.isSuccess&&<><p className="muted">{available.data.timezone} · {draft.date}</p><div className="slot-grid">{available.data.slots.map(s=><button key={s.startsAt} type="button" className="slot-button" aria-pressed={draft.startsAt===s.startsAt} disabled={available.isFetching} onClick={()=>selectSlot(s.startsAt)}>{clock(s.startsAt,available.data.timezone)} – {clock(s.endsAt,available.data.timezone)}</button>)}</div>
          {!available.data.slots.length&&<div className="calendar-empty"><p>Bu gün müsait saat yok.</p><Button type="button" variant="quiet" onClick={()=>void nearest()}>En yakın müsait günü bul</Button><small>Sonraki 30 gün taranır.</small></div>}
        </>}
      </section>}
      <label className="field">Müşteri adı<input maxLength={200} value={draft.customerName} disabled={!!draft.editing} onChange={e=>setDraft(d=>({...d,customerName:e.target.value}))}/></label>
      <label className="field">Telefon<input type="tel" maxLength={40} value={draft.customerPhone} disabled={!!draft.editing} onChange={e=>setDraft(d=>({...d,customerPhone:e.target.value}))}/></label>
      <label className="field">Müşteri e-posta adresi<input type="email" maxLength={254} required={!!draft.emailNotifications} value={draft.customerEmail??''} disabled={!!draft.editing} onChange={e=>setDraft(d=>({...d,customerEmail:e.target.value}))}/></label>
      <label className="notification-toggle"><input type="checkbox" checked={!!draft.emailNotifications} disabled={!!draft.editing} onChange={e=>setDraft(d=>({...d,emailNotifications:e.target.checked}))}/><span>Müşteriye randevu e-postaları gönder</span></label>
      <p className="muted">Onay, saat değişikliği, iptal ve 24 saat önce hatırlatma. 24 saatten yakın randevularda yalnızca onay gönderilir. Gönderim için Gmail ayarlarının açık olması gerekir; test modunda yalnızca gönderici Gmail adresine gönderilir.</p>
      <label className="field">Not<textarea maxLength={500} value={draft.notes} onChange={e=>setDraft(d=>({...d,notes:e.target.value}))}/></label>
    </fieldset>
    {searching&&<p role="status">En yakın müsait gün aranıyor… <Button type="button" variant="quiet" onClick={()=>{controller.current?.abort();setSearching(false);}}>Aramayı durdur</Button></p>}
    {searchMessage&&<p role="status">{searchMessage}</p>}
    {error&&<p role="alert" className="form-error">{error}</p>}
    <div className="modal-actions"><Button type="button" variant="quiet" disabled={busy} onClick={onClose}>Vazgeç</Button><Button disabled={busy||searching||available.isFetching||available.isError||!available.data?.slots.some(s=>s.startsAt===draft.startsAt)}>{busy?'Kaydediliyor…':draft.editing?'Yeni saate taşı':'Randevu oluştur'}</Button></div>
    </form>
  </Modal>;
}
