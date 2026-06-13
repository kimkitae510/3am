import { api } from './client';

// 공유 링크로 열리는 공개 분석 뷰. 근거 문장(evidence, rationale)은 서버가 아예 내려주지
// 않는다 — 공개 페이지는 확률, 총평, 신호 이름/등급까지만 그린다.
export interface SharedFactorView {
  name: string; // "상대신호"
  level: '매우유리' | '유리' | '중립' | '불리' | '매우불리';
  stage: string | null; // 대체자 세분("정황"/"정착"). 그 외 null
}

export interface SharedAssessmentResponse {
  probability: number | null;
  breakupType: string | null;
  jumpRule: string | null;
  reason: string;
  factors: SharedFactorView[];
  createdAt: string | null;
}

// 결과 화면의 공유하기 — 최신 분석의 공유 토큰. 분석 1건에 1개라 재공유는 같은 토큰이 온다.
export async function createShare(storyId: number): Promise<{ token: string }> {
  const { data } = await api.post<{ token: string }>(`/api/stories/${storyId}/assessments/share`);
  return data;
}

// 공유 링크의 공개 조회 — 로그인 없이 열린다.
export async function getSharedAssessment(token: string): Promise<SharedAssessmentResponse> {
  const { data } = await api.get<SharedAssessmentResponse>(`/api/share/${token}`);
  return data;
}
