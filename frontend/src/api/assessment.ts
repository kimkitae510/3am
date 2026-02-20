import { api } from './client';

// LET_GO(놓아주기)는 폐기. 확률(POSSIBLE), 근거부족(INSUFFICIENT), 사귀는 중(DATING — 확률 잠금, 유형만),
// 재회 성공(REUNITED — 전용 축하 화면, 확률 없음).
export type Verdict = 'POSSIBLE' | 'INSUFFICIENT' | 'DATING' | 'REUNITED';

export interface DeductionView {
  signal: string;
  delta: number;
  evidence: string;
}

// 유형 판정에 실제로 쓰인 행동 근거 하나(감점 신호와 같은 문법)
export interface AttachmentSignalView {
  signal: string;
  evidence: string;
}

// CONFIRMED = 근거가 여러 상황에 걸쳐 충분, TENTATIVE = 아직 추정("~로 보여요" 톤으로 표시)
export type AttachmentConfidence = 'CONFIRMED' | 'TENTATIVE';

// 행동 가이드 한 항목. DO = 지금 도움이 되는 것, DONT = 지금은 피할 것.
export interface GuidanceView {
  kind: 'DO' | 'DONT';
  advice: string;
  basis: string | null; // 어떤 신호/유형에서 나온 조언인지 한 줄
}

export interface AssessmentResponse {
  verdict: Verdict;
  probability: number | null; // POSSIBLE일 때만
  partnerAttachment: string | null; // 상대 애착유형 라벨(한국어). 행동 근거 부족이면 null (내 유형은 폐기)
  attachmentConfidence: AttachmentConfidence | null; // 유형이 null이면 null
  attachmentSignals: AttachmentSignalView[]; // 유형 판정 근거 목록
  reason: string;
  deductions: DeductionView[];
  guidance: GuidanceView[]; // 행동 가이드. POSSIBLE 외에는 빈 목록
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

// "만나는 중" 잠금을 유저가 직접 번복한다(진단이 오해했을 수 있음).
// 오판이던 잠금 판정이 지워지고 직전 확률 진단이 돌아온다(없으면 null — 첫 진단 안내로).
export async function confirmBreakup(storyId: number): Promise<AssessmentResponse | null> {
  const { data } = await api.post<AssessmentResponse | ''>(
    `/api/stories/${storyId}/assessments/confirm-breakup`,
  );
  return data || null;
}

// "상대의 재회 제안 유효(100%)" 확정을 유저가 직접 번복한다(제안이 아니었거나 없던 일이 됨).
// 원장에 정정이 남고, 저장된 신호의 재합산 값으로 즉시 되돌린 결과가 돌아온다(재진단 불필요).
export async function retractOffer(storyId: number): Promise<AssessmentResponse> {
  const { data } = await api.post<AssessmentResponse>(
    `/api/stories/${storyId}/assessments/retract-offer`,
  );
  return data;
}
