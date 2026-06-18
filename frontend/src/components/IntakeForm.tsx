import { useState } from 'react';
import {
  EMPTY_INTAKE,
  type ContactPoint,
  type PartnerAction,
  type StoryIntake,
} from '../api/intake';
import styles from './IntakeForm.module.css';

// 사연을 보내기 직전에 한 번 받는 기본 정보. 나이나 기간처럼 답이 하나로 정해지는 사실을
// 산문으로 쓰게 하면 유저는 손이 아프고, 빠뜨리면 상담자가 첫 질문을 그걸 되묻는 데 써버려
// 정작 파고들 것을 못 묻는다. 그 질문 예산을 아끼려고 앞에 세운 화면이다.
// 이별 사유와 귀책은 여기서 안 묻는다 — 답이 하나가 아니라 객관식으로 받으면 유저가 자기
// 사연을 보기에서 고르고 그게 그대로 오진이 된다. 그건 사연 본문이 할 일이다.

const INITIATORS = [
  { value: 'PARTNER', label: '상대가 먼저' },
  { value: 'SELF', label: '내가 먼저' },
  // 겉으로는 내가 통보한 이별인데 구도는 상대 통보인 판. 이걸 가르지 않으면 정말로 내
  // 마음이 식어서 찬 사례가 붙는다 — 폼을 만든 이유 중 가장 큰 하나다.
  { value: 'PUSHED', label: '말은 내가 꺼냈지만 상대가 그렇게 만들었다' },
  { value: 'UNKNOWN', label: '잘 모르겠다' },
] as const;

const CONTACT_MODES = [
  { value: 'PARTNER_REACHES', label: '상대가 먼저 연락해 온다' },
  { value: 'MUTUAL', label: '서로 주고받는다' },
  { value: 'I_INITIATE', label: '내가 보내면 답은 온다' },
  { value: 'READ_NO_REPLY', label: '읽고 답이 없다' },
  { value: 'NONE', label: '아예 없다' },
  { value: 'BLOCKED', label: '차단당했다' },
] as const;

const PRIOR_REUNIONS = [
  { value: 'NONE', label: '이번이 처음' },
  { value: 'ONCE', label: '한 번 있다' },
  { value: 'MANY', label: '두 번 이상' },
] as const;

// "없음"과 "모름"을 반드시 가른다 — 확인 안 된 것을 없음으로 저장하면 진단이 유리한 쪽으로
// 조용히 기운다. 그래서 기본값도 모름이 아니라 아예 비워둔다(고르게 한다).
const PARTNER_NEW = [
  { value: 'CONFIRMED', label: '있는 걸 확인했다' },
  { value: 'DENIED', label: '없는 걸 확인했다' },
  { value: 'UNKNOWN', label: '모르겠다' },
] as const;

const PRE_BREAKUP_CHANGES = [
  { value: 'SUDDEN', label: '전조 없이 갑자기' },
  { value: 'FIGHTS_INCREASED', label: '몇 주 전부터 싸움이 늘었다' },
  { value: 'GRADUAL_COOLING', label: '몇 달에 걸쳐 서서히 식었다' },
  { value: 'REPEATED_WARNINGS', label: '전부터 여러 번 힘들다고 했다' },
  { value: 'SINGLE_INCIDENT', label: '잘 지내다 사건 하나로' },
] as const;

const PARTNER_ACTIONS = [
  { value: 'REACHED_OUT', label: '먼저 연락해 왔다' },
  { value: 'ASKED_TO_MEET', label: '만나자고 했다' },
  { value: 'SNS_TRACE', label: 'SNS 흔적을 남긴다' },
  { value: 'BELONGINGS_ONLY', label: '물건이나 정리 얘기만' },
  { value: 'CUT_OFF', label: '차단하거나 정리했다' },
  { value: 'NOTHING', label: '아무것도 없었다' },
] as const;

const CONTACT_POINTS = [
  { value: 'NONE', label: '없다' },
  { value: 'SCHOOL_OR_WORK', label: '같은 학교나 직장' },
  { value: 'NEIGHBORHOOD', label: '같은 동네' },
  { value: 'MUTUAL_FRIENDS', label: '공통 친구 모임' },
  { value: 'SCHEDULED', label: '잡혀 있는 일정' },
  { value: 'UNSETTLED', label: '정리할 게 남았다' },
] as const;

// 기간은 두 칸(년/개월, 개월/일)으로 받는다. 단위를 고르게 하면 탭이 한 번 더 들어가고,
// 한 칸으로 받으면 "2년 3개월"을 27로 환산하는 일을 유저에게 시키게 된다.
function toMonths(years: string, months: string): number | null {
  const y = Number(years) || 0;
  const m = Number(months) || 0;
  return years === '' && months === '' ? null : y * 12 + m;
}

function toDays(months: string, days: string): number | null {
  const m = Number(months) || 0;
  const d = Number(days) || 0;
  return months === '' && days === '' ? null : m * 30 + d;
}

