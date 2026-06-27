import { useState } from 'react';
import type { ReadingDelta, ReadingView, StoryReport } from '../api/assessment';
import { bandLabel } from '../utils/assessmentScale';
import styles from './ReadingBook.module.css';

// 정밀 판독(스토리북 리포트) 뷰어. 표지(한 문장 판정 + 확률 + 이유 하나)에서 시작해
// 사연별 미스터리 장을 하나씩 넘긴다 — 장 개수와 제목이 사연마다 다르다(고정 목차는
// 템플릿으로 읽힌다). 요인 카드나 점수표는 여기 없다.
// 첫 독서는 장 넘김, 완독이나 전체 보기 후엔 세로 스크롤. 완독 여부는 저장하지 않는다.
// 디자인은 카드 상자 대신 텍스트 중심 에디토리얼 — 장 번호, 큰 제목, 본문 위계로만 구획한다.

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

// 장 목록은 리포트 내용에서 조립된다 — 미스터리 개수, 질문/복구 장 유무가 사연마다 다르다.
type Chapter =
  | { kind: 'cover' }
  | { kind: 'mystery'; index: number }
  | { kind: 'blockers' }
  | { kind: 'reselect' }
  | { kind: 'phase' };

function buildChapters(report: StoryReport): Chapter[] {
  const chapters: Chapter[] = [{ kind: 'cover' }];
  report.mysteries.forEach((_, index) => chapters.push({ kind: 'mystery', index }));
  if (report.blockers.length > 0) chapters.push({ kind: 'blockers' });
  chapters.push({ kind: 'reselect' });
  chapters.push({ kind: 'phase' });
  return chapters;
}

function chapterTitle(chapter: Chapter, report: StoryReport, probability: number | null): string {
  switch (chapter.kind) {
    case 'cover':
      return '';
    case 'mystery':
      return report.mysteries[chapter.index].title;
    case 'blockers':
      return probability != null ? `지금 ${probability}%를 막고 있는 것` : '지금 가능성을 막고 있는 것';
    case 'reselect':
      return report.reselect.title;
    case 'phase':
      return report.phase.label;
  }
}

