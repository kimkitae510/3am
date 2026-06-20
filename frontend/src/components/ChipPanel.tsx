import { useEffect, useRef, useState } from 'react';
import type { ChipView } from '../api/chip';
import { getStoryChips } from '../api/chip';
import styles from './ChipPanel.module.css';

// 추천 질문 칩. 상담자가 물은 것(QuestionActions)과 달리 이건 유저가 다음에 물을 것이라,
// 유저 말풍선 쪽에 붙여 "내가 하는 말"로 읽히게 둔다.

// 전체 목록은 사연마다 다르다(분석 유무로 갈린다). 방을 오갈 때마다 다시 받지 않게
// 사연별로 캐시한다 — 한 방에서 분석을 새로 받으면 새로고침 전까지 옛 목록이 남지만,
// 그 사이 늘어나는 건 분석 설명 칩 몇 개뿐이라 감수한다.
const catalogCache = new Map<number, ChipView[]>();

function useCatalog(storyId: number) {
  const [chips, setChips] = useState<ChipView[]>(catalogCache.get(storyId) ?? []);
  useEffect(() => {
    if (catalogCache.has(storyId)) {
      setChips(catalogCache.get(storyId)!);
      return;
    }
    let alive = true;
    getStoryChips(storyId)
      .then((all) => {
        catalogCache.set(storyId, all);
        if (alive) setChips(all);
      })
      .catch(() => {
        // 실패해도 추천 3개와 채팅은 그대로 돈다 — 이 시트만 비어 보인다
      });
    return () => {
      alive = false;
    };
  }, [storyId]);
  return chips;
}

// 답변 밑에 붙는 추천 3개. 마지막 답변에만 그린다 — 지난 답변에도 남기면 유저가
// 어느 시점의 추천을 누르는지 모르는 채 그때 맥락으로 상담이 열린다.
export function ChipRow({
  chips,
  onPick,
  onBrowse,
}: {
  chips: ChipView[];
  onPick: (chip: ChipView) => void;
  onBrowse: () => void;
}) {
  if (chips.length === 0) return null;
  return (
    <div className={styles.row}>
      {chips.map((chip) => (
        <button key={chip.id} className={styles.chip} onClick={() => onPick(chip)}>
          {chip.label}
        </button>
      ))}
      {/* 칩은 물을 수 있는 것의 전부가 아니라 추천이다. 나머지로 가는 문이 없으면
          유저는 이 셋 중에 골라야 하는 줄 안다.
          개수는 안 적는다 — 세려면 목록을 미리 받아와야 하고, 사연마다 달라 숫자가 흔들린다 */}
      <button className={styles.browse} onClick={onBrowse}>
        다른 질문 보기
      </button>
    </div>
  );
}

// INPUT 칩이 띄우는 입력 시트. 누르자마자 label을 보내면 상담자가 "무슨 일이 있었나요?"를
// 되묻느라 한 턴이 통째로 날아간다 — 그 되묻기를 화면이 먼저 하고 내용만 보낸다.
export function ChipInputSheet({
  chip,
  max,
  onSubmit,
  onClose,
}: {
  chip: ChipView;
  max: number;
  onSubmit: (text: string) => void;
  onClose: () => void;
}) {
  const [text, setText] = useState('');
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const preset = chip.inputPreset;

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  if (!preset) return null;
  const over = text.length > max;
  const ready = text.trim().length > 0 && !over;

  return (
    <div className={styles.backdrop} onClick={onClose}>
      <div className={styles.sheet} onClick={(e) => e.stopPropagation()}>
        <div className={styles.sheetTitle}>{preset.title}</div>
        <div className={styles.sheetBody}>{preset.placeholder}</div>
        <textarea
          ref={inputRef}
          className={styles.sheetInput}
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder={preset.placeholder}
          rows={4}
        />
        <div className={styles.sheetFoot}>
          {/* 도움말은 글자 수와 같은 줄에 둔다 — 입력칸 위아래로 설명이 쌓이면 폼처럼 보인다 */}
          <span className={styles.sheetHelper}>{preset.helper}</span>
          <span className={over ? styles.countOver : styles.count}>
            {text.length}/{max}
          </span>
        </div>
        <div className={styles.sheetActions}>
          <button className={styles.cancel} onClick={onClose}>
            취소
          </button>
          <button
            className={styles.submit}
            disabled={!ready}
            onClick={() => onSubmit(text.trim())}
          >
            {preset.submitLabel}
          </button>
        </div>
      </div>
    </div>
  );
}

// 모듈 id를 화면 머리말로 옮긴 표. 칩 라벨과 달리 이건 프롬프트로 안 나가는 순수 화면 문구라
// 자산 파일이 아니라 여기 둔다. 표에 없는 모듈은 머리말 없이 이어 그린다.
const GROUP_LABELS: Record<string, string> = {
  ACTION: '지금 어떻게 해야 할지',
  CONTACT: '연락은 어떻게 할지',
  MESSAGE: '어떤 말을 전할지',
  SIGNAL: '상대 행동은 무슨 의미인지',
  MEETING: '만남은 어떻게 할지',
  REUNION_CONDITION: '다시 이어지려면 필요한 것',
  RELATIONSHIP_OUTLOOK: '다시 만나면 어떻게 될지',
  SELF: '내 마음은 어떤 상태인지',
  SELF_BLAME: '내 잘못과 죄책감',
  DIAGNOSIS_EXPLAIN: '진단 결과 이해하기',
  UPDATE: '새롭게 생긴 변화',
};

// 추천 3개 밖의 질문을 직접 고르는 전체 목록.
export function ChipCatalogSheet({
  storyId,
  onPick,
  onClose,
}: {
  storyId: number;
  onPick: (chip: ChipView) => void;
  onClose: () => void;
}) {
  const chips = useCatalog(storyId);

  // 카탈로그 순서가 곧 모듈별 묶음 순서다. 다시 정렬하지 않고 경계에서만 머리말을 낸다.
  let lastModule = '';

  return (
    <div className={styles.backdrop} onClick={onClose}>
      <div className={styles.sheet} onClick={(e) => e.stopPropagation()}>
        <div className={styles.sheetTitle}>무엇이 궁금하신가요?</div>
        <div className={styles.catalog}>
          {chips.length === 0 && <div className={styles.sheetBody}>질문 목록을 불러오는 중입니다.</div>}
          {chips.map((chip) => {
            const head = chip.module !== lastModule ? GROUP_LABELS[chip.module] : undefined;
            lastModule = chip.module;
            return (
              <div key={chip.id}>
                {head && <div className={styles.group}>{head}</div>}
                <button className={styles.catalogItem} onClick={() => onPick(chip)}>
                  {chip.label}
                </button>
              </div>
            );
          })}
        </div>
        <div className={styles.sheetActions}>
          <button className={styles.cancel} onClick={onClose}>
            닫기
          </button>
        </div>
      </div>
    </div>
  );
}
