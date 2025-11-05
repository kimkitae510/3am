import { api } from './client';

// LET_GO(놓아주기)는 폐기. 확률(POSSIBLE) 또는 근거부족(INSUFFICIENT)만.
export type Verdict = 'POSSIBLE' | 'INSUFFICIENT';

export interface DeductionView {
  signal: string;
  delta: number;
  evidence: string;
}

export interface AssessmentResponse {
  verdict: Verdict;
  probability: number | null; // POSSIBLE일 때만
  myBreakupType: string | null; // 라벨(한국어)
  partnerType: string | null;
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