function ChapterBody({
  chapter,
  report,
  delta,
  probability,
  onAskChat,
}: {
  chapter: Chapter;
  report: StoryReport;
  delta: ReadingDelta | null;
  probability: number | null;
  onAskChat: (prefill: string) => void;
}) {
  if (chapter.kind === 'cover') {
    // 표지는 직관이 먼저다: 숫자와 대역 라벨 → 한 문장 판정 → "N%로 본 가장 큰 이유".
    // 예전 요인표의 장점("대체자가 있어서 낮구나"가 3초 안에 성립)을 표지가 이어받는다.
    return (
      <>
        {probability != null && (
          <div className={styles.coverProb}>
            재회 가능성 <span className={styles.coverProbNum}>{probability}%</span>
            <span className={styles.coverBand}>{bandLabel(probability)}</span>
          </div>
        )}
        <div className={styles.coverVerdict}>{report.coverVerdict}</div>
        <div className={styles.coverReasonLabel}>
          {probability != null ? `${probability}%로 본 가장 큰 이유` : '이렇게 본 가장 큰 이유'}
        </div>
        <div className={styles.coverReason}>{report.coverReason}</div>
      </>
    );
  }
  if (chapter.kind === 'mystery') {
    const mystery = report.mysteries[chapter.index];
    return (
      <>
        <div className={styles.answer}>{mystery.answer}</div>
        <div className={styles.reading}>{mystery.reading}</div>
        {mystery.principle && (
          <div className={styles.principle}>
            <div className={styles.principleLabel}>이런 충돌을 줄이려면</div>
            <div className={styles.principleText}>{mystery.principle}</div>
          </div>
        )}
      </>
    );
  }
  if (chapter.kind === 'blockers') {
    return (
      <div className={styles.blockerList}>
        {report.blockers.map((b) => (
          <div className={styles.blockerItem} key={b.rank}>
            <div className={styles.blockerRank}>{b.rank}</div>
            <div className={styles.blockerBody}>
              <div className={styles.blockerTitle}>{b.title}</div>
              <div className={styles.answer}>{b.answer}</div>
              {b.reading && <div className={styles.reading}>{b.reading}</div>}
            </div>
          </div>
        ))}
      </div>
    );
  }
  if (chapter.kind === 'reselect') {
    const reselect = report.reselect;
    return (
      <>
        <div className={styles.answer}>{reselect.answer}</div>
        {reselect.open.length > 0 && (
          <div className={styles.listBlock}>
            <div className={styles.listLabel}>아직 열려 있는 것</div>
            {reselect.open.map((line) => (
              <div className={styles.listLine} key={line}>
                {line}
              </div>
            ))}
          </div>
        )}
        {reselect.conditions.length > 0 && (
          <div className={styles.listBlock}>
            <div className={styles.listLabel}>다시 움직이는 조건</div>
            {reselect.conditions.map((line) => (
              <div className={styles.listLine} key={line}>
                {line}
              </div>
            ))}
          </div>
        )}
        {reselect.watchFor.length > 0 && (
          <div className={styles.listBlock}>
            <div className={styles.listLabel}>판단을 바꿀 신호</div>
            {reselect.watchFor.map((line) => (
              <div className={styles.listLine} key={line}>
                {line}
              </div>
            ))}
          </div>
        )}
        {/* 변동내역 — 새 사실이 반영돼 지난 판정에서 달라진 것. 결정론 diff라 서술과 어긋나지 않는다 */}
        {delta && delta.factors.length > 0 && (
          <div className={styles.listBlock}>
            <div className={styles.listLabel}>
              지난 판정에서 달라진 것 ({delta.probabilityFrom}% → {delta.probabilityTo}%)
            </div>
            {delta.factors.map((f) => (
              <div className={styles.listLine} key={f.name}>
                {f.name} {f.from} → {f.to}
              </div>
            ))}
          </div>
        )}
      </>
    );
  }
  // phase — 국면 판정 + 현재 판독 소계 + 이어지는 궁금증 칩(채팅 프리필) + 후속 질문
  const nowLabel = NOW_LABEL[report.internal.nowState];
  const reselectLabel = RESELECT_LABEL[report.internal.reselectState];
  return (
    <>
      <div className={styles.answer}>{report.phase.reading}</div>
      {(nowLabel || reselectLabel) && (
        <div className={styles.stateLine}>
          현재 판독 {[nowLabel, reselectLabel].filter(Boolean).join(', ')}
        </div>
      )}
      {report.phase.chipSeeds.length > 0 && (
        <div className={styles.chipRow}>
          {report.phase.chipSeeds.map((chip) => (
            <button className={styles.chip} key={chip} onClick={() => onAskChat(chip)}>
              {chip}
            </button>
          ))}
        </div>
      )}
      {report.followUp && (
        <div className={styles.listBlock}>
          <div className={styles.listLabel}>다음 분석 전에 알려주면 좋은 것</div>
          <div className={styles.listLine}>{report.followUp.question}</div>
          <div className={styles.followWhy}>{report.followUp.whyItMatters}</div>
        </div>
      )}
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
  const report = reading.report;
  const chapters = buildChapters(report);

  if (!book) {
    // 재열람 — 전체가 세로로 펼쳐진다. "그때 그 얘기 어디 있었지"에 다음 다음 다음이 없게.
    return (
      <div className={styles.scrollWrap}>
        {chapters.map((chapter, i) => (
          <section className={styles.scrollSection} key={i}>
            {chapter.kind !== 'cover' && (
              <div className={styles.scrollHead}>
                <span className={styles.scrollNum}>{String(i).padStart(2, '0')}</span>
                <span className={styles.scrollTitle}>
                  {chapterTitle(chapter, report, probability)}
                </span>
              </div>
            )}
            <ChapterBody
              chapter={chapter}
              report={report}
              delta={reading.delta}
              probability={probability}
              onAskChat={onAskChat}
            />
          </section>
        ))}
      </div>
    );
  }

  const chapter = chapters[page];
  const last = page === chapters.length - 1;
  const nextTitle = last ? null : chapterTitle(chapters[page + 1], report, probability);

  return (
    <div className={styles.bookWrap}>
      <div className={styles.bookBar}>
        <span className={styles.progress}>
          {page + 1} / {chapters.length}
        </span>
        {/* 순서대로 읽기를 권하지만 강제하진 않는다 — 건너뛰고 싶은 사람의 출구 */}
        <button className={styles.skipLink} onClick={onExitBook}>
          전체 보기
        </button>
      </div>
      {chapter.kind !== 'cover' && (
        <>
          <div className={styles.chapterNum}>{String(page).padStart(2, '0')}</div>
          <div className={styles.bookTitle}>{chapterTitle(chapter, report, probability)}</div>
        </>
      )}
      <div className={styles.bookBody}>
        <ChapterBody
          chapter={chapter}
          report={report}
          delta={reading.delta}
          probability={probability}
          onAskChat={onAskChat}
        />
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
            {page === 0 ? '왜 이 숫자인지 하나씩 풀어보기' : `다음 — ${nextTitle}`}
          </button>
        )}
      </div>
    </div>
  );
}
