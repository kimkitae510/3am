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
import { extractErrorCode, extractErrorMessage } from '../api/client';
import { formatListTime } from '../utils/datetime';
import { GAUGE_MAX, bandLabel } from '../utils/assessmentScale';
import { ATTACHMENT_PROFILES, ATTACHMENT_PROFILE_NOTE } from '../utils/attachmentProfiles';
import styles from './AssessmentPage.module.css';

// 수치 계산 방식(범위, 단계 기준)은 화면에 공개하지 않는다 — "왜 80이 최대냐" 같은 질문만 만든다.

const ARC_LEN = Math.PI * 120; // 반원 게이지 길이

/* 상태 화면(로딩, 기록 없음, 안내)의 모티프 — 별과 배경원까지 얹은 이전 버전은 장식이
   많아 만든 티가 났다(실측). 단색 초승달 하나만, 은은하게 */
function MoonArt() {
  return (
    <svg className={styles.stateArt} width="52" height="52" viewBox="0 0 52 52" fill="none" aria-hidden="true">
      <mask id="moon-cut">
        <rect width="52" height="52" fill="white" />
        <circle cx="34" cy="19" r="16.5" fill="black" />
      </mask>
      <circle cx="26" cy="26" r="17" fill="#B89DD1" opacity="0.55" mask="url(#moon-cut)" />
    </svg>
  );
}

