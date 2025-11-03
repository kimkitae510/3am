import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import {
  getMessages,
  getMessagesSince,
  listStories,
  sendMessage,
  type MessageResponse,
} from '../api/story';
import { extractErrorMessage } from '../api/client';
import { formatClock } from '../utils/datetime';
import styles from './ChatPage.module.css';

const POLL_INTERVAL = 1500;
const POLL_TIMEOUT = 20000;
const delay = (ms: number) => new Promise((r) => setTimeout(r, ms));

export function ChatPage() {
  const { storyId: storyIdParam } = useParams();
  const storyId = Number(storyIdParam);
  const navigate = useNavigate();

  const [title, setTitle] = useState('대화');
  const [messages, setMessages] = useState<MessageResponse[]>([]);
  const [cursor, setCursor] = useState<number | null>(null);
  const [hasOlder, setHasOlder] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [input, setInput] = useState('');
  const [waiting, setWaiting] = useState(false); // 어시스턴트 답 대기(타이핑)

  const aliveRef = useRef(true);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    aliveRef.current = true;
    (async () => {
      try {
        const [page, stories] = await Promise.all([getMessages(storyId), listStories()]);
        if (!aliveRef.current) return;
        setMessages(page.messages);
        setCursor(page.nextCursor);
        setHasOlder(page.hasNext);
        setTitle(stories.find((s) => s.id === storyId)?.title ?? '대화');
      } catch (e) {
        if (aliveRef.current) setError(extractErrorMessage(e, '대화를 불러오지 못했어요.'));
      } finally {
        if (aliveRef.current) setLoading(false);
      }
    })();
    return () => {
      aliveRef.current = false;
    };
  }, [storyId]);

  // 새 메시지·타이핑 표시 시 맨 아래로.
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ block: 'end' });
  }, [messages.length, waiting]);

  async function loadOlder() {
    if (cursor == null) return;
    try {
      const page = await getMessages(storyId, cursor);
      setMessages((prev) => [...page.messages, ...prev]);
      setCursor(page.nextCursor);
      setHasOlder(page.hasNext);
    } catch (e) {
      setError(extractErrorMessage(e, '이전 대화를 불러오지 못했어요.'));
    }
  }

  async function pollForReply(afterId: number) {
    const deadline = Date.now() + POLL_TIMEOUT;
    while (Date.now() < deadline) {
      await delay(POLL_INTERVAL);
      if (!aliveRef.current) return;
      try {
        const fresh = await getMessagesSince(storyId, afterId);
        if (fresh.length > 0) {
          setMessages((prev) => [...prev, ...fresh]);
          setWaiting(false);
          return;
        }
      } catch {
        // 일시적 오류는 무시하고 계속 폴링
      }
    }
    if (aliveRef.current) {
      setWaiting(false);
      setError('답이 늦어지고 있어요. 잠시 후 다시 시도해 주세요.');
    }
  }

  async function handleSend() {
    const content = input.trim();
    if (!content || waiting) return;
    setInput('');
    setError('');
    try {
      const userMsg = await sendMessage(storyId, content);
      setMessages((prev) => [...prev, userMsg]);
      setWaiting(true);
      pollForReply(userMsg.id);
    } catch (e) {
      setInput(content); // 실패 시 입력 복구
      setError(extractErrorMessage(e, '메시지를 보내지 못했어요.'));
    }
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  }

  return (
    <PhoneFrame>
      <div className={styles.wrap}>
        <div className={styles.topbar}>
          <div className={styles.topLeft}>
            <button className={styles.backButton} onClick={() => navigate('/stories')} aria-label="뒤로">
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
                <path d="M15 5l-7 7 7 7" stroke="#ECEAF0" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            </button>
            <div className={styles.storyTitle}>{title}</div>
          </div>
          <button
            className={styles.diagButton}
            onClick={() => navigate(`/stories/${storyId}/assessment`)}
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
              <path d="M4 19V9m5 10V5m5 14v-7m5 7V11" stroke="#ECEAF0" strokeWidth="1.8" strokeLinecap="round" />
            </svg>
            재회 진단
          </button>
        </div>

        <div className={styles.messages}>
          {loading ? (
            <div className={styles.state}>불러오는 중…</div>
          ) : (
            <>
              {hasOlder && (
                <button className={styles.loadMore} onClick={loadOlder}>
                  이전 대화 더 보기
                </button>
              )}
              {messages.length === 0 && !waiting && (
                <div className={styles.state}>
                  첫 마음을 적어보세요.
                  <br />
                  잠 안 오는 밤, 여기 있을게요.
                </div>
              )}
              {messages.map((m, i) => (
                <div key={m.id} style={{ display: 'contents' }}>
                  {i === 0 && <div className={styles.divider}>{formatClock(m.createdAt)}</div>}
                  <div className={`${styles.bubble} ${m.role === 'USER' ? styles.user : styles.assistant}`}>
                    {m.content}
                  </div>
                </div>
              ))}
              {waiting && (
                <div className={styles.typing}>
                  <span className={styles.dot} />
                  <span className={styles.dot} />
                  <span className={styles.dot} />
                </div>
              )}
              {error && <div className={styles.state}>{error}</div>}
            </>
          )}
          <div ref={bottomRef} />
        </div>

        <div className={styles.inputBar}>
          <textarea
            className={styles.input}
            placeholder="메시지 입력"
            rows={1}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={onKeyDown}
          />
          <button
            className={styles.send}
            onClick={handleSend}
            disabled={!input.trim() || waiting}
            aria-label="보내기"
          >
            <svg width="19" height="19" viewBox="0 0 24 24" fill="none">
              <path d="M12 19V6M6 12l6-6 6 6" stroke="#1B1720" strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>
        </div>
      </div>
    </PhoneFrame>
  );
}
