import { useLayoutEffect, useMemo, useRef } from 'react';
import { MeshReflectorMaterial, RoundedBox } from '@react-three/drei';
import * as THREE from 'three';
import { random, textTexture, type createShopTextures } from './textures';

type Textures = ReturnType<typeof createShopTextures>;
export type Quality = 'high' | 'low';

const BACK_Z = -4.6;
const COUNTER_TOP = 1.9;

function Leaves({ count, height, spread, seed, color = '#3f6b3a' }: { count: number; height: number; spread: number; seed: number; color?: string }) {
  const ref = useRef<THREE.InstancedMesh>(null);
  const geometry = useMemo(() => {
    const shape = new THREE.Shape();
    shape.moveTo(0, 0);
    shape.quadraticCurveTo(.13, .25, 0, .62);
    shape.quadraticCurveTo(-.13, .25, 0, 0);
    const leaf = new THREE.ShapeGeometry(shape, 6);
    leaf.rotateX(-Math.PI / 2.4);
    return leaf;
  }, []);
  useLayoutEffect(() => {
    const mesh = ref.current; if (!mesh) return;
    const rnd = random(seed); const dummy = new THREE.Object3D();
    const tint = new THREE.Color();
    for (let i = 0; i < count; i++) {
      const t = rnd();
      dummy.position.set((rnd() - .5) * spread * .3, .1 + t * height, (rnd() - .5) * spread * .3);
      dummy.rotation.set((rnd() - .5) * .9, rnd() * Math.PI * 2, (rnd() - .5) * .6 - .2 * t);
      const s = .7 + rnd() * .8 * (1 - t * .4);
      dummy.scale.set(s, s, s * (1 + rnd() * .4));
      dummy.updateMatrix();
      mesh.setMatrixAt(i, dummy.matrix);
      mesh.setColorAt(i, tint.set(color).offsetHSL((rnd() - .5) * .04, (rnd() - .5) * .15, (rnd() - .5) * .12));
    }
    mesh.instanceMatrix.needsUpdate = true;
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
  }, [count, height, spread, seed, color]);
  return <instancedMesh ref={ref} args={[geometry, undefined, count]} castShadow>
    <meshStandardMaterial side={THREE.DoubleSide} roughness={.62} />
  </instancedMesh>;
}

function Plant({ position, scale = 1, seed, quality }: { position: [number, number, number]; scale?: number; seed: number; quality: Quality }) {
  return <group position={position} scale={scale}>
    <mesh position={[0, .45, 0]} castShadow receiveShadow>
      <cylinderGeometry args={[.42, .32, .9, 32]} /><meshStandardMaterial color="#232526" roughness={.5} metalness={.2} />
    </mesh>
    <group position={[0, .9, 0]}><Leaves count={quality === 'high' ? 90 : 40} height={1.5} spread={1.4} seed={seed} /></group>
  </group>;
}

function Bottles({ xs, quality }: { xs: number[]; quality: Quality }) {
  const dark = useRef<THREE.InstancedMesh>(null);
  const amber = useRef<THREE.InstancedMesh>(null);
  const perStation = quality === 'high' ? 9 : 5;
  const total = xs.length * perStation;
  const geometry = useMemo(() => {
    const points = [[0, 0], [.1, 0], [.11, .02], [.11, .34], [.08, .4], [.04, .42], [.04, .5], [0, .5]].map(([x, y]) => new THREE.Vector2(x, y));
    return new THREE.LatheGeometry(points, quality === 'high' ? 16 : 8);
  }, [quality]);
  useLayoutEffect(() => {
    const rnd = random(99); const dummy = new THREE.Object3D();
    let d = 0, a = 0;
    xs.forEach(x => {
      for (let i = 0; i < perStation; i++) {
        const left = i < perStation / 2;
        dummy.position.set(x + (left ? -1.15 + rnd() * .55 : .55 + rnd() * .6), COUNTER_TOP + .07, BACK_Z + .3 + rnd() * .3);
        const s = .55 + rnd() * .65; dummy.scale.set(s, s * (.8 + rnd() * .6), s);
        dummy.updateMatrix();
        if (rnd() > .72 && amber.current) amber.current.setMatrixAt(a++, dummy.matrix);
        else dark.current?.setMatrixAt(d++, dummy.matrix);
      }
    });
    if (dark.current) { dark.current.count = d; dark.current.instanceMatrix.needsUpdate = true; }
    if (amber.current) { amber.current.count = a; amber.current.instanceMatrix.needsUpdate = true; }
  }, [xs, perStation]);
  return <>
    <instancedMesh ref={dark} args={[geometry, undefined, total]} castShadow>
      <meshPhysicalMaterial color="#141516" roughness={.25} clearcoat={.8} metalness={.1} />
    </instancedMesh>
    <instancedMesh ref={amber} args={[geometry, undefined, total]} castShadow>
      <meshPhysicalMaterial color="#6a3a12" roughness={.12} transmission={0} clearcoat={1} />
    </instancedMesh>
  </>;
}

