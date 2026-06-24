import { useState } from 'react';
import type { ReadingEvidence, ReadingView } from '../api/assessment';
import styles from './ReadingBook.module.css';

// 정밀 판독을 책처럼 읽는 뷰어. 첫 독서는 장 넘김(순서가 의미다 — 결론만 먼저 집어 읽으면
// 근거 없이 숫자만 소비된다), 완독이나 전체 보기 후엔 세로 스크롤. 완독 여부는 저장하지
// 않는다 — 세션 안에서만 구분하고, 재진입은 부모가 스크롤 모드로 연다.
// 장 제목은 판독이 케이스별로 생성한 훅을 쓰고, 비어 있으면 고정 제목으로 폴백한다.

const FIXED_TITLES: Record<string, string> = {
  narrative: '관계가 뒤집힌 순간',
  now: '상대는 지금 어떤 상태인가',
  resolveRemain: '결심은 진짜인가, 마음은 남았는가',
  reselect: '다시 선택할 가능성은',
};

type ChapterKey = 'overall' | 'narrative' | 'now' | 'resolveRemain' | 'reselect' | 'phase';

const CHAPTER_KEYS: ChapterKey[] = ['overall', 'narrative', 'now', 'resolveRemain', 'reselect', 'phase'];

function chapterTitle(key: ChapterKey, reading: ReadingView): string {
  if (key === 'overall') return '먼저 결론부터';
  if (key === 'phase') return '지금은 어떤 국면인가';
  return reading.chapterTitles?.[key] || FIXED_TITLES[key];
}

// 증거 줄 — 요인 이름표 + 방향 + 사실 + 해석. 추가신호는 채점 틀 밖 발견임을 표시한다
// (채점된 요인과 섞이면 "왜 확률엔 없냐"가 된다).
function EvidenceRow({ e }: { e: ReadingEvidence }) {
  return (
    <div className={styles.evItem}>
      <div className={styles.evTop}>
        <span className={styles.evName}>{e.name}</span>
        {e.source === '추가신호' && <span className={styles.evExtra}>추가신호</span>}
        <span className={e.direction === '유리' ? styles.evPlus : styles.evMinus}>
          {e.direction}
        </span>
      </div>
      <div className={styles.evFact}>{e.fact}</div>
      <div className={styles.evReading}>{e.interpretation}</div>
    </div>
  );
}

// 한 질문의 본문 — 답 한 줄(굵게)이 먼저, 서술과 증거가 뒤를 받친다.
function QuestionBlock({
  answer,
  reading,
  evidence,
}: {
  answer: string;
  reading: string;
  evidence: ReadingEvidence[];
}) {
  return (
    <>
      <div className={styles.answer}>{answer}</div>
      <div className={styles.reading}>{reading}</div>
      {evidence.length > 0 && (
        <div className={styles.evList}>
          {evidence.map((e, i) => (
            <EvidenceRow e={e} key={`${e.name}-${i}`} />
          ))}
        </div>
      )}
    </>
  );
}

