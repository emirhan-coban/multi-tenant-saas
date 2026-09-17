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
  return { terrazzo, counter, concrete, wood, leatherBump, glow };
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
