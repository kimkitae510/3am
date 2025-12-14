import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { getAssessments, runAssessment, type AssessmentResponse } from '../api/assessment';
import { getUsage } from '../api/usage';
import { extractErrorMessage } from '../api/client';
import { formatListTime } from '../utils/datetime';
import styles from './AssessmentPage.module.css';

const GAUGE_MAX = 80; // 확률 상한(정책, 백엔드 클램프와 동일). 게이지는 이 값을 만점으로 그린다.
const GAUGE_MIN = 5; // 확률 하한(정책)

// 애착유형 라벨(서버) → 한 줄 설명. 유형명만 던지면 뭔지 모르는 유저가 많다.
const ATTACH_DESC: Record<string, string> = {
  안정형: '감정을 말로 풀고, 갈등을 대화로 다루는 편이에요.',
  불안형: '확인받고 싶어 하고, 거리가 생기면 매달리는 편이에요.',
  거부회피형: '갈등과 감정 얘기를 피하고, 이별 후엔 뒤도 안 돌아보는 것처럼 보이는 편이에요.',
  공포회피형: '가까워지면 밀어내고 멀어지면 다시 찾아요. 잠수와 재연락을 반복하는 편이에요.',
};

const ARC_LEN = Math.PI * 120; // 반원 게이지 길이

function bandText(prob: number): string {
  if (prob >= 100) return '상대의 제안이 유효한 상태예요';
  return prob < 15 ? '아직은 낮아요' : prob < 40 ? '낮지도, 높지도 않아요' : '가능성이 보여요';
}

function BackBar({ onBack, onHelp }: { onBack: () => void; onHelp?: () => void }) {
  return (
    <div className={styles.topbar}>
      <button className={styles.backButton} onClick={onBack} aria-label="뒤로">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
          <path d="M15 5l-7 7 7 7" stroke="#ECEAF0" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </button>
      <div className={styles.topTitle}>진단</div>
      {onHelp && (
        <button className={styles.helpButton} onClick={onHelp} aria-label="도움말">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
            <circle cx="12" cy="12" r="9" stroke="#9B98A3" strokeWidth="1.6" />
            <path d="M9.6 9.2a2.4 2.4 0 114.1 1.7c-.7.7-1.7 1.1-1.7 2.2M12 16.4h.01" stroke="#9B98A3" strokeWidth="1.7" strokeLinecap="round" />
          </svg>
        </button>
      )}
    </div>
  );
}

