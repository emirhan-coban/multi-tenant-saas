// Faz 7.1 — Modern berber koltuğu üreticisi.
// Blender yerine tekrarlanabilir, sürüm kontrollü bir kod pipeline'ı: aynı kaynak
// masaüstü (detaylı) ve mobil (düşük poligon) iki .glb üretir. Malzeme isimleri
// (Leather, Chrome, BlackMetal, Rubber) sahnede PBR malzemelerle eşleştirilir.
// Blender'dan gelen gerçek bir model de aynı isim sözleşmesiyle doğrudan değiştirilebilir.
import * as THREE from 'three';
import { GLTFExporter } from 'three/examples/jsm/exporters/GLTFExporter.js';
import { RoundedBoxGeometry } from 'three/examples/jsm/geometries/RoundedBoxGeometry.js';
import { mergeGeometries, mergeVertices } from 'three/examples/jsm/utils/BufferGeometryUtils.js';
import { writeFile, mkdir } from 'node:fs/promises';

globalThis.FileReader = class {
  readAsArrayBuffer(blob) { blob.arrayBuffer().then(value => { this.result = value; this.onloadend?.(); }); }
  readAsDataURL(blob) { blob.arrayBuffer().then(value => { this.result = `data:${blob.type};base64,${Buffer.from(value).toString('base64')}`; this.onloadend?.(); }); }
};

