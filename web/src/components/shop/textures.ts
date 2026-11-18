import * as THREE from 'three';

/** Tekrarlanabilir (seed'li) rastgele sayı üreteci; dokular her yüklemede aynı görünür. */
function random(seed: number) {
  let value = seed >>> 0;
  return () => {
    value = (value + 0x6d2b79f5) >>> 0;
    let t = value;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function canvasTexture(size: number, draw: (ctx: CanvasRenderingContext2D, size: number) => void,
  options: { repeat?: [number, number]; color?: boolean; height?: number } = {}) {
  const canvas = document.createElement('canvas');
  canvas.width = size; canvas.height = options.height ?? size;
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('Canvas 2D desteklenmiyor');
  draw(ctx, size);
  const texture = new THREE.CanvasTexture(canvas);
  if (options.color !== false) texture.colorSpace = THREE.SRGBColorSpace;
  texture.wrapS = texture.wrapT = THREE.RepeatWrapping;
  texture.anisotropy = 8;
  if (options.repeat) texture.repeat.set(...options.repeat);
  return texture;
}

function speckles(ctx: CanvasRenderingContext2D, size: number, seed: number, count: number, colors: string[], min: number, max: number) {
  const rnd = random(seed);
  for (let i = 0; i < count; i++) {
    ctx.fillStyle = colors[Math.floor(rnd() * colors.length)];
    const x = rnd() * size, y = rnd() * size, r = min + Math.pow(rnd(), 3) * (max - min);
    ctx.beginPath();
    ctx.ellipse(x, y, r, r * (.55 + rnd() * .45), rnd() * Math.PI, 0, Math.PI * 2);
    ctx.fill();
  }
}

function clouds(ctx: CanvasRenderingContext2D, size: number, seed: number, count: number, light: string, dark: string) {
  const rnd = random(seed);
  for (let i = 0; i < count; i++) {
    const x = rnd() * size, y = rnd() * size, r = size * (.05 + rnd() * .25);
    const gradient = ctx.createRadialGradient(x, y, 0, x, y, r);
    gradient.addColorStop(0, rnd() > .5 ? light : dark);
    gradient.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.fillStyle = gradient;
    ctx.fillRect(x - r, y - r, r * 2, r * 2);
  }
}


/** Özgün, geometrik (Bauhaus esintili) çerçeve görselleri: telifli hiçbir eser kullanılmaz. */
const ART_PALETTES = [
  ['#e8d9b5', '#c8423a', '#f2b632', '#1f2a44'],
  ['#f1ead8', '#2c8aa6', '#e0653a', '#1b1b1b'],
  ['#1d2b3a', '#f2c14e', '#e4572e', '#f4ece0'],
  ['#efe4cf', '#3f7d5b', '#d9a441', '#2a2a2a'],
  ['#f3e9dc', '#b5382f', '#2d4a7a', '#e7b23c'],
  ['#222222', '#e9e2d2', '#c8423a', '#3a9ab5'],
  ['#e9dfc8', '#7a4a2e', '#d4883a', '#2f5d62'],
  ['#f0e6d2', '#d1495b', '#00798c', '#edae49'],
];

function artTexture(seed: number, palette: string[]) {
  return canvasTexture(256, (ctx, s) => {
    const rnd = random(seed * 977 + 13);
    ctx.fillStyle = palette[0]; ctx.fillRect(0, 0, s, s);
    const pick = () => palette[1 + Math.floor(rnd() * (palette.length - 1))];
    const shapes = 4 + Math.floor(rnd() * 3);
    for (let i = 0; i < shapes; i++) {
      const kind = Math.floor(rnd() * 5);
      const x = rnd() * s, y = rnd() * s, r = s * (.12 + rnd() * .26);
      ctx.fillStyle = pick();
      ctx.beginPath();
      if (kind === 0) ctx.arc(x, y, r, 0, Math.PI * 2);
      else if (kind === 1) ctx.arc(x, y, r, 0, Math.PI);
      else if (kind === 2) ctx.rect(x - r, y - r * .6, r * 2, r * 1.2);
      else if (kind === 3) { ctx.moveTo(x, y - r); ctx.lineTo(x + r, y + r); ctx.lineTo(x - r, y + r); ctx.closePath(); }
      else { for (let k = 0; k < 5; k++) ctx.rect(x - r, y - r + k * r * .4, r * 2, r * .18); }
      ctx.fill();
    }
    ctx.strokeStyle = palette[palette.length - 1]; ctx.lineWidth = 3;
    ctx.beginPath(); ctx.moveTo(0, rnd() * s); ctx.lineTo(s, rnd() * s); ctx.stroke();
    clouds(ctx, s, seed + 5, 6, 'rgba(255,255,255,.12)', 'rgba(0,0,0,.08)');
  });
}

export const ART_COUNT = ART_PALETTES.length;
export type ArtKey = `art${0 | 1 | 2 | 3 | 4 | 5 | 6 | 7}`;

export function createShopTextures(quality: 'high' | 'low') {
  const size = quality === 'high' ? 1024 : 512;
  const k = size / 1024;
  const terrazzo = canvasTexture(size, (ctx, s) => {
    ctx.fillStyle = '#aeaca6'; ctx.fillRect(0, 0, s, s);
    clouds(ctx, s, 3, 40, 'rgba(255,255,255,.07)', 'rgba(40,40,40,.06)');
    speckles(ctx, s, 11, 26000 * k * k, ['#6f6d69', '#b8b5ae', '#d6d3cc', '#55534f', '#8a847b', '#e8e6e0'], .6 * k, 2.2 * k);
    speckles(ctx, s, 17, 900 * k * k, ['#6d6b67', '#cfccc4', '#8a857d', '#eeece6', '#55534f'], 1.5 * k, 4.5 * k);
  }, { repeat: [7, 5] });
  const counter = canvasTexture(size / 2, (ctx, s) => {
    ctx.fillStyle = '#e4e1da'; ctx.fillRect(0, 0, s, s);
    speckles(ctx, s, 23, 5200 * k * k, ['#8f8b84', '#4e4c49', '#b9b4aa', '#c9c4b9', '#2f2e2c'], .5 * k, 3.4 * k);
  }, { repeat: [2, 1] });
  const concrete = canvasTexture(size, (ctx, s) => {
    ctx.fillStyle = '#b3afa7'; ctx.fillRect(0, 0, s, s);
    clouds(ctx, s, 31, 140, 'rgba(255,255,255,.08)', 'rgba(60,55,50,.08)');
    speckles(ctx, s, 37, 14000 * k * k, ['rgba(80,76,70,.25)', 'rgba(255,255,255,.2)'], .4 * k, 1.4 * k);
    const rnd = random(41);
    for (let i = 0; i < 18; i++) { // form bağlantı delikleri
      ctx.fillStyle = 'rgba(70,66,60,.35)';
      ctx.beginPath(); ctx.arc(rnd() * s, rnd() * s, 3 * k, 0, Math.PI * 2); ctx.fill();
    }
  }, { repeat: [3, 1.2] });
  const wood = canvasTexture(size / 2, (ctx, s) => {
    const rnd = random(53);
    ctx.fillStyle = '#7a5334'; ctx.fillRect(0, 0, s, s);
    for (let i = 0; i < 120; i++) {
      ctx.strokeStyle = `rgba(${rnd() > .5 ? '52,32,18' : '160,118,78'},${.12 + rnd() * .2})`;
      ctx.lineWidth = .5 + rnd() * 2.5;
      const x = rnd() * s;
      ctx.beginPath(); ctx.moveTo(x, 0);
      for (let y = 0; y <= s; y += 16) ctx.lineTo(x + Math.sin(y * .02 + i) * 4, y);
      ctx.stroke();
    }
  });
  const leatherBump = canvasTexture(256, (ctx, s) => {
    ctx.fillStyle = '#808080'; ctx.fillRect(0, 0, s, s);
    speckles(ctx, s, 61, 9000, ['#6c6c6c', '#949494', '#777777', '#8b8b8b'], .6, 2.2);
  }, { repeat: [3, 3], color: false });
  const glow = canvasTexture(256, (ctx, s) => {
    const gradient = ctx.createRadialGradient(s / 2, s / 2, s * .18, s / 2, s / 2, s / 2);
    gradient.addColorStop(0, 'rgba(66,214,205,0)');
    gradient.addColorStop(.55, 'rgba(66,214,205,.9)');
    gradient.addColorStop(.68, 'rgba(66,214,205,.35)');
    gradient.addColorStop(1, 'rgba(66,214,205,0)');
    ctx.fillStyle = gradient; ctx.fillRect(0, 0, s, s);
  });
  // Retro stüdyo: dama zemin (2x2 karo / tekrar), boyalı sıva duvar, tavla tahtası.
  const checker = canvasTexture(512, (ctx, s) => {
    const rnd = random(71); const half = s / 2;
    for (let y = 0; y < 2; y++) for (let x = 0; x < 2; x++) {
      ctx.fillStyle = (x + y) % 2 ? '#ebe8e0' : '#151515';
      ctx.fillRect(x * half, y * half, half, half);
    }
    speckles(ctx, s, 73, 5200 * k * k, ['rgba(255,255,255,.07)', 'rgba(0,0,0,.08)'], .4 * k, 1.6 * k);
    ctx.fillStyle = 'rgba(0,0,0,.28)';
    ctx.fillRect(0, 0, s, 2); ctx.fillRect(0, half - 1, s, 2); ctx.fillRect(0, 0, 2, s); ctx.fillRect(half - 1, 0, 2, s);
    for (let i = 0; i < 14; i++) { ctx.fillStyle = 'rgba(255,255,255,.04)'; ctx.fillRect(rnd() * s, rnd() * s, 18 * k, 2); }
  }, { repeat: [1, 1] });
  const plaster = canvasTexture(size / 2, (ctx, s) => {
    ctx.fillStyle = '#e9eef0'; ctx.fillRect(0, 0, s, s);
    clouds(ctx, s, 83, 90, 'rgba(255,255,255,.18)', 'rgba(40,70,90,.12)');
    speckles(ctx, s, 89, 7000 * k * k, ['rgba(40,70,90,.10)', 'rgba(255,255,255,.14)'], .4 * k, 1.2 * k);
  }, { repeat: [3, 1.4] });
  const board = canvasTexture(256, (ctx, s) => {
    ctx.fillStyle = '#c99b63'; ctx.fillRect(0, 0, s, s);
    ctx.fillStyle = '#3b2616'; ctx.fillRect(s / 2 - 6, 0, 12, s);
    for (let side = 0; side < 2; side++) for (let i = 0; i < 6; i++) for (const top of [true, false]) {
      const x0 = side * (s / 2 + 6) + 8 + i * ((s / 2 - 30) / 6) ; const w = (s / 2 - 30) / 6;
      ctx.fillStyle = (i + side) % 2 ? '#6b3a1e' : '#efe2c4';
      ctx.beginPath();
      if (top) { ctx.moveTo(x0, 10); ctx.lineTo(x0 + w, 10); ctx.lineTo(x0 + w / 2, s * .42); }
      else { ctx.moveTo(x0, s - 10); ctx.lineTo(x0 + w, s - 10); ctx.lineTo(x0 + w / 2, s * .58); }
      ctx.closePath(); ctx.fill();
    }
  });
  const art = Object.fromEntries(ART_PALETTES.map((palette, i) => [`art${i}`, artTexture(i + 1, palette)])) as Record<ArtKey, THREE.CanvasTexture>;
  return { terrazzo, counter, concrete, wood, leatherBump, glow, checker, plaster, board, ...art };
}

export function textTexture(lines: string[], options: { width: number; height: number; color: string; background?: string; font: string; lineHeight: number; align?: CanvasTextAlign; rotate?: number; x?: number; y?: number; rule?: boolean }) {
  return canvasTexture(options.width, ctx => {
    if (options.background) { ctx.fillStyle = options.background; ctx.fillRect(0, 0, options.width, options.height); }
    ctx.save();
    ctx.translate(options.x ?? options.width / 2, options.y ?? options.height / 2);
    ctx.rotate(options.rotate ?? 0);
    ctx.fillStyle = options.color;
    ctx.font = options.font;
    ctx.textAlign = options.align ?? 'center';
    ctx.textBaseline = 'middle';
    const start = -((lines.length - 1) * options.lineHeight) / 2;
    lines.forEach((line, index) => ctx.fillText(line, 0, start + index * options.lineHeight));
    if (options.rule) { ctx.fillRect(options.align === 'left' ? 0 : -24, start + lines.length * options.lineHeight, 48, 6); }
    ctx.restore();
  }, { height: options.height });
}

export { random };
