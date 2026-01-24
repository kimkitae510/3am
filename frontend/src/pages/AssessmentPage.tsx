import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { HelpModal } from '../components/HelpModal';
import {
  confirmBreakup,
  getAssessments,
  retractOffer,
  runAssessment,
  type AssessmentResponse,
} from '../api/assessment';
import { getUsage } from '../api/usage';
import { extractErrorMessage } from '../api/client';
import { formatListTime } from '../utils/datetime';
import { GAUGE_MAX, bandLabel } from '../utils/assessmentScale';
import styles from './AssessmentPage.module.css';

// 수치 계산 방식(범위, 단계 기준)은 화면에 공개하지 않는다 — "왜 80이 최대냐" 같은 질문만 만든다.

const ARC_LEN = Math.PI * 120; // 반원 게이지 길이

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
  const [paidRemaining, setPaidRemaining] = useState(0); // 결제 이용권 잔여(무료 소진 후 차감)
  const [showHelp, setShowHelp] = useState(false);
  const [confirming, setConfirming] = useState(false); // 헤어짐 확인 API 진행 중
  const [retracting, setRetracting] = useState(false); // 제안 번복 API 진행 중
  const aliveRef = useRef(true);

  // 에러 배너(쿼터 소진, 재진단 거부 등)가 화면에 계속 남지 않게 잠시 뒤 스스로 사라진다.
  useEffect(() => {
    if (!error) return;
    const timer = window.setTimeout(() => aliveRef.current && setError(''), 6000);
    return () => clearTimeout(timer);
  }, [error]);

  async function handleConfirmBreakup() {
    setConfirming(true);
    try {
      // 서버가 오판이던 잠금 판정을 지우고 직전 확률 진단을 돌려준다 — 화면이 즉시 복귀한다.
      const res = await confirmBreakup(storyId);
      if (aliveRef.current) {
        setResult(res);
        refreshUsage();
      }
    } catch (e) {
      if (aliveRef.current) setError(extractErrorMessage(e, '처리하지 못했어요. 잠시 후 다시 시도해 주세요.'));
    } finally {
      if (aliveRef.current) setConfirming(false);
    }
  }

  async function handleRetractOffer() {
    setRetracting(true);
    try {
      // 서버가 신호 재합산 값으로 되돌린 결과를 주므로, 그걸로 교체하면 게이지가 즉시 바뀐다.
      const res = await retractOffer(storyId);
      if (aliveRef.current) setResult(res);
    } catch (e) {
      if (aliveRef.current) setError(extractErrorMessage(e, '처리하지 못했어요. 잠시 후 다시 시도해 주세요.'));
    } finally {
      if (aliveRef.current) setRetracting(false);
    }
  }

  function refreshUsage() {
    getUsage()
      .then((u) => {
        if (!aliveRef.current) return;
        setRemaining(u.assessmentRemaining);
        setPaidRemaining(u.assessmentPaidRemaining);
      })
      .catch(() => {});
  }

  // 새 진단은 버튼으로만 실행한다 — 페이지 진입만으로 일일 쿼터가 닳지 않게.
  async function diagnose() {
    setDiagnosing(true);
    setError('');
    try {
      const res = await runAssessment(storyId);
      if (aliveRef.current) {
        // 이야기 부족(INSUFFICIENT)은 결과가 아니라 안내다 — 화면 전환 대신 배너로(소진 안내와 통일),
        // 기존 결과는 그대로 둔다(저장도 안 되는 임시 응답이라 결과 자리를 차지하면 안 된다).
        if (res.verdict === 'INSUFFICIENT') {
          setError(res.reason);
        } else {
          setResult(res);
        }
        refreshUsage(); // 후차감이라 성공 시점에 갱신
      }
    } catch (e) {
      // 소진(Q001)도 별도 버튼 없이 에러 배너로만 — 구매 동선은 상시 노출된 "추가 이용권 구매"가 담당.
      if (aliveRef.current) {
        setError(extractErrorMessage(e, '진단에 실패했어요. 잠시 후 다시 시도해 주세요.'));
      }
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
                {/* 진단 LLM이 느릴 때 이탈해도 손해가 아니라는 안내 — 결과는 저장돼 재진입 시 보인다 */}
                <span className={styles.stateSub}>
                  시간이 좀 걸릴 수 있어요.
                  <br />
                  화면을 나가도 결과는 저장돼요.
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

  // INSUFFICIENT는 저장되지 않고 diagnose()에서 배너로 처리되므로 여기 도달하는 결과는
  // POSSIBLE(확률), DATING(사귀는 중 — 확률만 잠금), REUNITED(재회 성공 — 게이지 대신 축하)뿐이다.
  const dating = result.verdict === 'DATING';
  const reunited = result.verdict === 'REUNITED';
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
          <div className={styles.meta}>마지막 진단 - {metaDate}</div>

          {/* 재회 성공은 확률 화면이 아니라 축하 화면 — 게이지 자체를 두지 않는다 */}
          {reunited ? (
            <div className={styles.reunitedHero}>
              <div className={styles.reunitedTitle}>다시 만나게 됐어요</div>
              <div className={styles.reunitedSub}>
                재회에 성공해서 확률 진단은 여기까지예요.
                <br />
                이제 관계를 이어가는 대화로 함께해요.
              </div>
            </div>
          ) : (
            <>
              <div className={styles.gaugeWrap}>
                <svg width="280" height="150" viewBox="0 0 280 150">
                  <path d="M20,138 A120,120 0 0 1 260,138" fill="none" stroke="#2A2833" strokeWidth="14" strokeLinecap="round" />
                  {!dating && (
                    <path
                      d="M20,138 A120,120 0 0 1 260,138"
                      fill="none"
                      stroke="#B89DD1"
                      strokeWidth="14"
                      strokeLinecap="round"
                      strokeDasharray={`${fill} ${ARC_LEN + 40}`}
                    />
                  )}
                </svg>
                {dating ? (
                  /* 사귀는 중 — 아이콘 대신 말로 잠근다(자물쇠 그림이 스티커처럼 떠 보이던 문제) */
                  <div className={styles.lockOverlay}>
                    <div className={styles.lockOverlayText}>만나는 중이에요</div>
                  </div>
                ) : (
                  <div className={styles.gaugeValue}>
                    <div className={styles.gaugeNum}>
                      {prob}
                      <span className={styles.gaugePct}>%</span>
                    </div>
                  </div>
                )}
              </div>
              <div className={styles.gaugeLabel}>재회 가능성</div>
            </>
          )}
          {reunited ? (
            <>
              {result.reason && <div className={styles.datingReason}>{result.reason}</div>}
              {/* 재회 후 다시 헤어질 수도, 재회로 오해했을 수도 있다 — 누르면 즉시 확률로 복귀 */}
              <div className={styles.lockCard}>
                <div className={styles.lockTitle}>혹시 다시 헤어지게 됐다면</div>
                <div className={styles.lockAskRow}>
                  <span className={styles.lockAskText}>
                    다시 헤어졌거나 제가 오해한 거라면 알려주세요. 확률 진단을 바로 다시 열게요.
                  </span>
                  <button
                    className={styles.lockConfirmBtn}
                    onClick={handleConfirmBreakup}
                    disabled={confirming}
                  >
                    {confirming ? '반영 중…' : '헤어진 게 맞아요'}
                  </button>
                </div>
              </div>
            </>
          ) : dating ? (
            <>
              {/* 잠금 설명과 번복 질문을 카드 하나로 — 문장이 따로 흩어져 있으면 어수선하다 */}
              <div className={styles.lockCard}>
                <div className={styles.lockTitle}>지금은 만나고 있는 사이로 알고 있어요</div>
                <div className={styles.lockDesc}>
                  재회 확률은 이별을 전제로 한 진단이라 헤어진 뒤에 다시 열려요.
                </div>
                {/* 진단이 오해했을 수 있다 — 누르면 오판 기록을 지우고 즉시 직전 확률로 복귀 */}
                <div className={styles.lockAskRow}>
                  <span className={styles.lockAskText}>
                    혹시 제가 오해한 거라면 알려주세요. 확률 진단을 바로 다시 열게요.
                  </span>
                  <button
                    className={styles.lockConfirmBtn}
                    onClick={handleConfirmBreakup}
                    disabled={confirming}
                  >
                    {confirming ? '반영 중…' : '헤어진 게 맞아요'}
                  </button>
                </div>
              </div>
              {result.reason && <div className={styles.datingReason}>{result.reason}</div>}
            </>
          ) : prob >= 100 ? (
            /* 100은 합산 결과가 아니라 "상대의 유효한 재회 제안" 확정값 — 사유 설명과 번복 창구를
               커플 잠금과 같은 카드 문법으로 제공한다. 번복하면 아래 신호들의 합산으로 즉시 되돌아간다 */
            <div className={styles.lockCard}>
              <div className={styles.lockTitle}>상대의 재회 제안이 유효한 상태예요</div>
              <div className={styles.lockDesc}>
                남은 것은 확률이 아니라 내 선택이라 100%로 보여드려요. 제안이 없던 일이 되면 아래
                신호들 기준으로 바로 다시 계산해 드려요.
              </div>
              <div className={styles.lockAskRow}>
                <span className={styles.lockAskText}>
                  제안이 없던 일이 됐거나 제가 잘못 알았다면 알려주세요.
                </span>
                <button
                  className={styles.lockConfirmBtn}
                  onClick={handleRetractOffer}
                  disabled={retracting}
                >
                  {retracting ? '반영 중…' : '유효하지 않아요'}
                </button>
              </div>
            </div>
          ) : (
            <div className={styles.gaugeSub}>{bandLabel(prob)}</div>
          )}

          {/* 유형은 나/상대 모두 애착유형 하나로 통일(커스텀 유형 폐기). 일반 설명은 도움말 모달로.
              판정 근거는 화면에 늘어놓지 않는다 — 채팅 주입에 실려 있어 "왜 이 결과인지 물어보기"로 들을 수 있다. */}
          <div className={styles.dedTitle}>애착유형</div>
          <div className={styles.typeRow}>
            <div className={styles.typeCard}>
              <div className={styles.typeKey}>나</div>
              <div className={styles.typeName}>{result.myAttachment ?? '미확정'}</div>
            </div>
            <div className={styles.typeCard}>
              <div className={styles.typeKey}>상대</div>
              <div className={styles.typeName}>{result.partnerAttachment ?? '미확정'}</div>
            </div>
          </div>

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
          <div className={styles.hintRow}>
            <div className={styles.hintCount}>
              {remaining != null ? `오늘 ${remaining}회 남음` : '하루 1회'}
              {paidRemaining > 0 && ` + 이용권 ${paidRemaining}회`}
            </div>
            {/* 소진 전에도 구매 위치가 보이게 상시 진입점 — 채팅 상단 아이콘과 같은 동선 */}
            <button className={styles.topupLink} onClick={() => navigate('/payment')}>
              추가 이용권 구매
            </button>
          </div>

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
          <HelpModal
            title="진단 가이드"
            onClose={() => setShowHelp(false)}
            sections={[
              {
                heading: '재회 가능성',
                text: '대화와 기록된 사실을 근거로 "상대가 돌아올 가능성"을 봅니다.',
              },
              {
                heading: '100%가 뜨는 경우',
                text: '상대가 재회 의사를 실제로 나에게 밝힌 경우입니다. 남은 것은 내 마음이기 때문입니다. 제안이 없던 일이 되면 다시 내려갑니다.',
              },
              {
                heading: '애착유형',
                text: '안정형 : 감정을 말로 풀고 갈등을 대화로 다루는 편\n불안형 : 확인받고 싶어 하고 거리가 생기면 매달리는 편\n거부회피형 : 감정 얘기를 피하고 이별 후 뒤돌아보지 않는 편\n공포회피형 : 밀어내고 다시 찾기를 반복하는 편\n행동 패턴이 여러 번 보여야 잡히기 때문에 처음에는 미확정으로 나올 수 있습니다.',
              },
              {
                heading: '진단 횟수',
                text: '진단은 하루 1회입니다. 이야기가 부족하다는 안내만 받은 경우에는 차감되지 않습니다.',
              },
            ]}
          />
        )}
      </div>
    </PhoneFrame>
  );
}
