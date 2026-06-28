import { useState } from 'react';
import type { ReadingDelta, ReadingView, StoryReport } from '../api/assessment';
import { bandLabel } from '../utils/assessmentScale';
import styles from './ReadingBook.module.css';

// 정밀 판독(스토리북 v4) 뷰어. 표지(확률 + 등급 + "왜 이 숫자인지" 미니 판독)에서 시작해
// 사연별 챕터를 하나씩 넘긴다 — 장 개수와 제목이 사연마다 다르다(고정 목차는 템플릿으로
// 읽힌다). 각 장은 eyebrow(왜 이 장을 읽는지)가 제목 위에 서고, 관계심리와 복구 원리는
// 독립 페이지가 아니라 장 안에 붙는다. 요인 카드나 점수표는 여기 없다.
// 첫 독서는 장 넘김, 완독이나 전체 보기 후엔 세로 스크롤. 완독 여부는 저장하지 않는다.

// 표지 제목 규칙 — 등급별 문구는 reading.yml의 프론트 제목 규칙과 짝이다.
function coverTitle(prob: number): string {
  if (prob >= 82) return `${prob}%로 재회 가능성을 매우 높게 본 가장 큰 이유`;
  if (prob >= 65) return `${prob}%로 재회 가능성을 높게 본 가장 큰 이유`;
  if (prob >= 45) return `${prob}%로 본 핵심 이유`;
  if (prob >= 25) return `${prob}%로 재회 가능성을 낮게 본 가장 큰 이유`;
  return `${prob}%로 재회 가능성을 매우 낮게 본 가장 큰 이유`;
}

// 장 목록은 리포트 내용에서 조립된다 — 챕터 수, 장벽/유지 장 유무가 사연마다 다르다.
type Chapter =
  | { kind: 'cover' }
  | { kind: 'chapter'; index: number }
  | { kind: 'barrier' }
  | { kind: 'maintenance' }
  | { kind: 'reselect' }
  | { kind: 'final' };

function buildChapters(report: StoryReport): Chapter[] {
  const chapters: Chapter[] = [{ kind: 'cover' }];
  report.chapters.forEach((_, index) => chapters.push({ kind: 'chapter', index }));
  if (report.currentBarrier) chapters.push({ kind: 'barrier' });
  if (report.maintenanceInsight) chapters.push({ kind: 'maintenance' });
  chapters.push({ kind: 'reselect' });
  chapters.push({ kind: 'final' });
  return chapters;
}

function chapterTitle(chapter: Chapter, report: StoryReport): string {
  switch (chapter.kind) {
    case 'cover':
      return '';
    case 'chapter':
      return report.chapters[chapter.index].title;
    case 'barrier':
      // 엔진 언어("N%를 막는 것") 금지 — 항상 이 제목(reading.yml 계약)
      return '지금 재회를 막고 있는 것';
    case 'maintenance':
      return report.maintenanceInsight?.title ?? '다시 만나면 같은 문제가 반복될까?';
    case 'reselect':
      return report.reselect.title;
    case 'final':
      return report.final.stateLabel;
  }
}

function chapterEyebrow(chapter: Chapter, report: StoryReport): string | null {
  if (chapter.kind === 'chapter') return report.chapters[chapter.index].eyebrow || null;
  if (chapter.kind === 'final') return '현재 국면';
  return null;
}

