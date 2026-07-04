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

// 문진(사실 보강) 재판정의 변동내역 — 직전 확률 판정 대비 결정론 diff. 첫 판정은 null.
export interface ReadingDelta {
  probabilityFrom: number;
  probabilityTo: number;
  factors: { name: string; from: string; to: string }[];
}

// ── 정밀 판독(스토리북 리포트) ──────────────────────────────────────────────
// 사연별 미스터리 장이 본문이다 — 장 개수와 제목이 사연마다 다르다.
// 요인 어휘는 안 내려온다: 채점 내부 용어라 유저 지면에 꺼내지 않는다.

// 확률을 만든 진단 한 줄. 문장은 1호출이 쓰고, 순위와 등급은 백엔드가 붙인다 —
// 판독은 이 카드를 읽기만 하고 고쳐 쓰지 못한다.
export interface ReadingDiagnosis {
  key: string;
  label: string; // 상대신호, 이별사유, 이별결심 등
  group: 'CORE' | 'CONDITIONAL' | 'EXTRA';
  rank: number;
  level: '매우유리' | '유리' | '중립' | '불리' | '매우불리';
  // 확인됨 / 없다는 것이 확인됨 / 아직 부분만 — "아직 모르는 것"을 화면이 구분해 그린다
  evidenceState: 'CONFIRMED' | 'ABSENCE_CONFIRMED' | 'PARTIAL';
  headline: string; // 핵심 판정 한 문장
  reading: string; // 펼쳤을 때 나오는 근거
  factIds: string[];
}

export interface ChapterPsychology {
  concept: string; // 관계심리 개념 — 장 안에서 실제 행동 순서로 풀린다
  reading: string;
}

export interface ReadingChapter {
  eyebrow: string | null; // 왜 이 장을 읽는지. 필요할 때만
  title: string; // 사연 고유의 질문형 제목
  chapterRole: string; // 내부 값. 화면 비노출
  interpretationId: string | null; // 유저 해석 교정 장이면 그 해석 참조. 화면 비노출
  answer: string; // 답부터
  reading: string;
  psychology: ChapterPsychology | null;
  repairPrinciple: string | null;
  evidenceIds: string[];
}

// 지금 어떻게 움직일지. 시점과 그 이유, 멈출 조건까지 한 덩이 —
// 행동만 있고 멈출 조건이 없으면 반응이 없을 때 계속 밀게 된다.
export interface ActionPlan {
  title: string;
  stance: string; // 내부 값(USE_EXISTING_EVENT 등). 화면 비노출
  answer: string;
  timing: string;
  whyThisTiming: string;
  goal: string;
  doList: string[];
  stopCondition: string;
  avoid: string[];
}

// v11.1 — 진단이 먼저다: 확률 게이지 → 진단 카드 → 심층 장 → 행동 계획 → 칩.
export interface StoryReport {
  diagnosisSummary: string;
  // 시간이 판에 어떻게 작용하는지의 보조 진단. 순위 카드와 섞지 않는다
  timeInsight: { label: string; headline: string; reading: string } | null;
  diagnosis: ReadingDiagnosis[];
  analysisSectionTitle: string; // 심층 장 묶음의 큰 질문
  analysisChapters: ReadingChapter[];
  actionPlan: ActionPlan;
  chipSeeds: string[];
  internal: {
    nowState: string;
    resolveState: string;
    remainState: string;
    reselectState: string;
  };
}

export interface ReadingView {
  report: StoryReport;
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
