import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { HelpModal } from '../components/HelpModal';
import {
  getMessages,
  getMessagesSince,
  listStories,
  sendMessage,
  type MessageResponse,
} from '../api/story';
import { extractErrorMessage } from '../api/client';
import { getUsage } from '../api/usage';
import { formatClock, formatDateDivider, isSameCalendarDate } from '../utils/datetime';
import styles from './ChatPage.module.css';

const MAX_LENGTH = 500; // 서버 검증(@Size)과 동일 값
const POLL_INTERVAL = 1500;
// 백엔드 LLM 타임아웃(30초) 안에는 답 또는 폴백 메시지가 반드시 저장되므로,
// 그보다 여유 있게 잡아 "..." 표시가 답이 올 때까지 끊기지 않게 한다.
const POLL_TIMEOUT = 45000;
const delay = (ms: number) => new Promise((r) => setTimeout(r, ms));

export function ChatPage() {
  const { storyId: storyIdParam } = useParams();
  const storyId = Number(storyIdParam);
  const navigate = useNavigate();
  const location = useLocation();

  const [title, setTitle] = useState('대화');
  const [messages, setMessages] = useState<MessageResponse[]>([]);
  const [cursor, setCursor] = useState<number | null>(null);
  const [hasOlder, setHasOlder] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [input, setInput] = useState('');
  const [waiting, setWaiting] = useState(false); // 어시스턴트 답 대기(타이핑)
  const [chatRemaining, setChatRemaining] = useState<number | null>(null); // 오늘 남은 대화 횟수
  const [chatPaidRemaining, setChatPaidRemaining] = useState(0); // 결제 이용권 잔여(무료 소진 후 차감)
  const [showHelp, setShowHelp] = useState(false);

  function refreshUsage() {
    getUsage()
      .then((u) => {
        if (!aliveRef.current) return;
        setChatRemaining(u.chatRemaining);
        setChatPaidRemaining(u.chatPaidRemaining);
      })
      .catch(() => {}); // 표시용 정보라 실패는 조용히 무시
  }

  const aliveRef = useRef(true);
  const bottomRef = useRef<HTMLDivElement>(null);

  // 진단 화면의 "대화로 물어보기"로 넘어온 경우 질문을 입력창에 미리 채워준다.
  useEffect(() => {
    const prefill = (location.state as { prefill?: string } | null)?.prefill;
    if (prefill) setInput(prefill);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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
        refreshUsage();
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

  // 새 메시지, 타이핑 표시 시 맨 아래로.
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
          refreshUsage(); // 답이 저장된 턴만 차감되므로(후차감) 이 시점에 갱신
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
          <div className={styles.topRight}>
            <button
              className={styles.diagButton}
              onClick={() => navigate(`/stories/${storyId}/assessment`)}
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
                <path d="M4 19V9m5 10V5m5 14v-7m5 7V11" stroke="#ECEAF0" strokeWidth="1.8" strokeLinecap="round" />
              </svg>
              진단
            </button>
            <button className={styles.helpButton} onClick={() => setShowHelp(true)} aria-label="도움말">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
                <circle cx="12" cy="12" r="9" stroke="#9B98A3" strokeWidth="1.6" />
                <path d="M9.6 9.2a2.4 2.4 0 114.1 1.7c-.7.7-1.7 1.1-1.7 2.2M12 16.4h.01" stroke="#9B98A3" strokeWidth="1.7" strokeLinecap="round" />
              </svg>
            </button>
          </div>
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
                  대화를 나눌수록 이야기와 기억이 쌓이고,
                  <br />
                  진단도 정확해져요.
                  <br />
                  첫 대화를 시작해 보세요.
                </div>
              )}
              {messages.map((m, i) => {
                const prev = messages[i - 1];
                const next = messages[i + 1];
                // 카톡식: 날짜가 바뀌는 첫 메시지 위에 "2026년 7월 3일 금요일" 구분선.
                const newDay = !prev || !isSameCalendarDate(prev.createdAt, m.createdAt);
                // 카톡식: 같은 사람이 같은 분(分)에 연달아 보낸 묶음은 마지막 말풍선에만 시각 표시.
                const showTime =
                  !next ||
                  next.role !== m.role ||
                  formatClock(next.createdAt) !== formatClock(m.createdAt) ||
                  !isSameCalendarDate(next.createdAt, m.createdAt);
                return (
                  <div key={m.id} style={{ display: 'contents' }}>
                    {newDay && <div className={styles.divider}>{formatDateDivider(m.createdAt)}</div>}
                    <div className={`${styles.msgRow} ${m.role === 'USER' ? styles.msgRowUser : ''}`}>
                      <div className={`${styles.bubble} ${m.role === 'USER' ? styles.user : styles.assistant}`}>
                        {m.content}
                      </div>
                      {showTime && <span className={styles.msgTime}>{formatClock(m.createdAt)}</span>}
                    </div>
                  </div>
                );
              })}
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

        {chatRemaining != null && (
          <div className={styles.usageHint}>
            오늘 남은 대화 {chatRemaining}회
            {chatPaidRemaining > 0 && ` + 이용권 ${chatPaidRemaining}회`}
          </div>
        )}
        {/* 한도(1000자, 서버 검증과 동일)에 가까워질 때만 카운터 노출 — 평소엔 조용히 */}
        {input.length >= MAX_LENGTH - 200 && (
          <div className={styles.lengthHint}>
            {input.length}/{MAX_LENGTH}자
          </div>
        )}
        <div className={styles.inputBar}>
          <textarea
            className={styles.input}
            placeholder={waiting ? '답변을 기다리는 중…' : '메시지 입력'}
            rows={1}
            value={input}
            maxLength={MAX_LENGTH}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={onKeyDown}
            disabled={waiting}
          />
          <button
            className={styles.send}
            onClick={handleSend}
            disabled={!input.trim() || waiting}
            aria-label={waiting ? '답변 생성 중' : '보내기'}
          >
            {waiting ? (
              // 생성 중 표시(정지 아님 — 서버가 fire-and-forget이라 중단 API는 아직 없다)
              <svg width="19" height="19" viewBox="0 0 24 24" fill="none">
                <rect x="7" y="7" width="10" height="10" rx="2" fill="#1B1720" />
              </svg>
            ) : (
              <svg width="19" height="19" viewBox="0 0 24 24" fill="none">
                <path d="M12 19V6M6 12l6-6 6 6" stroke="#1B1720" strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            )}
          </button>
        </div>

        {showHelp && (
          <HelpModal
            title="채팅 가이드"
            onClose={() => setShowHelp(false)}
            sections={[
              {
                heading: '기억',
                text: '이 방에서 나눈 대화로 이야기와 기억이 쌓이고, 진단이 정확해집니다. 기억은 방마다 따로 관리되므로 다른 사람 이야기는 새 방에서 시작해 주세요.',
              },
              {
                heading: '대화 횟수',
                text: '하루 10회, 한 번에 500자까지 보낼 수 있습니다.',
              },
              {
                heading: '진단',
                text: '오른쪽 위 진단 버튼에서 재회 가능성과 애착유형을 확인할 수 있습니다.',
              },
            ]}
          />
        )}
      </div>
    </PhoneFrame>
  );
}
