import { Component, Suspense, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ErrorInfo, type ReactNode } from 'react';
import { Canvas, useFrame, useThree } from '@react-three/fiber';
import { ContactShadows, Environment, Html, Lightformer, OrbitControls, Outlines, useGLTF } from '@react-three/drei';
import * as THREE from 'three';
import type { PublicSceneManifest } from '../api/shop-scene';
import { ShopRoom, type Quality } from './shop/ShopRoom';
import { createShopTextures } from './shop/textures';
import { initials, type ChairStatus } from './scene-helpers';

export type { ChairStatus };

type SceneChair = PublicSceneManifest['chairs'][number];
type SelectHandler = (staffId: string, chairKey: string) => void;

const DESKTOP_MODEL = '/models/barber-chair.glb';
const MOBILE_MODEL = '/models/barber-chair-mobile.glb';
/** Dolu koltuk soluk gri; seçili koltuk dükkânın kendi rengini korur (aşağıya bakınız). */
const FULL_LEATHER = '#4d5250';
const GLOW = '#46d3ca';

function media(query: string) {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia(query).matches;
}

function initialQuality(): Quality {
  const forced = typeof window !== 'undefined' ? new URLSearchParams(window.location.search).get('quality') : null;
  if (forced === 'high' || forced === 'low') return forced;
  const nav = typeof navigator === 'undefined' ? undefined : navigator as Navigator & { deviceMemory?: number };
  const weak = (nav?.hardwareConcurrency ?? 8) <= 4 || (nav?.deviceMemory ?? 8) <= 4;
  return media('(max-width: 840px), (pointer: coarse)') || weak ? 'low' : 'high';
}