/* 섹션 머리 — 제목과 개수 뒤로 가는 선을 흘려 구획을 눈에 보이게 한다(제목만 있으면 카드 더미에 묻힘) */
function SectionHead({ title, count }: { title: string; count?: number }) {
  return (
    <div className={styles.sectionHead}>
      <span className={styles.sectionTitle}>{title}</span>
      {count != null && <span className={styles.sectionCount}>{count}</span>}
      <span className={styles.sectionRule} />
    </div>
  );
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
  // 직전 진단의 확률 — 게이지 옆 "지난 진단보다 ±N" 표시용. 번복(잠금 해제, 제안 철회) 뒤에는
  // 비교 기준이 흐려져서 null로 지운다(엉뚱한 증감이 뜨는 것보다 안 뜨는 게 낫다).
  const [prevProb, setPrevProb] = useState<number | null>(null);
  const [loading, setLoading] = useState(true); // 진입 시 저장된 기록 조회(공짜 GET)
  const [diagnosing, setDiagnosing] = useState(false); // 새 진단(LLM 호출, 쿼터 차감) 실행 중
  const [error, setError] = useState('');
  // "이야기가 부족해요" 안내 — 에러 배너와 달리 스스로 사라지지 않는다.
  // 무엇을 더 말해야 하는지가 담겨 있어서, 유저가 읽고 뒤로가기로 나갈 때까지 떠 있어야 한다.
  const [notice, setNotice] = useState('');
  const [remaining, setRemaining] = useState<number | null>(null); // 오늘 남은 진단 횟수
  const [paidRemaining, setPaidRemaining] = useState(0); // 결제 이용권 잔여(무료 소진 후 차감)
  const [isGuest, setIsGuest] = useState(false); // 게스트는 진단 잠금 — 계정 연결 유도
  const [showHelp, setShowHelp] = useState(false);
  const [showTypeDetail, setShowTypeDetail] = useState(false); // 유형 상세 프로필 펼침
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
        setPrevProb(null);
        // 직전 확률 진단이 없으면(첫 진단부터 잠금) 빈 화면이 되는데, 맨 안내("기록이 없어요")로
        // 두면 번복이 무시된 것처럼 읽힌다 — 확인이 반영됐고 다음이 뭔지 말해준다.
        if (!res) {
          setNotice('헤어진 상태로 확인했어요. 아래 진단 받기를 누르면 재회 가능성을 진단해요.');
        }
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
      if (aliveRef.current) {
        setResult(res);
        setPrevProb(null);
      }
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
        setIsGuest(u.guest);
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
        // 이야기 부족(INSUFFICIENT)은 결과가 아니라 안내다 — 기존 결과는 그대로 두고
        // 사라지지 않는 안내 배너로 띄운다(저장도 안 되는 임시 응답이라 결과 자리를 차지하면 안 된다).
        if (res.verdict === 'INSUFFICIENT') {
          setNotice(res.reason);
        } else {
          setNotice('');
          // 새 결과로 갈아끼우기 전, 화면에 있던 확률이 이번 결과의 비교 기준이 된다.
          setPrevProb(result?.probability ?? null);
          setResult(res);
        }
        refreshUsage(); // 후차감이라 성공 시점에 갱신
      }
    } catch (e) {
      // 소진(Q001)을 백엔드 문구("이용권을 채우거나...")로 그대로 띄우면 아래 상시 '충전하기'와
      // 구매 권유가 이중이 된다 — 배너는 상태만 알리고, 동선은 링크 하나를 가리킨다(채팅과 동일 패턴).
      if (aliveRef.current) {
        setError(
          extractErrorCode(e) === 'Q001'
            ? '오늘 진단 횟수를 다 썼어요. 아래 충전하기로 이어갈 수 있어요.'
            : extractErrorMessage(e, '진단에 실패했어요. 잠시 후 다시 시도해 주세요.'),
        );
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
      .then((all) => {
        if (!aliveRef.current) return;
        setResult(all[0] ?? null);
        // 비교 기준은 "직전의 확률 있는 진단" — 사이에 낀 잠금 판정(DATING 등)은 건너뛴다.
        setPrevProb(all.slice(1).find((a) => a.probability != null)?.probability ?? null);
      })
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
            <MoonArt />
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

  // 게스트는 진단이 잠겨 있다 — 계정 연결로 유도한다(진단 버튼 대신).
  if (isGuest) {
    return (
      <PhoneFrame>
        <div className={styles.wrap}>
          <BackBar onBack={toChat} />
          <div className={styles.state}>
            <MoonArt />
            재회 진단은 계정을 연결하면 받을 수 있어요.
            <br />
            연결하면 지금까지의 대화가 그대로 이어지고,
            <br />
            대화 5회와 진단 1회도 선물로 드려요.
          </div>
          <div className={styles.footer}>
            <button className={styles.btnPrimary} onClick={() => navigate('/guest-link')}>
              계정 연결하고 진단받기
            </button>
          </div>
        </div>
      </PhoneFrame>
    );
  }

  // 진단 기록이 아직 없음 — 여기서만 첫 진단을 시작한다.
  // 방금 "이야기 부족" 안내를 받았다면 기본 문구 대신 그 안내를 계속 보여준다(자동 소멸 없음).
  if (!result) {
    return (
      <PhoneFrame>
        <div className={styles.wrap}>
          <BackBar onBack={toChat} />
          <div className={styles.state}>
            <MoonArt />
            {notice || (
              <>
                아직 진단 기록이 없어요.
                <br />
                지금까지의 대화를 읽고 재회 가능성을 진단해요.
                <br />
                대화를 충분히 나눌수록 정확해져요.
              </>
            )}
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
  const doItems = result.guidance.filter((g) => g.kind === 'DO');
  const dontItems = result.guidance.filter((g) => g.kind === 'DONT');

  return (
    <PhoneFrame>
      <div className={styles.wrap}>
        <BackBar onBack={toChat} onHelp={() => setShowHelp(true)} />
        {error && <div className={styles.errorBanner}>{error}</div>}
        {notice && (
          <div className={styles.noticeBanner}>
            <svg className={styles.noticeIcon} width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="12" cy="12" r="9" stroke="#B89DD1" strokeWidth="1.6" />
              <path d="M12 11v5M12 7.6h.01" stroke="#B89DD1" strokeWidth="1.8" strokeLinecap="round" />
            </svg>
            {notice}
          </div>
        )}
        <div className={styles.body}>
          <div className={styles.meta}>마지막 진단 - {metaDate}</div>

          {/* 재회 성공과 사귀는 중은 확률 화면이 아니다 — 게이지 대신 히어로 문법(같은 결)으로.
              게이지에 반투명 덮개를 씌우던 잠금은 미완성 화면처럼 읽혔다(실측) */}
          {reunited ? (
            <div className={styles.reunitedHero}>
              <div className={styles.reunitedTitle}>다시 만나게 됐어요</div>
              <div className={styles.reunitedSub}>
                재회에 성공해서 확률 진단은 여기까지예요.
                <br />
                이제 관계를 이어가는 대화로 함께해요.
              </div>
            </div>
          ) : dating ? (
            <div className={styles.reunitedHero}>
              <div className={styles.reunitedTitle}>지금은 만나는 중이에요</div>
              <div className={styles.reunitedSub}>
                재회 확률은 이별을 전제로 한 진단이라
                <br />
                헤어진 뒤에 다시 열려요.
              </div>
            </div>
          ) : (
            <>
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
              {/* 직전 진단 대비 변화 — 정지 사진이던 결과에 흐름을 붙인다(전체 추이는 기록 화면) */}
              {!dating && prevProb != null && prob < 100 && (
                <div
                  className={`${styles.deltaChip} ${
                    prob > prevProb ? styles.deltaChipUp : prob < prevProb ? styles.deltaChipDown : ''
                  }`}
                >
                  {prob === prevProb
                    ? '지난 진단과 같아요'
                    : `지난 진단보다 ${prob > prevProb ? '+' : ''}${prob - prevProb}%p`}
                </div>
              )}
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
              {/* 제목과 설명은 위 히어로가 말했으니 카드는 번복 창구만 — 중복 문장 제거 */}
              <div className={styles.lockCard}>
                <div className={styles.lockAskRowSolo}>
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
                남은 것은 확률이 아니라 내 선택이라 100%로 보여드려요. 제안이 없던 일이 되면
                저장해 둔 신호 기준으로 바로 다시 계산해 드려요.
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

          {/* 확률 화면에도 총평을 싣는다 — 신호 조각들만으론 서사가 없어 숫자가 건조하게 남는다 */}
          {!dating && !reunited && result.reason && (
            <div className={styles.reasonCard}>
              <div className={styles.reasonLabel}>총평</div>
              {result.reason}
            </div>
          )}

          {/* 상대 유형만 판정한다(내 유형 폐기 — 여기서 궁금한 건 상대다). 일반 설명은 도움말 모달로.
              판정 근거도 감점 신호처럼 목록으로 보여준다. 추정(TENTATIVE)이면 단정 대신 "~로 보여요" 톤. */}
          <SectionHead title="상대 애착유형" />
          <div className={styles.typeRow}>
            <div className={styles.typeCard}>
              {/* "~으로 보여요" 문장형 + 안내문의 겹침이 복잡함의 원인(실측) — 이름과 확신도
                  라벨 한 줄로 압축 */}
              <div className={styles.typeHead}>
                <div
                  className={`${styles.typeName} ${result.partnerAttachment ? '' : styles.typeNameUnknown}`}
                >
                  {result.partnerAttachment ?? '미확정'}
                </div>
                {result.partnerAttachment && result.attachmentConfidence === 'TENTATIVE' && (
                  <span className={styles.typeBadge}>추정</span>
                )}
              </div>
              {/* 미확정은 빈칸이 아니라 안내 — 뭘 더 들려주면 잡히는지 알려줘야 다음 진단으로 이어진다 */}
              {!result.partnerAttachment && (
                <div className={styles.typeNote}>
                  아직 행동 근거가 부족해서 유형을 잡지 못했어요. 갈등이 있을 때 상대가
                  어떻게 반응했는지, 이별을 어떤 방식으로 전했는지 들려주면 다음 진단에서
                  잡힐 수 있어요.
                </div>
              )}
              {result.attachmentSignals.length > 0 && (
                <div className={styles.typeSignals}>
                  {/* 근거 목록에 이름표가 없으면 유형 설명인지 근거인지 안 갈린다(실측 피드백) */}
                  <div className={styles.typeSignalsLabel}>판정 근거</div>
                  {result.attachmentSignals.map((s, i) => (
                    <div className={styles.typeSignal} key={i}>
                      <div className={styles.typeSignalName}>{s.signal}</div>
                      {s.evidence && <div className={styles.typeSignalEvidence}>{s.evidence}</div>}
                    </div>
                  ))}
                </div>
              )}
              {/* 유형 상세 — "아 그래서 걔가 그랬구나"를 주는 정적 프로필. 근거 없는 통념
                  ("회피형은 몇 달 뒤 무너져 돌아온다")은 본문에서 통념임을 명시해 대기 심리를 안 부추긴다 */}
              {result.partnerAttachment && ATTACHMENT_PROFILES[result.partnerAttachment] && (
                <>
                  <button className={styles.typeMoreBtn} onClick={() => setShowTypeDetail((v) => !v)}>
                    {showTypeDetail ? '접기' : '이 유형 더 알아보기'}
                  </button>
                  {showTypeDetail && (
                    <div className={styles.typeProfile}>
                      {(
                        [
                          ['연애할 때', ATTACHMENT_PROFILES[result.partnerAttachment].during],
                          ['이별 후에는', ATTACHMENT_PROFILES[result.partnerAttachment].after],
                          ['이 유형을 대할 때', ATTACHMENT_PROFILES[result.partnerAttachment].approach],
                          ['흔한 오해', ATTACHMENT_PROFILES[result.partnerAttachment].myths],
                        ] as [string, string[]][]
                      ).map(([heading, lines]) => (
                        <div key={heading}>
                          <div className={styles.typeProfileHeading}>{heading}</div>
                          {lines.map((line, i) => (
                            <p className={styles.typeProfileText} key={i}>
                              {line}
                            </p>
                          ))}
                        </div>
                      ))}
                      <div className={styles.typeProfileNote}>{ATTACHMENT_PROFILE_NOTE}</div>
                    </div>
                  )}
                </>
              )}
            </div>
          </div>

          {/* 한 목록에 부호로 섞여 오므로(감점 음수, 가점 양수) 나눠서 보여준다.
              카드 구조: 제목+점수(상단 정렬) / 사실 / 판독 이유(어두운 박스) — 층이 한눈에 갈리게.
              제안 확정(100%)일 땐 숨긴다 — 수락만 남은 상태에 점수 셈이 떠 있으면 어색하다
              (재회 성공 화면과 같은 원칙, 신호는 번복 대비로 저장만 유지) */}
          {prob < 100 && minus.length > 0 && (
            <>
              <SectionHead title="가능성을 낮춘 신호" count={minus.length} />
              <div className={styles.dedList}>
                {minus.map((d, i) => (
                  <div className={styles.dedItem} key={i}>
                    <div className={styles.dedTop}>
                      <div className={styles.dedSignal}>{d.signal}</div>
                      <div className={styles.minusDelta}>−{Math.abs(d.delta)}</div>
                    </div>
                    {d.evidence && <div className={styles.dedEvidence}>{d.evidence}</div>}
                    {d.rationale && <div className={styles.dedRationale}>{d.rationale}</div>}
                  </div>
                ))}
              </div>
            </>
          )}

          {prob < 100 && plus.length > 0 && (
            <>
              <SectionHead title="가능성을 올린 신호" count={plus.length} />
              <div className={styles.dedList}>
                {plus.map((d, i) => (
                  <div className={styles.dedItem} key={i}>
                    <div className={styles.dedTop}>
                      <div className={styles.dedSignal}>{d.signal}</div>
                      <div className={styles.boostDelta}>+{d.delta}</div>
                    </div>
                    {d.evidence && <div className={styles.dedEvidence}>{d.evidence}</div>}
                    {d.rationale && <div className={styles.dedRationale}>{d.rationale}</div>}
                  </div>
                ))}
              </div>
            </>
          )}

          {/* 행동 가이드 — 판정(무엇이다) 다음에 행동(그래서 뭐)을 붙인다. 재회 기술이 아니라
              회복을 지키는 프레임으로 생성되고(루브릭), 여기선 그대로 보여주기만 한다 */}
          {doItems.length > 0 && (
            <>
              <SectionHead title="지금 도움이 되는 것" />
              <div className={styles.dedList}>
                {doItems.map((g, i) => (
                  <div className={styles.guideItem} key={i}>
                    <div className={styles.guideText}>{g.advice}</div>
                    {g.basis && <div className={styles.guideBasis}>{g.basis}</div>}
                  </div>
                ))}
              </div>
            </>
          )}

          {dontItems.length > 0 && (
            <>
              <SectionHead title="지금은 피할 것" />
              <div className={styles.dedList}>
                {dontItems.map((g, i) => (
                  <div className={styles.guideItem} key={i}>
                    <div className={styles.guideText}>{g.advice}</div>
                    {g.basis && <div className={styles.guideBasis}>{g.basis}</div>}
                  </div>
                ))}
              </div>
            </>
          )}

          {/* 갱신 안내 문구는 제거 — 새 이야기 없이 다시 진단하면 서버가 사유를 설명하며 거부해서 중복 안내였다 */}
          <div className={styles.hintRow}>
            <div className={styles.hintCount}>
              {/* 무료/이용권 각각 보여주되 숫자만 밝게(채팅 잔여 줄과 같은 문법) */}
              {remaining != null ? (
                <>
                  오늘 남은 진단 <span className={styles.hintCountNum}>{remaining}회</span>
                  {paidRemaining > 0 && (
                    <>
                      {' '}+ 이용권 <span className={styles.hintCountNum}>{paidRemaining}회</span>
                    </>
                  )}
                </>
              ) : (
                '하루 1회'
              )}
            </div>
            {/* 소진 전에도 구매 위치가 보이게 상시 진입점 — 채팅의 충전하기와 같은 동선 */}
            <button className={styles.topupLink} onClick={() => navigate('/payment')}>
              <svg width="11" height="11" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M12 4.5v15M4.5 12h15" stroke="#B89DD1" strokeWidth="2.2" strokeLinecap="round" />
              </svg>
              충전하기
            </button>
          </div>

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
                heading: '상대 애착유형',
                text: '안정형 : 감정을 말로 풀고 갈등을 대화로 다루는 편\n불안형 : 확인받고 싶어 하고 거리가 생기면 매달리는 편\n거부회피형 : 감정 얘기를 피하고 이별 후 뒤돌아보지 않는 편\n공포회피형 : 밀어내고 다시 찾기를 반복하는 편\n판정에 쓰인 행동 근거는 유형 카드에 함께 보여드립니다. 근거가 아직 얇으면 추정 표시가 붙고, 이야기가 쌓이면 분명해집니다.',
              },
              {
                heading: '행동 가이드',
                text: '이번 진단의 신호와 상대 유형을 근거로 "지금 도움이 되는 것"과 "지금은 피할 것"을 제안합니다. 상대를 되돌리는 기술이 아니라 나를 지키면서 남은 가능성을 깎지 않는 방향의 제안이고, 결정은 언제나 내 몫입니다.',
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
