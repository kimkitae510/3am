import { useState } from 'react';
import type { ReadingView } from '../api/assessment';
import styles from './ReadingBook.module.css';

// 정밀 판독 뷰어. 표지(판정이 주인공, 확률은 보조 숫자)에서 시작해 여섯 장을 넘기며
// 상대의 진실이 하나씩 밝혀지는 구성. 요인 카드나 점수표는 여기 없다 — 요인은 엔진의
// 채점 언어고, 유저가 읽는 목차는 실제 궁금증(지금 무슨 생각일까, 마음은 남았을까)이어야 한다.
// 첫 독서는 장 넘김, 완독이나 전체 보기 후엔 세로 스크롤. 완독 여부는 저장하지 않는다.

const FIXED_TITLES: Record<string, string> = {
  now: '상대는 지금 무슨 생각일까',
  resolve: '정말 끝낼 결심이었을까',
  remain: '마음은 남아 있을까',
  drift: '왜 멀어졌을까',
  blocking: '지금 재회를 막고 있는 건 뭘까',
  route: '무엇이 바뀌면 다시 움직일까',
};

// state는 내부 값이지만, 국면 장의 "현재 판독" 소계 한 줄에만 번역해 쓴다.
const NOW_LABEL: Record<string, string> = {
  EMOTIONAL_OVERWHELM: '감정 수습 단계',
  RELATIONSHIP_RECONSIDERATION: '관계 재평가 단계',
  DETACHED: '거리두기 단계',
  MOVING_ON: '정리 수순 단계',
  MIXED: '복합 상태',
};

const RESELECT_LABEL: Record<string, string> = {
  OPEN: '재선택 가능성 열려 있음',
  CONDITIONAL: '재선택 가능성 조건부로 열려 있음',
  NARROW: '재선택 여지 좁음',
  CLOSED_CURRENTLY: '지금은 재선택이 닫혀 있음',
};

// 국면 장의 후속 칩 — 눌러서 이어지는 궁금증. 구체 행동 상담은 리포트가 아니라 채팅의 몫이라,
// 칩이 그 문을 연다(입력창 프리필).
const PHASE_CHIPS = ['그래서 지금 어떻게 해야 할까?', '먼저 연락해도 될까?', '상대 마음을 더 깊게 보고 싶어'];

type ChapterKey = 'cover' | 'now' | 'resolve' | 'remain' | 'drift' | 'blocking' | 'route' | 'phase';

const CHAPTER_KEYS: ChapterKey[] = [
  'cover',
  'now',
  'resolve',
  'remain',
  'drift',
  'blocking',
  'route',
  'phase',
];

function chapterTitle(key: ChapterKey, reading: ReadingView): string {
  if (key === 'cover') return '현재 두 사람을 먼저 판단하면';
  if (key === 'phase') return '지금은 어떤 국면인가';
  return reading.chapterTitles?.[key] || FIXED_TITLES[key];
}

// 한 질문 장의 본문 — 답 한 줄(굵게)이 먼저, 서술이 뒤를 받친다.
function QuestionBlock({ answer, reading }: { answer: string; reading: string }) {
  return (
    <>
      <div className={styles.answer}>{answer}</div>
      <div className={styles.reading}>{reading}</div>
    </>
  );
}

