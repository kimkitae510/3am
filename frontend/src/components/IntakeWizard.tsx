import type { IntakeGender, StoryIntake } from '../api/intake';
import { EMPTY_INTAKE } from '../api/intake';
import styles from './IntakeWizard.module.css';

// 사연을 받기 전에 한 장씩 넘기며 받는 네 가지. 열둘을 한 화면에 세우면 접수증이 되고,
// 사연을 다 쓴 뒤에 받으면 털어놓은 사람에게 폼을 내미는 꼴이 된다.
// 여기 남긴 것은 셋을 다 만족하는 것뿐이다 — 사연을 안 읽고도 물을 수 있고, 답이 하나로
// 정해지고, 안 물으면 상담자가 첫 턴을 되묻는 데 써버리는 것.
// 나머지는 사연을 들은 뒤에 묻는다: 이별 사유나 귀책처럼 답이 하나가 아닌 것을 사연도 쓰기
// 전에 보기로 주면, 유저가 자기 상황을 보기에서 고르고 그게 그대로 오진이 된다.

export const INTAKE_STEPS = 4;

// 숫자를 문자열로 들고 있는다 — number로 받으면 "1"을 지우는 중간 상태가 0으로 굳어,
// 안 지워지는 칸이 된다.
export interface IntakeDraft {
  userAge: string;
  partnerAge: string;
  userGender: IntakeGender | null;
  datingYears: string;
  datingMonths: string;
  sinceMonths: string;
  sinceDays: string;
}

export const EMPTY_DRAFT: IntakeDraft = {
  userAge: '',
  partnerAge: '',
  userGender: null,
  datingYears: '',
  datingMonths: '',
  sinceMonths: '',
  sinceDays: '',
};

// 기간은 두 칸으로 받아 하나로 합친다. 단위를 고르게 하면 탭이 한 번 더 들어가고,
// 한 칸으로 받으면 "2년 3개월"을 27로 환산하는 일을 유저에게 시키게 된다.
// 둘 다 비면 0이 아니라 null이다 — 안 물어본 것과 0을 섞으면 진단이 조용히 기운다.
function merge(big: string, small: string, unit: number): number | null {
  if (big === '' && small === '') return null;
  return (Number(big) || 0) * unit + (Number(small) || 0);
}

export function draftToIntake(draft: IntakeDraft): StoryIntake {
  return {
    ...EMPTY_INTAKE,
    userAge: draft.userAge === '' ? null : Number(draft.userAge),
    partnerAge: draft.partnerAge === '' ? null : Number(draft.partnerAge),
    userGender: draft.userGender,
    datingMonths: merge(draft.datingYears, draft.datingMonths, 12),
    daysSinceBreakup: merge(draft.sinceMonths, draft.sinceDays, 30),
  };
}

// 하나도 안 채운 채 넘어갔으면 저장을 아예 안 보낸다 — 전부 null로 덮으면 서버가
// "냈다"로 표시해, 나중에 물어야 할 자리에서 이미 받은 것으로 읽힌다.
export function hasAnyAnswer(draft: IntakeDraft): boolean {
  return Object.values(draft).some((v) => v !== '' && v !== null);
}

const ASKS = [
  '두 분 나이가 어떻게 되십니까',
  '성별이 어떻게 되십니까',
  '얼마나 만나셨습니까',
  '헤어진 지 얼마나 되셨습니까',
];

function answered(step: number, draft: IntakeDraft): boolean {
  if (step === 0) return draft.userAge !== '' || draft.partnerAge !== '';
  if (step === 1) return draft.userGender !== null;
  if (step === 2) return draft.datingYears !== '' || draft.datingMonths !== '';
  return draft.sinceMonths !== '' || draft.sinceDays !== '';
}

export function IntakeWizard({
  step,
  draft,
  onChange,
  onNext,
  onBack,
}: {
  step: number;
  draft: IntakeDraft;
  onChange: (patch: Partial<IntakeDraft>) => void;
  onNext: () => void;
  onBack: () => void;
}) {
  const filled = answered(step, draft);

  return (
    <div className={styles.wrap}>
      <div className={styles.count}>
        {step + 1} / {INTAKE_STEPS}
      </div>
      <h2 className={styles.ask}>{ASKS[step]}</h2>

      {/* 단계가 바뀌면 통째로 새로 그린다 — 안 그러면 리액트가 같은 input을 재활용해서
          앞 단계에 준 자동 초점이 다음 단계에서 안 걸린다 */}
      <div className={styles.body} key={step}>
        {step === 0 && (
          <>
            <NumberBox
              label="나"
              unit="세"
              autoFocus
              value={draft.userAge}
              onChange={(v) => onChange({ userAge: v })}
            />
            <NumberBox
              label="상대"
              unit="세"
              value={draft.partnerAge}
              onChange={(v) => onChange({ partnerAge: v })}
            />
          </>
        )}

        {step === 1 && (
          <div className={styles.options}>
            {(['MALE', 'FEMALE'] as const).map((g) => (
              <button
                key={g}
                className={`${styles.option} ${draft.userGender === g ? styles.optionOn : ''}`}
                // 같은 값을 다시 누르면 해제된다 — 잘못 고른 뒤 되돌릴 길이 이것뿐이다
                onClick={() => onChange({ userGender: draft.userGender === g ? null : g })}
              >
                {g === 'MALE' ? '남성' : '여성'}
              </button>
            ))}
          </div>
        )}

        {step === 2 && (
          <>
            <NumberBox
              unit="년"
              autoFocus
              value={draft.datingYears}
              onChange={(v) => onChange({ datingYears: v })}
            />
            <NumberBox
              unit="개월"
              value={draft.datingMonths}
              onChange={(v) => onChange({ datingMonths: v })}
            />
          </>
        )}

        {step === 3 && (
          <>
            <NumberBox
              unit="개월"
              autoFocus
              value={draft.sinceMonths}
              onChange={(v) => onChange({ sinceMonths: v })}
            />
            <NumberBox
              unit="일"
              value={draft.sinceDays}
              onChange={(v) => onChange({ sinceDays: v })}
            />
          </>
        )}
      </div>

      <div className={styles.foot}>
        {/* 버튼을 둘로 나누지 않는다. 안 채운 채로 누르는 것과 건너뛰는 것이 어차피 같은
            일이라, 따로 세우면 유저가 둘의 차이를 찾느라 멈춘다. 라벨만 바뀐다 */}
        <button className={styles.next} onClick={onNext}>
          {filled ? '다음' : '건너뛰기'}
        </button>
        {step > 0 && (
          <button className={styles.back} onClick={onBack}>
            이전
          </button>
        )}
      </div>
    </div>
  );
}

function NumberBox({
  label,
  unit,
  value,
  autoFocus,
  onChange,
}: {
  label?: string;
  unit: string;
  value: string;
  autoFocus?: boolean;
  onChange: (value: string) => void;
}) {
  return (
    <label className={styles.numberBox}>
      {label && <span className={styles.numberLabel}>{label}</span>}
      <input
        className={styles.number}
        type="number"
        inputMode="numeric"
        autoFocus={autoFocus}
        value={value}
        onChange={(e) => onChange(e.target.value)}
      />
      <span className={styles.unit}>{unit}</span>
    </label>
  );
}
