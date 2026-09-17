import * as THREE from 'three';
import { GLTFExporter } from 'three/examples/jsm/exporters/GLTFExporter.js';
import { writeFile, mkdir } from 'node:fs/promises';

globalThis.FileReader = class {
  readAsArrayBuffer(blob) { blob.arrayBuffer().then(value => { this.result = value; this.onloadend?.(); }); }
  readAsDataURL(blob) { blob.arrayBuffer().then(value => { this.result = `data:${blob.type};base64,${Buffer.from(value).toString('base64')}`; this.onloadend?.(); }); }
};

const chair = new THREE.Group();
const green = new THREE.MeshStandardMaterial({ color: 0x245e50, roughness: 0.52, metalness: 0.08 });
const metal = new THREE.MeshStandardMaterial({ color: 0x242c2a, roughness: 0.22, metalness: 0.72 });
const add = (geometry, material, position, rotation = [0, 0, 0]) => {
  const mesh = new THREE.Mesh(geometry, material);
  mesh.position.set(...position); mesh.rotation.set(...rotation); mesh.castShadow = true; chair.add(mesh);
};
add(new THREE.BoxGeometry(1.55, .34, 1.38), green, [0, 1.25, 0]);
add(new THREE.BoxGeometry(1.55, 1.8, .3), green, [0, 2.05, -.58], [-.12, 0, 0]);
add(new THREE.BoxGeometry(.28, .22, 1.25), metal, [-.92, 1.52, 0]);
add(new THREE.BoxGeometry(.28, .22, 1.25), metal, [.92, 1.52, 0]);
add(new THREE.CylinderGeometry(.68, .82, .24, 32), metal, [0, .12, 0]);
add(new THREE.CylinderGeometry(.12, .12, 1.05, 20), metal, [0, .68, 0]);

const exporter = new GLTFExporter();
const binary = await new Promise((resolve, reject) => exporter.parse(chair, resolve, reject, { binary: true }));
await mkdir(new URL('../public/models/', import.meta.url), { recursive: true });
await writeFile(new URL('../public/models/barber-chair.glb', import.meta.url), Buffer.from(binary));