function ChapterBody({
  chapter,
  reading,
  probability,
  onAskChat,
}: {
  chapter: ChapterKey;
  reading: ReadingView;
  probability: number | null;
  onAskChat: (prefill: string) => void;
}) {
  if (chapter === 'cover') {
    // 판정 문장이 주인공, 숫자는 보조 — 숫자를 먼저 크게 걸면 나머지 글 전부가
    // "그 숫자의 정당화"로 읽히고, 끝까지 숨기면 숫자 생각에 분석이 안 들어온다.
    return (
      <>
        <div className={styles.coverVerdict}>{reading.overall}</div>
        {probability != null && (
          <div className={styles.coverProb}>
            재회 가능성 <span className={styles.coverProbNum}>{probability}%</span>
          </div>
        )}
        <div className={styles.coverReasons}>
          <div className={styles.coverReason}>
            <div className={styles.coverReasonLabel}>가능성을 열어두는 가장 큰 이유</div>
            <div className={styles.coverReasonText}>{reading.coverRaise}</div>
          </div>
          <div className={styles.coverReason}>
            <div className={styles.coverReasonLabel}>지금 가능성을 막는 가장 큰 이유</div>
            <div className={styles.coverReasonText}>{reading.coverBlock}</div>
          </div>
        </div>
      </>
    );
  }
  if (chapter === 'now') {
    return <QuestionBlock answer={reading.now.answer} reading={reading.now.reading} />;
  }
  if (chapter === 'resolve') {
    return <QuestionBlock answer={reading.resolve.answer} reading={reading.resolve.reading} />;
  }
  if (chapter === 'remain') {
    return <QuestionBlock answer={reading.remain.answer} reading={reading.remain.reading} />;
  }
  if (chapter === 'drift') {
    return <div className={styles.reading}>{reading.drift}</div>;
  }
  if (chapter === 'blocking') {
    return <div className={styles.reading}>{reading.blocking}</div>;
  }
  if (chapter === 'route') {
    const delta = reading.delta;
    return (
      <>
        <div className={styles.answer}>{reading.reselect.answer}</div>
        <div className={styles.routeGrid}>
          <div className={styles.routeItem}>
            <div className={styles.routeLabel}>아직 열려 있는 것</div>
            <div className={styles.routeText}>{reading.reselect.open}</div>
          </div>
          <div className={styles.routeItem}>
            <div className={styles.routeLabel}>다시 움직이는 경우</div>
            <div className={styles.routeText}>{reading.reselect.route}</div>
          </div>
        </div>
        {/* 변동내역 — 새 사실이 반영돼 지난 판정에서 달라진 것. 결정론 diff라 서술과 어긋나지 않는다 */}
        {delta && delta.factors.length > 0 && (
          <div className={styles.routeItem + ' ' + styles.deltaBox}>
            <div className={styles.routeLabel}>
              지난 판정에서 달라진 것 ({delta.probabilityFrom}% → {delta.probabilityTo}%)
            </div>
            {delta.factors.map((f) => (
              <div className={styles.deltaRow} key={f.name}>
                {f.name} {f.from} → {f.to}
              </div>
            ))}
          </div>
        )}
      </>
    );
  }
  // phase — 국면 판정 + 작은 현재 판독 소계 + 이어지는 궁금증 칩(채팅 프리필)
  const nowLabel = NOW_LABEL[reading.now.state];
  const reselectLabel = RESELECT_LABEL[reading.reselect.state];
  return (
    <>
      <div className={styles.answer}>{reading.phase}</div>
      {(nowLabel || reselectLabel) && (
        <div className={styles.stateLine}>
          현재 판독 {[nowLabel, reselectLabel].filter(Boolean).join(', ')}
        </div>
      )}
      <div className={styles.chipRow}>
        {PHASE_CHIPS.map((chip) => (
          <button className={styles.chip} key={chip} onClick={() => onAskChat(chip)}>
            {chip}
          </button>
        ))}
      </div>
    </>
  );
}

export function ReadingBook({
  reading,
  probability,
  book,
  onExitBook,
  onAskChat,
}: {
  reading: ReadingView;
  probability: number | null;
  // 책 모드 여부는 부모가 쥔다 — 부모가 책 모드 동안 화면의 다른 요소를 감춰 독서에
  // 집중시키고, 나가는 순간 되살려야 하기 때문. 새 판독(첫 독서)만 책으로 열린다.
  book: boolean;
  onExitBook: () => void;
  // 칩을 누르면 채팅으로 — 질문이 입력창에 미리 채워진다.
  onAskChat: (prefill: string) => void;
}) {
  const [page, setPage] = useState(0);

  if (!book) {
    // 재열람 — 전체가 세로로 펼쳐진다. "그때 그 얘기 어디 있었지"에 다음 다음 다음이 없게.
    return (
      <div className={styles.scrollWrap}>
        {CHAPTER_KEYS.map((key) => (
          <section className={styles.scrollSection} key={key}>
            <div className={styles.scrollTitle}>{chapterTitle(key, reading)}</div>
            <ChapterBody
              chapter={key}
              reading={reading}
              probability={probability}
              onAskChat={onAskChat}
            />
          </section>
        ))}
      </div>
    );
  }

  const key = CHAPTER_KEYS[page];
  const last = page === CHAPTER_KEYS.length - 1;
  const nextTitle = last ? null : chapterTitle(CHAPTER_KEYS[page + 1], reading);

  return (
    <div className={styles.bookWrap}>
      <div className={styles.bookBar}>
        <span className={styles.progress}>
          {page + 1} / {CHAPTER_KEYS.length}
        </span>
        {/* 순서대로 읽기를 권하지만 강제하진 않는다 — 건너뛰고 싶은 사람의 출구 */}
        <button className={styles.skipLink} onClick={onExitBook}>
          전체 보기
        </button>
      </div>
      <div className={styles.bookTitle}>{chapterTitle(key, reading)}</div>
      <div className={styles.bookBody}>
        <ChapterBody chapter={key} reading={reading} probability={probability} onAskChat={onAskChat} />
      </div>
      <div className={styles.bookNav}>
        {page > 0 && (
          <button className={styles.prevBtn} onClick={() => setPage(page - 1)}>
            이전
          </button>
        )}
        {last ? (
          <button className={styles.fullBtn} onClick={onExitBook}>
            전체 분석 다시 보기
          </button>
        ) : (
          <button className={styles.nextBtn} onClick={() => setPage(page + 1)}>
            {page === 0 ? '왜 이 판정인지 하나씩 풀어보기' : `다음 — ${nextTitle}`}
          </button>
        )}
      </div>
    </div>
  );
}
