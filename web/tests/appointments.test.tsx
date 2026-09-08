import { cleanup,fireEvent,render,screen,waitFor,act,within } from '@testing-library/react';
import { QueryClient,QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { afterEach,beforeEach,beforeAll,expect,test,vi } from 'vitest';
import { TenantProvider } from '../src/store/tenant';
import { AppointmentsScreen } from '../src/screens/AppointmentsScreen';
import { positions,calendarDays,addDays,localDate,requestKey } from '../src/features/calendar';
import { AppointmentCalendar } from '../src/components/AppointmentCalendar';
import * as appointments from '../src/api/appointments';
import * as management from '../src/api/management';
vi.mock('../src/api/appointments',()=>({listAppointments:vi.fn(),availability:vi.fn(),createAppointment:vi.fn(),patchAppointment:vi.fn()}));
vi.mock('../src/api/management',()=>({getStaff:vi.fn(),getServices:vi.fn()}));
const first='2030-01-07T09:00:00Z';
const row:appointments.Appointment={id:'one',staffId:'staff',serviceId:'service',startsAt:first,endsAt:'2030-01-07T09:30:00Z',busyUntil:'2030-01-07T09:40:00Z',status:'CONFIRMED',customerName:'Test',customerPhone:'555',notes:'Örnek not',version:3};
const people=[{id:'staff',displayName:'Deniz',active:true},{id:'other',displayName:'Ece',active:true}];
const offerings=[{id:'service',name:'Kesim',durationMin:30,bufferMin:10,price:100}];
const slots={timezone:'UTC',date:'2030-01-07',slots:[{startsAt:first,endsAt:row.endsAt}]};
function mount() {
 const client=new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}});
 const rendered=render(<QueryClientProvider client={client}><TenantProvider user={{userId:'user',tenantId:'tenant',role:'OWNER'}}><MemoryRouter><AppointmentsScreen/></MemoryRouter></TenantProvider></QueryClientProvider>);
 return {client,...rendered};
}
beforeAll(()=>{
 HTMLDialogElement.prototype.showModal=function(){this.setAttribute('open','');};
 HTMLDialogElement.prototype.close=function(){this.removeAttribute('open');};
});
beforeEach(()=>{
 vi.resetAllMocks();sessionStorage.clear();
 vi.mocked(management.getStaff).mockResolvedValue(people);
 vi.mocked(management.getServices).mockResolvedValue(offerings);
 vi.mocked(appointments.listAppointments).mockResolvedValue([]);
 vi.mocked(appointments.availability).mockResolvedValue(slots);
 vi.mocked(appointments.createAppointment).mockImplementation(async input=>({...row,...input}));
 vi.mocked(appointments.patchAppointment).mockResolvedValue({...row,version:4});
});
afterEach(cleanup);
async function choose() {
 const open=await screen.findByRole('button',{name:'+ Yeni randevu'});
 await waitFor(()=>expect(open.hasAttribute('disabled')).toBe(false));fireEvent.click(open);
 fireEvent.change(screen.getByLabelText('1. Hizmet'),{target:{value:'service'}});
 fireEvent.change(screen.getByLabelText('2. Personel'),{target:{value:'staff'}});
 fireEvent.change(screen.getByLabelText('3. Tarih'),{target:{value:'2030-01-07'}});
 fireEvent.click(await screen.findByRole('button',{name:'09:00 – 09:30'}));
}
async function showExisting() {
 vi.mocked(appointments.listAppointments).mockResolvedValue([row]);
 mount();fireEvent.change(screen.getByLabelText('Takvim tarihi'),{target:{value:'2030-01-07'}});
 return screen.findByRole('button',{name:/Test randevu detayı/});
}
test('slot button sends UTC and UUID header key',async()=>{
 mount();await choose();fireEvent.change(screen.getByLabelText('Müşteri adı'),{target:{value:'Ada'}});
 fireEvent.click(screen.getByRole('button',{name:'Randevu oluştur'}));
 await screen.findByText('Randevu oluşturuldu.');
 expect(appointments.createAppointment).toHaveBeenCalledWith(expect.objectContaining({startsAt:first,customerName:'Ada'}),expect.stringMatching(/^[0-9a-f-]{36}$/));
});
test('customer email requires explicit opt-in and is sent with the booking',async()=>{
 mount();await choose();
 const checkbox=screen.getByRole('checkbox',{name:'Müşteriye randevu e-postaları gönder'});
 expect((checkbox as HTMLInputElement).checked).toBe(false);
 fireEvent.change(screen.getByLabelText('Müşteri e-posta adresi'),{target:{value:'customer@example.test'}});
 fireEvent.click(checkbox);
 expect((screen.getByLabelText('Müşteri e-posta adresi') as HTMLInputElement).required).toBe(true);
 fireEvent.click(screen.getByRole('button',{name:'Randevu oluştur'}));
 await screen.findByText('Randevu oluşturuldu.');
 expect(appointments.createAppointment).toHaveBeenCalledWith(expect.objectContaining({customerEmail:'customer@example.test',emailNotifications:true}),expect.any(String));
});
test('email is optional when notifications are not selected',async()=>{
 mount();await choose();
 expect((screen.getByLabelText('Müşteri e-posta adresi') as HTMLInputElement).required).toBe(false);
 fireEvent.click(screen.getByRole('button',{name:'Randevu oluştur'}));
 await screen.findByText('Randevu oluşturuldu.');
 const input=vi.mocked(appointments.createAppointment).mock.calls[0][0];
 expect(input.emailNotifications).not.toBe(true);
});
test('pending calendar block appears immediately and rolls back on network failure',async()=>{
 let reject!:(e:Error)=>void;
 vi.mocked(appointments.createAppointment).mockReturnValue(new Promise((_,r)=>{reject=r;}));
 const {container}=mount();await choose();fireEvent.change(screen.getByLabelText('Müşteri adı'),{target:{value:'Ada'}});
 fireEvent.click(screen.getByRole('button',{name:'Randevu oluştur'}));
 await waitFor(()=>expect(container.querySelector('.event-pending')).not.toBeNull());
 expect(screen.queryByRole('dialog')).toBeNull();
 expect(appointments.createAppointment).toHaveBeenCalledOnce();
 await act(async()=>reject(new Error('offline')));
 await screen.findByRole('dialog');
 expect(container.querySelector('.event-pending')).toBeNull();
 expect((screen.getByLabelText('Müşteri adı') as HTMLInputElement).value).toBe('Ada');
});
test('network retry reuses key and successful response replaces pending block',async()=>{
 vi.mocked(appointments.createAppointment).mockRejectedValueOnce(new Error('offline'));
 vi.mocked(appointments.listAppointments).mockImplementation(async()=>vi.mocked(appointments.createAppointment).mock.calls.length>=2?[row]:[]);
 mount();await choose();fireEvent.click(screen.getByRole('button',{name:'Randevu oluştur'}));
 await screen.findByRole('alert');
 await waitFor(()=>expect(screen.getByRole('button',{name:'Randevu oluştur'}).hasAttribute('disabled')).toBe(false));
 fireEvent.click(screen.getByRole('button',{name:'Randevu oluştur'}));
 await screen.findByText('Randevu oluşturuldu.');
 expect(vi.mocked(appointments.createAppointment).mock.calls[0][1]).toBe(vi.mocked(appointments.createAppointment).mock.calls[1][1]);
});
test('409 rolls back, refreshes availability and requires a new slot choice',async()=>{
 vi.mocked(appointments.createAppointment).mockRejectedValue({isAxiosError:true,response:{status:409,data:{code:'SLOT_UNAVAILABLE'}}});
 mount();await choose();const calls=vi.mocked(appointments.availability).mock.calls.length;
 fireEvent.click(screen.getByRole('button',{name:'Randevu oluştur'}));
 await screen.findByText(/Bu saat az önce doldu/);
 expect(screen.getByRole('button',{name:'Randevu oluştur'}).hasAttribute('disabled')).toBe(true);
 await waitFor(()=>expect(vi.mocked(appointments.availability).mock.calls.length).toBeGreaterThan(calls));
});
test('service/date changes clear old selected slot',async()=>{
 mount();await choose();fireEvent.change(screen.getByLabelText('3. Tarih'),{target:{value:'2030-01-08'}});
 expect(screen.getByRole('button',{name:'Randevu oluştur'}).hasAttribute('disabled')).toBe(true);
 await waitFor(()=>expect(screen.getByRole('button',{name:'09:00 – 09:30'}).getAttribute('aria-pressed')).toBe('false'));
});
test('nearest day searches sequentially and stops at first available date',async()=>{
 vi.mocked(appointments.availability).mockImplementation(async(_s,_h,date)=>({...slots,date,slots:date==='2030-01-09'?slots.slots:[]}));
 mount();const open=await screen.findByRole('button',{name:'+ Yeni randevu'});
 await waitFor(()=>expect(open.hasAttribute('disabled')).toBe(false));fireEvent.click(open);
 fireEvent.change(screen.getByLabelText('3. Tarih'),{target:{value:'2030-01-07'}});
 fireEvent.change(screen.getByLabelText('1. Hizmet'),{target:{value:'service'}});
 fireEvent.change(screen.getByLabelText('2. Personel'),{target:{value:'staff'}});
 fireEvent.click(await screen.findByRole('button',{name:'En yakın müsait günü bul'}));
 await waitFor(()=>expect((screen.getByLabelText('3. Tarih') as HTMLInputElement).value).toBe('2030-01-09'));
 expect(vi.mocked(appointments.availability).mock.calls.some(c=>c[2]==='2030-01-10')).toBe(false);
});
test('details show customer and notes, cancellation includes version',async()=>{
 const button=await showExisting();fireEvent.click(button);
 await screen.findByRole('dialog',{name:'Randevu detayı'});
 expect(screen.getByText('555')).toBeTruthy();
 expect((screen.getByLabelText('Randevu notu') as HTMLTextAreaElement).value).toBe('Örnek not');
 fireEvent.click(screen.getByRole('button',{name:'İptal et'}));
 await screen.findByText('Randevu güncellendi.');
 expect(appointments.patchAppointment).toHaveBeenCalledWith('one',{version:3,status:'CANCELLED'});
});
test('move dialog excludes itself from availability and preserves version',async()=>{
 fireEvent.click(await showExisting());fireEvent.click(screen.getByRole('button',{name:'Taşı'}));
 fireEvent.click(await screen.findByRole('button',{name:'09:00 – 09:30'}));
 fireEvent.click(screen.getByRole('button',{name:'Yeni saate taşı'}));
 await screen.findByText('Randevu güncellendi.');
 expect(appointments.availability).toHaveBeenCalledWith('staff','service',expect.any(String),expect.any(AbortSignal),'one');
 expect(appointments.patchAppointment).toHaveBeenCalledWith('one',{version:3,startsAt:first,notes:'Örnek not'});
});
test('staff tabs filter blocks and week view requests seven-day range',async()=>{
 const button=await showExisting();expect(button).toBeTruthy();
 fireEvent.click(within(screen.getByRole('group',{name:'Personel filtresi'})).getByRole('button',{name:'Ece'}));
 expect(screen.queryByRole('button',{name:/Test randevu detayı/})).toBeNull();
 fireEvent.click(screen.getByRole('button',{name:'Hafta',exact:true}));
 await waitFor(()=>expect(vi.mocked(appointments.listAppointments).mock.calls.some(c=>(Date.parse(c[1])-Date.parse(c[0]))===7*86400000)).toBe(true));
});
test('cancelled blocks remain visible but cannot be dragged',()=>{
 const {container}=render(<AppointmentCalendar days={['2030-01-07']} view="day" rows={[{...row,status:'CANCELLED'}]} staff={people} services={offerings} filter="" busy={false} onOpen={vi.fn()} onDrop={vi.fn()}/>);
 const button=container.querySelector('.event-cancelled')!;
 expect(button).not.toBeNull();expect(button.getAttribute('draggable')).toBe('false');
});
test('dragging onto same staff invokes target date; other staff is rejected',()=>{
 const onDrop=vi.fn();
 const {container}=render(<AppointmentCalendar days={['2030-01-07']} view="day" rows={[row]} staff={people} services={offerings} filter="" busy={false} onOpen={vi.fn()} onDrop={onDrop}/>);
 const dataTransfer={setData:vi.fn(),effectAllowed:''};
 const button=screen.getByRole('button',{name:/Test randevu detayı/});
 const columns=container.querySelectorAll('.calendar-day');
 fireEvent.dragStart(button,{dataTransfer});fireEvent.drop(columns[1],{clientY:90,dataTransfer});
 expect(onDrop).not.toHaveBeenCalled();
 fireEvent.dragStart(button,{dataTransfer});fireEvent.drop(columns[0],{clientY:90,dataTransfer});
 expect(onDrop).toHaveBeenCalledWith(row,expect.any(Date));
});
test('overlapping blocks use separate lanes; adjacent blocks share lane',()=>{
 const overlapping={...row,id:'two',startsAt:'2030-01-07T09:15:00Z',endsAt:'2030-01-07T09:45:00Z'};
 const p=positions([row,overlapping],'2030-01-07');expect(p[0].lane).not.toBe(p[1].lane);
 expect(positions([row,{...row,id:'two',startsAt:row.endsAt,endsAt:'2030-01-07T10:00:00Z'}],'2030-01-07').every(p=>p.lane===0)).toBe(true);
});
test('week begins Monday and dates cross year safely',()=>{
 expect(calendarDays('2030-01-06','week')[0]).toBe('2029-12-31');
 expect(addDays('2029-12-31',1)).toBe('2030-01-01');
 expect(localDate(new Date(2030,0,7,12))).toBe('2030-01-07');
});
test('request key survives identical retry and changes for new body',()=>{
 const k=requestKey('test','one');expect(requestKey('test','one')).toBe(k);expect(requestKey('test','two')).not.toBe(k);
});
test('missing prerequisites show setup links',async()=>{
 vi.mocked(management.getStaff).mockResolvedValue([]);mount();
 await screen.findByText('Önce personel ve hizmet ekleyin');
 expect(screen.getByRole('link',{name:'çalışma saatlerini'}).getAttribute('href')).toBe('/app/working-hours');
});
