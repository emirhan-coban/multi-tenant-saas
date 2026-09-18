/** 3D sahne ile rezervasyon ekranı arasında paylaşılan hafif yardımcılar (three.js içermez). */
export type ChairStatus = { state: 'loading' | 'available' | 'full'; firstSlot?: string };

export function initials(name: string) {
  return name.split(' ').filter(Boolean).map(part => part[0]).join('').slice(0, 2).toLocaleUpperCase('tr-TR');
}