function Chair({ chair, index, model, accentColor, selected, status, textures, quality, compact, onSelect }: {
  chair: SceneChair; index: number; model: string; accentColor: string; selected: boolean; status?: ChairStatus;
  textures: ReturnType<typeof createShopTextures>; quality: Quality; compact: boolean; onSelect: SelectHandler;
}) {
  const gltf = useGLTF(model);
  const invalidate = useThree(state => state.invalidate);
  const [hovered, setHovered] = useState(false);
  const [spotTarget] = useState(() => new THREE.Object3D());
  const full = status?.state === 'full';
  const parts = useMemo(() => {
    const list: { geometry: THREE.BufferGeometry; material: THREE.MeshStandardMaterial; name: string }[] = [];
    gltf.scene.traverse(object => {
      if (!(object instanceof THREE.Mesh)) return;
      const source = object.material as THREE.MeshStandardMaterial;
      const material = source.clone();
      if (source.name === 'Leather') {
        material.bumpMap = textures.leatherBump;
        material.bumpScale = .6;
      }
      if (source.name === 'Chrome') material.envMapIntensity = 1.4;
      list.push({ geometry: object.geometry, material, name: source.name });
    });
    return list;
  }, [gltf.scene, textures.leatherBump]);

  useEffect(() => {
    for (const part of parts) {
      if (part.name !== 'Leather') continue;
      // Seçim, rengi değiştirerek değil parlaklık + turkuaz kontur + zemin halkasıyla anlatılır:
      // farklı bir renk (eski kırmızı) yeşil bir dükkânda "dolu koltuk" gibi okunuyordu.
      part.material.color.set(full ? FULL_LEATHER : accentColor);
      part.material.roughness = full ? .7 : selected ? .32 : .46;
      part.material.emissive.set(selected || hovered ? GLOW : '#000000');
      part.material.emissiveIntensity = selected ? .1 : hovered ? .06 : 0;
    }
    invalidate();
  }, [parts, selected, full, hovered, accentColor, invalidate]);

  useEffect(() => () => { document.body.style.cursor = ''; }, []);
  const number = String(index + 1).padStart(2, '0');
  const firstName = chair.staffName.split(' ')[0];
  const select = () => onSelect(chair.staffId, chair.chairKey);

  return <group position={[chair.positionX, 0, chair.positionZ]}>
    <group rotation={[0, chair.rotationY, 0]}
      onClick={event => { event.stopPropagation(); select(); }}
      onPointerOver={event => { event.stopPropagation(); setHovered(true); document.body.style.cursor = 'pointer'; }}
      onPointerOut={() => { setHovered(false); document.body.style.cursor = ''; }}>
      {parts.map(part => <mesh key={part.name} geometry={part.geometry} material={part.material} castShadow receiveShadow>
        {selected && <Outlines thickness={.045} color={GLOW} transparent opacity={.95} />}
      </mesh>)}
    </group>
    {/* Zemin halkası: seçili = turkuaz ışık, üzerine gelince hafif */}
    {(selected || hovered) && <mesh rotation={[-Math.PI / 2, 0, 0]} position={[0, .02, .15]}>
      <planeGeometry args={[3.1, 3.1]} />
      <meshBasicMaterial map={textures.glow} transparent opacity={selected ? .95 : .4} depthWrite={false} toneMapped={false} blending={THREE.AdditiveBlending} />
    </mesh>}
    <spotLight position={[0, 8.5, 1.4]} angle={.42} penumbra={.85} decay={2} intensity={selected ? 110 : full ? 35 : 70}
      color={selected ? '#fff4e6' : '#ffe9cf'} castShadow={quality === 'high' && selected} shadow-mapSize={[1024, 1024]} shadow-bias={-.0004}
      target={spotTarget} />
    <primitive object={spotTarget} position={[0, 1, 0]} />
    {selected && !compact && <Html position={[0, 3.62, -.55]} center zIndexRange={[20, 10]} className="scene-html">
      <span className="scene-selected-badge"><i aria-hidden="true">✓</i>Seçildi</span>
    </Html>}
    {/* Faz 10: dar ekranda etikette yalnızca koltuk numarası durur — uzun isimler
        yan yana koltuklarda birbirini örtüyordu. İsim ve ilk müsait saat sahnenin
        altındaki berber şeridinde, çakışma riski olmadan gösterilir. */}
    <Html position={[0, .05, 1.75]} center zIndexRange={[20, 10]} className="scene-html">
      <button type="button" className={`scene-chair-label${compact ? ' is-compact' : ''}${selected ? ' is-selected' : ''}${full ? ' is-full' : ''}`} onClick={select}
        aria-pressed={selected} aria-label={`${chair.staffName}, koltuk ${number}`}>
        {compact ? <strong className="scene-chair-number">{number}</strong> : <>
          <span className="scene-avatar" aria-hidden="true">{initials(chair.staffName)}</span>
          <span className="scene-label-copy">
            <strong>{number} · {firstName}</strong>
            <small>{status?.state === 'full' ? 'Bugün dolu' : status?.firstSlot ? `İlk müsait ${status.firstSlot}` : status?.state === 'loading' ? 'Saatler yükleniyor' : 'Müsait'}</small>
          </span>
        </>}
      </button>
    </Html>
  </group>;
}

/** Aynalar için odayı bir kez küp haritasına çizer; seçim değişince tazelenir. */
function useRoomReflection(enabled: boolean, refreshKey: string) {
  const gl = useThree(state => state.gl);
  const scene = useThree(state => state.scene);
  const invalidate = useThree(state => state.invalidate);
  const [target] = useState(() => new THREE.WebGLCubeRenderTarget(256, { type: THREE.HalfFloatType }));
  const camera = useMemo(() => { const cube = new THREE.CubeCamera(.1, 60, target); cube.position.set(0, 2.6, 1.2); return cube; }, [target]);
  const pending = useRef(3);
  useEffect(() => { pending.current = 3; invalidate(); }, [refreshKey, invalidate]);
  useEffect(() => () => target.dispose(), [target]);
  useFrame(() => {
    if (!enabled || pending.current <= 0) return;
    pending.current -= 1;
    camera.update(gl, scene);
    if (pending.current > 0) invalidate();
  });
  return enabled ? target.texture : null;
}