export function IntakeForm({
  initial,
  submitting,
  error,
  onSubmit,
}: {
  initial: StoryIntake;
  submitting: boolean;
  error: string;
  onSubmit: (intake: StoryIntake) => void;
}) {
  const [value, setValue] = useState<StoryIntake>({ ...EMPTY_INTAKE, ...initial });
  // 이미 낸 값을 다시 열었을 때 두 칸으로 되돌린다 — 저장은 한 숫자라 나눠 보여줘야 한다
  const [datingYears, setDatingYears] = useState(str(divide(initial.datingMonths, 12)));
  const [datingRestMonths, setDatingRestMonths] = useState(str(remainder(initial.datingMonths, 12)));
  const [sinceMonths, setSinceMonths] = useState(str(divide(initial.daysSinceBreakup, 30)));
  const [sinceDays, setSinceDays] = useState(str(remainder(initial.daysSinceBreakup, 30)));

  function patch(next: Partial<StoryIntake>) {
    setValue((prev) => ({ ...prev, ...next }));
  }

  // 같은 값을 다시 누르면 해제된다 — 잘못 고른 뒤 되돌릴 길이 이것뿐이다(전부 선택 항목이라
  // "해당 없음" 보기를 따로 두지 않았다).
  function pick<K extends keyof StoryIntake>(key: K, next: StoryIntake[K]) {
    patch({ [key]: value[key] === next ? null : next } as Partial<StoryIntake>);
  }

  // 배타 보기("아무것도 없었다", "없다")는 다른 값과 같이 설 수 없다. 서버도 한 번 더 접지만,
  // 화면에서 안 막으면 유저 눈에 모순된 선택이 그대로 켜져 있다.
  function toggleMulti<T extends string>(list: T[], next: T, exclusive: T): T[] {
    if (list.includes(next)) return list.filter((v) => v !== next);
    if (next === exclusive) return [exclusive];
    return [...list.filter((v) => v !== exclusive), next];
  }

  // 이름만 필수다. 나머지를 다 비우고 내는 길은 그대로 열어둔다 — 넘어가기 버튼을 없앤 것은
  // 이름을 받으려는 것이지 폼을 다 채우게 하려는 것이 아니다.
  const named = value.callName.trim().length > 0;

  function submit() {
    if (!named) {
      return;
    }
    onSubmit({
      ...value,
      callName: value.callName.trim(),
      datingMonths: toMonths(datingYears, datingRestMonths),
      daysSinceBreakup: toDays(sinceMonths, sinceDays),
    });
  }

  return (
    <div className={styles.sheet}>
      <div className={styles.scroll}>
        <h2 className={styles.title}>시작하기 전에 몇 가지만 여쭙겠습니다</h2>
        <p className={styles.lead}>
          여기서 알려주시면 그만큼 덜 여쭙고 이야기로 바로 들어갈 수 있습니다. 이름 말고는
          모르거나 말하기 어려운 항목을 비워두셔도 됩니다.
        </p>

        <div className={styles.field}>
          <div className={styles.label}>뭐라고 불러드릴까요</div>
          <div className={styles.hint}>대화 내내 이렇게 불러드립니다. 본명이 아니어도 됩니다</div>
          <input
            className={styles.text}
            type="text"
            maxLength={8}
            value={value.callName}
            onChange={(e) => patch({ callName: e.target.value })}
            /* 폼에서 유일한 자유 입력이라 자동완성이 이름, 주소를 들이민다 — 별명으로 적어도
               되는 칸에 실명이 자동으로 채워지는 게 이 칸의 취지와 정반대다 */
            autoComplete="off"
          />
        </div>

        <div className={styles.field}>
          <div className={styles.label}>나이</div>
          <div className={styles.row}>
            <NumberBox
              label="나"
              unit="세"
              value={value.userAge}
              onChange={(n) => patch({ userAge: n })}
            />
            <NumberBox
              label="상대"
              unit="세"
              value={value.partnerAge}
              onChange={(n) => patch({ partnerAge: n })}
            />
          </div>
        </div>

        <div className={styles.field}>
          <div className={styles.label}>내 성별</div>
          <div className={styles.options}>
            {(['MALE', 'FEMALE'] as const).map((g) => (
              <button
                key={g}
                className={`${styles.option} ${value.userGender === g ? styles.optionOn : ''}`}
                onClick={() => pick('userGender', g)}
              >
                {g === 'MALE' ? '남' : '여'}
              </button>
            ))}
          </div>
        </div>

        <div className={styles.field}>
          <div className={styles.label}>얼마나 만나셨습니까</div>
          <div className={styles.row}>
            <NumberBox label="" unit="년" value={numOrNull(datingYears)} onChange={(n) => setDatingYears(str(n))} />
            <NumberBox label="" unit="개월" value={numOrNull(datingRestMonths)} onChange={(n) => setDatingRestMonths(str(n))} />
          </div>
        </div>

        <div className={styles.field}>
          <div className={styles.label}>헤어진 지 얼마나 되셨습니까</div>
          <div className={styles.row}>
            <NumberBox label="" unit="개월" value={numOrNull(sinceMonths)} onChange={(n) => setSinceMonths(str(n))} />
            <NumberBox label="" unit="일" value={numOrNull(sinceDays)} onChange={(n) => setSinceDays(str(n))} />
          </div>
        </div>

        <Choice
          label="누가 먼저 이별을 원했습니까"
          options={INITIATORS}
          selected={value.initiator}
          onPick={(v) => pick('initiator', v)}
        />

        <Choice
          label="지금 연락은 어떻습니까"
          options={CONTACT_MODES}
          selected={value.contactMode}
          onPick={(v) => pick('contactMode', v)}
        />

        <Choice
          label="이 상대와 헤어진 게 처음이십니까"
          hint="헤어지자는 말을 꺼낸 게 아니라, 실제로 헤어졌다 다시 만난 횟수입니다"
          options={PRIOR_REUNIONS}
          selected={value.priorReunion}
          onPick={(v) => pick('priorReunion', v)}
        />

        <Choice
          label="상대에게 새로운 사람이 있습니까"
          options={PARTNER_NEW}
          selected={value.partnerHasNew}
          onPick={(v) => pick('partnerHasNew', v)}
        />

        <Choice
          label="이별 직전에 관계가 어떻게 변했습니까"
          options={PRE_BREAKUP_CHANGES}
          selected={value.preBreakupChange}
          onPick={(v) => pick('preBreakupChange', v)}
        />

        <MultiChoice
          label="이별한 뒤 상대가 먼저 한 행동이 있습니까"
          hint="해당하는 것을 모두 골라주십시오"
          options={PARTNER_ACTIONS}
          selected={value.partnerActions}
          onToggle={(v) =>
            patch({ partnerActions: toggleMulti<PartnerAction>(value.partnerActions, v, 'NOTHING') })
          }
        />

        <MultiChoice
          label="앞으로 마주칠 일이 있습니까"
          options={CONTACT_POINTS}
          selected={value.contactPoints}
          onToggle={(v) =>
            patch({ contactPoints: toggleMulti<ContactPoint>(value.contactPoints, v, 'NONE') })
          }
        />

        {error && <div className={styles.error}>{error}</div>}
      </div>

      <div className={styles.footer}>
        <button className={styles.submit} onClick={submit} disabled={submitting || !named}>
          {submitting ? '보내는 중…' : '이야기 들려주기'}
        </button>
      </div>
    </div>
  );
}

