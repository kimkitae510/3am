import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { HelpModal, CONTACT_OPENCHAT_URL } from './HelpModal';
import { BusinessInfo } from './BusinessInfo';
import { listStories, deleteStory, type StoryResponse } from '../api/story';
import { getUsage, type UsageStatusResponse } from '../api/usage';
import { logout } from '../api/auth';
import { extractErrorMessage } from '../api/client';
import { formatListTime } from '../utils/datetime';
import styles from './StoryDrawer.module.css';

const LONG_PRESS_MS = 450;
const SWIPE_WIDTH = 84; // 드러나는 삭제 버튼 폭
const clamp = (v: number, min: number, max: number) => Math.min(Math.max(v, min), max);

// 목록은 화면이 아니라 서랍이다 — 사연이 보통 하나라 화면 하나를 잡을 무게가 아니고,
// 앱을 열면 목록을 거치지 않고 대화로 바로 들어가야 한다.
export function StoryDrawer({ currentStoryId, onClose }: { currentStoryId?: number; onClose: () => void }) {
  const navigate = useNavigate();
  const [stories, setStories] = useState<StoryResponse[]>([]);
  const [usage, setUsage] = useState<UsageStatusResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
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
        if (alive) setError(extractErrorMessage(e, '대화 목록을 불러오지 못했습니다.'));
      } finally {
        if (alive) setLoading(false);
      }
    })();
    // 잔여 현황은 부가 정보 — 실패해도 목록은 그대로 보여준다
    getUsage().then((u) => alive && setUsage(u)).catch(() => {});
    return () => {
      alive = false;
    };
  }, []);

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
    if (s.id === currentStoryId) {
      onClose(); // 보고 있던 방을 다시 고른 것 — 이동 없이 서랍만 닫는다
      return;
    }
    navigate(`/stories/${s.id}`);
    onClose();
  }

  async function confirmDelete() {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await deleteStory(deleteTarget.id);
      setStories((prev) => prev.filter((x) => x.id !== deleteTarget.id));
      // 보고 있던 방을 지웠으면 남을 화면이 없다 — 목록 진입점으로 되돌려 다음 방을 잡게 한다
      const wasCurrent = deleteTarget.id === currentStoryId;
      setDeleteTarget(null);
      if (wasCurrent) navigate('/stories', { replace: true });
    } catch (e) {
      setError(extractErrorMessage(e, '삭제하지 못했습니다.'));
    } finally {
      setDeleting(false);
    }
  }

  // 보고 있는 방을 맨 위로 — 어디에 있는지를 배지가 아니라 자리로 알린다
  const ordered = stories
    .slice()
    .sort((a, b) => Number(b.id === currentStoryId) - Number(a.id === currentStoryId));

  function offsetFor(id: number): number {
    if (dragId === id) return dragX;
    return openId === id ? -SWIPE_WIDTH : 0;
  }

  return (
    <div className={styles.scrim} onClick={onClose}>
      <div className={styles.panel} onClick={(e) => e.stopPropagation()}>
        <div className={styles.header}>
          <div className={styles.title}>서랍</div>
          <div className={styles.headerActions}>
            {/* 게스트의 계정 연결은 서랍이 아니라 채팅 화면 입력창 위가 맡는다 — 서랍은 열어야
                보이는 자리라 정작 연결이 필요한 사람에게 안 보인다 */}
            {usage && !usage.guest && (
              <button className={styles.payButton} onClick={() => navigate('/payment')}>
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <rect x="3.5" y="6" width="17" height="12.5" rx="2.5" stroke="#B89DD1" strokeWidth="1.7" />
                  <path d="M3.5 10.2h17" stroke="#B89DD1" strokeWidth="1.7" />
                </svg>
                이용권
              </button>
            )}
            <button className={styles.iconButton} onClick={() => setShowHelp(true)} aria-label="도움말">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
                <circle cx="12" cy="12" r="9" stroke="#98989f" strokeWidth="1.6" />
                <path
                  d="M9.6 9.2a2.4 2.4 0 114.1 1.7c-.7.7-1.7 1.1-1.7 2.2M12 16.4h.01"
                  stroke="#98989f"
                  strokeWidth="1.7"
                  strokeLinecap="round"
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
          <div className={styles.state}>아직 시작한 이야기가 없습니다.</div>
        ) : (
          <div className={styles.list}>
            {ordered.map((s) => (
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
                  className={`${styles.item} ${s.id === currentStoryId ? styles.itemCurrent : ''}`}
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
                    {(s.lastMessage || s.unread) && (
                      <div className={styles.itemBottom}>
                        <span className={styles.itemSub}>{s.lastMessage}</span>
                        {s.unread && <span className={styles.unreadBadge}>NEW</span>}
                      </div>
                    )}
                  </div>
                </button>
              </div>
            ))}
          </div>
        )}

        {/* 랜딩을 없애면 재방문 회원이 로그인 입구를 못 찾는다 — 다른 기기에서 온 사람에게는
            자기 대화가 통째로 사라진 것처럼 보인다. 그래서 서랍 하단에 상시로 둔다 */}
        <div className={styles.footer}>
          <button className={styles.footLink} onClick={handleLogout}>
            {usage?.guest ? '로그인' : '로그아웃'}
          </button>
          <span className={styles.footDot}>|</span>
          <a className={styles.footLink} href={CONTACT_OPENCHAT_URL} target="_blank" rel="noreferrer">
            1:1 문의
          </a>
        </div>

        {/* 로그인한 유저는 초기화면을 다시 볼 일이 없다 — 서랍이 이 앱의 푸터다 */}
        <div className={styles.bizWrap}>
          <BusinessInfo />
        </div>

        {showHelp && (
          <HelpModal
            title="이용 가이드"
            onClose={() => setShowHelp(false)}
            sections={[
              {
                heading: '사람마다 방을 따로 만듭니다',
                text: '한 방에는 한 사람과의 이별 이야기만 담아 주세요. 대화로 쌓은 기억과 진단 결과가 방마다 따로 관리되기 때문에, 여러 사람 이야기를 한 방에서 하면 진단이 섞여 부정확해집니다.',
              },
              {
                heading: '목록 다루기',
                text: '방을 왼쪽으로 밀거나 길게 누르면 삭제할 수 있습니다. 보라색 배지는 읽지 않은 새 답변입니다.',
              },
              {
                heading: '이용권',
                text: '대화와 진단은 이용권에서 1회씩 차감됩니다. 대화는 길이와 무관하게 한 번 주고받을 때마다 1회입니다. 남은 횟수는 기한 없이 유지되며, 위 이용권 버튼에서 잔여 확인과 충전을 할 수 있습니다.',
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
                기억하지 못합니다. 되돌릴 수 없습니다.
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
    </div>
  );
}
