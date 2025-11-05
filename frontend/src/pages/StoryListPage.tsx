import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { listStories, createStory, deleteStory, type StoryResponse } from '../api/story';
import { logout } from '../api/auth';
import { extractErrorMessage } from '../api/client';
import { formatListTime } from '../utils/datetime';
import styles from './StoryListPage.module.css';

const LONG_PRESS_MS = 450;

export function StoryListPage() {
  const navigate = useNavigate();
  const [stories, setStories] = useState<StoryResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [creating, setCreating] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<StoryResponse | null>(null);
  const [deleting, setDeleting] = useState(false);

  const pressTimer = useRef<number | null>(null);
  const longPressed = useRef(false);

  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const data = await listStories();
        if (alive) setStories(data);
      } catch (e) {
        if (alive) setError(extractErrorMessage(e, '대화 목록을 불러오지 못했어요.'));
      } finally {
        if (alive) setLoading(false);
      }
    })();
    return () => {
      alive = false;
    };
  }, []);

  async function handleNew() {
    if (creating) return;
    setCreating(true);
    try {
      const story = await createStory();
      navigate(`/stories/${story.id}`);
    } catch (e) {
      setError(extractErrorMessage(e, '새 대화를 시작하지 못했어요.'));
      setCreating(false);
    }
  }

  async function handleLogout() {
    await logout();
    navigate('/login');
  }

  // 길게 누르면 짧은 진동(발견성) + 삭제 확인. 짧게 누르면 대화로 진입.
  function startPress(story: StoryResponse) {
    longPressed.current = false;
    pressTimer.current = window.setTimeout(() => {
      longPressed.current = true;
      navigator.vibrate?.(20); // iOS Safari는 미지원 — 있으면만 울린다
      setDeleteTarget(story);
    }, LONG_PRESS_MS);
  }

  function cancelPress() {
    if (pressTimer.current) {
      clearTimeout(pressTimer.current);
      pressTimer.current = null;
    }
  }

  function handleItemClick(story: StoryResponse) {
    if (longPressed.current) {
      longPressed.current = false;
      return; // 길게 누른 경우 진입 막기
    }
    navigate(`/stories/${story.id}`);
  }

  async function confirmDelete() {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await deleteStory(deleteTarget.id);
      setStories((prev) => prev.filter((x) => x.id !== deleteTarget.id));
      setDeleteTarget(null);
    } catch (e) {
      setError(extractErrorMessage(e, '삭제하지 못했어요.'));
    } finally {
      setDeleting(false);
    }
  }

  return (
    <PhoneFrame>
      <div className={styles.wrap}>
        <div className={styles.header}>
          <div>
            <div className={styles.title}>대화</div>
            <div className={styles.count}>{loading ? ' ' : `${stories.length}개`}</div>
          </div>
          <div className={styles.headerActions}>
            <button className={styles.iconButton} onClick={handleLogout} aria-label="로그아웃">
              <svg width="21" height="21" viewBox="0 0 24 24" fill="none">
                <path
                  d="M15 4h3a2 2 0 012 2v12a2 2 0 01-2 2h-3M10 17l5-5-5-5M15 12H3"
                  stroke="#9B98A3"
                  strokeWidth="1.7"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </svg>
            </button>
          </div>
        </div>

        {loading ? (
          <div className={styles.state}>불러오는 중…</div>
        ) : error ? (
          <div className={styles.state}>{error}</div>
        ) : stories.length === 0 ? (
          <div className={styles.state}>
            아직 대화가 없어요.
            <br />
            아래 버튼으로 첫 대화를 시작해요.
          </div>
        ) : (
          <div className={styles.list}>
            {stories.map((s) => (
              <button
                key={s.id}
                className={`${styles.item} ${deleteTarget?.id === s.id ? styles.itemActive : ''}`}
                onClick={() => handleItemClick(s)}
                onPointerDown={() => startPress(s)}
                onPointerUp={cancelPress}
                onPointerLeave={cancelPress}
                onContextMenu={(e) => e.preventDefault()}
              >
                <div className={styles.avatar}>{s.title?.trim()?.[0] ?? '새'}</div>
                <div className={styles.itemBody}>
                  <div className={styles.itemTop}>
                    <span className={styles.itemName}>{s.title || '제목 없음'}</span>
                    <span className={styles.itemTime}>{formatListTime(s.updatedAt)}</span>
                  </div>
                </div>
              </button>
            ))}
          </div>
        )}

        <button className={styles.newButton} onClick={handleNew} disabled={creating}>
          <svg width="19" height="19" viewBox="0 0 24 24" fill="none">
            <path d="M12 5v14M5 12h14" stroke="#1B1720" strokeWidth="2.2" strokeLinecap="round" />
          </svg>
          {creating ? '시작하는 중…' : '새 대화'}
        </button>

        {deleteTarget && (
          <div className={styles.overlay} onClick={() => !deleting && setDeleteTarget(null)}>
            <div className={styles.dialog} onClick={(e) => e.stopPropagation()}>
              <div className={styles.dialogTitle}>이 대화를 삭제할까요?</div>
              <div className={styles.dialogText}>
                {deleteTarget.title || '이 대화'}과 나눈 대화랑 진단 기록이
                <br />
                모두 지워져요. 되돌릴 수 없어요.
              </div>
              <div className={styles.dialogButtons}>
                <button className={styles.cancelBtn} onClick={() => setDeleteTarget(null)} disabled={deleting}>
                  취소
                </button>
                <button className={styles.deleteBtn} onClick={confirmDelete} disabled={deleting}>
                  {deleting ? '삭제 중…' : '삭제'}
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </PhoneFrame>
  );
}
