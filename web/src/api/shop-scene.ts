export type SceneChair = {
  chairKey: string;
  label: string;
  staffId: string;
  staffName: string;
  positionX: number;
  positionZ: number;
  rotationY: number;
};

export type PublicSceneManifest = {
  version: number;
  templateKey: 'CLASSIC_3_CHAIR';
  accentColor: string;
  chairs: SceneChair[];
};

export type ShopScene = PublicSceneManifest & {
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
  revision: number;
  publishedAt: string | null;
};

export type ShopDesignState = {
  draft: ShopScene;
  published: ShopScene;
  history: number[];
  staff: { id: string; displayName: string }[];
};
