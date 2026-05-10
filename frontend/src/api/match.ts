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
  outcome: string | null; // 성공 / 실패 / 성공후재이별
  periodLabel: string | null; // "재회 네 달째" 같은 시점 프로즈
  reunionRecord: string | null;
  datingMonths: number | null; // 만난 기간(개월) — 카드 메타 줄
  monthsSinceBreakup: number | null; // 이별 후 경과(개월)
  matchedOn: string | null; // 닮은 지점 라벨 나열("이별 사유, 지금 연락 상태")
}

// emptyReason: NO_PROFILE(진단을 더 해야 열림) / NO_MATCH(닮은 사례가 아직 없음).
// 둘을 갈라 말해야 화면이 "대화를 더 해달라"와 "데이터가 부족하다"를 구분해 안내할 수 있다.
export interface SimilarCases {
  cases: SimilarCase[];
  emptyReason: 'NO_PROFILE' | 'NO_MATCH' | null;
}

// 진단이 뽑아둔 분류로 참조 사례를 찾는다. LLM을 안 쓰므로 횟수 차감이 없다.
export async function getSimilarCases(storyId: number): Promise<SimilarCases> {
  const { data } = await api.get<SimilarCases>(`/api/stories/${storyId}/similar-cases`);
  return data;
}
