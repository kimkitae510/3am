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
  type FactorView,
} from '../api/assessment';
import { getUsage } from '../api/usage';
import { getSimilarCases, type SimilarCases } from '../api/match';
import { extractErrorCode, extractErrorMessage } from '../api/client';
import { formatListTime } from '../utils/datetime';
import { GAUGE_MAX, bandLabel } from '../utils/assessmentScale';
import styles from './AssessmentPage.module.css';

// 수치 계산 방식(범위, 단계 기준)은 화면에 공개하지 않는다 — "왜 80이 최대냐" 같은 질문만 만든다.

const ARC_LEN = Math.PI * 120; // 반원 게이지 길이

// 요인별 점수는 화면에 숫자로 보여주지 않는다 — 숫자는 백엔드 상수라 정밀해 보이지만
// 유저에겐 합산 산수 검증거리만 된다. 방향(유리/불리)은 색으로, 무게는 순서로 말한다
// (백엔드가 무게 순으로 내려준다).
// 근거 없는 요인(중립 + "근거 없음")은 판정 카드 대신 "알려주면 정확해져요" 안내로 바꾼다.
const NO_EVIDENCE = '근거 없음';

// 요인별로 유저에게 물을 문구 — 부족 정보 안내에 쓴다.
const FACTOR_ASK: Record<string, string> = {
  상대신호: '이별 후 상대의 반응(연락, 차단, SNS)',
  대체자: '상대에게 새로 만나는 사람이 있는지',
  유저대처: '이별 후 내가 어떻게 했는지',
  통보온도: '헤어지자던 순간 상대의 태도',
  상대패턴: '상대의 과거 연애 패턴(재회 이력, 성향)',
  관계자산: '얼마나 만났고 얼마나 깊었는지(공개 연애, 미래 얘기)',
  접점: '다시 만날 접점이 있는지(약속, 같은 소속, 공통 지인)',
};

/* 로딩/진단 중 점 애니메이션 — 일러스트(달) 대신 쓰는 유일한 장식 */
function Dots() {
  return (
    <span className={styles.stateDots} aria-hidden="true">
      <span className={styles.stateDot} />
      <span className={styles.stateDot} />
      <span className={styles.stateDot} />
    </span>
  );
}

/* 섹션 머리 — 선 장식 없이 제목 크기와 여백으로만 구획한다(그룹 카드가 경계를 대신 잡아준다).
   countClass: 신호 섹션의 개수를 그 섹션 점수 색과 맞출 때 쓴다 */
