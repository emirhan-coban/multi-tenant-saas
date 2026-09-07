import { useRef, type DragEvent } from 'react';
import type { Staff, Service } from '../api/types';
import { clock, positions, type CalendarRow } from '../features/calendar';

type Props={days:string[];view:'day'|'week';rows:CalendarRow[];staff:Staff[];services:Service[];filter:string;busy:boolean;
  onOpen:(row:CalendarRow)=>void;onDrop:(row:CalendarRow,target:Date)=>void};
export function AppointmentCalendar({days,view,rows,staff,services,filter,busy,onOpen,onDrop}:Props) {
  const dragged=useRef<CalendarRow|null>(null);
  const people=staff.filter(s=>!filter||s.id===filter);
  const columns=view==='day'
    ? (people.length?people:[{id:filter,displayName:'Randevular'}]).map(s=>({id:s.id,day:days[0],title:s.displayName,staffId:s.id}))
    : days.map(day=>({id:day,day,title:new Date(day+'T12:00:00').toLocaleDateString('tr-TR',{weekday:'short',day:'numeric',month:'short'}),staffId:filter}));
  const visible=rows.filter(r=>!filter||r.staffId===filter);
  const placed=columns.map(c=>positions(visible.filter(r=>!c.staffId||r.staffId===c.staffId),c.day));
  const first=Math.min(540,...placed.flat().map(p=>Math.floor(p.start/60)*60));
  const last=Math.min(1440,Math.max(1080,...placed.flat().map(p=>Math.ceil(p.displayEnd/60)*60)));
  const height=(last-first)*1.5;
  function drop(event:DragEvent<HTMLDivElement>,column:typeof columns[number]) {
    event.preventDefault();
    const row=dragged.current; dragged.current=null;
    if(!row||busy||(column.staffId&&column.staffId!==row.staffId))return;
    const rect=event.currentTarget.getBoundingClientRect();
    const minute=Math.max(first,Math.min(last-1,Math.round((first+(event.clientY-rect.top)/1.5)/5)*5));
    const target=new Date(column.day+'T00:00:00');target.setHours(Math.floor(minute/60),minute%60,0,0);
    onDrop(row,target);
  }
  return <div className="calendar-scroll" tabIndex={0} aria-label="Saat çizelgesi; yatay kaydırılabilir">
    <div className="calendar-grid" style={{gridTemplateColumns:`58px repeat(${columns.length}, minmax(${view==='week'?150:190}px, 1fr))`}}>
      <div className="calendar-corner">SAAT</div>{columns.map(c=><div className="calendar-column-title" key={c.id}>{c.title}</div>)}
      <div className="calendar-axis" style={{height}}>{Array.from({length:(last-first)/60+1},(_,i)=><span key={i} style={{top:i*90}}>{String(first/60+i).padStart(2,'0')}:00</span>)}</div>
      {columns.map((column,i)=><div key={column.id} className="calendar-day" style={{height}} data-calendar-day={column.day}
        onDragOver={e=>{if(dragged.current&&!busy&&(!column.staffId||column.staffId===dragged.current.staffId))e.preventDefault();}}
        onDrop={e=>drop(e,column)}>
        {placed[i].map(p=><button key={p.row.id} type="button" className={`calendar-event event-${p.row.status.toLowerCase()}${p.row.pending?' event-pending':''}`}
          style={{top:(p.start-first)*1.5,height:Math.max(34,(p.end-p.start)*1.5-3),left:`calc(${p.lane/p.lanes*100}% + 3px)`,width:`calc(${100/p.lanes}% - 6px)`}}
          draggable={!busy&&!p.row.pending&&p.row.status==='CONFIRMED'}
          onDragStart={e=>{dragged.current=p.row;e.dataTransfer.setData('text/plain',p.row.id);e.dataTransfer.effectAllowed='move';}}
          onDragEnd={()=>{dragged.current=null;}}
          disabled={!!p.row.pending} onClick={()=>onOpen(p.row)}
          aria-label={`${clock(p.row.startsAt)} ${p.row.customerName||'İsimsiz müşteri'} randevu detayı`}>
          <span className="event-time">{clock(p.row.startsAt)}–{clock(p.row.endsAt)}{p.row.pending?' · Kaydediliyor…':''}</span>
          <strong>{p.row.customerName||'İsimsiz müşteri'}</strong>
          <span>{services.find(s=>s.id===p.row.serviceId)?.name??'Hizmet'} · {staff.find(s=>s.id===p.row.staffId)?.displayName??'Personel'}</span>
        </button>)}
      </div>)}
    </div>
  </div>;
}