function Choice<T extends string>({
  label,
  hint,
  options,
  selected,
  onPick,
}: {
  label: string;
  hint?: string;
  options: readonly { value: T; label: string }[];
  selected: T | null;
  onPick: (value: T) => void;
}) {
  return (
    <div className={styles.field}>
      <div className={styles.label}>{label}</div>
      {hint && <div className={styles.hint}>{hint}</div>}
      <div className={styles.options}>
        {options.map((option) => (
          <button
            key={option.value}
            className={`${styles.option} ${selected === option.value ? styles.optionOn : ''}`}
            onClick={() => onPick(option.value)}
          >
            {option.label}
          </button>
        ))}
      </div>
    </div>
  );
}

function MultiChoice<T extends string>({
  label,
  hint,
  options,
  selected,
  onToggle,
}: {
  label: string;
  hint?: string;
  options: readonly { value: T; label: string }[];
  selected: T[];
  onToggle: (value: T) => void;
}) {
  return (
    <div className={styles.field}>
      <div className={styles.label}>{label}</div>
      {hint && <div className={styles.hint}>{hint}</div>}
      <div className={styles.options}>
        {options.map((option) => (
          <button
            key={option.value}
            className={`${styles.option} ${selected.includes(option.value) ? styles.optionOn : ''}`}
            onClick={() => onToggle(option.value)}
          >
            {option.label}
          </button>
        ))}
      </div>
    </div>
  );
}

function NumberBox({
  label,
  unit,
  value,
  onChange,
}: {
  label: string;
  unit: string;
  value: number | null;
  onChange: (value: number | null) => void;
}) {
  return (
    <label className={styles.numberBox}>
      {label && <span className={styles.numberLabel}>{label}</span>}
      <input
        className={styles.number}
        type="number"
        inputMode="numeric"
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value === '' ? null : Number(e.target.value))}
      />
      <span className={styles.unit}>{unit}</span>
    </label>
  );
}

function divide(total: number | null, unit: number): number | null {
  return total == null ? null : Math.floor(total / unit);
}

function remainder(total: number | null, unit: number): number | null {
  return total == null ? null : total % unit;
}

function numOrNull(raw: string): number | null {
  return raw === '' ? null : Number(raw);
}

function str(value: number | null): string {
  return value == null ? '' : String(value);
}
