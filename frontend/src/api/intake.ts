import { api } from './client';

// 첫 대화 전에 한 번 받는 기본 정보. 값은 서버 enum의 이름 그대로 주고받는다 —
// 화면에 보이는 한글 문구가 바뀌어도 저장값과 매칭 사전은 흔들리지 않게 한다.
export type IntakeGender = 'MALE' | 'FEMALE';
export type BreakupInitiator = 'PARTNER' | 'SELF' | 'PUSHED' | 'UNKNOWN';
export type ContactMode =
  | 'PARTNER_REACHES'
  | 'MUTUAL'
  | 'I_INITIATE'
  | 'READ_NO_REPLY'
  | 'NONE'
  | 'BLOCKED';
export type PriorReunion = 'NONE' | 'ONCE' | 'MANY';
export type PartnerNewRelation = 'CONFIRMED' | 'DENIED' | 'UNKNOWN';
export type PreBreakupChange =
  | 'SUDDEN'
  | 'FIGHTS_INCREASED'
  | 'GRADUAL_COOLING'
  | 'REPEATED_WARNINGS'
  | 'SINGLE_INCIDENT';
export type PartnerAction =
  | 'REACHED_OUT'
  | 'ASKED_TO_MEET'
  | 'SNS_TRACE'
  | 'BELONGINGS_ONLY'
  | 'CUT_OFF'
  | 'NOTHING';
export type ContactPoint =
  | 'NONE'
  | 'SCHOOL_OR_WORK'
  | 'NEIGHBORHOOD'
  | 'MUTUAL_FRIENDS'
  | 'SCHEDULED'
  | 'UNSETTLED';

export interface StoryIntake {
  // 상담자가 대화에서 부르는 이름. 폼에서 유일하게 비워둘 수 없는 칸이라 null이 아니다.
  callName: string;
  userAge: number | null;
  partnerAge: number | null;
  userGender: IntakeGender | null;
  datingMonths: number | null;
  daysSinceBreakup: number | null;
  initiator: BreakupInitiator | null;
  contactMode: ContactMode | null;
  priorReunion: PriorReunion | null;
  partnerHasNew: PartnerNewRelation | null;
  preBreakupChange: PreBreakupChange | null;
  partnerActions: PartnerAction[];
  contactPoints: ContactPoint[];
}

export interface StoryIntakeResponse extends StoryIntake {
  // false면 아직 안 낸 사연 — 이때만 폼을 띄운다. 나머지 값은 직전 사연에서 물려받은 미리 채움.
  submitted: boolean;
}

export const EMPTY_INTAKE: StoryIntake = {
  callName: '',
  userAge: null,
  partnerAge: null,
  userGender: null,
  datingMonths: null,
  daysSinceBreakup: null,
  initiator: null,
  contactMode: null,
  priorReunion: null,
  partnerHasNew: null,
  preBreakupChange: null,
  partnerActions: [],
  contactPoints: [],
};

export async function getIntake(storyId: number): Promise<StoryIntakeResponse> {
  const { data } = await api.get<StoryIntakeResponse>(`/api/stories/${storyId}/intake`);
  return data;
}

// 부분 수정이 아니라 통째 교체다 — 건너뛴 칸과 지운 칸을 구분할 수 없어 늘 전체를 보낸다.
export async function putIntake(storyId: number, intake: StoryIntake): Promise<StoryIntakeResponse> {
  const { data } = await api.put<StoryIntakeResponse>(`/api/stories/${storyId}/intake`, intake);
  return data;
}
