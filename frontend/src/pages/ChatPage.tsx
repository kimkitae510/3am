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
import { extractErrorCode, extractErrorMessage } from '../api/client';
import { getUsage } from '../api/usage';
import { formatClock, formatDateDivider, isSameCalendarDate } from '../utils/datetime';
import styles from './ChatPage.module.css';

const MAX_LENGTH = 2000; // 서버 검증(@Size)과 동일 값 — 긴 사연이 600자에서 끊겨 흐름이 깨졌다(실측)
const UNIT_LENGTH = 300; // 대화 1회로 치는 길이 — 초과분은 회수로 환산(서버 CHAT_UNIT_CHARS와 동일 값)
const POLL_INTERVAL = 1500;
// 백엔드 LLM 타임아웃(30초) 안에는 답 또는 폴백 메시지가 반드시 저장되므로,
// 그보다 여유 있게 잡아 "..." 표시가 답이 올 때까지 끊기지 않게 한다.
const POLL_TIMEOUT = 45000;
const delay = (ms: number) => new Promise((r) => setTimeout(r, ms));

// 문단 안 줄바꿈을 문장 경계로 추가 분리할지 판단하는 길이 기준.
// 양쪽 다 이보다 길면 "긴 문장 두 개가 뭉친 것"으로 보고 가른다(실측: '다행이네 +
// 마지막 인상이 될 거야' 뭉침). 한쪽이라도 짧으면 의도된 연속타("울어도 돼 / 근데
// 걔한테는 울지 마")로 보고 한 풍선에 남긴다.
const LONG_LINE = 20;

