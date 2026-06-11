import { useEffect, useState } from 'react';
import { getReviewStatus, submitReviewComment, submitReviewScore } from '../api/review';
import { extractErrorMessage } from '../api/client';
import styles from './ReviewBlock.module.css';

// 인덱스 + 1 = 점수. 묻는 것은 "재회가 될지"가 아니라 상황을 제대로 짚었는지다 —
// 예측은 평가 시점에 검증할 수 없고, 유저가 그 자리에서 아는 건 자기 상황뿐이다.
const SCALE = ['전혀 달라요', '별로 안 맞아요', '반반이에요', '꽤 맞아요', '소름 돋게 맞아요'];

// 점수대별 후기 질문과 예시. 예시를 placeholder로 주는 건 "좋아요" 한 줄이 아니라
// 구체적인 문장을 부르기 위해서다 — 전시 가치는 구체성에서 나온다.
function commentAsk(score: number): { label: string; placeholder: string } {
  if (score >= 4) {
    return { label: '어떤 부분이 맞았나요?', placeholder: '예: 상대 심리를 짚어준 게 놀라웠어요' };
  }
  if (score <= 2) {
    return { label: '어떤 부분이 달랐나요?', placeholder: '예: 상대 상황이 실제와 달랐어요' };
  }
  return { label: '어떤 부분이 맞고, 어떤 부분이 달랐나요?', placeholder: '예: 유형은 맞는데 연락 상황이 달랐어요' };
}

const COMMENT_MAX = 300;

// 진단 결과 하단의 평가 블록. 점수는 원탭 업서트(다시 누르면 바뀜), 후기도 언제든 수정.
// 보상(+2)은 후기까지 완성한 첫 번째 한 번뿐이라, 문구도 지급 가능할 때만 띄운다.
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
  const [savedComment, setSavedComment] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);
  const [rewardAvailable, setRewardAvailable] = useState(false);
  const [bonusGranted, setBonusGranted] = useState(0); // 이번 방문에서 지급된 양(안내용)
  const [comment, setComment] = useState('');
  const [scoreSaving, setScoreSaving] = useState(false);
  const [commentSaving, setCommentSaving] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    let alive = true;
    setLoaded(false);
    setScore(null);
    setSavedComment(null);
    setEditing(false);
    setBonusGranted(0);
    setComment('');
    setError('');
    getReviewStatus(storyId)
      .then((s) => {
        if (!alive) return;
        setScore(s.reviewed ? s.score : null);
        setSavedComment(s.comment);
        setComment(s.comment ?? '');
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
    if (scoreSaving || value === score) return;
    const prev = score;
    setScore(value); // 낙관 반영 — 원탭이 서버 왕복을 기다리면 칩이 굼떠 보인다
    setScoreSaving(true);
    setError('');
    try {
      await submitReviewScore(storyId, value);
    } catch (e) {
      setScore(prev);
      setError(extractErrorMessage(e, '평가를 남기지 못했습니다. 잠시 후 다시 시도해 주세요.'));
    } finally {
      setScoreSaving(false);
    }
  }

  async function handleComment() {
    const text = comment.trim();
    if (!text || commentSaving) return;
    setCommentSaving(true);
    setError('');
    try {
      const res = await submitReviewComment(storyId, text);
      setSavedComment(text);
      setEditing(false);
      if (res.chatBonus > 0) {
        setBonusGranted(res.chatBonus);
        setRewardAvailable(false);
        onRewarded?.();
      }
    } catch (e) {
      setError(extractErrorMessage(e, '남기지 못했습니다. 잠시 후 다시 시도해 주세요.'));
    } finally {
      setCommentSaving(false);
    }
  }

  if (!loaded) return null;

  const ask = score != null ? commentAsk(score) : null;
  return (
    <>
      <div className={styles.head}>진단 평가</div>
      <div className={styles.card}>
        <div className={styles.title}>이번 진단, 내 상황을 얼마나 제대로 짚었나요?</div>
        {/* 보상은 후기 완성 시 유저당 1회 — 이미 받은 유저에게 띄우면 지급 없는 약속이 된다 */}
        {rewardAvailable && <div className={styles.sub}>후기까지 남기면 대화 2회를 드려요</div>}
        <div className={styles.scale}>
          {SCALE.map((label, i) => (
            <button
              type="button"
              className={`${styles.chip} ${score === i + 1 ? styles.chipSelected : ''}`}
              key={label}
              disabled={scoreSaving}
              onClick={() => handleScore(i + 1)}
            >
              {label}
            </button>
          ))}
        </div>

        {ask && savedComment != null && !editing ? (
          <div className={styles.commentForm}>
            <div className={styles.savedComment}>{savedComment}</div>
            <div className={styles.formRow}>
              <span className={styles.thanks}>
                남겨주셔서 감사합니다.
                {bonusGranted > 0 && (
                  <>
                    {' '}
                    <span className={styles.thanksBonus}>대화 {bonusGranted}회</span>를 충전해
                    드렸어요.
                  </>
                )}
              </span>
              <button className={styles.ghostBtn} onClick={() => setEditing(true)}>
                수정
              </button>
            </div>
          </div>
        ) : ask ? (
          <div className={styles.commentForm}>
            <div className={styles.commentLabel}>{ask.label}</div>
            <textarea
              className={styles.commentInput}
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              placeholder={ask.placeholder}
              rows={2}
              maxLength={COMMENT_MAX}
            />
            <div className={styles.formRow}>
              <span className={styles.notice}>
                {score != null && score >= 4 ? '후기는 익명으로 서비스 소개에 쓰일 수 있어요' : ''}
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
        ) : null}
        {error && <div className={styles.error}>{error}</div>}
      </div>
    </>
  );
}
