import { api } from './client';

// LET_GO(놓아주기)는 폐기. 확률(POSSIBLE), 근거부족(INSUFFICIENT), 사귀는 중(DATING — 확률 잠금),
// 재회 성공(REUNITED — 전용 축하 화면). 폭력/학대 전용 잠금도 폐기 — 확률은 사실을 잰다.
export type Verdict = 'POSSIBLE' | 'INSUFFICIENT' | 'DATING' | 'REUNITED';

// 고정 5요인의 판정 하나. 백엔드가 화면 표기용 한국어 라벨로 내려준다.
// level '중립' + evidence '근거 없음'이면 "알려주면 정확해져요" 안내로 바뀐다.
export interface FactorView {
  name: string; // "상대신호" 등 7종 — 내려오는 순서가 무게 순서
  level: '매우유리' | '유리' | '중립' | '불리' | '매우불리';
  evidence: string;
  rationale: string | null;
  stage: string | null; // 대체자 불리의 세분("정황"/"정착"). 그 외 null
}

// 관찰 포인트 — "이게 확인되면 판이 바뀐다".
export interface WatchView {
  point: string;
  effect: string;
}

export interface AssessmentResponse {
  verdict: Verdict;
  probability: number | null; // 잠금 판정이면 null
  breakupType: string | null; // 이별 유형 라벨("충동형"). 과거(v1) 데이터와 잠금 판정은 null
  typeEvidence: string | null;
  jumpRule: string | null; // 점프 라벨("유저통보미련흔적" 등). 유저 통보 판이면 유형 대신 이게 대역을 정함
  relapseRisk: string | null; // 유지 전망 라벨("높음")
  relapseReason: string | null;
  reason: string;
  factors: FactorView[];
  watchFor: WatchView[];
  // 상담자가 물었는데 답이 안 온 질문. 비어 있으면 요인 슬롯의 고정 문구로 폴백한다.
  unansweredQuestions?: string[];
  createdAt: string | null; // INSUFFICIENT는 저장 안 돼서 null
  // 연속 실패 쿨다운으로 막힌 응답에만 채워진다. 남은 초(시각이 아니라)라서
  // 기기 시계가 틀어져 있어도 카운트다운이 어긋나지 않는다.
  retryAfterSeconds?: number | null;
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
