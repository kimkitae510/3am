import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { getSharedAssessment, type SharedAssessmentResponse } from '../api/share';
import { GAUGE_MAX, bandLabel } from '../utils/assessmentScale';
import { FACTOR_LABEL, JUMP_CARD, STAGE_LEVEL, TYPE_CHIP, TYPE_READING } from '../utils/assessmentView';
import styles from './SharedResultPage.module.css';

const ARC_LEN = Math.PI * 120;

interface Row {
  name: string;
  level: string;
  reading: string | null;
}

// 공유 링크로 열리는 읽기 전용 결과. 보는 사람은 비회원일 수 있고 이 사연의 당사자가 아니다 —
// 근거 문장은 서버가 아예 내려주지 않으므로 신호는 이름과 등급만, 판독문은 고정 문장만 싣는다.
// 분석 화면과 같은 게이지/카드 문법을 쓰되, 조작(재분석, 답 남기기)은 전부 뺀다.
export function SharedResultPage() {
  const { token } = useParams();
  const navigate = useNavigate();
  const [result, setResult] = useState<SharedAssessmentResponse | null>(null);
  const [gone, setGone] = useState(false); // 죽은 링크(삭제된 방, 오타)

  useEffect(() => {
    let alive = true;
    getSharedAssessment(token ?? '')
      .then((r) => alive && setResult(r))
      .catch(() => alive && setGone(true));
    return () => {
      alive = false;
    };
  }, [token]);

  if (gone) {
    return (
      <PhoneFrame>
        <div className={styles.wrap}>
          <div className={styles.state}>
            <div className={styles.stateTitle}>열 수 없는 링크입니다</div>
            <div className={styles.stateBody}>
              공유가 취소되었거나 이야기가 삭제되었습니다.
            </div>
          </div>
          <div className={styles.footer}>
            <button className={styles.btnPrimary} onClick={() => navigate('/')}>
              새벽 세시 시작하기
            </button>
          </div>
        </div>
      </PhoneFrame>
    );
  }

  if (!result) {
    return (
      <PhoneFrame>
        <div className={styles.wrap}>
          <div className={styles.state}>불러오는 중…</div>
        </div>
      </PhoneFrame>
    );
  }

  const prob = result.probability ?? 0;
  const fill = (Math.min(prob, GAUGE_MAX) / GAUGE_MAX) * ARC_LEN;

  // 분석 화면과 같은 2층 구성 — 유형(이별 사유)과 점프(이별 후 상황)가 목록 맨 위에 온다.
  const jumpCard = result.jumpRule ? JUMP_CARD[result.jumpRule] : undefined;
  const heads: Row[] = [];
  if (result.breakupType) {
    heads.push({
      name: '이별 사유',
      level: TYPE_CHIP[result.breakupType] ?? '불리',
      reading: TYPE_READING[result.breakupType] ?? null,
    });
  }
  if (jumpCard) {
    heads.push({ name: '이별 후 상황', level: jumpCard.level, reading: jumpCard.reading });
  }
  const shown: Row[] = result.factors.map((f) => ({
    name: FACTOR_LABEL[f.name] ?? f.name,
    level:
      f.stage && (f.level === '불리' || f.level === '매우불리')
        ? STAGE_LEVEL[f.stage] ?? f.level
        : f.level,
    reading: null,
  }));
  const raises = (r: Row) => r.level === '유리' || r.level === '매우유리';
  const unfavorable = [...heads.filter((r) => !raises(r)), ...shown.filter((r) => r.level === '불리' || r.level === '매우불리')];
  const favorable = [...heads.filter(raises), ...shown.filter((r) => r.level === '유리' || r.level === '매우유리')];

  const section = (title: string, rows: Row[], plus: boolean) =>
    rows.length > 0 && (
      <>
        <div className={styles.sectionTitle}>{title}</div>
        <div className={styles.list}>
          {rows.map((r) => (
            <div className={styles.item} key={r.name}>
              <div className={styles.itemTop}>
                <span className={styles.itemName}>{r.name}</span>
                <span className={`${styles.chip} ${plus ? styles.chipPlus : styles.chipMinus}`}>
                  {r.level}
                </span>
              </div>
              {r.reading && <div className={styles.itemReading}>{r.reading}</div>}
            </div>
          ))}
        </div>
      </>
    );

  return (
    <PhoneFrame>
      <div className={styles.wrap}>
        <div className={styles.body}>
          <div className={styles.brand}>새벽 세시</div>
          <div className={styles.subTitle}>대화를 읽고 정리한 분석 결과</div>

          <div className={styles.gaugeWrap}>
            <svg width="280" height="150" viewBox="0 0 280 150">
              <path d="M20,138 A120,120 0 0 1 260,138" fill="none" stroke="#2a2a2e" strokeWidth="11" strokeLinecap="round" />
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
          <div className={styles.gaugeSub}>{bandLabel(prob)}</div>

          {result.reason && (
            <div className={styles.reasonCard}>
              <div className={styles.reasonLabel}>총평</div>
              {result.reason}
            </div>
          )}

          {prob < 100 && (
            <>
              {section('가능성을 낮춘 신호', unfavorable, false)}
              {section('가능성을 올린 신호', favorable, true)}
            </>
          )}

          <div className={styles.note}>
            당사자가 나눈 대화만 근거로 계산한 결과입니다. 자세한 근거는 당사자 화면에만 보입니다.
          </div>
        </div>

        <div className={styles.footer}>
          <button className={styles.btnPrimary} onClick={() => navigate('/')}>
            나도 분석 리포트 받기
          </button>
          <div className={styles.foot}>가입 없이 바로 이야기를 시작할 수 있어요</div>
        </div>
      </div>
    </PhoneFrame>
  );
}
