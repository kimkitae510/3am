import { api } from './client';
import type { RelationshipPsychology } from './assessment';

// 공유 링크로 열리는 공개 분석 뷰. 본인 화면과 같은 재료가 내려온다 — 빠지는 건 둘뿐이다:
// 중립(판단 안 됨) 요인(남에겐 정보가 아니다)과 비슷한 사례(유저 것이 아니라 서비스 자산).
export interface SharedFactorView {
  name: string; // "상대신호"
  level: '매우유리' | '유리' | '중립' | '불리' | '매우불리';
  evidence: string;
  rationale: string | null;
  stage: string | null; // 대체자 세분("정황"/"정착"). 그 외 null
}

export interface SharedAssessmentResponse {
  probability: number | null;
  breakupType: string | null;
  typeEvidence: string | null;
  jumpRule: string | null;
  relapseRisk: string | null;
  relapseReason: string | null;
  relationshipPsychology?: RelationshipPsychology | null;
  reason: string;
  factors: SharedFactorView[];
  createdAt: string | null;
}

// 결과 화면의 공유하기 — 최신 분석의 공유 토큰. 분석 1건에 살아 있는 링크는 1개라
// 재공유는 같은 토큰이 온다. 단 한 번 취소한 뒤 다시 공유하면 새 토큰이 나온다.
export async function createShare(storyId: number): Promise<{ token: string }> {
  const { data } = await api.post<{ token: string }>(`/api/stories/${storyId}/assessments/share`);
  return data;
}

// 살아 있는 링크가 있는지. 없으면 token이 null이다 — 화면은 이 값으로 "공유 중" 줄과
// 취소 버튼을 그릴지 정한다.
export async function getActiveShare(storyId: number): Promise<string | null> {
  const { data } = await api.get<{ token: string | null }>(
    `/api/stories/${storyId}/assessments/share`,
  );
  return data.token;
}

// 공유 취소 — 이 사연의 살아 있는 링크를 전부 끈다. 이미 보낸 주소도 그 순간부터 안 열린다.
export async function revokeShare(storyId: number): Promise<void> {
  await api.delete(`/api/stories/${storyId}/assessments/share`);
}

// 공유 링크의 공개 조회 — 로그인 없이 열린다.
export async function getSharedAssessment(token: string): Promise<SharedAssessmentResponse> {
  const { data } = await api.get<SharedAssessmentResponse>(`/api/share/${token}`);
  return data;
}
