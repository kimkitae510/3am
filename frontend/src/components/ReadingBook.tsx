import { useState } from 'react';
import type { ReadingDelta, ReadingView, StoryReport } from '../api/assessment';
import { bandLabel } from '../utils/assessmentScale';
import styles from './ReadingBook.module.css';

// 정밀 판독(v7) 뷰어. 진단이 먼저다: 확률 게이지 → "재회 가능성을 만든 진단"(펼치면 근거) →
// 사연별 심층 장면 → 현재 위치 → 무엇이 바뀌면 → 칩.
// 게이지는 메인 콘텐츠가 아니라 "내가 어느 위치인지"를 한눈에 읽는 장치라 한 줄로만 그린다
// (도넛, 대형 차트는 66이라는 숫자를 측정값처럼 정밀해 보이게 만든다).
// 첫 독서는 장 넘김, 완독이나 전체 보기 후엔 세로 스크롤. 완독 여부는 저장하지 않는다.

// 확률이 준 영향. 요인 카드 시절부터 쓰던 어휘를 그대로 쓴다 —
// 판독 없는 옛 결과와 공유 화면이 아직 이 말을 쓰고 있어, 섞이면 같은 서비스가 두 말을 한다.
const IMPACT_LABEL: Record<string, string> = {
  STRONG_UP: '매우유리',
  UP: '유리',
  NEUTRAL: '중립',
  DOWN: '불리',
  STRONG_DOWN: '매우불리',
};

// 장 목록은 리포트 내용에서 조립된다 — 심층 장 수, 유지 인사이트 유무가 사연마다 다르다.
type Chapter =
  | { kind: 'diagnosis' }
  | { kind: 'chapter'; index: number }
  | { kind: 'maintenance' }
  | { kind: 'reselect' }
  | { kind: 'final' };

function buildChapters(report: StoryReport): Chapter[] {
  const chapters: Chapter[] = [{ kind: 'diagnosis' }];
  report.analysisChapters.forEach((_, index) => chapters.push({ kind: 'chapter', index }));
  if (report.maintenanceInsight) chapters.push({ kind: 'maintenance' });
  chapters.push({ kind: 'reselect' });
  chapters.push({ kind: 'final' });
  return chapters;
}

function chapterTitle(chapter: Chapter, report: StoryReport): string {
  switch (chapter.kind) {
    case 'diagnosis':
      return '재회 가능성을 만든 진단';
    case 'chapter':
      return report.analysisChapters[chapter.index].title;
    case 'maintenance':
      return report.maintenanceInsight?.title ?? '다시 만나면 같은 문제가 반복될까?';
    case 'reselect':
      return report.reselect.title;
    case 'final':
      return report.final.stateLabel;
  }
}

function chapterEyebrow(chapter: Chapter, report: StoryReport): string | null {
  if (chapter.kind === 'chapter') return report.analysisChapters[chapter.index].eyebrow || null;
  if (chapter.kind === 'final') return '현재 관계 위치';
  return null;
}

// 한 줄 게이지 — 낮음에서 높음까지의 눈금 위에 지금 위치를 점 하나로 찍는다.
function Gauge({ probability }: { probability: number }) {
  const left = Math.max(2, Math.min(98, probability));
  return (
    <div className={styles.gauge}>
      <div className={styles.gaugeHead}>
        재회 가능성 <span className={styles.gaugeNum}>{probability}%</span>
        <span className={styles.gaugeBand}>{bandLabel(probability)}</span>
      </div>
      <div className={styles.gaugeTrack}>
        <div className={styles.gaugeDot} style={{ left: `${left}%` }} />
      </div>
      <div className={styles.gaugeScale}>
        <span>낮음</span>
        <span>보통</span>
        <span>높음</span>
      </div>
    </div>
  );
}

// 재진단이 쌓였을 때만 그리는 추세 — 실제 변화가 있을 때 값이 있다.
function Trend({ history }: { history: number[] }) {
  return (
    <div className={styles.trend}>
      <div className={styles.trendLabel}>지난 분석에서 지금까지</div>
      <div className={styles.trendRow}>
        {history.map((p, i) => (
          <span key={i} className={styles.trendItem}>
            {i > 0 && <span className={styles.trendArrow}>→</span>}
            <span className={i === history.length - 1 ? styles.trendNow : undefined}>{p}%</span>
          </span>
        ))}
      </div>
    </div>
  );
}