// 관계심리 블록 — 개념 이름표 + 이 사연에서 어떻게 작동했는지. 장 안에 붙는다.
function PsychologyBlock({ psychology }: { psychology: { concept: string; reading: string } }) {
  return (
    <div className={styles.principle}>
      <div className={styles.principleLabel}>{psychology.concept}</div>
      <div className={styles.principleText}>{psychology.reading}</div>
    </div>
  );
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
    // 확률과 등급이 먼저(3초 안에 성립), 그 아래 등급별 제목과 미니 판독 3~5문장.
    return (
      <>
        {probability != null && (
          <div className={styles.coverProb}>
            재회 가능성 <span className={styles.coverProbNum}>{probability}%</span>
            <span className={styles.coverBand}>{bandLabel(probability)}</span>
          </div>
        )}
        <div className={styles.coverVerdict}>
          {probability != null ? coverTitle(probability) : '이렇게 본 가장 큰 이유'}
        </div>
        <div className={styles.coverReason}>{report.probabilityReading.reading}</div>
      </>
    );
  }
  if (chapter.kind === 'chapter') {
    const item = report.chapters[chapter.index];
    return (
      <>
        <div className={styles.answer}>{item.answer}</div>
        <div className={styles.reading}>{item.reading}</div>
        {item.psychology && <PsychologyBlock psychology={item.psychology} />}
        {item.repairPrinciple && (
          <div className={styles.principle}>
            <div className={styles.principleLabel}>이런 충돌을 줄이려면</div>
            <div className={styles.principleText}>{item.repairPrinciple}</div>
          </div>
        )}
      </>
    );
  }
  if (chapter.kind === 'barrier') {
    const barrier = report.currentBarrier!;
    return (
      <>
        <div className={styles.answer}>{barrier.answer}</div>
        <div className={styles.reading}>{barrier.reading}</div>
        {report.secondaryBarrier && (
          <div className={styles.listBlock}>
            <div className={styles.listLabel}>그 아래 남아 있는 문제</div>
            <div className={styles.listLine}>{report.secondaryBarrier.answer}</div>
            {report.secondaryBarrier.reading && (
              <div className={styles.followWhy}>{report.secondaryBarrier.reading}</div>
            )}
          </div>
        )}
      </>
    );
  }
  if (chapter.kind === 'maintenance') {
    const insight = report.maintenanceInsight!;
    return (
      <>
        <div className={styles.answer}>{insight.answer}</div>
        <div className={styles.reading}>{insight.reading}</div>
        {insight.psychology && <PsychologyBlock psychology={insight.psychology} />}
        <div className={styles.principle}>
          <div className={styles.principleLabel}>이런 충돌이 덜 반복되려면</div>
          <div className={styles.principleText}>{insight.repairPrinciple}</div>
        </div>
      </>
    );
  }
  if (chapter.kind === 'reselect') {
    const reselect = report.reselect;
    return (
      <>
        <div className={styles.answer}>{reselect.answer}</div>
        <div className={styles.reading}>{reselect.reading}</div>
        {reselect.turningPoints.length > 0 && (
          <div className={styles.listBlock}>
            <div className={styles.listLabel}>판을 바꿀 다음 행동</div>
            {reselect.turningPoints.map((line) => (
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
  // final — 국면 한 줄은 제목이 말했고, 여기는 다음으로 이어지는 칩만.
  return (
    <>
      {report.final.chipSeeds.length > 0 && (
        <>
          <div className={styles.reading}>이어지는 고민은 대화에서 같이 정리해요.</div>
          <div className={styles.chipRow}>
            {report.final.chipSeeds.map((chip) => (
              <button className={styles.chip} key={chip} onClick={() => onAskChat(chip)}>
                {chip}
              </button>
            ))}
          </div>
        </>
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
                <span className={styles.scrollTitle}>{chapterTitle(chapter, report)}</span>
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
  const nextTitle = last ? null : chapterTitle(chapters[page + 1], report);
  const eyebrow = chapterEyebrow(chapter, report);

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
          <div className={styles.chapterNum}>
            {String(page).padStart(2, '0')}
            {eyebrow && <span className={styles.eyebrow}>{eyebrow}</span>}
          </div>
          <div className={styles.bookTitle}>{chapterTitle(chapter, report)}</div>
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
            {page === 0 ? '한 장씩 풀어보기' : `다음 — ${nextTitle}`}
          </button>
        )}
      </div>
    </div>
  );
}
