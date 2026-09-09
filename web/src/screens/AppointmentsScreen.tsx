import { useEffect, useRef, useState } from 'react';
import '../calendar.css';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import axios, { CanceledError } from 'axios';
import { Link } from 'react-router';
import { Button, Card } from '../components/ui';
import { Modal } from '../components/Modal';
import { AppointmentCalendar } from '../components/AppointmentCalendar';
import { AppointmentBooking, type BookingDraft } from '../components/AppointmentBooking';
import { useStaff, useServices } from '../query/management';
import { useTenant } from '../store/tenant';
import { errorMessage } from '../api/errors';
import { addDays, calendarDays, clock, dateInZone, localDate, requestKey, statusLabels, type CalendarRow } from '../features/calendar';
import * as api from '../api/appointments';
export { localDate, requestKey } from '../features/calendar';
type Operation={type:'create';draft:BookingDraft;key:string}|{type:'patch';row:api.Appointment;patch:{version:number;startsAt?:string;status?:api.AppointmentStatus;notes?:string}};
export function AppointmentsScreen() {
  const {tenantId,userId}=useTenant(), client=useQueryClient();
  const staff=useStaff(),services=useServices();
  const [date,setDate]=useState(localDate),[view,setView]=useState<'day'|'week'>('day'),[filter,setFilter]=useState('');
  const [draft,setDraft]=useState<BookingDraft|null>(null),[detail,setDetail]=useState<api.Appointment|null>(null);
  const [detailNotes,setDetailNotes]=useState('');
  const [message,setMessage]=useState(''),[error,setError]=useState('');
  const [checking,setChecking]=useState(false);
  const [dropProposal,setDropProposal]=useState<{row:api.Appointment;startsAt:string}|null>(null);
  const latch=useRef(false), mounted=useRef(true), dropController=useRef<AbortController|null>(null);
  useEffect(()=>{mounted.current=true;return()=>{mounted.current=false;dropController.current?.abort();};},[]);
  const days=calendarDays(date,view), from=new Date(days[0]+'T00:00:00').toISOString(),to=new Date(addDays(days[days.length-1],1)+'T00:00:00').toISOString();
  const root=['tenant',tenantId,'appointments'],key=[...root,from,to];
  const storageKey='saas.appointment-request:'+tenantId+':'+userId;
  const list=useQuery({
    queryKey:key,
    queryFn:async({signal}):Promise<CalendarRow[]>=>{
      const result=await api.listAppointments(from,to,'',signal);
      const pending=client.getQueryData<CalendarRow[]>(key)?.filter(r=>r.pending)??[];
      return [...result.filter(r=>!pending.some(p=>p.id===r.id)),...pending];
    },
  });
  const mutation=useMutation({
    mutationFn:async(op:Operation)=>{
      if(op.type==='create'){
        const {staffId,serviceId,startsAt,customerName,customerPhone,notes,customerEmail,emailNotifications}=op.draft;
        return api.createAppointment({staffId,serviceId,startsAt,customerName,customerPhone,notes,...(customerEmail?.trim()?{customerEmail:customerEmail.trim(),emailNotifications:!!emailNotifications}:{})},op.key);
      }
      return api.patchAppointment(op.row.id,op.patch);
    },
    onMutate:async(op:Operation)=>{
      const query=client.getQueryCache().find({queryKey:key,exact:true});
      await client.cancelQueries({queryKey:root});
      if(!query||client.getQueryCache().find({queryKey:key,exact:true})!==query)throw new CanceledError();
      let optimistic:CalendarRow;
      if(op.type==='create'){
        const service=services.data?.find(s=>s.id===op.draft.serviceId);
        const start=Date.parse(op.draft.startsAt);
        optimistic={...op.draft,id:'pending-'+op.key,endsAt:new Date(start+(service?.durationMin??30)*60000).toISOString(),
          busyUntil:new Date(start+((service?.durationMin??30)+(service?.bufferMin??0))*60000).toISOString(),status:'CONFIRMED',version:0,pending:true};
      } else {
        const delta=op.patch.startsAt?Date.parse(op.patch.startsAt)-Date.parse(op.row.startsAt):0;
        optimistic={...op.row,...op.patch,endsAt:new Date(Date.parse(op.row.endsAt)+delta).toISOString(),busyUntil:new Date(Date.parse(op.row.busyUntil)+delta).toISOString(),pending:true};
      }
      client.setQueryData<CalendarRow[]>(key,rows=>[...(rows??[]).filter(r=>r.id!==optimistic.id),optimistic]);
      return {id:optimistic.id,key,query};
    },
    onSuccess:(saved,op,context)=>{
      if(!context||client.getQueryCache().find({queryKey:context.key,exact:true})!==context.query)return;
      client.setQueryData<CalendarRow[]>(context.key,rows=>rows?[...rows.filter(r=>r.id!==context.id&&r.id!==saved.id),saved]:rows);
      if(op.type==='create')sessionStorage.removeItem(storageKey);
    },
    onError:(_e,op,context)=>{
      if(!context||client.getQueryCache().find({queryKey:context.key,exact:true})!==context.query)return;
      client.setQueryData<CalendarRow[]>(context.key,rows=>rows?.flatMap(r=>r.id===context.id?(op.type==='patch'?[op.row]:[]):[r]));
    },
    onSettled:()=>{
      if(mounted.current){
        void client.invalidateQueries({queryKey:root});
        void client.invalidateQueries({queryKey:['tenant',tenantId,'availability']});
      }
    },
  });
  const busy=mutation.isPending||checking;
  function failure(e:unknown) {
    if(axios.isAxiosError(e)&&e.response?.status===409&&e.response.data?.code==='SLOT_UNAVAILABLE')
      return 'Bu saat az önce doldu. Müsait saatleri yeniledik; lütfen başka bir saat seçin.';
    return errorMessage(e,'İşlem kaydedilemedi. Bilgileriniz korundu; tekrar deneyebilirsiniz.');
  }
  async function run(op:Operation) {
    if(latch.current)return;
    latch.current=true;setError('');setMessage('');
    // Close the editor so the pending block is visible immediately in the calendar.
    setDraft(null);setDetail(null);setDropProposal(null);
    try{
      await mutation.mutateAsync(op);
      if(mounted.current)setMessage(op.type==='create'?'Randevu oluşturuldu.':'Randevu güncellendi.');
    }catch(e){
      if(mounted.current&&!axios.isCancel(e)){
        setError(failure(e));
        if(op.type==='create')setDraft({...op.draft,startsAt:axios.isAxiosError(e)&&e.response?.status===409?'':op.draft.startsAt});
        else {setDetail(op.row);setDetailNotes(op.row.notes??'');}
      }
    }finally{latch.current=false;}
  }
  function save(d:BookingDraft) {
    if(latch.current)return;
    setFilter(d.staffId);
    if(d.editing){void run({type:'patch',row:d.editing,patch:{version:d.editing.version,startsAt:d.startsAt,notes:d.notes}});return;}
    try{
      const {staffId,serviceId,startsAt,customerName,customerPhone,notes,customerEmail,emailNotifications}=d;
      const id=requestKey(storageKey,JSON.stringify({staffId,serviceId,startsAt,customerName,customerPhone,notes,...(customerEmail?.trim()?{customerEmail:customerEmail.trim(),emailNotifications:!!emailNotifications}:{})}));
      void run({type:'create',draft:d,key:id});
    }catch(e){setError(errorMessage(e,'Tarayıcı kayıt anahtarını saklayamadı. Depolama iznini kontrol edin.'));}
  }
  function open(row:api.Appointment){setDetail(row);setDetailNotes(row.notes??'');setError('');}
  function move(row:api.Appointment){
    setDetail(null);setError('');
    setDraft({staffId:row.staffId,serviceId:row.serviceId,startsAt:'',customerName:row.customerName??'',customerPhone:row.customerPhone??'',customerEmail:row.customerEmail??'',emailNotifications:row.emailNotifications,notes:row.notes??'',date:localDate(new Date(row.startsAt)),editing:row});
  }
  async function drop(row:api.Appointment,target:Date){
    if(latch.current||busy)return;
    setChecking(true);setError('');
    const controller=new AbortController();dropController.current=controller;
    try{
      // Read the tenant zone first; calendar coordinates are in the browser's zone.
      const meta=await api.availability(row.staffId,row.serviceId,localDate(target),controller.signal,row.id);
      const tenantDay=dateInZone(target,meta.timezone);
      const available=tenantDay===meta.date?meta:await api.availability(row.staffId,row.serviceId,tenantDay,controller.signal,row.id);
      if(controller.signal.aborted)return;
      const nearest=[...available.slots].sort((a,b)=>Math.abs(Date.parse(a.startsAt)-target.getTime())-Math.abs(Date.parse(b.startsAt)-target.getTime()))[0];
      if(!nearest) {setError('Bıraktığınız gün müsait saat yok. Randevu yerinde kaldı.');return;}
      if(Date.parse(nearest.startsAt)===Date.parse(row.startsAt)){setMessage('Randevu zaten en yakın müsait saatte.');return;}
      setDropProposal({row,startsAt:nearest.startsAt});
    }catch(e){if(!controller.signal.aborted)setError(failure(e));}
    finally{if(!controller.signal.aborted)setChecking(false);}
  }
  const rows=list.data??[],visible=rows.filter(r=>!filter||r.staffId===filter);
  const currentDetail=detail?(rows.find(r=>r.id===detail.id)??detail):null;
  const stale=!!detail&&!!currentDetail&&detail.version!==currentDetail.version;
  return <div className="appointments-page">
    <div className="page-heading calendar-heading"><div><p className="eyebrow">ZAMANINIZ, BİR ARADA</p><h1>Randevu takvimi</h1><p className="muted">Ekibinizin gününü planlayın. Her randevuya yer açın.</p></div>
      <Button disabled={busy||!staff.data?.length||!services.data?.length||list.isPending} onClick={()=>{setError('');setDraft({date,staffId:filter,serviceId:'',startsAt:'',customerName:'',customerPhone:'',notes:''});}}>+ Yeni randevu</Button></div>
    <div className="calendar-toolbar">
      <div className="calendar-controls"><Button variant="quiet" aria-label="Önceki dönem" disabled={busy} onClick={()=>setDate(addDays(date,view==='day'?-1:-7))}>←</Button>
        <label className="field">Takvim tarihi<input type="date" value={date} disabled={busy} onChange={e=>{if(e.target.value)setDate(e.target.value);}}/></label>
        <Button variant="quiet" aria-label="Sonraki dönem" disabled={busy} onClick={()=>setDate(addDays(date,view==='day'?1:7))}>→</Button>
        <Button variant="quiet" disabled={busy} onClick={()=>setDate(localDate())}>Bugün</Button></div>
      <div className="calendar-switch" aria-label="Takvim görünümü">{(['day','week'] as const).map(v=><button key={v} type="button" disabled={busy} aria-pressed={view===v} onClick={()=>setView(v)}>{v==='day'?'Gün':'Hafta'}</button>)}</div>
    </div>
    <div className="staff-tabs" role="group" aria-label="Personel filtresi">
      <button aria-pressed={!filter} disabled={busy} onClick={()=>setFilter('')}>Hepsi</button>
      {staff.data?.map((p,i)=><button key={p.id} aria-pressed={filter===p.id} disabled={busy} onClick={()=>setFilter(p.id)}><span className="staff-dot" style={{background:['#367e70','#6279b5','#b77b45','#9870a8'][i%4]}}/>{p.displayName}</button>)}
    </div>
    <div className="calendar-summary"><span>{visible.filter(r=>r.status!=='CANCELLED').length} randevu</span><span>Saat dilimi: {Intl.DateTimeFormat().resolvedOptions().timeZone}</span><span>Masaüstünde sürükleyin veya detaydan Taşı'yı seçin.</span></div>
    {message&&<p role="status" className="success-notice">{message}</p>}
    {error&&!draft&&!detail&&<p role="alert" className="form-error">{error}</p>}
    {mutation.isPending&&<p role="status" className="pending-notice">Randevu kaydediliyor… Soluk blok sunucu onayını bekliyor.</p>}
    {checking&&<p role="status">Bırakılan konum için müsait saat kontrol ediliyor…</p>}
    {(staff.isPending||services.isPending||list.isPending)&&<p role="status">Takvim yükleniyor…</p>}
    {(staff.isError||services.isError||list.isError)&&<div className="error-notice" role="alert">Takvim verileri yüklenemedi. <Button onClick={()=>{void staff.refetch();void services.refetch();void list.refetch();}}>Tekrar dene</Button></div>}
    {staff.isSuccess&&services.isSuccess&&(!staff.data.length||!services.data.length)&&<Card><h2>Önce personel ve hizmet ekleyin</h2><p><Link to="/app/staff">Personel</Link> ve <Link to="/app/services">hizmet</Link> ekleyin, ardından <Link to="/app/working-hours">çalışma saatlerini</Link> belirleyin.</p></Card>}
    <div className="calendar-legend">{Object.entries(statusLabels).map(([s,label])=><span key={s}><i className={'legend-'+s.toLowerCase()}/>{label}</span>)}<span><i className="legend-pending"/>Bekliyor</span></div>
    {list.data&&<AppointmentCalendar days={days} view={view} rows={rows} staff={staff.data??[]} services={services.data??[]} filter={filter} busy={busy} onOpen={open} onDrop={(r,d)=>void drop(r,d)}/>}
    {list.isSuccess&&!visible.length&&<p className="muted">Bu dönemde randevu yok. Yeni randevu ile takviminizi doldurun.</p>}
    {draft&&<AppointmentBooking initial={draft} staff={staff.data??[]} services={(services.data??[]).filter(s=>!s.optimistic)} onClose={()=>setDraft(null)} onSave={save} onDate={setDate} busy={busy} error={error}/>}
    {detail&&currentDetail&&<Modal title="Randevu detayı" onClose={()=>setDetail(null)} busy={busy}>
      <div className="appointment-detail"><span className={'detail-status event-'+currentDetail.status.toLowerCase()}>{statusLabels[currentDetail.status]}</span>
        <h3>{currentDetail.customerName||'İsimsiz müşteri'}</h3><p>{currentDetail.customerPhone||'Telefon belirtilmedi'}</p>
        <p>{currentDetail.customerEmail||'E-posta belirtilmedi'} · {currentDetail.emailNotifications?'Müşteri e-posta bildirimi açık':'Müşteri e-posta bildirimi kapalı'}</p>
        <dl><dt>Hizmet</dt><dd>{services.data?.find(s=>s.id===currentDetail.serviceId)?.name??'Hizmet'}</dd><dt>Personel</dt><dd>{staff.data?.find(s=>s.id===currentDetail.staffId)?.displayName??'Personel'}</dd>
          <dt>Tarih ve saat</dt><dd>{new Date(currentDetail.startsAt).toLocaleDateString('tr-TR')} · {clock(currentDetail.startsAt)}–{clock(currentDetail.endsAt)}</dd><dt>Tampon bitişi</dt><dd>{clock(currentDetail.busyUntil)}</dd></dl>
        <label className="field">Randevu notu<textarea maxLength={500} disabled={busy||currentDetail.status!=='CONFIRMED'||stale} value={detailNotes} onChange={e=>setDetailNotes(e.target.value)}/></label>
        {error&&<p role="alert" className="form-error">{error}</p>}
        {stale&&<p role="alert">Randevu değişti. <Button onClick={()=>open(currentDetail)}>Güncel kaydı yükle</Button></p>}
        {currentDetail.status==='CONFIRMED'&&<div className="detail-actions">
          <Button disabled={busy||stale||detailNotes===(detail.notes??'')} onClick={()=>void run({type:'patch',row:detail,patch:{version:detail.version,notes:detailNotes}})}>Notu kaydet</Button>
          <Button variant="quiet" disabled={busy||stale} onClick={()=>move(currentDetail)}>Taşı</Button>
          {(['COMPLETED','NO_SHOW','CANCELLED'] as const).map(status=><Button key={status} variant="quiet" disabled={busy||stale||(status!=='CANCELLED'&&new Date(currentDetail.startsAt)>new Date())}
            onClick={()=>void run({type:'patch',row:detail,patch:{version:detail.version,status}})}>{status==='CANCELLED'?'İptal et':statusLabels[status]}</Button>)}
        </div>}
      </div>
    </Modal>}
    {dropProposal&&<Modal title="Randevuyu yeni saate taşı" busy={busy} onClose={()=>setDropProposal(null)}>
      <p>Bıraktığınız konuma en yakın müsait saat:</p><p><strong>{new Date(dropProposal.startsAt).toLocaleString('tr-TR')}</strong></p>
      <p className="muted">Önceki saat: {new Date(dropProposal.row.startsAt).toLocaleString('tr-TR')}. Personel ve hizmet aynı kalır.</p>
      <div className="modal-actions"><Button variant="quiet" onClick={()=>setDropProposal(null)}>Vazgeç</Button><Button onClick={()=>void run({type:'patch',row:dropProposal.row,patch:{version:dropProposal.row.version,startsAt:dropProposal.startsAt}})}>Taşımayı onayla</Button></div>
    </Modal>}
  </div>;
}
