import { useEffect, useState } from 'react';
import { getReviewStatus, submitReviewComment, submitReviewScore } from '../api/review';
import { extractErrorCode, extractErrorMessage } from '../api/client';
import styles from './ReviewBlock.module.css';

// 인덱스 + 1 = 점수. 묻는 것은 "재회가 될지"가 아니라 상황을 제대로 짚었는지다 —
// 예측은 평가 시점에 검증할 수 없고, 유저가 그 자리에서 아는 건 자기 상황뿐이다.
const SCALE = ['전혀 달라요', '별로 안 맞아요', '반반이에요', '꽤 맞아요', '소름 돋게 맞아요'];

const COMMENT_MAX = 300;

// 진단 결과 하단의 평가 블록. 점수는 원탭 즉시 저장(보상 지급)하고 텍스트는 이어서
// 따로 남긴다 — 쓰다 나가도 점수는 남는다. 3점(반반)은 텍스트를 묻지 않는다.
export function ReviewBlock({
  storyId,
  resultKey,
  onRewarded,
}: {
  storyId: number;
  resultKey: string; // 진단 시각 — 새 진단이 오면 평가 상태를 다시 읽는다
  onRewarded?: () => void;
}) {
  const [loaded, setLoaded] = useState(false);
  const [score, setScore] = useState<number | null>(null);
  const [fresh, setFresh] = useState(false); // 이번 방문에서 제출했는지 — 재방문은 접힌 줄만
  const [rewardAvailable, setRewardAvailable] = useState(false); // 보상은 유저당 1회
  const [bonus, setBonus] = useState(0);
  const [saving, setSaving] = useState(false);
  const [comment, setComment] = useState('');
  const [commentSaved, setCommentSaved] = useState(false);
  const [commentSaving, setCommentSaving] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    let alive = true;
    setLoaded(false);
    setScore(null);
    setFresh(false);
    setBonus(0);
    setComment('');
    setCommentSaved(false);
    setError('');
    getReviewStatus(storyId)
      .then((s) => {
        if (!alive) return;
        setScore(s.reviewed ? s.score : null);
        setRewardAvailable(s.rewardAvailable);
        setLoaded(true);
      })
      // 상태 조회 실패는 조용히 블록을 접는다 — 부속 기능이 결과 화면을 막을 일이 아니다
      .catch(() => alive && setLoaded(false));
    return () => {
      alive = false;
    };
  }, [storyId, resultKey]);

  async function handleScore(value: number) {
    if (saving || score != null) return;
    setSaving(true);
    setError('');
    try {
      const res = await submitReviewScore(storyId, value);
      setScore(value);
      setFresh(true);
      setBonus(res.chatBonus);
      onRewarded?.();
    } catch (e) {
      // 이미 평가된 진단(다른 탭 등) — 다시 누르게 두면 계속 같은 거절만 받는다
      if (extractErrorCode(e) === 'R002') {
        setScore(value);
      } else {
        setError(extractErrorMessage(e, '평가를 남기지 못했습니다. 잠시 후 다시 시도해 주세요.'));
      }
    } finally {
      setSaving(false);
    }
  }

  async function handleComment() {
    const text = comment.trim();
    if (!text || commentSaving) return;
    setCommentSaving(true);
    setError('');
    try {
      await submitReviewComment(storyId, text);
      setCommentSaved(true);
    } catch (e) {
      setError(extractErrorMessage(e, '남기지 못했습니다. 잠시 후 다시 시도해 주세요.'));
    } finally {
      setCommentSaving(false);
    }
  }

  if (!loaded) return null;

  // 이전 방문에서 이미 평가한 진단 — 한 줄로만 접어둔다.
  if (score != null && !fresh) {
    return <div className={styles.doneLine}>이 진단에는 평가를 남겼습니다 ({SCALE[score - 1]})</div>;
  }

  if (score == null) {
    return (
      <div className={styles.card}>
        <div className={styles.title}>이번 진단, 내 상황을 얼마나 제대로 짚었나요?</div>
        {/* 보상은 유저당 1회 — 이미 받은 유저에게 띄우면 지급 없는 약속이 된다 */}
        {rewardAvailable && <div className={styles.sub}>평가만 해도 대화 2회를 드려요</div>}
        <div className={styles.scale}>
          {SCALE.map((label, i) => (
            <button
              type="button"
              className={styles.chip}
              key={label}
              disabled={saving}
              onClick={() => handleScore(i + 1)}
            >
              {label}
            </button>
          ))}
        </div>
        {error && <div className={styles.error}>{error}</div>}
      </div>
    );
  }

  // 제출 직후 — 보상 안내와, 점수에 따라 갈리는 텍스트 요청(3점은 묻지 않는다).
  const askText = score >= 4 ? '어떤 부분이 맞았나요?' : score <= 2 ? '어떤 부분이 달랐나요?' : null;
  return (
    <div className={styles.card}>
      <div className={styles.thanks}>
        {bonus > 0 ? (
          <>
            평가 고마워요. <span className={styles.thanksBonus}>대화 {bonus}회</span>를 충전해
            드렸어요.
          </>
        ) : (
          '평가 고마워요.'
        )}
      </div>
      {askText && !commentSaved && (
        <div className={styles.commentForm}>
          <div className={styles.commentLabel}>{askText} (선택)</div>
          <textarea
            className={styles.commentInput}
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            placeholder="한두 줄이면 충분해요"
            rows={2}
            maxLength={COMMENT_MAX}
          />
          <div className={styles.formRow}>
            <span className={styles.notice}>
              {score >= 4
                ? '남긴 글은 익명으로 서비스 소개에 쓰일 수 있어요'
                : '남긴 글은 진단을 고치는 데만 쓰여요'}
            </span>
            <button
              className={styles.submit}
              disabled={!comment.trim() || commentSaving}
              onClick={handleComment}
            >
              {commentSaving ? '남기는 중' : '남기기'}
            </button>
          </div>
        </div>
      )}
      {commentSaved && <div className={styles.sub}>남겨주신 이야기 잘 받았어요.</div>}
      {error && <div className={styles.error}>{error}</div>}
    </div>
  );
}
