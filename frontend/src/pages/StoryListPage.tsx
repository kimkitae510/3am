import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { listStories, createStory, deleteStory, type StoryResponse } from '../api/story';
import { logout } from '../api/auth';
import { extractErrorMessage } from '../api/client';
import { formatListTime } from '../utils/datetime';
import styles from './StoryListPage.module.css';

const LONG_PRESS_MS = 450;
const SWIPE_WIDTH = 84; // 드러나는 삭제 버튼 폭
const clamp = (v: number, min: number, max: number) => Math.min(Math.max(v, min), max);

export function StoryListPage() {
  const navigate = useNavigate();
  const [stories, setStories] = useState<StoryResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [creating, setCreating] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<StoryResponse | null>(null);
  const [deleting, setDeleting] = useState(false);

  const [openId, setOpenId] = useState<number | null>(null); // 스와이프로 열린 행
  const [dragId, setDragId] = useState<number | null>(null); // 지금 드래그 중인 행
  const [dragX, setDragX] = useState(0);

  const startX = useRef(0);
  const startY = useRef(0);
  const moved = useRef(false);
  const longPressed = useRef(false);
  const longTimer = useRef<number | null>(null);

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

  function clearLong() {
    if (longTimer.current) {
      clearTimeout(longTimer.current);
      longTimer.current = null;
    }
  }

  function askDelete(story: StoryResponse) {
    setOpenId(null);
    setDeleteTarget(story);
  }

  function onPointerDown(e: React.PointerEvent, s: StoryResponse) {
    e.currentTarget.setPointerCapture?.(e.pointerId);
    startX.current = e.clientX;
    startY.current = e.clientY;
    moved.current = false;
    longPressed.current = false;
    if (openId != null && openId !== s.id) setOpenId(null); // 다른 열린 행 닫기
    longTimer.current = window.setTimeout(() => {
      longPressed.current = true;
      navigator.vibrate?.(20); // iOS 사파리는 미지원 — 되는 기기에서만
      askDelete(s);
    }, LONG_PRESS_MS);
  }

  function onPointerMove(e: React.PointerEvent, s: StoryResponse) {
    const dx = e.clientX - startX.current;
    const dy = e.clientY - startY.current;
    if (!moved.current && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) {
      moved.current = true;
      clearLong();
    }
    if (moved.current && Math.abs(dx) > Math.abs(dy)) {
      const base = openId === s.id ? -SWIPE_WIDTH : 0;
      setDragId(s.id);
      setDragX(clamp(base + dx, -SWIPE_WIDTH, 0));
    }
  }

  function onPointerUp(e: React.PointerEvent, s: StoryResponse) {
    e.currentTarget.releasePointerCapture?.(e.pointerId);
    clearLong();
    if (dragId === s.id) {
      setOpenId(dragX < -SWIPE_WIDTH / 2 ? s.id : null);
      setDragId(null);
      setDragX(0);
      return;
    }
    if (moved.current || longPressed.current) return;
    if (openId === s.id) {
      setOpenId(null); // 열린 행을 탭하면 닫기
      return;
    }
    navigate(`/stories/${s.id}`);
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

  function offsetFor(id: number): number {
    if (dragId === id) return dragX;
    return openId === id ? -SWIPE_WIDTH : 0;
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

        <div className={styles.concept}>
          방 하나에 한 사람과의 이별 이야기를 담아요. 진단과 기억은 방마다 따로 쌓여요.
        </div>

        {loading ? (
          <div className={styles.state}>불러오는 중…</div>
        ) : error ? (
          <div className={styles.state}>{error}</div>
        ) : stories.length === 0 ? (
          <div className={styles.state}>
            아직 이야기가 없어요.
            <br />
            아래 버튼으로 그 사람 얘기를 시작해요.
          </div>
        ) : (
          <div className={styles.list}>
            {stories.map((s) => (
              <div className={styles.swipeRow} key={s.id}>
                <button className={styles.swipeDelete} onClick={() => askDelete(s)}>
                  삭제
                </button>
                <button
                  className={styles.item}
                  style={{
                    transform: `translateX(${offsetFor(s.id)}px)`,
                    transition: dragId === s.id ? 'none' : undefined,
                  }}
                  onClick={(e) => e.preventDefault()}
                  onPointerDown={(e) => onPointerDown(e, s)}
                  onPointerMove={(e) => onPointerMove(e, s)}
                  onPointerUp={(e) => onPointerUp(e, s)}
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
              </div>
            ))}
          </div>
        )}

        <button className={styles.newButton} onClick={handleNew} disabled={creating}>
          <svg width="19" height="19" viewBox="0 0 24 24" fill="none">
            <path d="M12 5v14M5 12h14" stroke="#1B1720" strokeWidth="2.2" strokeLinecap="round" />
          </svg>
          {creating ? '시작하는 중…' : '새 이야기'}
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