function SectionHead({
  title,
  count,
  countClass,
}: {
  title: string;
  count?: number;
  countClass?: string;
}) {
  return (
    <div className={styles.sectionHead}>
      <span className={styles.sectionTitle}>{title}</span>
      {count != null && (
        <span className={`${styles.sectionCount} ${countClass ?? ''}`}>{count}</span>
      )}
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
  // 비슷한 사례. 진단이 뽑아둔 분류로 찾으므로 LLM 호출도 차감도 없다 — 실패해도 조용히 접는다.
  const [similar, setSimilar] = useState<SimilarCases | null>(null);
  // 본문이 길어 기본은 접어두고, 펼친 것만 전문을 보여준다.
  const [openCase, setOpenCase] = useState<number | null>(null);
  const [confirming, setConfirming] = useState(false); // 헤어짐 확인 API 진행 중
  const [retracting, setRetracting] = useState(false); // 제안 번복 API 진행 중
  // 진단 생성이 실패했을 때 뜨는 재시도 패널. 스스로 사라지는 에러 배너와 달리, 유저가 누를
  // 때까지 남는다 — "다시 진단을 눌러 주세요"라고 시키는 대신 누를 것을 화면에 둔다.
  const [retryable, setRetryable] = useState(false);
  // 연속 실패 쿨다운의 남은 초(서버가 내려준 값에서 시작). 0이면 즉시 재시도 가능.
  const [cooldown, setCooldown] = useState(0);
  const aliveRef = useRef(true);

  // 에러 배너(쿼터 소진, 재진단 거부 등)가 화면에 계속 남지 않게 잠시 뒤 스스로 사라진다.
  useEffect(() => {
    if (!error) return;
    const timer = window.setTimeout(() => aliveRef.current && setError(''), 6000);
    return () => clearTimeout(timer);
  }, [error]);

  // 쿨다운 카운트다운. 매 초 setTimeout을 새로 거는 방식이라 interval이 어긋나 쌓이지 않고,
  // 0이 되면 재시도 버튼이 새로고침 없이 스스로 살아난다.
  useEffect(() => {
    if (cooldown <= 0) return;
    const timer = window.setTimeout(() => aliveRef.current && setCooldown(cooldown - 1), 1000);
    return () => clearTimeout(timer);
  }, [cooldown]);

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
          // 쿨다운으로 막힌 응답은 안내가 아니라 재시도 대상이다 — 패널이 사유와 남은 시간을
          // 함께 보여주므로 같은 말을 배너로 또 띄우지 않는다.
          if (res.retryAfterSeconds) {
            setNotice('');
            setRetryable(true);
            setCooldown(res.retryAfterSeconds);
          } else {
            // 이야기 부족은 재시도가 답이 아니다(대화가 답) — 재시도 패널을 띄우지 않는다.
            setNotice(res.reason);
            setRetryable(false);
            setCooldown(0);
          }
        } else {
          setNotice('');
          setRetryable(false);
          setCooldown(0);
          // 새 결과로 갈아끼우기 전, 화면에 있던 확률이 이번 결과의 비교 기준이 된다.
          setPrevProb(result?.probability ?? null);
          setResult(res);
          // 이번 진단이 분류를 새로 뽑았을 수 있으니 사례도 다시 찾는다.
          refreshSimilar();
        }
        refreshUsage(); // 후차감이라 성공 시점에 갱신
      }
    } catch (e) {
      // 소진(Q001)을 백엔드 문구("이용권을 채우거나...")로 그대로 띄우면 아래 상시 '충전하기'와
      // 구매 권유가 이중이 된다 — 배너는 상태만 알리고, 동선은 링크 하나를 가리킨다(채팅과 동일 패턴).
      if (aliveRef.current) {
        const code = extractErrorCode(e);
        // L001(생성 실패)은 사라지는 배너로 처리하지 않는다. 유저가 할 일이 '다시 시도'인데
        // 6초 뒤 배너가 사라지면 무엇을 눌러야 할지가 화면에서 없어진다 — 재시도 패널로 넘긴다.
        if (code === 'L001') {
          setRetryable(true);
          setCooldown(0);
        } else {
          setError(
            code === 'Q001'
              ? '오늘 진단 횟수를 다 썼어요. 아래 충전하기로 이어갈 수 있어요.'
              : extractErrorMessage(e, '진단에 실패했어요. 잠시 후 다시 시도해 주세요.'),
          );
        }
      }
    } finally {
      if (aliveRef.current) setDiagnosing(false);
    }
  }

  // 사례 조회는 조회 한 번이라 진단과 달리 자유롭게 부른다. 실패는 삼킨다 —
  // 부속 정보라 못 불러왔다고 진단 화면에 에러를 띄울 일이 아니다.
  function refreshSimilar() {
    getSimilarCases(storyId)
      .then((res) => aliveRef.current && setSimilar(res))
      .catch(() => {});
  }

  useEffect(() => {
    aliveRef.current = true;
    refreshUsage();
    refreshSimilar();
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

  // 실패/빈 화면에서도 남은 횟수가 보여야 한다(실측: 진단 실패 후 몇 회 남았는지 알 길이 없었음).
  // 실패는 후차감이라 차감되지 않는데, 그걸 확인할 방법이 이 표시다.
  const remainingHint =
    remaining != null ? (
      <div className={styles.stateHint}>
        오늘 남은 진단 <span className={styles.hintCountNum}>{remaining}회</span>
        {paidRemaining > 0 && (
          <>
            {' '}+ 이용권 <span className={styles.hintCountNum}>{paidRemaining}회</span>
          </>
        )}
        <button className={styles.topupLink} onClick={() => navigate('/payment')}>
          <svg width="11" height="11" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M12 4.5v15M4.5 12h15" stroke="#B89DD1" strokeWidth="2.2" strokeLinecap="round" />
          </svg>
          충전하기
        </button>
      </div>
    ) : null;

  // 진단 생성 실패와 연속 실패 쿨다운의 공용 조각. 결과가 있을 때(배너 자리)와 없을 때(빈 화면)
  // 양쪽에서 같은 모양으로 쓰인다 — 실패 화면이 두 벌로 갈라지지 않게.
  const retryPanel = retryable ? (
    <div className={styles.retryPanel}>
      <div className={styles.retryTitle}>
        {cooldown > 0 ? '연이어 실패해서 잠시 쉬어가요' : '진단을 만들지 못했어요'}
      </div>
      <div className={styles.retryBody}>실패한 진단은 횟수가 차감되지 않았어요</div>
      {cooldown > 0 && (
        // 남은 시간은 숫자 하나로만. 초 단위로 줄어드는 게 보여야 "그냥 기다리라"는 말과 달라진다.
        <div className={styles.retryClock} aria-live="off">
          {Math.floor(cooldown / 60)}:{String(cooldown % 60).padStart(2, '0')}
        </div>
      )}
      <button className={styles.retryBtn} onClick={diagnose} disabled={cooldown > 0}>
        다시 시도
      </button>
    </div>
  ) : null;

  if (loading || diagnosing) {
    return (
      <PhoneFrame>
        <div className={styles.wrap}>
          <BackBar onBack={toChat} />
          <div className={styles.state}>
            {diagnosing ? (
              <>
                <div className={styles.stateTitle}>이야기를 읽고 있어요</div>
                <Dots />
                {/* 진단 LLM이 느릴 때 이탈해도 손해가 아니라는 안내 — 결과는 저장돼 재진입 시 보인다 */}
                <div className={styles.stateBody}>
                  지금까지의 대화에서 신호를 찾는 중이에요
                  <br />
                  시간이 좀 걸릴 수 있어요, 화면을 나가도 결과는 저장돼요
                </div>
              </>
            ) : (
              <Dots />
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
          <div className={styles.state}>
            <div className={styles.stateBody}>{error}</div>
          </div>
          {remainingHint}
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
            <div className={styles.stateTitle}>계정을 연결하면 진단이 열려요</div>
            <div className={styles.stateBody}>
              연결하면 지금까지의 대화가 그대로 이어지고,
              <br />
              대화 5회와 진단 1회도 선물로 드려요
            </div>
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
            {retryPanel ?? (
              notice ? (
                <div className={styles.stateBody}>{notice}</div>
              ) : (
                <>
                  <div className={styles.stateTitle}>아직 진단 기록이 없어요</div>
                  <div className={styles.stateBody}>
                    지금까지의 대화를 읽고 재회 가능성을 진단해요
                    <br />
                    대화를 충분히 나눌수록 정확해져요
                  </div>
                </>
              )
            )}
          </div>
          {remainingHint}
          {/* 재시도 패널이 떠 있으면 그 안의 버튼이 유일한 동선이다 — 같은 일을 하는 버튼을
              하단에 또 두면 쿨다운 중 비활성 버튼과 활성 버튼이 나란히 보인다 */}
          {!retryPanel && (
            <div className={styles.footer}>
              <button className={styles.btnPrimary} onClick={diagnose}>
                진단 받기
              </button>
            </div>
          )}
        </div>
      </PhoneFrame>
    );
  }

  // "계속 대화하면 진단도 따라 갱신된다"는 오해가 있어, 이 결과가 언제 것인지 명시한다.
  const metaDate = result.createdAt ? formatListTime(result.createdAt) : '방금';

  // INSUFFICIENT는 저장되지 않고 diagnose()에서 배너로 처리되므로 여기 도달하는 결과는
  // POSSIBLE(확률), DATING/REUNITED(잠금 — 게이지 대신 전용 화면)뿐이다.
  const dating = result.verdict === 'DATING';
  const reunited = result.verdict === 'REUNITED';
  const locked = dating || reunited;
  const prob = result.probability ?? 0;
  const fill = (Math.min(prob, GAUGE_MAX) / GAUGE_MAX) * ARC_LEN;
  const factors = result.factors ?? [];
  // 이별 사유(유형)는 요인이 아니라 기본 구간을 정하는 1층이지만, 유저 눈엔 "가능성을
  // 낮춘/올린 것" 중 가장 큰 항목이다 — 목록 맨 위에 합성 카드로 보여준다.
  // 사실 줄은 LLM이 쓴 유형 판정 근거(typeEvidence)를 그대로 싣고, 판독 줄만 유형별 고정
  // 문장(다른 카드와 같은 관찰문 결)으로 채운다 — 프론트가 지어낸 티가 나면 안 된다.
  const typeRaises = result.breakupType === '충동형' || result.breakupType === '상황형';
  // 유형은 판정이 아니라 대역이지만, 카드 칩은 대역 위치를 4단으로 번역해 보여준다
  // (신뢰붕괴형에 '불리'는 과소 표현이라는 실측 피드백 — 바닥 구간 유형은 '매우불리'로).
  const TYPE_CHIP: Record<string, '매우유리' | '유리' | '불리' | '매우불리'> = {
    충동형: '매우유리',
    상황형: '유리',
    외부요인형: '불리',
    권태식음형: '불리',
    소진형: '불리',
    결심완료형: '매우불리',
    환승형: '매우불리',
    신뢰붕괴형: '매우불리',
  };
  const TYPE_READING: Record<string, string> = {
    충동형: '감정이 격해진 순간의 이별이라 기본 확률 구간이 높은 축에 속함.',
    상황형: '마음보다 환경이 가른 이별이라 기본 확률 구간이 높은 축에 속함.',
    외부요인형: '마음 밖의 고착된 조건이 막는 이별이라 기본 확률 구간이 중간 아래에 속함.',
    권태식음형: '설렘과 애정이 잦아들어 끝난 이별이라 기본 확률 구간이 낮은 축에 속함.',
    소진형: '상대가 지쳐서 끝낸 이별이라 기본 확률 구간이 낮은 축에 속함.',
    결심완료형: '오래 고민하고 정리를 끝낸 통보라 기본 확률 구간이 낮은 축에 속함.',
    환승형: '마음이 이미 다른 사람에게 옮겨간 이별이라 기본 확률 구간이 낮은 축에 속함.',
    신뢰붕괴형: '상대의 신뢰가 무너진 이별이라 기본 확률 구간이 가장 낮은 축에 속함.',
  };
  const typeItem: FactorView | null = result.breakupType
    ? {
        name: `이별 사유(${result.breakupType})`,
        level: TYPE_CHIP[result.breakupType] ?? (typeRaises ? '유리' : '불리'),
        evidence: result.typeEvidence ?? '',
        rationale: TYPE_READING[result.breakupType] ?? null,
        stage: null,
      }
    : null;
  const unfavorable = [
    ...(typeItem && !typeRaises ? [typeItem] : []),
    ...factors.filter((f) => f.level === '불리' || f.level === '매우불리'),
  ];
  const favorable = [
    ...(typeItem && typeRaises ? [typeItem] : []),
    ...factors.filter((f) => f.level === '유리' || f.level === '매우유리'),
  ];
  // 판단 근거가 없던 요인 — 카드 대신 "알려주면 정확해져요"로 뒤집어 다음 대화를 유도한다.
  const missing = factors.filter((f) => f.level === '중립' && f.evidence === NO_EVIDENCE);

  return (
    <PhoneFrame>
      <div className={styles.wrap}>
        <BackBar onBack={toChat} onHelp={() => setShowHelp(true)} />
        {error && (
          <div className={styles.errorBanner}>
            <svg className={styles.noticeIcon} width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="12" cy="12" r="9" stroke="#D88B9F" strokeWidth="1.6" />
              <path d="M12 8v5M12 15.8h.01" stroke="#D88B9F" strokeWidth="1.8" strokeLinecap="round" />
            </svg>
            {error}
          </div>
        )}
        {notice && (
          <div className={styles.noticeBanner}>
            <svg className={styles.noticeIcon} width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="12" cy="12" r="9" stroke="#B89DD1" strokeWidth="1.6" />
              <path d="M12 11v5M12 7.6h.01" stroke="#B89DD1" strokeWidth="1.8" strokeLinecap="round" />
            </svg>
            {notice}
          </div>
        )}
        {retryPanel}
        <div className={styles.body}>
          <div className={styles.meta}>마지막 진단 {metaDate}</div>

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
                {/* 선을 가늘게(14→11) — 두꺼운 아크는 계기판 티가 난다. 수치는 숫자가 말하고 아크는 거든다 */}
                <svg width="280" height="150" viewBox="0 0 280 150">
                  <path d="M20,138 A120,120 0 0 1 260,138" fill="none" stroke="#2A2833" strokeWidth="11" strokeLinecap="round" />
                  <path
                    d="M20,138 A120,120 0 0 1 260,138"
                    fill="none"
                    stroke="#B89DD1"
                    strokeWidth="11"
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
                    : `지난 진단보다 ${prob > prevProb ? '+' : ''}${prob - prevProb}%`}
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

          {/* 확률 화면에도 총평을 싣는다 — 요인 조각들만으론 서사가 없어 숫자가 건조하게 남는다 */}
          {!locked && result.reason && (
            <div className={styles.reasonCard}>
              <div className={styles.reasonLabel}>총평</div>
              {result.reason}
            </div>
          )}

          {/* 요인 카드: 제목 / 사실 / 판독 이유(어두운 박스). 무게는 내려온 순서가 말한다.
              제안 확정(100%)일 땐 숨긴다 — 수락만 남은 상태에 판정 셈이 떠 있으면 어색하다
              (재회 성공 화면과 같은 원칙, 판정은 번복 대비로 저장만 유지) */}
          {!locked && prob < 100 && unfavorable.length > 0 && (
            <>
              <SectionHead title="가능성을 낮춘 요인" />
              <div className={styles.dedList}>
                {unfavorable.map((f) => (
                  <div className={styles.dedItem} key={f.name}>
                    <div className={styles.dedTop}>
                      <div className={styles.dedSignal}>{f.name}</div>
                      <span className={`${styles.weightLabel} ${f.level === '매우불리' ? styles.weightMinusStrong : styles.weightMinus}`}>{f.level}</span>
                    </div>
                    {f.evidence && f.evidence !== NO_EVIDENCE && (
                      <div className={styles.dedEvidence}>{f.evidence}</div>
                    )}
                    {f.rationale && <div className={styles.dedRationale}>{f.rationale}</div>}
                  </div>
                ))}
              </div>
            </>
          )}

          {!locked && prob < 100 && favorable.length > 0 && (
            <>
              <SectionHead title="가능성을 올린 요인" />
              <div className={styles.dedList}>
                {favorable.map((f) => (
                  <div className={styles.dedItem} key={f.name}>
                    <div className={styles.dedTop}>
                      <div className={styles.dedSignal}>{f.name}</div>
                      <span className={`${styles.weightLabel} ${f.level === '매우유리' ? styles.weightPlusStrong : styles.weightPlus}`}>{f.level}</span>
                    </div>
                    {f.evidence && f.evidence !== NO_EVIDENCE && (
                      <div className={styles.dedEvidence}>{f.evidence}</div>
                    )}
                    {f.rationale && <div className={styles.dedRationale}>{f.rationale}</div>}
                  </div>
                ))}
              </div>
            </>
          )}

          {/* 관찰 포인트 — "이게 확인되면 판이 바뀐다". 행동 지시가 아니라 판독의 연장이고,
              유저가 다음 진단을 돌릴 이유가 되는 자리다 */}
          {!locked && prob < 100 && (result.watchFor?.length ?? 0) > 0 && (
            <>
              <SectionHead title="지켜볼 신호" />
              <div className={styles.dedList}>
                {result.watchFor.map((w, i) => (
                  <div className={styles.dedItem} key={i}>
                    <div className={styles.dedSignal}>{w.point}</div>
                    <div className={styles.dedRationale}>{w.effect}</div>
                  </div>
                ))}
              </div>
            </>
          )}

          {/* 비슷한 사례 — 진단이 뽑은 분류로 찾은 참조 사례. 유사도 순 그대로 보여준다:
              성공담을 골라 끼우면 헛된 희망을 파는 것이라, 확률에서 지켜온 원칙과 어긋난다.
              그래서 "너도 이렇게 된다"가 아니라 "비슷한 상황이 이랬다"로 읽히게 문구를 잡는다 */}
          {similar && similar.cases.length > 0 && (
            <>
              <SectionHead title="비슷한 사례" />
              <div className={styles.caseNote}>
                실제 사례들을 참고해 글로 요약했고, 사례들의 결과가 내 경우를 예고하진 않습니다.
              </div>
              <div className={styles.dedList}>
                {similar.cases.map((c) => {
                  const open = openCase === c.id;
                  return (
                    <div className={styles.caseItem} key={c.id}>
                      <div className={styles.caseTop}>
                        <span
                          className={`${styles.caseOutcome} ${
                            c.outcome === '성공'
                              ? styles.outcomeSuccess
                              : c.outcome === '성공후재이별'
                                ? styles.outcomeMixed
                                : styles.outcomeFail
                          }`}
                        >
                          {c.outcome === '성공'
                            ? '재회 성공'
                            : c.outcome === '실패'
                              ? '재회 실패'
                              : c.outcome === '성공후재이별'
                                ? '재회 후 재이별'
                                : '진행 중'}
                        </span>
                      </div>
                      <div className={open ? styles.caseStory : styles.caseStoryClamped}>
                        {c.story}
                      </div>
                      <button
                        className={styles.caseMore}
                        onClick={() => setOpenCase(open ? null : c.id)}
                      >
                        {open ? '접기' : '더 보기'}
                      </button>
                    </div>
                  );
                })}
              </div>
            </>
          )}

          {/* 사례가 없을 때도 섹션 머리를 달고 이유를 갈라 말한다 — 머리 없이 문장만 있으면
              어느 섹션의 말인지 못 읽는다(실측). 진단 기록이 없는 첫 화면에선 굳이 띄우지 않는다 */}
          {(similar?.emptyReason === 'NO_PROFILE' || similar?.emptyReason === 'NO_MATCH') && (
            <>
              <SectionHead title="비슷한 사례" />
              <div className={styles.caseEmpty}>
                {similar?.emptyReason === 'NO_PROFILE'
                  ? '어쩌다 헤어졌는지 대화에서 더 들려주면 비슷한 사례를 찾아드려요.'
                  : '아직 닮은 사례를 찾지 못했어요.'}
              </div>
            </>
          )}

          {/* 근거가 없어 중립으로 남은 요인 — 채워달라는 요청으로 뒤집어 다음 대화를 유도한다.
              채팅에서 말하면 다음 진단에 반영된다. 맨 아래 배치 — 판독(요인, 사례)이 먼저,
              다음 진단을 위한 요청은 마지막이 자연스러운 독서 순서다 */
          }
          {!locked && prob < 100 && missing.length > 0 && (
            <>
              <SectionHead title="알려주면 더 정확해져요" />
              <div className={styles.missingCard}>
                {missing.map((f) => (
                  <div className={styles.missingItem} key={f.name}>
                    {FACTOR_ASK[f.name] ?? f.name}
                  </div>
                ))}
                <div className={styles.missingHint}>대화에서 알려주면 다음 진단에 반영돼요</div>
              </div>
            </>
          )}

        </div>

        {/* 잔여 줄은 body(스크롤) 밖에 둬서 스크롤과 무관하게 하단에 고정한다 — 채팅처럼 항상 보이게 */}
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

        <div className={styles.footer}>
          <button className={styles.btnGhost} onClick={() => navigate(`/stories/${storyId}/history`)}>
            기록
          </button>
          {/* 쿨다운 중엔 여기서도 막는다 — 위 패널은 비활성인데 아래로 우회되면 서버만 거절하고
              화면은 왜 안 되는지 말해주지 않는 상태가 된다 */}
          <button className={styles.btnPrimary} onClick={diagnose} disabled={cooldown > 0}>
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
                heading: '가능성을 움직인 신호',
                text: '확률을 낮춘 신호와 올린 신호를 근거와 함께 보여드려요. 각 신호를 얼마나 무겁게 봤는지는 결정적, 중요, 참고로 나뉘어요. 무거운 신호부터 위에 옵니다.',
              },
              {
                heading: '비슷한 사례',
                text: '이별 사유와 상황이 닮은 사례를 찾아 보여드려요. 이별을 부른 계기가 얼마나 겹치는지를 가장 크게 보고, 통보한 쪽과 지금 연락 상태, 이별 후 지난 기간을 함께 견줍니다. 결과가 좋은 사례를 골라 보여드리지는 않아요. 남의 결말이 내 결말을 예고하지 않기 때문입니다. 사례 보기는 횟수가 차감되지 않습니다.',
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
