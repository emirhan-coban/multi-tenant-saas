import { Component, Suspense, useMemo, type ErrorInfo, type ReactNode } from 'react';
import { Canvas } from '@react-three/fiber';
import { ContactShadows, OrbitControls, useGLTF } from '@react-three/drei';
import * as THREE from 'three';
import type { PublicSceneManifest } from '../api/shop-scene';

function Chair({ chair, accentColor, selected, onSelect }: {
  chair: PublicSceneManifest['chairs'][number]; accentColor: string; selected: boolean; onSelect: (staffId: string, chairKey: string) => void;
}) {
  const model = useGLTF('/models/barber-chair.glb');
  const clone = useMemo(() => {
    const scene = model.scene.clone(true);
    scene.traverse(object => {
      if (!(object instanceof THREE.Mesh)) return;
      const material = (object.material as THREE.MeshStandardMaterial).clone();
      if (material.color.getHexString() === '245e50') material.color.set(accentColor);
      object.material = material;
    });
    return scene;
  }, [model.scene, accentColor]);
  return <group position={[chair.positionX, 0, chair.positionZ]} rotation={[0, chair.rotationY, 0]}
    onClick={event => { event.stopPropagation(); onSelect(chair.staffId, chair.chairKey); }}>
    <primitive object={clone} />
    <mesh position={[0, .025, 0]} rotation={[-Math.PI / 2, 0, 0]} visible={selected}>
      <ringGeometry args={[.95, 1.12, 48]} /><meshBasicMaterial color="#d7ed87" side={THREE.DoubleSide} />
    </mesh>
  </group>;
}

function Shop({ manifest, selectedId, onSelect }: {
  manifest: PublicSceneManifest; selectedId: string | null; onSelect: (staffId: string, chairKey: string) => void;
}) {
  return <>
    <color attach="background" args={['#d9ded4']} />
    <fog attach="fog" args={['#d9ded4', 11, 24]} />
    <hemisphereLight args={['#fffdf2', '#385047', 2.3]} />
    <directionalLight position={[-4, 8, 5]} intensity={4.1} castShadow />
    <mesh rotation={[-Math.PI / 2, 0, 0]} receiveShadow><planeGeometry args={[18, 13]} /><meshStandardMaterial color="#b8c0b3" roughness={.92} /></mesh>
    <mesh position={[0, 3, -4.4]} receiveShadow><boxGeometry args={[18, 6, .2]} /><meshStandardMaterial color="#ece9df" roughness={.8} /></mesh>
    {manifest.chairs.map(chair => <group key={`station-${chair.chairKey}`}>
      <mesh position={[chair.positionX, 2.65, -4.22]}><boxGeometry args={[2.25, 2.7, .08]} /><meshStandardMaterial color="#90a3a0" metalness={.75} roughness={.18} /></mesh>
      <Chair chair={chair} accentColor={manifest.accentColor} selected={selectedId === chair.staffId} onSelect={onSelect} />
    </group>)}
    <ContactShadows position={[0, .02, 0]} opacity={.34} scale={15} blur={2.4} far={5} />
    <OrbitControls makeDefault target={[0, 1.2, -1]} enablePan={false} enableZoom minPolarAngle={.82} maxPolarAngle={1.38} minDistance={8} maxDistance={13} minAzimuthAngle={-.38} maxAzimuthAngle={.38} />
  </>;
}

class SceneBoundary extends Component<{ children: ReactNode; fallback: ReactNode; onError: () => void }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  componentDidCatch(_error: Error, _info: ErrorInfo) { this.props.onError(); }
  render() { return this.state.failed ? this.props.fallback : this.props.children; }
}

export function BarberShopScene({ manifest, selectedId, onSelect, onFailure }: {
  manifest: PublicSceneManifest; selectedId: string | null;
  onSelect: (staffId: string, chairKey: string) => void; onFailure: () => void;
}) {
  const fallback = <div className="scene-fallback" role="status">3D görünüm açılamadı. Liste görünümüne geçiliyor.</div>;
  return <SceneBoundary fallback={fallback} onError={onFailure}>
    <div className="barber-scene" data-testid="shop-scene">
      <Canvas shadows="basic" frameloop="demand" dpr={[1, 1.75]} camera={{ fov: 38, position: [0, 6.6, 10], near: .1, far: 100 }}
        gl={{ antialias: true, powerPreference: 'high-performance' }} onCreated={({ gl }) => { gl.domElement.dataset.sceneReady = 'true'; }}>
        <Suspense fallback={null}><Shop manifest={manifest} selectedId={selectedId} onSelect={onSelect} /></Suspense>
      </Canvas>
    </div>
  </SceneBoundary>;
}

useGLTF.preload('/models/barber-chair.glb');