// 장문 답변을 말풍선으로 쪼갠다 — 사람이 나눠 보내는 것처럼.
// 빈 줄은 항상 경계. 문단 안 줄바꿈은 길이 기준 하이브리드로만 가른다:
// 전부 가르면 모든 답이 또박또박 기관총이 되고, 안 가르면 긴 문장 뭉침이 재발한다.
// 저장은 한 덩어리 그대로라 재입장 시에도 같은 규칙으로 똑같이 쪼개진다.
function splitParagraphs(text: string): string[] {
  const paragraphs = text
    .split(/\n{2,}/)
    .map((s) => s.trim())
    .filter(Boolean);
  const parts = paragraphs.flatMap((p) => {
    const lines = p
      .split('\n')
      .map((s) => s.trim())
      .filter(Boolean);
    if (lines.length < 2) return [p];
    // 짧은 줄이 하나라도 끼면 연속타 문단으로 보고 통째로 유지
    if (lines.some((line) => line.length < LONG_LINE)) return [p];
    return lines;
  });
  return parts.length > 0 ? parts : [text];
}

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
  // 방금 도착한 답만 조각을 순차 공개한다. null이면 전부 표시(과거 메시지 포함).
  const [reveal, setReveal] = useState<{ id: number; shown: number } | null>(null);
  const [chatRemaining, setChatRemaining] = useState<number | null>(null); // 오늘 남은 대화 횟수
  const [chatPaidRemaining, setChatPaidRemaining] = useState(0); // 결제 이용권 잔여(무료 소진 후 차감)
  const [quotaOver, setQuotaOver] = useState(false); // 무료+이용권 모두 소진(Q001) → 구매 유도
  const [isGuest, setIsGuest] = useState(false); // 게스트면 충전 대신 '계정 연결' 동선
  const [guestBlocked, setGuestBlocked] = useState(false); // 게스트 대화 소진(U010) → 계정 연결 유도
  const [showHelp, setShowHelp] = useState(false);

  function refreshUsage() {
    getUsage()
      .then((u) => {
        if (!aliveRef.current) return;
        setChatRemaining(u.chatRemaining);
        setChatPaidRemaining(u.chatPaidRemaining);
        setIsGuest(u.guest);
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

  // 새 메시지, 타이핑 표시, 조각 공개 시 맨 아래로.
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ block: 'end' });
  }, [messages.length, waiting, reveal]);

  // 다음 조각 길이에 비례한 간격으로 하나씩 공개 — 실제로 치는 듯한 리듬.
  // 늦게 발화한 타이머가 다른 방/다른 답의 공개 상태를 덮지 않게 자기 id일 때만 갱신.
  function scheduleReveal(id: number, next: number, segs: string[]) {
    const ms = Math.min(600 + segs[next - 1].length * 25, 2200);
    window.setTimeout(() => {
      if (!aliveRef.current) return;
      if (next < segs.length) {
        setReveal((prev) => (prev?.id === id ? { id, shown: next } : prev));
        scheduleReveal(id, next + 1, segs);
      } else {
        setReveal((prev) => (prev?.id === id ? null : prev)); // 전부 공개 — 이후엔 일반 렌더와 동일
      }
    }, ms);
  }

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
          const lastReply = [...fresh].reverse().find((f) => f.role !== 'USER');
          if (lastReply) {
            const segs = splitParagraphs(lastReply.content);
            if (segs.length > 1) {
              setReveal({ id: lastReply.id, shown: 1 });
              scheduleReveal(lastReply.id, 2, segs);
            }
          }
          return;
        }
      } catch {
        // 일시적 오류는 무시하고 계속 폴링
      }
    }
    if (aliveRef.current) {
      setWaiting(false);
      setError('답이 좀 늦네, 미안 잠깐 뒤에 다시 보내줄래?');
    }
  }

  async function handleSend() {
    const content = input.trim();
    if (!content || waiting) return;
    setInput('');
    setError('');
    setQuotaOver(false);
    setGuestBlocked(false);
    try {
      const userMsg = await sendMessage(storyId, content);
      setMessages((prev) => [...prev, userMsg]);
      setWaiting(true);
      pollForReply(userMsg.id);
    } catch (e) {
      setInput(content); // 실패 시 입력 복구
      const code = extractErrorCode(e);
      // 게스트 소진(U010)은 충전이 아니라 계정 연결로 푼다 — 배너를 다르게 띄운다.
      if (code === 'U010') {
        setGuestBlocked(true);
      } else if (code === 'L001') {
        // 생성 실패의 백엔드 문구는 기계 티가 난다 — 입력은 복구돼 있으니 다시 보내라고만
        setError('미안, 지금 답을 정리하기가 어렵네 조금 있다가 다시 보내줄 수 있어?');
      } else {
        setError(extractErrorMessage(e, '메시지가 안 보내졌어, 미안 다시 한 번 보내줄래?'));
        setQuotaOver(code === 'Q001');
      }
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
            {/* 아이콘 단독은 뜻이 안 읽혀 기각됐던 이력 — 글자는 유지하고 아이콘을 곁들인다.
                반원 게이지 축소판은 뜻이 안 살았고(실측), 펄스 라인이 '진단'과 바로 이어진다 */}
            <button
              className={styles.diagButton}
              onClick={() => navigate(`/stories/${storyId}/assessment`)}
            >
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M3.5 12h4l2.5-6.5 4 13 2.5-6.5h4" stroke="#B89DD1" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
              진단
            </button>
            {/* 이용권 진입점은 입력창 위 충전하기가 담당 — 헤더는 진단 하나만 */}
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
                  <div className={styles.stateTitle}>첫 대화를 시작해 보세요</div>
                  <div className={styles.stateBody}>
                    대화를 나눌수록 이야기와 기억이 쌓이고
                    <br />
                    진단도 정확해져요
                  </div>
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
                // 어시스턴트 답은 문단 단위 말풍선으로. 유저 입력은 쓴 그대로 한 덩어리.
                const segs = m.role === 'USER' ? [m.content] : splitParagraphs(m.content);
                const shown = reveal?.id === m.id ? Math.min(reveal.shown, segs.length) : segs.length;
                return (
                  <div key={m.id} style={{ display: 'contents' }}>
                    {newDay && <div className={styles.divider}>{formatDateDivider(m.createdAt)}</div>}
                    {segs.slice(0, shown).map((seg, si) => (
                      <div className={`${styles.msgRow} ${m.role === 'USER' ? styles.msgRowUser : ''}`} key={si}>
                        <div className={`${styles.bubble} ${m.role === 'USER' ? styles.user : styles.assistant}`}>
                          {seg}
                        </div>
                        {/* 시각은 묶음의 마지막 조각에만, 그것도 전부 공개된 뒤에 */}
                        {showTime && shown === segs.length && si === shown - 1 && (
                          <span className={styles.msgTime}>{formatClock(m.createdAt)}</span>
                        )}
                      </div>
                    ))}
                  </div>
                );
              })}
              {(waiting || reveal != null) && (
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
            {isGuest ? (
              <>
                둘러보기 남은 대화 <span className={styles.usageCount}>{chatRemaining}회</span>
                {/* 게스트는 충전이 아니라 계정 연결로 이어간다 */}
                <button className={styles.usageTopup} onClick={() => navigate('/guest-link')}>
                  계정 연결
                </button>
              </>
            ) : (
              <>
                {/* 무료/이용권은 각각 보여주되 숫자만 밝게 — 나열 자체가 아니라
                    숫자가 안 읽히는 게 문제였다(합산 시도는 기각, 실측) */}
                오늘 남은 대화 <span className={styles.usageCount}>{chatRemaining}회</span>
                {chatPaidRemaining > 0 && (
                  <>
                    {' '}+ 이용권 <span className={styles.usageCount}>{chatPaidRemaining}회</span>
                  </>
                )}
                {/* 남은 횟수를 보는 그 자리에서 바로 살 수 있게 — 소진 배너가 뜨기 전의 진입점 */}
                <button className={styles.usageTopup} onClick={() => navigate('/payment')}>
                  <svg width="11" height="11" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                    <path d="M12 4.5v15M4.5 12h15" stroke="#B89DD1" strokeWidth="2.2" strokeLinecap="round" />
                  </svg>
                  충전하기
                </button>
              </>
            )}
          </div>
        )}
        {/* 게스트 대화 소진(U010) — 충전이 아니라 계정 연결로 이어간다 */}
        {(guestBlocked || (isGuest && chatRemaining === 0)) && (
          <div className={styles.quotaBanner}>
            <svg className={styles.quotaIcon} width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="12" cy="12" r="9" stroke="#B89DD1" strokeWidth="1.6" />
              <path d="M12 11v5M12 7.6h.01" stroke="#B89DD1" strokeWidth="1.8" strokeLinecap="round" />
            </svg>
            <div className={styles.quotaText}>
              둘러보기로 나눌 수 있는 대화를 다 썼어요. 계정을 연결하면 지금까지의 대화를 그대로
              이어갈 수 있어요. 연결하면 대화 5회와 진단 1회도 선물로 드려요.
            </div>
          </div>
        )}
        {/* 소진 상태(잔여 0 또는 Q001 거절) — 안내만. 구매 버튼은 위 충전하기가 담당(중복 제거) */}
        {!isGuest && (quotaOver || (chatRemaining === 0 && chatPaidRemaining === 0)) && (
          <div className={styles.quotaBanner}>
            <svg className={styles.quotaIcon} width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="12" cy="12" r="9" stroke="#D88B9F" strokeWidth="1.6" />
              <path d="M12 8v5M12 15.8h.01" stroke="#D88B9F" strokeWidth="1.8" strokeLinecap="round" />
            </svg>
            <div className={styles.quotaText}>
              오늘 무료 대화를 다 썼어요. 위의 충전하기로 이어서 대화할 수 있어요.
            </div>
          </div>
        )}
        {/* 회수가 올라가는 순간부터 노출 — 모르고 여러 회 쓰는 일이 없게. 평소엔 조용히.
            채운 색 대신 잔여 줄과 같은 문법(흐린 문장 + 숫자만 밝게)으로. 회수가 바뀔 때마다
            key가 갈려 페이드가 다시 재생돼서, 색을 안 써도 변화가 눈에 들어온다 */}
        {(input.length > UNIT_LENGTH || input.length >= MAX_LENGTH * 0.9) && (
          <div className={styles.lengthHint}>
            {input.length > UNIT_LENGTH && (
              <span
                className={styles.lengthCost}
                key={Math.ceil(input.length / UNIT_LENGTH)}
              >
                대화 <span className={styles.lengthCostNum}>{Math.ceil(input.length / UNIT_LENGTH)}회</span> 소진
              </span>
            )}
            {input.length >= MAX_LENGTH * 0.9 && (
              <span className={styles.lengthCount}>
                {input.length}/{MAX_LENGTH}
              </span>
            )}
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
                text: '무료 대화는 하루 5회씩 제공되고, 처음 가입하면 선물로 대화 5회와 진단 1회 이용권을 드립니다(기한 없음). 한 번에 2000자까지 보낼 수 있고, 300자를 넘으면 300자마다 대화 1회로 계산됩니다(예: 700자는 3회). 회수가 올라가면 입력창 위에 미리 표시됩니다. 무료 횟수를 다 쓰면 이용권으로 이어서 대화할 수 있으며, 이용권은 위 카드 모양 버튼에서 구매합니다.',
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
