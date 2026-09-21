import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import { errorMessage } from '../api/errors';
import { Button, Card } from '../components/ui';
import { useTenant } from '../store/tenant';

type Ticket={id:string;category:string;subject:string;message:string;status:string;assignedOwner:string;createdAt:string};
const labels:Record<string,string>={TECHNICAL:'Teknik sorun',BILLING:'Ödeme ve fatura',CANCELLATION:'Abonelik iptali',PRIVACY:'Gizlilik ve veri',OTHER:'Diğer'};
export function SupportScreen(){
  const {tenantId,role}=useTenant(), client=useQueryClient();
  const [form,setForm]=useState({category:'TECHNICAL',subject:'',message:''});
  const management=role==='OWNER'||role==='MANAGER';
  const tickets=useQuery({queryKey:['tenant',tenantId,'support'],queryFn:async()=>(await api.get<Ticket[]>('/api/support/requests')).data,enabled:management});
  const create=useMutation({mutationFn:async()=>(await api.post<Ticket>('/api/support/requests',form)).data,onSuccess:()=>{setForm({category:'TECHNICAL',subject:'',message:''});void client.invalidateQueries({queryKey:['tenant',tenantId,'support']});}});
  function submit(event:FormEvent){event.preventDefault();create.mutate();}
  return <><div className="page-heading"><p className="eyebrow">YARDIM</p><h1>Destek</h1><p>Teknik, ödeme, iptal veya gizlilik taleplerinizi kayıt altına alın.</p></div>
    <Card><h2>Yeni destek talebi</h2><form onSubmit={submit}><label className="field"><span>Konu türü</span><select value={form.category} onChange={event=>setForm({...form,category:event.target.value})}>{Object.entries(labels).map(([value,label])=><option key={value} value={value}>{label}</option>)}</select></label>
      <label className="field"><span>Başlık</span><input required maxLength={160} value={form.subject} onChange={event=>setForm({...form,subject:event.target.value})}/></label>
      <label className="field"><span>Açıklama</span><textarea required minLength={10} maxLength={4000} rows={6} value={form.message} onChange={event=>setForm({...form,message:event.target.value})}/></label>
      {create.isError&&<p className="form-error" role="alert">{errorMessage(create.error,'Talep kaydedilemedi.')}</p>}{create.isSuccess&&<p className="info-notice" role="status">Talebiniz kaydedildi. Destek sorumlusu inceleyecek.</p>}
      <Button disabled={create.isPending}>{create.isPending?'Kaydediliyor…':'Talebi gönder'}</Button></form></Card>
    {management&&<section className="operations-section"><h2>Son talepler</h2>{tickets.isPending&&<p role="status">Talepler yükleniyor…</p>}{tickets.isError&&<p role="alert">Talepler yüklenemedi.</p>}{tickets.data&&!tickets.data.length&&<p className="muted">Henüz destek talebi yok.</p>}
      {tickets.data?.map(ticket=><Card key={ticket.id}><div className="support-ticket-head"><strong>{ticket.subject}</strong><span className="role-badge">{ticket.status}</span></div><p>{ticket.message}</p><small>{labels[ticket.category]??ticket.category} · {new Date(ticket.createdAt).toLocaleString('tr-TR')} · Sorumlu: {ticket.assignedOwner}</small></Card>)}</section>}</>;
}
