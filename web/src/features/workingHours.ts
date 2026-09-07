import type { WorkingDay, WorkingHours } from '../api/types';

export const dayNames = ['Pazartesi', 'Salı', 'Çarşamba', 'Perşembe', 'Cuma', 'Cumartesi', 'Pazar'];
export type DayDraft = { dayOfWeek: number; closed: boolean; startTime: string; endTime: string };
export function fromHours(rows: WorkingHours[]): DayDraft[] {
  return dayNames.map((_, dayOfWeek) => {
    const row = rows.find(item => item.dayOfWeek === dayOfWeek);
    return { dayOfWeek, closed: !row, startTime: row?.startTime ?? '09:00', endTime: row?.endTime ?? '18:00' };
  });
}
export function weekPayload(days: DayDraft[]): WorkingDay[] {
  return days.map(day => ({ dayOfWeek: day.dayOfWeek, closed: day.closed,
    startTime: day.closed ? null : normalizeTime(day.startTime), endTime: day.closed ? null : normalizeTime(day.endTime) }));
}
function normalizeTime(time: string) { return time.length === 5 ? `${time}:00` : time; }
export function weekErrors(days: DayDraft[]) {
  const errors: Record<number, string> = {};
  for (const day of days) {
    if (day.closed) continue;
    if (![day.startTime, day.endTime].every(time => /^([01]\d|2[0-3]):[0-5]\d(:[0-5]\d)?$/.test(time))) errors[day.dayOfWeek] = 'Başlangıç ve bitiş saatlerini girin.';
    else if (normalizeTime(day.startTime) >= normalizeTime(day.endTime)) errors[day.dayOfWeek] = 'Bitiş saati başlangıçtan sonra olmalı.';
  }
  return errors;
}