function Station({ x, textures, mirrorEnv, quality }: { x: number; textures: Textures; mirrorEnv: THREE.Texture | null; quality: Quality }) {
  return <group position={[x, 0, 0]}>
    {/* Asma tezgâh: taş tabla + koyu dolap */}
    <mesh position={[0, COUNTER_TOP, BACK_Z + .5]} castShadow receiveShadow>
      <boxGeometry args={[2.9, .12, 1]} /><meshStandardMaterial map={textures.counter} roughness={.32} />
    </mesh>
    <mesh position={[0, COUNTER_TOP - .45, BACK_Z + .45]} castShadow receiveShadow>
      <boxGeometry args={[1.5, .8, .86]} /><meshStandardMaterial color="#3a3c3d" roughness={.55} metalness={.35} />
    </mesh>
    <mesh position={[0, COUNTER_TOP + .062, BACK_Z + .55]} rotation={[-Math.PI / 2, 0, 0]}>
      <planeGeometry args={[.7, .42]} /><meshStandardMaterial color="#1c1d1e" roughness={.2} metalness={.6} />
    </mesh>
    {/* Ayna: siyah metal çerçeve + yansıtıcı yüzey */}
    <RoundedBox args={[2.36, 3.3, .1]} radius={.12} smoothness={4} position={[0, 3.75, BACK_Z + .06]} castShadow>
      <meshStandardMaterial color="#141516" roughness={.35} metalness={.7} />
    </RoundedBox>
    <RoundedBox args={[2.14, 3.08, .02]} radius={.08} smoothness={4} position={[0, 3.75, BACK_Z + .12]}>
      <meshStandardMaterial color={mirrorEnv ? '#b9bfc0' : '#8f9593'} metalness={mirrorEnv ? 1 : .85} roughness={mirrorEnv ? .02 : .22} envMap={mirrorEnv ?? undefined} envMapIntensity={mirrorEnv ? 1.1 : 1.3} />
    </RoundedBox>
  </group>;
}

function LightBar({ x }: { x: number }) {
  return <group position={[x, 3.7, BACK_Z + .16]}>
    <mesh><capsuleGeometry args={[.045, 1.5, 4, 12]} /><meshStandardMaterial color="#ffd9a0" emissive="#ffc27a" emissiveIntensity={4} toneMapped={false} /></mesh>
    <mesh position={[0, 0, -.04]}><boxGeometry args={[.05, 1.7, .04]} /><meshStandardMaterial color="#1a1a1a" metalness={.6} roughness={.4} /></mesh>
    <pointLight position={[0, 0, .35]} color="#ffbe78" intensity={2.2} distance={3.2} decay={2} />
  </group>;
}