/**
 * Tüm koltukların kadraja sığması için gereken kamera mesafesi.
 *
 * Önceden kamera sabit bir uzaklıkta duruyordu ve OrbitControls `maxDistance` ile
 * kırpıyordu: dar (dikey) ekranda üç koltuklu dükkânda yalnızca bir koltuk görünüyordu.
 * Artık mesafe, koltukların yatay yayılımından ve ekranın en-boy oranından hesaplanır;
 * kaç koltuk olursa olsun hepsi kadrajda kalır.
 */
function frameDistance(halfWidth: number, halfHeight: number, fov: number, aspect: number) {
  const vertical = (fov * Math.PI) / 180;
  const byHeight = halfHeight / Math.tan(vertical / 2);
  const horizontal = 2 * Math.atan(Math.tan(vertical / 2) * Math.max(.35, aspect));
  const byWidth = halfWidth / Math.tan(horizontal / 2);
  return Math.max(byHeight, byWidth);
}

function CameraRig({ focus, center, spread, zoom, resetToken, onFit }: {
  focus: [number, number]; center: [number, number]; spread: number; zoom: number; resetToken: number;
  onFit: (distance: number) => void;
}) {
  const camera = useThree(state => state.camera);
  const controls = useThree(state => state.controls) as unknown as { target: THREE.Vector3; update: () => void; addEventListener: (type: string, cb: () => void) => void; removeEventListener: (type: string, cb: () => void) => void } | null;
  const invalidate = useThree(state => state.invalidate);
  const animating = useRef(true);
  const reduced = useMemo(() => media('(prefers-reduced-motion: reduce)'), []);
  const aspect = useThree(state => state.size.width / Math.max(1, state.size.height));
  const fov = (camera as THREE.PerspectiveCamera).fov ?? 34;
  const desired = useMemo(() => {
    const [focusX] = focus;
    const [centerX, centerZ] = center;
    // Yarı genişlik: koltukların yayılımı + bir koltuk genişliği + kenar boşluğu.
    const halfWidth = spread / 2 + 2.6;
    const fit = frameDistance(halfWidth, 3.1, fov, aspect);
    // Yakınlaştırma yalnızca çarpan: en geniş hâlde bile hiçbir koltuk kırpılmaz.
    const dolly = 1 - Math.max(-2, Math.min(3, zoom)) * .12;
    const distance = Math.max(8, fit * Math.max(.55, dolly));
    // Bakış hedefi dükkânın ortasıdır; seçili koltuğa yalnızca hafifçe kayar.
    const bias = aspect < 1 ? .18 : .3;
    const targetX = centerX + (focusX - centerX) * bias;
    const elevation = .42;  // ~24°: içeri girip bakıyormuş hissi
    return {
      position: new THREE.Vector3(targetX * .35, 1.5 + Math.sin(elevation) * distance, centerZ + Math.cos(elevation) * distance),
      target: new THREE.Vector3(targetX, 1.45, centerZ - .2),
      distance,
    };
  }, [focus, center, spread, zoom, aspect, fov]);

  useEffect(() => { onFit(desired.distance); }, [desired.distance, onFit]);

  useLayoutEffect(() => {
    // Giriş animasyonu: kapıdan içeri süzülür gibi başlar.
    camera.position.set(0, desired.position.y * .7, desired.position.z * 1.35);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [camera]);

  useEffect(() => { animating.current = true; invalidate(); }, [desired, resetToken, invalidate]);
  useEffect(() => {
    if (!controls) return;
    const stop = () => { animating.current = false; };
    controls.addEventListener('start', stop);
    return () => controls.removeEventListener('start', stop);
  }, [controls]);

  useFrame((_, delta) => {
    if (!animating.current || !controls) return;
    const alpha = reduced ? 1 : 1 - Math.exp(-Math.min(delta, .05) * 3.2);
    camera.position.lerp(desired.position, alpha);
    controls.target.lerp(desired.target, alpha);
    controls.update();
    if (camera.position.distanceTo(desired.position) < .01 && controls.target.distanceTo(desired.target) < .01) animating.current = false;
    else invalidate();
  });
  return null;
}

/** İlk saniyelerde kare süresini ölçer; yavaş cihazda kaliteyi otomatik düşürür. */
function QualityProbe({ quality, onDecline }: { quality: Quality; onDecline: () => void }) {
  const invalidate = useThree(state => state.invalidate);
  const samples = useRef<number[]>([]);
  const done = useRef(quality === 'low');
  useFrame((_, delta) => {
    if (done.current) return;
    samples.current.push(delta);
    if (samples.current.length > 90) {
      done.current = true;
      const measured = samples.current.slice(30);
      const average = measured.reduce((sum, value) => sum + value, 0) / measured.length;
      if (average > 1 / 28) onDecline();
      return;
    }
    invalidate();
  });
  return null;
}

function Shop({ manifest, selectedId, statuses, quality, compact, zoom, resetToken, onSelect, onDecline }: {
  manifest: PublicSceneManifest; selectedId: string | null; statuses?: Record<string, ChairStatus>; quality: Quality;
  compact: boolean; zoom: number; resetToken: number; onSelect: SelectHandler; onDecline: () => void;
}) {
  const textures = useMemo(() => createShopTextures(quality), [quality]);
  useEffect(() => () => Object.values(textures).forEach(texture => texture.dispose()), [textures]);
  const selected = manifest.chairs.find(chair => chair.staffId === selectedId);
  const xs = useMemo(() => manifest.chairs.map(chair => chair.positionX), [manifest.chairs]);
  const mirrorEnv = useRoomReflection(quality === 'high', `${selectedId}-${JSON.stringify(statuses ?? {})}`);
  const model = quality === 'high' ? DESKTOP_MODEL : MOBILE_MODEL;
  const focus = useMemo<[number, number]>(() => [selected?.positionX ?? 0, selected?.positionZ ?? -1.25], [selected?.positionX, selected?.positionZ]);
  // Dükkânın gerçek ölçüsü: kaç koltuk varsa kadraj ona göre kurulur.
  const layout = useMemo(() => {
    const zs = manifest.chairs.map(chair => chair.positionZ);
    const minX = Math.min(...xs, 0), maxX = Math.max(...xs, 0);
    return {
      center: [(minX + maxX) / 2, zs.length ? (Math.min(...zs) + Math.max(...zs)) / 2 : -1.25] as [number, number],
      spread: maxX - minX,
    };
  }, [xs, manifest.chairs]);
  const [fit, setFit] = useState(22);
  const onFit = useCallback((distance: number) => setFit(distance), []);

  return <>
    <color attach="background" args={['#cdc9c1']} />
    {/* Sis kamera mesafesine bağlı: geri çekildiğinde koltuklar sisin içinde kaybolmaz. */}
    <fog attach="fog" args={['#cdc9c1', fit * 1.25, fit * 2.4]} />
    <Environment resolution={128} environmentIntensity={.6}>
      <color attach="background" args={['#6f6a63']} />
      <Lightformer form="rect" intensity={2.2} color="#fff6ea" position={[0, 9, 0]} rotation-x={Math.PI / 2} scale={[14, 6, 1]} />
      <Lightformer form="rect" intensity={1.4} color="#f4f8ff" position={[10, 4, 4]} rotation-y={-Math.PI / 2} scale={[10, 5, 1]} />
      <Lightformer form="rect" intensity={.6} color="#ffd8a8" position={[-10, 3, -2]} rotation-y={Math.PI / 2} scale={[8, 3, 1]} />
      <Lightformer form="ring" intensity={1.2} color="#ffffff" position={[0, 5, 12]} scale={3} />
    </Environment>
    <hemisphereLight args={['#fff8ee', '#6b6158', .35]} />
    <directionalLight position={[6, 12, 7]} intensity={2.4} color="#fff3e2" castShadow={quality === 'high'}
      shadow-mapSize={[2048, 2048]} shadow-bias={-.0003} shadow-normalBias={.02} shadow-radius={6}
      shadow-camera-left={-11} shadow-camera-right={11} shadow-camera-top={9} shadow-camera-bottom={-9} shadow-camera-far={40} />
    <ShopRoom xs={xs} textures={textures} quality={quality} mirrorEnv={mirrorEnv} />
    {manifest.chairs.map((chair, index) => <Chair key={chair.chairKey} chair={chair} index={index} model={model} accentColor={manifest.accentColor}
      selected={selectedId === chair.staffId} status={statuses?.[chair.staffId]} textures={textures} quality={quality} compact={compact} onSelect={onSelect} />)}
    <ContactShadows position={[0, .01, -1]} opacity={.55} scale={[22, 12]} blur={2.2} far={3.5} resolution={quality === 'high' ? 1024 : 512} frames={1} color="#2a2420" />
    {/* Sınırlar kadraja bağlı: kullanıcı yaklaşabilir ama varsayılan görünümde
        hiçbir koltuk kadraj dışında kalmaz. */}
    <OrbitControls makeDefault enablePan={false} enableDamping={false} minPolarAngle={.62} maxPolarAngle={1.24}
      minDistance={Math.max(6, fit * .45)} maxDistance={fit * 1.45} minAzimuthAngle={-.45} maxAzimuthAngle={.45} />
    <CameraRig focus={focus} center={layout.center} spread={layout.spread} zoom={zoom} resetToken={resetToken} onFit={onFit} />
    <QualityProbe quality={quality} onDecline={onDecline} />
  </>;
}

class SceneBoundary extends Component<{ children: ReactNode; fallback: ReactNode; onError: () => void }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  componentDidCatch(_error: Error, _info: ErrorInfo) { this.props.onError(); }
  render() { return this.state.failed ? this.props.fallback : this.props.children; }
}

