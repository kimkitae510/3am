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

// 관계 심리 — 확률과 무관한 "관계 이해용" 층. 라벨은 화면 표기용 한국어("불안형", "추구-회피").
// confidence("높음"/"중간"/"낮음")는 지금 화면에선 안 쓰지만 백엔드가 판정과 함께 저장한다.
export interface AttachmentStyle {
  label: string;
  confidence: string;
}

export interface RelationshipPsychology {
  attachment: {
    user: AttachmentStyle | null;
    partner: AttachmentStyle | null;
    description: string | null;
  } | null;
  interactionPattern: { label: string; confidence: string; description: string | null } | null;
  needConflict: { left: string | null; right: string | null; description: string | null } | null;
}

// 정밀 판독(2호출)의 한 질문 답. state는 내부 값 — 국면의 "현재 판독" 소계에만 번역해 쓴다.
export interface ReadingSection {
  state: string;
  answer: string;
  reading: string;
}

// 문진(사실 보강) 재판정의 변동내역 — 직전 확률 판정 대비 결정론 diff. 첫 판정은 null.
export interface ReadingDelta {
  probabilityFrom: number;
  probabilityTo: number;
  factors: { name: string; from: string; to: string }[];
}

// 정밀 판독 — 표지(판정이 주인공, 확률은 보조)와 여섯 장, 국면.
// 요인 어휘는 안 내려온다: 채점 내부 용어라 유저 지면에 꺼내지 않는다.
export interface ReadingView {
  overall: string; // 표지 판정 — 확률보다 크게 걸리는 문장
  coverRaise: string; // 가능성을 열어두는 가장 큰 이유 한 줄
  coverBlock: string; // 지금 막는 가장 큰 이유 한 줄
  now: ReadingSection;
  resolve: ReadingSection;
  remain: ReadingSection;
  drift: string; // 왜 멀어졌는가 — 장면 해석 + 이번 갈등의 상호작용 방식
  blocking: string; // 지금 재회를 막는 것 — 감정/현실 구분
  reselect: ReadingSection & { open: string; route: string };
  phase: string;
  // 케이스별 장 제목(now/resolve/remain/drift/blocking/route). 없는 키는 고정 제목으로 폴백.
  chapterTitles: Record<string, string> | null;
  delta: ReadingDelta | null;
  createdAt: string | null;
}

export interface AssessmentResponse {
  verdict: Verdict;
  probability: number | null; // 잠금 판정이면 null
  breakupType: string | null; // 이별 유형 라벨("충동형"). 과거(v1) 데이터와 잠금 판정은 null
  typeEvidence: string | null;
  jumpRule: string | null; // 점프 라벨("유저통보미련흔적" 등). 유저 통보 판이면 유형 대신 이게 대역을 정함
  relapseRisk: string | null; // 유지 전망 라벨("높음")
  relapseReason: string | null;
  // 관계 심리(애착 경향, 관계 패턴, 욕구 충돌). 정보가 부족한 진단과 옛 데이터는 null
  relationshipPsychology?: RelationshipPsychology | null;
  reason: string;
  factors: FactorView[];
  watchFor: WatchView[];
  // 상담자가 물었는데 답이 안 온 질문. 비어 있으면 요인 슬롯의 고정 문구로 폴백한다.
  unansweredQuestions?: string[];
  createdAt: string | null; // INSUFFICIENT는 저장 안 돼서 null
  // 연속 실패 쿨다운으로 막힌 응답에만 채워진다. 남은 초(시각이 아니라)라서
  // 기기 시계가 틀어져 있어도 카운트다운이 어긋나지 않는다.
  retryAfterSeconds?: number | null;
  // 정밀 판독. 확률 있는 일반 판정에만 붙고, 판독 생성이 실패한 판정은 null(판정부만 그린다).
  reading?: ReadingView | null;
}

// 지금 대화를 근거로 새 분석을 실행한다(POST). INSUFFICIENT면 저장되지 않는다.
export async function runAssessment(storyId: number): Promise<AssessmentResponse> {
  const { data } = await api.post<AssessmentResponse>(`/api/stories/${storyId}/assessments`);
  return data;
}

// 저장된 분석 이력(최신순). 05 히스토리에서 사용.
export async function getAssessments(storyId: number): Promise<AssessmentResponse[]> {
  const { data } = await api.get<AssessmentResponse[]>(`/api/stories/${storyId}/assessments`);
  return data;
}

// "만나는 중" 잠금을 유저가 직접 번복한다(분석이 오해했을 수 있음).
// 오판이던 잠금 판정이 지워지고 직전 확률 분석이 돌아온다(없으면 null — 첫 분석 안내로).
export async function confirmBreakup(storyId: number): Promise<AssessmentResponse | null> {
  const { data } = await api.post<AssessmentResponse | ''>(
    `/api/stories/${storyId}/assessments/confirm-breakup`,
  );
  return data || null;
}

// "상대의 재회 제안 유효(100%)" 확정을 유저가 직접 번복한다(제안이 아니었거나 없던 일이 됨).
// 원장에 정정이 남고, 저장된 신호의 재합산 값으로 즉시 되돌린 결과가 돌아온다(재분석 불필요).
export async function retractOffer(storyId: number): Promise<AssessmentResponse> {
  const { data } = await api.post<AssessmentResponse>(
    `/api/stories/${storyId}/assessments/retract-offer`,
  );
  return data;
}
