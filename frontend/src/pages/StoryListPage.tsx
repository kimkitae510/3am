import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { listStories, createStory, type StoryResponse } from '../api/story';
import { logout } from '../api/auth';
import { extractErrorMessage } from '../api/client';
import { formatListTime } from '../utils/datetime';
import styles from './StoryListPage.module.css';

export function StoryListPage() {
  const navigate = useNavigate();
  const [stories, setStories] = useState<StoryResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [creating, setCreating] = useState(false);

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
                className={styles.item}
                onClick={() => navigate(`/stories/${s.id}`)}
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
      </div>
    </PhoneFrame>
  );
}