function buildChair(detail) {
  const hi = detail === 'high';
  const seg = { box: hi ? 4 : 2, cap: hi ? 6 : 3, radial: hi ? 18 : 10, tube: hi ? 48 : 18, lathe: hi ? 48 : 20 };
  const buckets = { Leather: [], Chrome: [], BlackMetal: [], Rubber: [] };

  const node = (parent, position = [0, 0, 0], rotation = [0, 0, 0], scale = [1, 1, 1]) => {
    const object = new THREE.Object3D();
    object.position.set(...position); object.rotation.set(...rotation); object.scale.set(...scale);
    (parent ?? root).add(object);
    return object;
  };
  const root = new THREE.Object3D();
  const part = (material, geometry, parent, position = [0, 0, 0], rotation = [0, 0, 0], scale) => {
    const holder = node(parent, position, rotation, scale);
    holder.userData = { geometry, material };
  };
  const roundedBox = (w, h, d, r) => new RoundedBoxGeometry(w, h, d, seg.box, r);
  const capsule = (radius, length) => new THREE.CapsuleGeometry(radius, length, seg.cap, seg.radial);
  const cylinder = (rt, rb, h) => new THREE.CylinderGeometry(rt, rb, h, seg.radial);

  // --- Hidrolik taban ---
  const baseProfile = [[0, 0], [.86, 0], [.9, .02], [.9, .05], [.82, .1], [.3, .13], [0, .13]].map(([x, y]) => new THREE.Vector2(x, y));
  part('Chrome', new THREE.LatheGeometry(baseProfile, seg.lathe));
  part('Rubber', new THREE.TorusGeometry(.88, .025, 8, seg.lathe), null, [0, .025, 0], [Math.PI / 2, 0, 0]);
  const bellows = [];
  for (let i = 0; i <= 8; i++) bellows.push(new THREE.Vector2(i % 2 ? .25 : .2, .13 + i * .045));
  part('Rubber', new THREE.LatheGeometry([new THREE.Vector2(0, .13), ...bellows, new THREE.Vector2(0, .49)], seg.lathe));
  part('Chrome', cylinder(.12, .12, .42), null, [0, .7, 0]);
  part('Chrome', cylinder(.2, .15, .08), null, [0, .9, 0]);
  part('Chrome', roundedBox(.07, .05, .46, .02), null, [.42, .2, .42], [0, -.75, -.25]);
  part('Rubber', roundedBox(.12, .06, .12, .02), null, [.6, .15, .6]);

  // --- Oturak ---
  part('BlackMetal', roundedBox(1.12, .2, 1.02, .05), null, [0, .98, 0]);
  const seat = node(null, [0, 1.16, .02]);
  part('Leather', roundedBox(1.42, .24, 1.3, .1), seat);
  for (let i = 0; i < 6; i++) part('Leather', capsule(.11, 1.0), seat, [-.55 + i * .22, .1, .02], [Math.PI / 2, 0, 0], [1, 1, .5]);
  part('Chrome', roundedBox(1.46, .05, 1.34, .02), seat, [0, -.13, 0]);

  // --- Bacak desteği ---
  const apron = node(null, [0, .72, .74], [.38, 0, 0]);
  part('Leather', roundedBox(1.26, .74, .2, .08), apron);
  for (let i = 0; i < 5; i++) part('Leather', capsule(.115, .46), apron, [-.46 + i * .23, .02, .08], [0, 0, 0], [1, 1, .45]);

  // --- Ayaklık ---
  const foot = node(null, [0, .36, 1.08], [-.08, 0, 0]);
  part('Chrome', roundedBox(1.3, .06, .48, .025), foot);
  for (let i = 0; i < 6; i++) part('Rubber', roundedBox(1.18, .025, .035, .01), foot, [0, .04, -.18 + i * .072]);
  for (const x of [-.5, .5]) part('Chrome', cylinder(.035, .035, .5), null, [x, .52, .98], [.5, 0, 0]);

  // --- Sırtlık ---
  const back = node(null, [0, 2.12, -.56], [-.2, 0, 0]);
  part('BlackMetal', roundedBox(1.46, 1.62, .12, .05), back, [0, 0, -.16]);
  part('Leather', roundedBox(1.36, 1.52, .26, .1), back);
  for (let i = 0; i < 6; i++) part('Leather', capsule(.112, 1.16), back, [-.55 + i * .22, .02, .11], [0, 0, 0], [1, 1, .5]);
  for (const x of [-.74, .74]) part('Chrome', capsule(.035, 1.5), back, [x, 0, -.1]);

  // --- Başlık ---
  part('Chrome', cylinder(.045, .045, .42), back, [0, .95, -.1]);
  const head = node(back, [0, 1.2, -.02]);
  part('Leather', roundedBox(.8, .34, .24, .1), head);
  for (let i = 0; i < 3; i++) part('Leather', capsule(.11, .16), head, [-.22 + i * .22, 0, .1], [0, 0, 0], [1, 1, .5]);

  // --- Kolçaklar ---
  for (const side of [-1, 1]) {
    const x = side * .86;
    part('Leather', roundedBox(.26, .15, 1.28, .07), null, [x, 1.66, .02]);
    part('Chrome', roundedBox(.05, .32, 1.02, .02), null, [side * .78, 1.34, .04]);
    const curve = new THREE.CatmullRomCurve3([
      [x, 1.58, .64], [x, 1.36, .74], [x, 1.1, .6], [x, 1.02, .18], [x, 1.06, -.42], [x, 1.56, -.54],
    ].map(point => new THREE.Vector3(...point)));
    part('Chrome', new THREE.TubeGeometry(curve, seg.tube, .042, hi ? 12 : 6, false), null);
  }

  root.updateMatrixWorld(true);
  root.traverse(object => {
    const data = object.userData;
    if (!data?.geometry) return;
    let geometry = data.geometry.index ? data.geometry.toNonIndexed() : data.geometry;
    for (const name of Object.keys(geometry.attributes)) if (!['position', 'normal', 'uv'].includes(name)) geometry.deleteAttribute(name);
    geometry.applyMatrix4(object.matrixWorld);
    buckets[data.material].push(geometry);
  });

  const materials = {
    Leather: new THREE.MeshPhysicalMaterial({ name: 'Leather', color: 0x245e50, roughness: .48, clearcoat: .25, clearcoatRoughness: .5 }),
    Chrome: new THREE.MeshStandardMaterial({ name: 'Chrome', color: 0xe8ecee, roughness: .12, metalness: 1 }),
    BlackMetal: new THREE.MeshStandardMaterial({ name: 'BlackMetal', color: 0x1b1d1e, roughness: .38, metalness: .6 }),
    Rubber: new THREE.MeshStandardMaterial({ name: 'Rubber', color: 0x101112, roughness: .85, metalness: 0 }),
  };
  const chair = new THREE.Group();
  chair.name = 'BarberChair';
  let triangles = 0;
  for (const [name, list] of Object.entries(buckets)) {
    const merged = mergeVertices(mergeGeometries(list, false), 1e-4);
    merged.computeBoundingSphere();
    triangles += (merged.index?.count ?? merged.attributes.position.count) / 3;
    const mesh = new THREE.Mesh(merged, materials[name]);
    mesh.name = name;
    chair.add(mesh);
  }
  return { chair, triangles };
}

const exporter = new GLTFExporter();
await mkdir(new URL('../public/models/', import.meta.url), { recursive: true });
for (const [detail, file] of [['high', 'barber-chair.glb'], ['low', 'barber-chair-mobile.glb']]) {
  const { chair, triangles } = buildChair(detail);
  const binary = await new Promise((resolve, reject) => exporter.parse(chair, resolve, reject, { binary: true }));
  await writeFile(new URL(`../public/models/${file}`, import.meta.url), Buffer.from(binary));
  console.log(`${file}: ${Math.round(triangles)} üçgen, ${(binary.byteLength / 1024).toFixed(0)} KB`);
}