export function BarberShopScene({ manifest, selectedId, statuses, compact = false, onSelect, onFailure, onReady, onCaptureReady }: {
  manifest: PublicSceneManifest; selectedId: string | null; statuses?: Record<string, ChairStatus>; compact?: boolean;
  onSelect: SelectHandler; onFailure: () => void;
  /** Sahne ilk kez seçilebilir hâle geldiğinde bir kez çağrılır (TTS ölçümü). */
  onReady?: () => void;
  /** Sahnenin o anki karesini görüntüye çeviren işlevi üst bileşene verir. */
  onCaptureReady?: (capture: () => string | null) => void;
}) {
  const [quality, setQuality] = useState<Quality>(initialQuality);
  const [zoom, setZoom] = useState(0);
  const [resetToken, setResetToken] = useState(0);
  const [ready, setReady] = useState(false);
  const host = useRef<HTMLDivElement>(null);
  const visible = useSceneVisibility(host);
  const fallback = <div className="scene-fallback" role="status">3D görünüm açılamadı. Liste görünümüne geçiliyor.</div>;
  return <SceneBoundary fallback={fallback} onError={onFailure}>
    <div ref={host} className={`barber-scene${ready ? ' is-ready' : ''}`} data-testid="shop-scene" data-quality={quality} data-rendering={visible ? 'on' : 'off'}>
      {/* Faz 10: sahne ekranda değilken ya da sekme arkadayken hiç render edilmez. */}
      <Canvas key={quality} shadows={quality === 'high' ? 'percentage' : false} frameloop={visible ? 'demand' : 'never'}
        dpr={quality === 'high' ? [1, 1.75] : [1, 1.25]}
        camera={{ fov: 34, position: [0, 6, 22], near: .1, far: 80 }}
        gl={{ antialias: quality === 'high', powerPreference: quality === 'high' ? 'high-performance' : 'default',
          toneMapping: THREE.ACESFilmicToneMapping, toneMappingExposure: 1.05 }}
        onCreated={({ gl }) => { gl.domElement.dataset.sceneReady = 'true'; }}>
        <Suspense fallback={null}>
          <Shop manifest={manifest} selectedId={selectedId} statuses={statuses} quality={quality} compact={compact} zoom={zoom} resetToken={resetToken}
            onSelect={onSelect} onDecline={() => setQuality('low')} />
          <ReadySignal onReady={() => { setReady(true); onReady?.(); }} />
          <CaptureBridge onCaptureReady={onCaptureReady} />
        </Suspense>
      </Canvas>
      {!ready && <div className="scene-loading" role="status"><span />Dükkân hazırlanıyor</div>}
      <div className="scene-controls" role="group" aria-label="Kamera kontrolleri">
        <button type="button" aria-label="Görünümü sıfırla" onClick={() => { setZoom(0); setResetToken(value => value + 1); }}>
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 12a8 8 0 1 1-2.35-5.65M20 4v5h-5" /></svg>
        </button>
        <span>
          <button type="button" aria-label="Uzaklaş" onClick={() => setZoom(value => Math.max(-2, value - 1))}>
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12h14" /></svg>
          </button>
          <button type="button" aria-label="Yakınlaş" onClick={() => setZoom(value => Math.min(3, value + 1))}>
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg>
          </button>
        </span>
      </div>
    </div>
  </SceneBoundary>;
}

