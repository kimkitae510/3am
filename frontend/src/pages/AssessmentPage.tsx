import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { runAssessment, type AssessmentResponse } from '../api/assessment';
import { listStories } from '../api/story';
import { extractErrorMessage } from '../api/client';
import { formatListTime } from '../utils/datetime';
import styles from './AssessmentPage.module.css';

const GAUGE_MAX = 70; // 확률 상한(정책). 게이지는 이 값을 만점으로 그린다.
const ARC_LEN = Math.PI * 120; // 반원 게이지 길이

function bandText(prob: number): string {
  const band = prob < 15 ? '아직은 낮아요' : prob < 40 ? '낮지도, 높지도 않아요' : '가능성이 보여요';
  return `${band} · 최대 ${GAUGE_MAX}%까지만 봐요`;
}

function BackBar({ onBack }: { onBack: () => void }) {
  return (
    <div className={styles.topbar}>
      <button className={styles.backButton} onClick={onBack} aria-label="뒤로">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
          <path d="M15 5l-7 7 7 7" stroke="#ECEAF0" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </button>
      <div className={styles.topTitle}>재회 진단</div>
    </div>
  );
}

export function AssessmentPage() {
  const { storyId: storyIdParam } = useParams();
  const storyId = Number(storyIdParam);
  const navigate = useNavigate();

  const [title, setTitle] = useState('');
  const [result, setResult] = useState<AssessmentResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const aliveRef = useRef(true);

  async function diagnose() {
    setLoading(true);
    setError('');
    try {
      const res = await runAssessment(storyId);
      if (aliveRef.current) setResult(res);
    } catch (e) {
      if (aliveRef.current) setError(extractErrorMessage(e, '진단에 실패했어요. 잠시 후 다시 시도해 주세요.'));
    } finally {
      if (aliveRef.current) setLoading(false);
    }
  }

  useEffect(() => {
    aliveRef.current = true;
    listStories()
      .then((ss) => aliveRef.current && setTitle(ss.find((s) => s.id === storyId)?.title ?? ''))
      .catch(() => {});
    diagnose();
    return () => {
      aliveRef.current = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storyId]);

  const toChat = () => navigate(`/stories/${storyId}`);

  if (loading) {
    return (
      <PhoneFrame>
        <div className={styles.wrap}>
          <BackBar onBack={toChat} />
          <div className={styles.state}>대화를 읽고 진단하는 중…</div>
        </div>
      </PhoneFrame>
    );
  }

  if (error || !result) {
    return (
      <PhoneFrame>
        <div className={styles.wrap}>
          <BackBar onBack={toChat} />
          <div className={styles.state}>{error || '결과를 불러오지 못했어요.'}</div>
          <div className={styles.footer}>
            <button className={styles.btnPrimary} onClick={diagnose}>
              다시 시도
            </button>
          </div>
        </div>
      </PhoneFrame>
    );
  }

  const metaDate = result.createdAt ? formatListTime(result.createdAt) : '방금';
  const meta = [title, metaDate].filter(Boolean).join(' · ');

  // 데이터 부족
  if (result.verdict === 'INSUFFICIENT') {
    return (
      <PhoneFrame>
        <div className={styles.wrap}>
          <BackBar onBack={toChat} />
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

  return (
    <PhoneFrame>
      <div className={styles.wrap}>
        <BackBar onBack={toChat} />
        <div className={styles.body}>
          <div className={styles.meta}>{meta}</div>

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

          <div className={styles.typeRow}>
            <div className={styles.typeCard}>
              <div className={styles.typeKey}>나</div>
              <div className={styles.typeName}>{result.myBreakupType ?? '—'}</div>
            </div>
            <div className={styles.typeCard}>
              <div className={styles.typeKey}>상대</div>
              <div className={styles.typeName}>{result.partnerType ?? '—'}</div>
            </div>
          </div>

          {result.deductions.length > 0 && (
            <>
              <div className={styles.dedTitle}>가능성을 낮춘 신호</div>
              <div className={styles.dedList}>
                {result.deductions.map((d, i) => (
                  <div className={styles.dedItem} key={i}>
                    <div className={styles.dedMain}>
                      <div className={styles.dedSignal}>{d.signal}</div>
                      {d.evidence && <div className={styles.dedEvidence}>{d.evidence}</div>}
                    </div>
                    <div className={styles.dedDelta}>−{d.delta}</div>
                  </div>
                ))}
              </div>
            </>
          )}

          <div className={styles.hint}>대화를 더 나눌수록 진단이 정교해져요.</div>
        </div>

        <div className={styles.footer}>
          <button className={styles.btnGhost} onClick={() => navigate(`/stories/${storyId}/history`)}>
            기록
          </button>
          <button className={styles.btnPrimary} onClick={diagnose}>
            다시 진단
          </button>
        </div>
      </div>
    </PhoneFrame>
  );
}
