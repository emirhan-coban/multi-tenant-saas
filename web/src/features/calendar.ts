import type { Appointment } from '../api/appointments';
export type CalendarRow = Appointment & { pending?: boolean };
export const statusLabels = { CONFIRMED:'Onaylandı', COMPLETED:'Tamamlandı', CANCELLED:'İptal edildi', NO_SHOW:'Gelmedi' };
export function localDate(date=new Date()) {
  return String(date.getFullYear())+'-'+String(date.getMonth()+1).padStart(2,'0')+'-'+String(date.getDate()).padStart(2,'0');
}
export function addDays(date:string,amount:number) {
  const d=new Date(date+'T12:00:00'); d.setDate(d.getDate()+amount); return localDate(d);
}
export function calendarDays(date:string,view:'day'|'week') {
  if(view==='day') return [date];
  const weekday=new Date(date+'T12:00:00').getDay();
  const monday=addDays(date,-((weekday+6)%7));
  return Array.from({length:7},(_,i)=>addDays(monday,i));
}
export function clock(value:string,timezone?:string) {
  return new Date(value).toLocaleTimeString('tr-TR',{hour:'2-digit',minute:'2-digit',timeZone:timezone});
}
export function dateInZone(value:Date,timezone:string) {
  const parts=new Intl.DateTimeFormat('en-CA',{year:'numeric',month:'2-digit',day:'2-digit',timeZone:timezone}).formatToParts(value);
  return ['year','month','day'].map(k=>parts.find(p=>p.type===k)!.value).join('-');
}
export function minutes(value:string) { const d=new Date(value); return d.getHours()*60+d.getMinutes(); }
export function dayEvents(rows:CalendarRow[],day:string) {
  const from=new Date(day+'T00:00:00').getTime(), to=new Date(addDays(day,1)+'T00:00:00').getTime();
  return rows.filter(r=>Date.parse(r.startsAt)<to && Date.parse(r.endsAt)>from);
}
export function positions(rows:CalendarRow[],day:string) {
  const start=new Date(day+'T00:00:00').getTime(), end=new Date(addDays(day,1)+'T00:00:00').getTime();
  const sorted=dayEvents(rows,day).map(row=>({
    row, start:Date.parse(row.startsAt)<=start?0:minutes(row.startsAt),
    end:Date.parse(row.endsAt)>=end?1440:minutes(row.endsAt),
  })).sort((a,b)=>a.start-b.start || a.end-b.end || a.row.id.localeCompare(b.row.id));
  const lanes:number[]=[];
  const laid=sorted.map(item=>{
    // Minimum visual height participates in collision layout too.
    const displayEnd=Math.max(item.end,item.start+24);
    let lane=lanes.findIndex(end=>end<=item.start);
    if(lane===-1)lane=lanes.length;
    lanes[lane]=displayEnd;
    return {...item,lane,displayEnd};
  });
  return laid.map(item=>({...item,lanes:Math.max(1,lanes.length)}));
}
export function requestKey(storageKey:string,payload:string) {
  try { const saved=JSON.parse(sessionStorage.getItem(storageKey)??'null'); if(saved?.payload===payload && typeof saved.key==='string')return saved.key as string; } catch {}
  const key=crypto.randomUUID(); sessionStorage.setItem(storageKey,JSON.stringify({payload,key})); return key;
}

