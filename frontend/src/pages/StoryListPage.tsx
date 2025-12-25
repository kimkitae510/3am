import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { HelpModal } from '../components/HelpModal';
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

  const [showHelp, setShowHelp] = useState(false);
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
          <div className={styles.title}>대화</div>
          <div className={styles.headerActions}>
            <button className={styles.iconButton} onClick={() => navigate('/payment')} aria-label="이용권">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
                <path
                  d="M4 9a1 1 0 011-1h14a1 1 0 011 1v1.5a1.5 1.5 0 000 3V15a1 1 0 01-1 1H5a1 1 0 01-1-1v-1.5a1.5 1.5 0 000-3V9z"
                  stroke="#9B98A3"
                  strokeWidth="1.6"
                  strokeLinejoin="round"
                />
                <path d="M14.5 8.5v7" stroke="#9B98A3" strokeWidth="1.5" strokeLinecap="round" strokeDasharray="2 2.4" />
              </svg>
            </button>
            <button className={styles.iconButton} onClick={() => setShowHelp(true)} aria-label="도움말">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
                <circle cx="12" cy="12" r="9" stroke="#9B98A3" strokeWidth="1.6" />
                <path d="M9.6 9.2a2.4 2.4 0 114.1 1.7c-.7.7-1.7 1.1-1.7 2.2M12 16.4h.01" stroke="#9B98A3" strokeWidth="1.7" strokeLinecap="round" />
              </svg>
            </button>
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
            아직 이야기가 없어요.
            <br />
            아래 버튼으로 그 사람 얘기를 시작해요.
          </div>
        ) : (
          <div className={styles.list}>
            {stories.map((s) => (
              <div className={styles.swipeRow} key={s.id}>
                {/* 평상시엔 완전히 감춘다 — 모서리로 빨간 배경이 비치는 잔상 방지 */}
                <button
                  className={styles.swipeDelete}
                  style={{ opacity: offsetFor(s.id) < 0 ? 1 : 0 }}
                  onClick={() => askDelete(s)}
                >
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
                      <span className={styles.itemRight}>
                        <span className={styles.itemTime}>{formatListTime(s.updatedAt)}</span>
                        {s.unread && <span className={styles.unreadBadge}>1</span>}
                      </span>
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

        {showHelp && (
          <HelpModal
            title="3AM 가이드"
            onClose={() => setShowHelp(false)}
            sections={[
              {
                heading: '방 하나 = 한 사람 이야기',
                text: '방 하나에 한 사람과의 이별 이야기를 담습니다. 진단과 기억은 방마다 따로 쌓입니다.',
              },
              {
                heading: '목록 다루기',
                text: '방을 왼쪽으로 밀거나 길게 누르면 삭제할 수 있습니다. 보라색 배지는 읽지 않은 새 답변입니다.',
              },
              {
                heading: '이용권',
                text: '무료 횟수를 다 쓰면 이용권으로 이어서 쓸 수 있습니다. 위 카드 모양 버튼에서 구매할 수 있습니다.',
              },
            ]}
          />
        )}

        {deleteTarget && (
          <div className={styles.overlay} onClick={() => !deleting && setDeleteTarget(null)}>
            <div className={styles.dialog} onClick={(e) => e.stopPropagation()}>
              <div className={styles.dialogTitle}>이 대화를 삭제할까요?</div>
              <div className={styles.dialogText}>
                이 방에서 나눈 대화, 재회 진단 기록, 쌓아온 기억이
                <br />
                모두 지워져요. 새 방을 만들어도 이 이야기는
                <br />
                기억하지 못해요. 되돌릴 수 없어요.
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