export function AssessmentPage() {
  const { storyId: storyIdParam } = useParams();
  const storyId = Number(storyIdParam);
  const navigate = useNavigate();

  const [result, setResult] = useState<AssessmentResponse | null>(null);
  const [loading, setLoading] = useState(true); // 진입 시 저장된 기록 조회(공짜 GET)
  const [diagnosing, setDiagnosing] = useState(false); // 새 진단(LLM 호출, 쿼터 차감) 실행 중
  const [error, setError] = useState('');
  const [remaining, setRemaining] = useState<number | null>(null); // 오늘 남은 진단 횟수
  const [showHelp, setShowHelp] = useState(false);
  const aliveRef = useRef(true);

  // 에러 배너(쿼터 소진, 재진단 거부 등)가 화면에 계속 남지 않게 잠시 뒤 스스로 사라진다.
  useEffect(() => {
    if (!error) return;
    const timer = window.setTimeout(() => aliveRef.current && setError(''), 6000);
    return () => clearTimeout(timer);
  }, [error]);

  function refreshUsage() {
    getUsage()
      .then((u) => aliveRef.current && setRemaining(u.assessmentRemaining))
      .catch(() => {});
  }

  // 새 진단은 버튼으로만 실행한다 — 페이지 진입만으로 일일 쿼터가 닳지 않게.
  async function diagnose() {
    setDiagnosing(true);
    setError('');
    try {
      const res = await runAssessment(storyId);
      if (aliveRef.current) {
        setResult(res);
        refreshUsage(); // 후차감이라 성공 시점에 갱신
      }
    } catch (e) {
      if (aliveRef.current) setError(extractErrorMessage(e, '진단에 실패했어요. 잠시 후 다시 시도해 주세요.'));
    } finally {
      if (aliveRef.current) setDiagnosing(false);
    }
  }

  useEffect(() => {
    aliveRef.current = true;
    refreshUsage();
    // 진입 시엔 저장된 최신 진단만 보여준다. LLM 호출 없음.
    getAssessments(storyId)
      .then((all) => aliveRef.current && setResult(all[0] ?? null))
      .catch((e) => aliveRef.current && setError(extractErrorMessage(e, '진단 기록을 불러오지 못했어요.')))
      .finally(() => aliveRef.current && setLoading(false));
    return () => {
      aliveRef.current = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storyId]);

  const toChat = () => navigate(`/stories/${storyId}`);

  if (loading || diagnosing) {
    return (
      <PhoneFrame>
        <div className={styles.wrap}>
          <BackBar onBack={toChat} />
          <div className={styles.state}>
            {diagnosing ? (
              <>
                재회 진단중입니다
                <span className={styles.stateDots}>
                  <span className={styles.stateDot} />
                  <span className={styles.stateDot} />
                  <span className={styles.stateDot} />
                </span>
              </>
            ) : (
              '불러오는 중…'
            )}
          </div>
        </div>
      </PhoneFrame>
    );
  }

  // 결과가 아예 없을 때만 전체 화면 에러. 결과가 있으면 아래에서 배너로 보여준다(결과를 가리지 않게).
  if (error && !result) {
    return (
      <PhoneFrame>
        <div className={styles.wrap}>
          <BackBar onBack={toChat} />
          <div className={styles.state}>{error}</div>
          <div className={styles.footer}>
            <button className={styles.btnGhost} onClick={toChat}>
              대화로
            </button>
            <button className={styles.btnPrimary} onClick={diagnose}>
              다시 진단 (1회 차감)
            </button>
          </div>
        </div>
      </PhoneFrame>
    );
  }

  // 진단 기록이 아직 없음 — 여기서만 첫 진단을 시작한다.
  if (!result) {
    return (
      <PhoneFrame>
        <div className={styles.wrap}>
          <BackBar onBack={toChat} />
          <div className={styles.state}>
            아직 진단 기록이 없어요.
            <br />
            지금까지의 대화를 읽고 재회 가능성을 진단해요.
            <br />
            대화를 충분히 나눌수록 정확해져요.
          </div>
          <div className={styles.footer}>
            <button className={styles.btnPrimary} onClick={diagnose}>
              진단 받기
            </button>
          </div>
        </div>
      </PhoneFrame>
    );
  }

  // "계속 대화하면 진단도 따라 갱신된다"는 오해가 있어, 이 결과가 언제 것인지 명시한다.
  const metaDate = result.createdAt ? formatListTime(result.createdAt) : '방금';

  // 데이터 부족
  if (result.verdict === 'INSUFFICIENT') {
    return (
      <PhoneFrame>
        <div className={styles.wrap}>
          <BackBar onBack={toChat} />
          {error && <div className={styles.errorBanner}>{error}</div>}
          <div className={styles.centerBody}>
            <div className={styles.icon}>
              <svg width="26" height="26" viewBox="0 0 24 24" fill="none">
                <path d="M12 8v5M12 16h.01" stroke="#9B98A3" strokeWidth="1.8" strokeLinecap="round" />
                <circle cx="12" cy="12" r="9" stroke="#9B98A3" strokeWidth="1.6" />
              </svg>
            </div>
            <div className={styles.kicker}>데이터 부족</div>
            <div className={styles.bigMsg}>
              아직 진단하기엔
              <br />
              이야기가 부족해요.
            </div>
            <div className={styles.subMsg}>{result.reason}</div>
          </div>
          <div className={styles.footer}>
            <button className={styles.btnPrimary} onClick={toChat}>
              대화 더 하기
            </button>
          </div>
        </div>
      </PhoneFrame>
    );
  }

  // POSSIBLE — 확률
  const prob = result.probability ?? 0;
  const fill = (Math.min(prob, GAUGE_MAX) / GAUGE_MAX) * ARC_LEN;
  const minus = result.deductions.filter((d) => d.delta < 0);
  const plus = result.deductions.filter((d) => d.delta > 0);

  return (
    <PhoneFrame>
      <div className={styles.wrap}>
        <BackBar onBack={toChat} onHelp={() => setShowHelp(true)} />
        {error && <div className={styles.errorBanner}>{error}</div>}
        <div className={styles.body}>
          <div className={styles.meta}>재회 확률은 이 대화방의 이야기 기준이에요</div>
          <div className={styles.metaSub}>마지막 진단 {metaDate}</div>

          <div className={styles.gaugeWrap}>
            <svg width="280" height="150" viewBox="0 0 280 150">
              <path d="M20,138 A120,120 0 0 1 260,138" fill="none" stroke="#2A2833" strokeWidth="14" strokeLinecap="round" />
              <path
                d="M20,138 A120,120 0 0 1 260,138"
                fill="none"
                stroke="#B89DD1"
                strokeWidth="14"
                strokeLinecap="round"
                strokeDasharray={`${fill} ${ARC_LEN + 40}`}
              />
            </svg>
            <div className={styles.gaugeValue}>
              <div className={styles.gaugeNum}>
                {prob}
                <span className={styles.gaugePct}>%</span>
              </div>
            </div>
          </div>
          <div className={styles.gaugeLabel}>재회 가능성</div>
          <div className={styles.gaugeSub}>{bandText(prob)}</div>
          <div className={styles.gaugeNote}>
            확률은 보통 {GAUGE_MIN}~{GAUGE_MAX}% 사이로만 봅니다.
            <br />
            100%는 상대가 먼저 만나자고 한 상태에서만 나옵니다.
          </div>

          {/* 유형은 나/상대 모두 애착유형 하나로 통일(커스텀 유형 폐기) */}
          <div className={styles.dedTitle}>애착유형</div>
          <div className={styles.typeRow}>
            <div className={styles.typeCard}>
              <div className={styles.typeKey}>나</div>
              <div className={styles.typeName}>{result.myAttachment ?? '—'}</div>
            </div>
            <div className={styles.typeCard}>
              <div className={styles.typeKey}>상대</div>
              <div className={styles.typeName}>{result.partnerAttachment ?? '—'}</div>
            </div>
          </div>
          {result.partnerAttachment && ATTACH_DESC[result.partnerAttachment] && (
            <div className={styles.attachDesc}>상대: {ATTACH_DESC[result.partnerAttachment]}</div>
          )}
          {result.myAttachment && ATTACH_DESC[result.myAttachment] && (
            <div className={styles.attachDesc}>나: {ATTACH_DESC[result.myAttachment]}</div>
          )}

          {/* 한 목록에 부호로 섞여 오므로(감점 음수, 가점 양수) 나눠서 보여준다 */}
          {minus.length > 0 && (
            <>
              <div className={styles.dedTitle}>가능성을 낮춘 신호</div>
              <div className={styles.dedList}>
                {minus.map((d, i) => (
                  <div className={styles.dedItem} key={i}>
                    <div className={styles.dedMain}>
                      <div className={styles.dedSignal}>{d.signal}</div>
                      {d.evidence && <div className={styles.dedEvidence}>{d.evidence}</div>}
                    </div>
                    <div className={styles.dedDelta}>−{Math.abs(d.delta)}</div>
                  </div>
                ))}
              </div>
            </>
          )}

          {plus.length > 0 && (
            <>
              <div className={styles.dedTitle}>가능성을 올린 신호</div>
              <div className={styles.dedList}>
                {plus.map((d, i) => (
                  <div className={styles.dedItem} key={i}>
                    <div className={styles.dedMain}>
                      <div className={styles.dedSignal}>{d.signal}</div>
                      {d.evidence && <div className={styles.dedEvidence}>{d.evidence}</div>}
                    </div>
                    <div className={styles.boostDelta}>+{d.delta}</div>
                  </div>
                ))}
              </div>
            </>
          )}

          {/* 갱신 안내 문구는 제거 — 새 이야기 없이 다시 진단하면 서버가 사유를 설명하며 거부해서 중복 안내였다 */}
          <div className={styles.hintCount}>{remaining != null ? `오늘 ${remaining}회 남음` : '하루 2회'}</div>

          <button
            className={styles.askChat}
            onClick={() =>
              navigate(`/stories/${storyId}`, {
                state: { prefill: '진단 결과가 왜 이렇게 나온 건지 설명해줄래?' },
              })
            }
          >
            왜 이 결과인지 대화로 물어보기
          </button>
        </div>

        <div className={styles.footer}>
          <button className={styles.btnGhost} onClick={() => navigate(`/stories/${storyId}/history`)}>
            기록
          </button>
          <button className={styles.btnPrimary} onClick={diagnose}>
            다시 진단 (1회 차감)
          </button>
        </div>

        {showHelp && (
          <div className={styles.overlay} onClick={() => setShowHelp(false)}>
            <div className={styles.dialog} onClick={(e) => e.stopPropagation()}>
              <div className={styles.dialogTitle}>진단은 이렇게 봐요</div>
              <div className={styles.helpBlock}>
                <div className={styles.helpKey}>재회 가능성</div>
                대화와 기록된 사실을 근거로 "상대가 돌아올 가능성"을 봐요. 보통 {GAUGE_MIN}~{GAUGE_MAX}%
                사이이고, 상대가 먼저 만나자고 한 상태에서만 100%가 나와요.
              </div>
              <div className={styles.helpBlock}>
                <div className={styles.helpKey}>단계</div>
                15% 미만 "아직은 낮아요", 40% 미만 "낮지도, 높지도 않아요", 40% 이상 "가능성이 보여요"
              </div>
              <div className={styles.helpBlock}>
                <div className={styles.helpKey}>애착유형</div>
                안정형, 불안형, 거부회피형(거회), 공포회피형(공회) 네 가지예요. 대화에 드러난 행동
                패턴으로 판정하고, 근거가 부족하면 비워둬요.
              </div>
              <div className={styles.helpBlock}>
                <div className={styles.helpKey}>횟수</div>
                진단은 하루 2회, 대화는 하루 10회예요. "다시 진단"은 1회가 차감되지만, 이야기가
                부족하다는 안내만 받은 경우엔 차감되지 않아요.
              </div>
              <button className={styles.btnPrimary} onClick={() => setShowHelp(false)}>
                알겠어요
              </button>
            </div>
          </div>
        )}
      </div>
    </PhoneFrame>
  );
}
