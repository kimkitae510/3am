import { api } from './client';

// LET_GO(놓아주기)는 폐기. 확률(POSSIBLE), 근거부족(INSUFFICIENT), 사귀는 중(DATING — 확률 잠금, 유형만),
// 재회 성공(REUNITED — 전용 축하 화면, 확률 없음).
export type Verdict = 'POSSIBLE' | 'INSUFFICIENT' | 'DATING' | 'REUNITED';

export interface DeductionView {
  signal: string;
  delta: number;
  evidence: string;
}

export interface AssessmentResponse {
  verdict: Verdict;
  probability: number | null; // POSSIBLE일 때만
  myAttachment: string | null; // 유저 애착유형 라벨(한국어). 행동 근거 부족이면 null
  partnerAttachment: string | null; // 상대 애착유형 라벨
  myAttachmentEvidence: string | null; // 유형 판정 근거 한 줄. 유형이 null이면 null
  partnerAttachmentEvidence: string | null;
  reason: string;
  deductions: DeductionView[];
  createdAt: string | null; // INSUFFICIENT는 저장 안 돼서 null
}

// 지금 대화를 근거로 새 진단을 실행한다(POST). INSUFFICIENT면 저장되지 않는다.
export async function runAssessment(storyId: number): Promise<AssessmentResponse> {
  const { data } = await api.post<AssessmentResponse>(`/api/stories/${storyId}/assessments`);
  return data;
}

// 저장된 진단 이력(최신순). 05 히스토리에서 사용.
export async function getAssessments(storyId: number): Promise<AssessmentResponse[]> {
  const { data } = await api.get<AssessmentResponse[]>(`/api/stories/${storyId}/assessments`);
  return data;
}

// "사귀는 중" 잠금을 유저가 직접 번복한다(진단이 오해했을 수 있음).
// 원장에 확인 기록만 남고, 확률은 헤어진 경위를 대화한 뒤의 다음 진단에서 열린다.
export async function confirmBreakup(storyId: number): Promise<void> {
  await api.post(`/api/stories/${storyId}/assessments/confirm-breakup`);
}

// "상대의 재회 제안 유효(100%)" 확정을 유저가 직접 번복한다(제안이 아니었거나 없던 일이 됨).
// 원장에 정정이 남고, 저장된 신호의 재합산 값으로 즉시 되돌린 결과가 돌아온다(재진단 불필요).
export async function retractOffer(storyId: number): Promise<AssessmentResponse> {
  const { data } = await api.post<AssessmentResponse>(
    `/api/stories/${storyId}/assessments/retract-offer`,
  );
  return data;
}