function ChapterBody({ chapter, reading }: { chapter: ChapterKey; reading: ReadingView }) {
  const byQuestion = (q: ReadingEvidence['question']) =>
    reading.evidence.filter((e) => e.question === q);

  if (chapter === 'overall') {
    return <div className={styles.overall}>{reading.overall}</div>;
  }
  if (chapter === 'narrative') {
    return <div className={styles.reading}>{reading.narrative}</div>;
  }
  if (chapter === 'now') {
    return (
      <QuestionBlock
        answer={reading.now.answer}
        reading={reading.now.reading}
        evidence={byQuestion('상대의지금')}
      />
    );
  }
  if (chapter === 'resolveRemain') {
    // 결심과 남은 마음을 한 장에 — "마음이 남았어도 재선택은 낮을 수 있다"는 어긋남이
    // 이 장의 반전이라, 두 답이 나란히 보여야 그 어긋남이 읽힌다.
    return (
      <>
        <div className={styles.subHead}>정말 끝낼 결심이었을까</div>
        <QuestionBlock
          answer={reading.resolve.answer}
          reading={reading.resolve.reading}
          evidence={byQuestion('결심강도')}
        />
        <div className={styles.subHead}>마음은 남아 있을까</div>
        <QuestionBlock
          answer={reading.remain.answer}
          reading={reading.remain.reading}
          evidence={byQuestion('남은마음')}
        />
      </>
    );
  }
  if (chapter === 'reselect') {
    const delta = reading.delta;
    return (
      <>
        <div className={styles.answer}>{reading.reselect.answer}</div>
        <div className={styles.reading}>{reading.reselect.reading}</div>
        <div className={styles.routeGrid}>
          <div className={styles.routeItem}>
            <div className={styles.routeLabel}>지금 닫혀 있는 것</div>
            <div className={styles.routeText}>{reading.reselect.closed}</div>
          </div>
          <div className={styles.routeItem}>
            <div className={styles.routeLabel}>아직 열려 있는 것</div>
            <div className={styles.routeText}>{reading.reselect.open}</div>
          </div>
          <div className={styles.routeItem}>
            <div className={styles.routeLabel}>다시 움직일 조건</div>
            <div className={styles.routeText}>{reading.reselect.route}</div>
          </div>
        </div>
        {byQuestion('재선택').length > 0 && (
          <div className={styles.evList}>
            {byQuestion('재선택').map((e, i) => (
              <EvidenceRow e={e} key={`${e.name}-${i}`} />
            ))}
          </div>
        )}
        {/* 변동내역 — 새 사실이 반영돼 지난 판정에서 달라진 것. 결정론 diff라 서술과 어긋나지 않는다 */}
        {delta && delta.factors.length > 0 && (
          <div className={styles.deltaBox}>
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
  return <div className={styles.answer}>{reading.phase}</div>;
}

export function ReadingBook({
  reading,
  book,
  onExitBook,
  onToChat,
}: {
  reading: ReadingView;
  // 책 모드 여부는 부모가 쥔다 — 부모가 책 모드 동안 아래 판정부(요인 카드 등)를 감춰
  // 독서에 집중시키고, 나가는 순간 되살려야 하기 때문. 새 판독(첫 독서)만 책으로 열린다.
  book: boolean;
  onExitBook: () => void;
  onToChat: () => void;
}) {
  const [page, setPage] = useState(0);

  if (!book) {
    // 재열람 — 전체가 세로로 펼쳐진다. "그때 그 얘기 어디 있었지"에 다음 다음 다음이 없게.
    return (
      <div className={styles.scrollWrap}>
        {CHAPTER_KEYS.map((key) => (
          <section className={styles.scrollSection} key={key}>
            <div className={styles.scrollTitle}>{chapterTitle(key, reading)}</div>
            <ChapterBody chapter={key} reading={reading} />
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
        <ChapterBody chapter={key} reading={reading} />
      </div>
      <div className={styles.bookNav}>
        {page > 0 && (
          <button className={styles.prevBtn} onClick={() => setPage(page - 1)}>
            이전
          </button>
        )}
        {last ? (
          <>
            {/* 국면 장의 끝 — 절벽으로 끝내지 않는다. 뭘 할지는 채팅이 이어받는다 */}
            <button className={styles.nextBtn} onClick={onToChat}>
              이제 뭘 할지 같이 정하기 — 대화로
            </button>
            <button className={styles.fullBtn} onClick={onExitBook}>
              전체 분석 다시 보기
            </button>
          </>
        ) : (
          <button
            className={styles.nextBtn}
            onClick={() => {
              setPage(page + 1);
            }}
          >
            {page === 0 ? '분석 시작하기' : `다음 — ${nextTitle}`}
          </button>
        )}
      </div>
    </div>
  );
}
