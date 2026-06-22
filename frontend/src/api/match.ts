import { api } from './client';

export interface SimilarCase {
  id: number;
  story: string;
  gender: string | null;
  ageGroup: string | null;
  reason: string | null;
  subReasons: string[]; // 앞이 주(방아쇠)
  dumper: string | null;
  contactState: string | null;
  outcome: string | null; // 성공 / 실패
  periodLabel: string | null; // "재회 네 달째" 같은 시점 프로즈
  datingMonths: number | null; // 만난 기간(개월)
  monthsSinceBreakup: number | null; // 이별 후 경과(개월)
  // 나와 겹친 지점의 태그("3년 만남", "차단", "이별의 결정적 계기"). 서버가 노출 가능한 값만
  // 골라 내려주므로 화면은 받은 것을 그대로 그린다 — 거를 값은 응답에 실리지도 않는다.
  matchedTags: string[];
}

// emptyReason: NO_PROFILE(분석을 더 해야 열림) / NO_MATCH(닮은 사례가 아직 없음).
// 둘을 갈라 말해야 화면이 "대화를 더 해달라"와 "데이터가 부족하다"를 구분해 안내할 수 있다.
export interface SimilarCases {
  cases: SimilarCase[];
  emptyReason: 'NO_PROFILE' | 'NO_MATCH' | null;
}

// 분석이 뽑아둔 분류로 참조 사례를 찾는다. LLM을 안 쓰므로 횟수 차감이 없다.
export async function getSimilarCases(storyId: number): Promise<SimilarCases> {
  const { data } = await api.get<SimilarCases>(`/api/stories/${storyId}/similar-cases`);
  return data;
}

// ---- 유료 사례 매칭 ----
// 무료 매칭과 달리 LLM이 후보 본문을 읽고 고른다. 확률 대역이 구성의 상한을 정하고
// (낮음 성공1+실패1, 보통 성공1~2+실패1, 높음 성공2), 실제 장수는 LLM이 정한다.

export interface PickedCase extends SimilarCase {
  similarity: string | null; // 내 상황과 겹치는 지점
  reading: string | null; // 이 판이 그렇게 된 이유의 추측
}

// summary: 카드를 가로질러 읽는 한 줄. 카드가 한 장이거나 폴백으로 채운 판에서는 null이다.
// locked: 아직 안 돌린 상태 — 화면은 실행 버튼을 그린다.
export interface PickedCases {
  cases: PickedCase[];
  summary: string | null;
  emptyReason: 'NO_PROFILE' | 'NO_MATCH' | null;
  locked: boolean;
}

// 이미 돌렸는지 확인만 한다. 횟수 차감이 없다.
export async function getPickedCases(storyId: number): Promise<PickedCases> {
  const { data } = await api.get<PickedCases>(`/api/stories/${storyId}/similar-cases/picked`);
  return data;
}

// 실행. 같은 분석에 이미 결과가 있으면 저장분이 그대로 오고 차감되지 않는다.
export async function pickCases(storyId: number): Promise<PickedCases> {
  const { data } = await api.post<PickedCases>(`/api/stories/${storyId}/similar-cases/picked`);
  return data;
}
