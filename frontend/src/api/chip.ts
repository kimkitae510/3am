import { api } from './client';

// 누르면 바로 보낼지, 먼저 내용을 받을지. 칩마다 폼을 따로 만들지 않으려고 서버가 내려주는 구분.
export type ChipInteraction = 'DIRECT' | 'INPUT';

// INPUT 칩이 띄우는 입력 UI 문구. 여러 칩이 같은 프리셋을 공유한다.
export interface ChipInputPreset {
  title: string;
  placeholder: string;
  helper: string;
  submitLabel: string;
}

// 화면에 나가는 칩. 전문 상담 프롬프트는 서버에만 있고 여기 실리지 않는다.
export interface ChipView {
  id: string;
  label: string;
  module: string;
  interaction: ChipInteraction;
  inputPreset: ChipInputPreset | null;
}

// 추천 3개 밖의 질문을 직접 고르는 전체 목록. 사연마다 고를 수 있는 것이 다르다 —
// 분석을 안 받은 사연에서는 분석 결과를 설명하는 칩이 목록에서 빠진다.
export async function getStoryChips(storyId: number): Promise<ChipView[]> {
  const { data } = await api.get<ChipView[]>(`/api/stories/${storyId}/chips`);
  return data;
}
