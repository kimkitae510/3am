// 분석 결과를 화면 카드로 번역하는 사전들. 분석 페이지와 공유(공개) 페이지가 같은 값을
// 쓰도록 한곳에 모은다 — 따로 두면 한쪽만 고쳐 두 화면의 등급이 어긋난다.

import type { RelationshipPsychology } from '../api/assessment';

export type ChipLevel = '매우유리' | '유리' | '중립' | '불리' | '매우불리';

// 판단 근거가 없을 때 백엔드가 채우는 문자열. 사실 줄을 그릴지 가를 때 쓴다.
export const NO_EVIDENCE = '근거 없음';

// 요인 이름은 슬롯 이름을 거의 그대로 노출한다 — 통보온도, 관계자산, 대체자 같은
// 조어가 이 서비스 유저층(타로류 감성)에 풀어 쓴 설명("헤어질 때 상대 태도")보다
// 잘 읽힌다는 피드백. 예외는 유저대처 하나 — 유저를 "유저"라 부르는 화면은 차갑다.
export const FACTOR_LABEL: Record<string, string> = {
  상대신호: '상대신호',
  대체자: '대체자',
  유저대처: '이별 후 대처',
  통보온도: '통보온도',
  상대패턴: '상대패턴',
  관계자산: '관계자산',
  연락통로: '연락이 닿는지',
  접점: '접점',
};

// 대체자만 level이 부호만 정하고 크기는 stage가 정한다(정황 -6, 정착 -12, 정착은 하한도
// 안 본다). 그대로 두면 무게가 두 배 차이인 둘이 같은 칩으로 보이고, 정황인데 '매우불리'로
// 떠서 라벨이 셀수록 덜 깎이는 역전까지 난다. 칩만 실제 무게로 맞춘다 — stage 값('정황',
// '정착')은 내부 용어라 화면에 쓰지 않는다.
export const STAGE_LEVEL: Record<string, '불리' | '매우불리'> = { 정황: '불리', 정착: '매우불리' };

// 유저가 통보한 이별은 유형 대신 상대의 미련 단계(점프)가 구간을 정한다 — 카드도 그 문법으로.
export const JUMP_CARD: Record<string, { level: '매우유리' | '유리' | '불리' | '매우불리'; reading: string }> = {
  유저통보상대미련: { level: '매우유리', reading: '내가 통보했지만 상대에게 미련이 뚜렷하게 남아 있음.' },
  유저통보미련흔적: { level: '유리', reading: '내가 통보했고 상대에게 미련의 흔적이 남아 있음.' },
  유저통보미련없음: { level: '매우불리', reading: '내가 통보한 뒤로 상대가 붙잡거나 미련을 보이는 행동이 아직 없음.' },
  상대접촉재개: { level: '유리', reading: '닫혀 있던 상대가 통로를 열고 먼저 연락해 와 관계가 다시 움직일 여지가 생김.' },
  상대재회의사: { level: '매우유리', reading: '상대가 다시 만날 뜻을 내비치고 있음.' },
  반복재회패턴: { level: '매우유리', reading: '헤어질 때마다 다시 만나온 관계라 이번에도 돌아올 여지가 있음.' },
  상대문닫힘: { level: '매우불리', reading: '상대가 정리를 요구하고 문을 닫아둔 상태임.' },
  상대결혼약혼: { level: '매우불리', reading: '상대의 결혼이나 약혼이 확인돼 되돌리기 가장 어려운 상태임.' },
};

// 유형은 판정이 아니라 대역이지만, 카드 칩은 대역 위치를 4단으로 번역해 보여준다
// (신뢰붕괴형에 '불리'는 과소 표현이라는 실측 피드백 — 바닥 구간 유형은 '매우불리'로).
export const TYPE_CHIP: Record<string, '매우유리' | '유리' | '불리' | '매우불리'> = {
  충동형: '매우유리',
  상황형: '유리',
  외부요인형: '불리',
  권태식음형: '불리',
  소진형: '불리',
  결심완료형: '매우불리',
  환승형: '매우불리',
  신뢰붕괴형: '매우불리',
};

// 관계 심리 카드 세 줄(애착 경향, 관계 패턴, 핵심 욕구). 보류값(판단보류, 뚜렷하지않음)은
// 행을 만들지 않는다 — "모르겠다"를 카드로 만들면 소음이다.
export interface PsychRow {
  name: string;
  value: string;
  description: string | null;
}

export function psychRows(psych: RelationshipPsychology | null | undefined): PsychRow[] {
  if (!psych) return [];
  const rows: PsychRow[] = [];
  const attachment = psych.attachment;
  const sides = [
    attachment?.user && attachment.user.label !== '판단보류' ? `나 ${attachment.user.label}` : null,
    attachment?.partner && attachment.partner.label !== '판단보류'
      ? `상대 ${attachment.partner.label}`
      : null,
  ].filter((s): s is string => s != null);
  if (attachment && sides.length > 0) {
    rows.push({ name: '애착 경향', value: sides.join(', '), description: attachment.description });
  }
  const pattern = psych.interactionPattern;
  if (pattern && pattern.label !== '뚜렷하지않음') {
    rows.push({ name: '관계 패턴', value: pattern.label, description: pattern.description });
  }
  const needs = psych.needConflict;
  if (needs?.left && needs.right) {
    rows.push({
      name: '핵심 욕구',
      value: `${needs.left} ↔ ${needs.right}`,
      description: needs.description,
    });
  }
  return rows;
}

export const TYPE_READING: Record<string, string> = {
  충동형: '감정이 격해진 순간의 이별이라 되돌릴 여지가 큰 편임.',
  상황형: '마음보다 환경이 가른 이별이라 되돌릴 여지가 큰 편임.',
  외부요인형: '마음 밖의 고착된 조건이 막고 있어 쉽지 않은 편임.',
  권태식음형: '상대의 설렘과 애정이 잦아들어 끝난 이별이라 되돌리기 어려운 편임.',
  소진형: '상대가 지쳐서 끝낸 이별이라 되돌리기 어려운 편임.',
  결심완료형: '상대가 오래 고민한 끝에 내린 통보라 되돌리기 어려운 편임.',
  환승형: '상대의 마음이 이미 다른 사람에게 옮겨간 이별이라 되돌리기 어려운 편임.',
  신뢰붕괴형: '내 행동으로 상대의 신뢰가 무너진 이별이라 되돌리기 가장 어려운 편임.',
};