function ReadySignal({ onReady }: { onReady: () => void }) {
  const called = useRef(false);
  useEffect(() => {
    if (called.current) return;
    called.current = true;
    onReady();
  }, [onReady]);
  return null;
}

/**
 * Faz 10 — sahnenin o anki karesini görüntüye çevirir.
 *
 * Kullanıcı koltuğunu seçtikten sonra canvas tamamen sökülür; yerine bu durağan kare
 * konur. Böylece tarih-saat adımında WebGL bağlamı hiç çalışmaz (pil ve INP kazancı)
 * ama kullanıcı seçtiği koltuğu görmeye devam eder.
 */
function CaptureBridge({ onCaptureReady }: { onCaptureReady?: (capture: () => string | null) => void }) {
  const gl = useThree(state => state.gl);
  const scene = useThree(state => state.scene);
  const camera = useThree(state => state.camera);
  useEffect(() => {
    if (!onCaptureReady) return;
    onCaptureReady(() => {
      try {
        // Okuma, render ile aynı görevde yapılır; çizim arabelleği bu sayede doludur.
        gl.render(scene, camera);
        const source = gl.domElement;
        // Küçültülerek kodlanır: tam çözünürlükte JPEG kodlaması dokunmayı saniyelerce
        // bekletiyordu (INP). Küçük resim zaten ekranda ~92 px genişliğinde gösteriliyor.
        const width = 360;
        const height = Math.max(1, Math.round((source.height / Math.max(1, source.width)) * width));
        const thumbnail = document.createElement('canvas');
        thumbnail.width = width;
        thumbnail.height = height;
        thumbnail.getContext('2d')?.drawImage(source, 0, 0, width, height);
        return thumbnail.toDataURL('image/jpeg', .7);
      } catch {
        return null;
      }
    });
  }, [gl, scene, camera, onCaptureReady]);
  return null;
}

/** Sahne görünür değilken (kaydırıldı ya da sekme arkada) render döngüsü durur. */
function useSceneVisibility(host: React.RefObject<HTMLDivElement | null>) {
  const [visible, setVisible] = useState(true);
  useEffect(() => {
    if (typeof document === 'undefined') return;
    let onScreen = true;
    const apply = () => setVisible(onScreen && document.visibilityState !== 'hidden');
    const tab = () => apply();
    document.addEventListener('visibilitychange', tab);
    let observer: IntersectionObserver | undefined;
    if (host.current && typeof IntersectionObserver === 'function') {
      observer = new IntersectionObserver(entries => {
        onScreen = entries.some(entry => entry.isIntersecting);
        apply();
      }, { threshold: .05 });
      observer.observe(host.current);
    }
    apply();
    return () => {
      document.removeEventListener('visibilitychange', tab);
      observer?.disconnect();
    };
  }, [host]);
  return visible;
}

useGLTF.preload(initialQuality() === 'high' ? DESKTOP_MODEL : MOBILE_MODEL);