export function ShopRoom({ xs, textures, quality, mirrorEnv }: { xs: number[]; textures: Textures; quality: Quality; mirrorEnv: THREE.Texture | null }) {
  const sorted = useMemo(() => [...xs].sort((a, b) => a - b), [xs]);
  const minX = Math.min(-3.25, sorted[0] ?? 0) - 2.65;
  const maxX = Math.max(3.25, sorted[sorted.length - 1] ?? 0) + 2.7;
  const bars = useMemo(() => {
    if (!sorted.length) return [];
    const list = [sorted[0] - 1.62];
    for (let i = 0; i < sorted.length - 1; i++) list.push((sorted[i] + sorted[i + 1]) / 2);
    list.push(sorted[sorted.length - 1] + 1.62);
    return list;
  }, [sorted]);
  const wallText = useMemo(() => textTexture(['İYİ', 'SAÇ', 'DAHA İYİ', 'HİSSETTİRİR'], {
    width: 512, height: 640, color: '#2b2a28', font: '700 64px "Space Grotesk", "DM Sans", sans-serif', lineHeight: 78, rule: true,
  }), []);
  const poster = useMemo(() => textTexture(['KESİM', 'SAKAL', 'BAKIM', 'STİL'], {
    width: 256, height: 384, color: '#1d1d1c', background: '#f1eee7', font: '700 40px "Space Grotesk", sans-serif', lineHeight: 52, rule: true,
  }), []);
  const rug = useMemo(() => textTexture(['BERBER', 'SADECE DAHA İYİSİ'], {
    width: 512, height: 320, color: '#b8b3a8', background: '#2a2a29', font: '700 58px "Space Grotesk", sans-serif', lineHeight: 92,
  }), []);
  const bookCover = useMemo(() => textTexture(['GOOD', 'HAIR', 'GOOD', 'MOOD'], {
    width: 256, height: 320, color: '#f2efe8', background: '#1e1f20', font: '700 50px "Space Grotesk", sans-serif', lineHeight: 58,
  }), []);
  const width = maxX - minX;
  const slats = Array.from({ length: 22 }, (_, i) => i);

  return <group>
    {/* Zemin: terrazzo */}
    <mesh rotation={[-Math.PI / 2, 0, 0]} position={[(minX + maxX) / 2, 0, 1.5]} receiveShadow>
      <planeGeometry args={[width + 8, 16]} />
      {quality === 'high'
        ? <MeshReflectorMaterial map={textures.terrazzo} color="#cfccc5" roughness={.6} metalness={0} blur={[500, 200]} resolution={1024}
            mixBlur={1} mixStrength={.35} mixContrast={1} depthScale={1} minDepthThreshold={.45} maxDepthThreshold={1.3} mirror={0} />
        : <meshStandardMaterial map={textures.terrazzo} color="#cfccc5" roughness={.5} />}
    </mesh>
    {/* Duvarlar: beton */}
    <mesh position={[(minX + maxX) / 2, 4.5, BACK_Z]} receiveShadow>
      <planeGeometry args={[width + 2, 9]} /><meshStandardMaterial map={textures.concrete} color="#dcd8d0" roughness={.92} />
    </mesh>
    <mesh position={[minX, 4.5, 1]} rotation={[0, Math.PI / 2, 0]} receiveShadow>
      <planeGeometry args={[11.2, 9]} /><meshStandardMaterial map={textures.concrete} color="#dcd8d0" roughness={.92} />
    </mesh>
    <mesh position={[minX + .02, 4.4, -2.9]} rotation={[0, Math.PI / 2, 0]}>
      <planeGeometry args={[2.2, 2.75]} /><meshStandardMaterial map={wallText} transparent roughness={.9} />
    </mesh>
    {/* Sağ: ahşap lamel bölme */}
    <group position={[maxX, 0, BACK_Z]}>
      {slats.map(i => <mesh key={i} position={[0, 4.2, .12 + i * .21]} castShadow receiveShadow>
        <boxGeometry args={[.1, 8.4, .13]} /><meshStandardMaterial map={textures.wood} roughness={.6} />
      </mesh>)}
      <mesh position={[.2, 4.2, 2.4]}><boxGeometry args={[.1, 8.4, 4.8]} /><meshStandardMaterial color="#231a13" roughness={.9} /></mesh>
    </group>
    {/* Raf ünitesi ve poster */}
    <group position={[maxX - .9, 0, BACK_Z + 1.2]}>
      {[-.55, .55].map(x => <mesh key={x} position={[x, 2.6, 0]} castShadow><boxGeometry args={[.06, 5.2, .06]} /><meshStandardMaterial color="#161718" metalness={.7} roughness={.35} /></mesh>)}
      {[.9, 2.1, 3.3, 4.6].map(y => <mesh key={y} position={[0, y, 0]} castShadow receiveShadow><boxGeometry args={[1.16, .05, .6]} /><meshStandardMaterial color="#1c1d1e" metalness={.6} roughness={.4} /></mesh>)}
      <mesh position={[0, 3.95, -.2]} castShadow><boxGeometry args={[.9, 1.25, .05]} /><meshStandardMaterial color="#111" roughness={.5} /></mesh>
      <mesh position={[0, 3.95, -.17]}><planeGeometry args={[.8, 1.15]} /><meshStandardMaterial map={poster} roughness={.85} /></mesh>
      <Plant position={[0, 4.63, 0]} scale={.55} seed={7} quality={quality} />
    </group>
    {/* Sol: arabalı takım dolabı */}
    <group position={[minX + .75, 0, BACK_Z + 1.5]}>
      <RoundedBox args={[.9, 1.6, .7]} radius={.04} position={[0, 1.05, 0]} castShadow receiveShadow>
        <meshStandardMaterial color="#1b1c1d" metalness={.5} roughness={.4} />
      </RoundedBox>
      {[-.3, .3].map(x => <mesh key={x} position={[x, .12, 0]} rotation={[0, 0, Math.PI / 2]}><cylinderGeometry args={[.1, .1, .08, 16]} /><meshStandardMaterial color="#0d0d0d" /></mesh>)}
      <mesh position={[.48, 1.05, 0]} castShadow><boxGeometry args={[.04, .9, .3]} /><meshStandardMaterial color="#e9e6df" roughness={.95} /></mesh>
    </group>
    <Plant position={[minX + .75, 0, .9]} scale={1.05} seed={3} quality={quality} />

    {sorted.map(x => <Station key={x} x={x} textures={textures} mirrorEnv={mirrorEnv} quality={quality} />)}
    {bars.map(x => <LightBar key={x} x={x} />)}
    <Bottles xs={sorted} quality={quality} />
    {sorted.slice(0, 2).map((x, i) => <Plant key={x} position={[x + (i ? 1.05 : -1.05), COUNTER_TOP + .06, BACK_Z + .5]} scale={.3} seed={20 + i} quality={quality} />)}

    {/* Ön plan: bekleme alanı */}
    <group position={[-3.7, 0, 3.7]} scale={.78}>
      <mesh position={[0, 1.02, 0]} castShadow receiveShadow>
        <cylinderGeometry args={[1.25, 1.25, .08, 64]} /><meshStandardMaterial color="#2a2b2c" roughness={.12} metalness={.2} />
      </mesh>
      <mesh position={[0, .5, 0]} castShadow><cylinderGeometry args={[.12, .5, 1, 24]} /><meshStandardMaterial color="#141414" metalness={.6} roughness={.35} /></mesh>
      <mesh position={[.45, 1.1, .25]} rotation={[0, -.5, 0]} castShadow>
        <boxGeometry args={[.8, .1, 1]} /><meshStandardMaterial color="#1e1f20" roughness={.6} />
      </mesh>
      <mesh position={[.45, 1.155, .25]} rotation={[-Math.PI / 2, 0, -.5]}>
        <planeGeometry args={[.74, .94]} /><meshStandardMaterial map={bookCover} roughness={.7} />
      </mesh>
      <Plant position={[-.45, 1.06, -.2]} scale={.32} seed={31} quality={quality} />
    </group>
    <group position={[-6.4, 0, 4.4]} rotation={[0, .35, 0]}>
      <RoundedBox args={[1.5, .8, 3.8]} radius={.15} position={[0, .6, 0]} castShadow receiveShadow><meshPhysicalMaterial color="#5a3a26" roughness={.45} clearcoat={.3} /></RoundedBox>
      <RoundedBox args={[.5, 1.4, 3.8]} radius={.18} position={[-.6, 1.2, 0]} castShadow><meshPhysicalMaterial color="#5a3a26" roughness={.45} clearcoat={.3} /></RoundedBox>
    </group>
    <mesh position={[3.9, .012, 3.9]} rotation={[-Math.PI / 2, 0, .18]} receiveShadow>
      <planeGeometry args={[2.9, 1.8]} /><meshStandardMaterial map={rug} roughness={1} />
    </mesh>
    <Plant position={[-5.3, 0, 6.4]} scale={1.5} seed={41} quality={quality} />
  </group>;
}