// 진단 한 줄. 접혀 있고, 누르면 왜 그렇게 봤는지가 펼쳐진다.
function DiagnosisRow({
  item,
  open,
  onToggle,
}: {
  item: StoryReport['diagnosis'][number];
  open: boolean;
  onToggle: () => void;
}) {
  const tone =
    item.impact === 'STRONG_UP' || item.impact === 'UP'
      ? styles.impactUp
      : item.impact === 'NEUTRAL'
        ? styles.impactNeutral
        : styles.impactDown;
  return (
    <div className={styles.diagItem}>
      <button className={styles.diagTop} onClick={onToggle} aria-expanded={open}>
        <span className={styles.diagRank}>{item.rank}</span>
        <span className={styles.diagLabel}>{item.label}</span>
        <span className={`${styles.diagImpact} ${tone}`}>
          {IMPACT_LABEL[item.impact] ?? item.impact}
        </span>
      </button>
      <div className={styles.diagVerdict}>{item.verdict}</div>
      {open && item.reading && <div className={styles.diagReading}>{item.reading}</div>}
    </div>
  );
}

function ChapterBody({
  chapter,
  report,
  delta,
  probability,
  history,
  onAskChat,
}: {
  chapter: Chapter;
  report: StoryReport;
  delta: ReadingDelta | null;
  probability: number | null;
  history: number[];
  onAskChat: (prefill: string) => void;
}) {
  const [openKey, setOpenKey] = useState<string | null>(null);

  if (chapter.kind === 'diagnosis') {
    return (
      <>
        {probability != null && <Gauge probability={probability} />}
        {history.length >= 2 && <Trend history={history} />}
        <div className={styles.summary}>{report.diagnosisSummary}</div>
        <div className={styles.diagList}>
          {report.diagnosis.map((item) => (
            <DiagnosisRow
              key={item.key}
              item={item}
              open={openKey === item.key}
              onToggle={() => setOpenKey(openKey === item.key ? null : item.key)}
            />
          ))}
        </div>
        {/* 변동내역 — 새 사실이 반영돼 지난 판정에서 달라진 것. 결정론 diff라 서술과 어긋나지 않는다 */}
        {delta && delta.factors.length > 0 && (
          <div className={styles.listBlock}>
            <div className={styles.listLabel}>
              지난 분석에서 달라진 것 ({delta.probabilityFrom}% → {delta.probabilityTo}%)
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
  if (chapter.kind === 'chapter') {
    const item = report.analysisChapters[chapter.index];
    return (
      <>
        <div className={styles.answer}>{item.answer}</div>
        <div className={styles.reading}>{item.reading}</div>
        {item.psychology && (
          <div className={styles.principle}>
            <div className={styles.principleLabel}>{item.psychology.concept}</div>
            <div className={styles.principleText}>{item.psychology.reading}</div>
          </div>
        )}
        {item.repairPrinciple && (
          <div className={styles.principle}>
            <div className={styles.principleLabel}>이런 충돌을 줄이려면</div>
            <div className={styles.principleText}>{item.repairPrinciple}</div>
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
        {insight.psychology && (
          <div className={styles.principle}>
            <div className={styles.principleLabel}>{insight.psychology.concept}</div>
            <div className={styles.principleText}>{insight.psychology.reading}</div>
          </div>
        )}
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
            <div className={styles.listLabel}>판단을 바꿀 다음 행동</div>
            {reselect.turningPoints.map((line) => (
              <div className={styles.listLine} key={line}>
                {line}
              </div>
            ))}
          </div>
        )}
      </>
    );
  }
  // final — 위치는 제목이 말했고, 여기는 다음으로 이어지는 칩만.
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
  history,
  book,
  onExitBook,
  onAskChat,
}: {
  reading: ReadingView;
  probability: number | null;
  // 재진단 확률 이력(과거→현재). 2개 이상일 때만 추세로 그린다.
  history: number[];
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
            <div className={styles.scrollHead}>
              <span className={styles.scrollNum}>{String(i + 1).padStart(2, '0')}</span>
              {/* eyebrow는 재열람에서도 그린다 — 왜 이 장을 읽는지가 제목만으로는 안 읽히고,
                  빠지면 책 모드와 내용이 달라 보인다 */}
              {chapterEyebrow(chapter, report) && (
                <span className={styles.scrollEyebrow}>{chapterEyebrow(chapter, report)}</span>
              )}
              <span className={styles.scrollTitle}>{chapterTitle(chapter, report)}</span>
            </div>
            <ChapterBody
              chapter={chapter}
              report={report}
              delta={reading.delta}
              probability={probability}
              history={history}
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
      <div className={styles.chapterNum}>
        {String(page + 1).padStart(2, '0')}
        {eyebrow && <span className={styles.eyebrow}>{eyebrow}</span>}
      </div>
      <div className={styles.bookTitle}>{chapterTitle(chapter, report)}</div>
      <div className={styles.bookBody}>
        <ChapterBody
          chapter={chapter}
          report={report}
          delta={reading.delta}
          probability={probability}
          history={history}
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
            {page === 0 ? '더 깊게 봐야 할 장면으로' : `다음 — ${nextTitle}`}
          </button>
        )}
      </div>
    </div>
  );
}
