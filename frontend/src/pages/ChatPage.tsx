import { useEffect, useRef, useState, type UIEvent } from 'react';
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
// 이 시간을 넘기면 폴링 간격을 성기게 늦춘다(포기가 아니다). 백엔드 LLM 타임아웃(50초) 안에
// 답 또는 폴백이 저장되는 게 정상이라, 이 뒤는 지연이 아니라 이상 상황 — 그래도 끝까지 기다린다.
const POLL_SLOWDOWN_AFTER = 75000;
const POLL_SLOW_INTERVAL = 5000;
// 재입장 시 마지막 유저 메시지가 이보다 어리면 아직 답을 기다리는 판으로 본다.
// 서버가 답이든 폴백이든 저장하고도 남는 시간 — 이보다 늙었으면 과거에 끝난(실패한) 방이다.
const RESUME_WAIT_WINDOW = 180000;
const delay = (ms: number) => new Promise((r) => setTimeout(r, ms));

// 장문 답변을 말풍선으로 쪼갠다 — 사람이 나눠 보내는 것처럼.
// 1차 경계는 '빈 줄'이다(모델이 묶은 의도 존중). 단 모델이 빈 줄 없이 문장 줄바꿈만으로
// 보내는 답이 잦아 한 덩어리 풍선이 되는 실측이 있어, 빈 줄이 아예 없으면 줄 단위로
// 폴백해 카톡처럼 나눈다. 저장은 한 덩어리라 재입장 시에도 똑같이 쪼개진다.
function splitParagraphs(text: string): string[] {
  const parts = text
    .split(/\n{2,}/)
    .map((s) => s.trim())
    .filter(Boolean);
  if (parts.length > 1) {
    return parts;
  }
  const lines = text
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean);
  return lines.length > 0 ? lines : [text];
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
  // 유저가 맨 아래 근처를 보고 있는지 — 위로 올려 읽는 중이면 새 답이 와도 화면을 끌어내리지 않는다.
  const atBottomRef = useRef(true);
  // 마지막으로 본 메시지 id. 이게 바뀌었을 때만 "새 메시지"로 취급한다(이전 대화 더 보기와 구분).
  const lastMsgIdRef = useRef<number | null>(null);
  // 읽는 중에 도착한 답의 미리보기 — 카톡처럼 하단 배너로 알리고, 누르면 내려간다.
  const [newPreview, setNewPreview] = useState<string | null>(null);

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
        // 답을 기다리다 나갔다 온 판 — 마지막이 내 메시지면 아직 답이 안 붙은 것이라
        // 대기 상태(입력 잠금 + 타이핑 표시)를 복원하고 이어서 기다린다. 오래된 판은 제외:
        // 과거에 실패로 끝난 방을 열 때마다 영영 잠긴 입력창이 되면 안 된다.
        const last = page.messages[page.messages.length - 1];
        if (
          last &&
          last.role === 'USER' &&
          Date.now() - new Date(last.createdAt).getTime() < RESUME_WAIT_WINDOW
        ) {
          setWaiting(true);
          pollForReply(last.id);
        }
      } catch (e) {
        if (aliveRef.current) setError(extractErrorMessage(e, '대화를 불러오지 못했어요.'));
      } finally {
        if (aliveRef.current) setLoading(false);
      }
    })();
    return () => {
      aliveRef.current = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storyId]);

  // 새 메시지, 타이핑 표시, 조각 공개 시 맨 아래로 — 단, 유저가 맨 아래를 보고 있을 때만.
  // 위로 올려 읽는 중이면 따라 내려가지 않고 하단 배너로만 알린다. 내가 보낸 메시지는 예외로
  // 항상 내려간다(내가 방금 쳤는데 안 내려가면 전송이 안 된 것처럼 보인다).
  useEffect(() => {
    const last = messages[messages.length - 1];
    const lastChanged = last != null && last.id !== lastMsgIdRef.current;
    if (lastChanged) lastMsgIdRef.current = last.id;
    if (atBottomRef.current || (lastChanged && last.role === 'USER')) {
      bottomRef.current?.scrollIntoView({ block: 'end' });
      setNewPreview(null);
      return;
    }
    if (lastChanged && last.role !== 'USER') {
      // 여러 문단이어도 배너엔 첫 줄만 — 미리보기는 한 줄이면 충분하다
      setNewPreview(last.content.split('\n')[0]);
    }
  }, [messages, waiting, reveal]);

  // 스크롤 위치 추적. 맨 아래로 돌아오면 배너는 볼 일이 끝난 것이라 치운다.
  function handleScroll(e: UIEvent<HTMLDivElement>) {
    const el = e.currentTarget;
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 60;
    atBottomRef.current = atBottom;
    if (atBottom) setNewPreview(null);
  }

  function jumpToLatest() {
    bottomRef.current?.scrollIntoView({ block: 'end' });
    setNewPreview(null);
  }

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

  // 진행 중인 폴링 루프의 세대 번호. 새 폴링이 시작되면 이전 루프는 다음 바퀴에서 멈춘다 —
  // StrictMode(개발)가 마운트 효과를 두 번 돌려 재입장 복원 폴링이 두 루프로 뜨고, aliveRef는
  // 공유 ref라 두 번째 마운트가 true로 되돌려 첫 루프도 살아남았다(같은 답이 두 벌 붙는 실측).
  const pollSeqRef = useRef(0);

  async function pollForReply(afterId: number) {
    // 시간 제한 없이 답이 붙을 때까지 기다린다 — 제한으로 먼저 포기하면 "..."가 사라진 뒤
    // 답이 유령처럼 나타나고, 그 사이 유저가 또 보내 대화가 꼬인다. 페이지를 떠나면 멈추고,
    // 재입장 복원이 이어받는다.
    const seq = ++pollSeqRef.current;
    const started = Date.now();
    for (;;) {
      await delay(Date.now() - started > POLL_SLOWDOWN_AFTER ? POLL_SLOW_INTERVAL : POLL_INTERVAL);
      if (!aliveRef.current || seq !== pollSeqRef.current) return;
      try {
        const fresh = await getMessagesSince(storyId, afterId);
        if (fresh.length > 0) {
          // 이미 화면에 있는 id는 거른다 — 어떤 경로로든 루프가 겹쳐도 중복 풍선은 안 생기게.
          setMessages((prev) => {
            const known = new Set(prev.map((m) => m.id));
            const add = fresh.filter((f) => !known.has(f.id));
            return add.length > 0 ? [...prev, ...add] : prev;
          });
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

        <div className={styles.messages} onScroll={handleScroll}>
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
                          {/* 한 풍선 안 여러 문장은 줄만 바꾸면 바로 밑에 붙어 답답하다(실측) —
                              문장마다 블록으로 나눠 사이를 띄운다 */}
                          {seg.split('\n').map((line, li) => (
                            <div className={styles.bubbleLine} key={li}>
                              {line}
                            </div>
                          ))}
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
          {newPreview != null && (
            <button className={styles.newMsgBar} onClick={jumpToLatest}>
              <span className={styles.newMsgLabel}>새 메시지</span>
              <span className={styles.newMsgText}>{newPreview}</span>
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M6 10l6 6 6-6" stroke="#B89DD1" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            </button>
          )}
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
        {/* 회수가 올라가는 걸 '넘긴 뒤'에 알리면 유저는 놀란다(도움말을 다 읽지도 않는다).
            경계 60자 전부터 미리 예고하고, 넘긴 뒤엔 현재 회수를 보여준다. 글자수는 안내가
            떠 있는 동안 늘 같이 — 숫자가 올라가는 걸 봐야 길이와 회수의 관계가 이해된다.
            색은 안 쓰고(촌스러웠다) 흐린 문장 + 숫자만 밝게, 회수가 바뀌면 key가 갈려 페이드 재생 */}
        {(() => {
          const units = Math.ceil(input.length / UNIT_LENGTH);
          const nextBoundary = units * UNIT_LENGTH;
          const nearNext = input.length > 0 && nextBoundary - input.length <= 60;
          if (units <= 1 && !nearNext) return null;
          return (
            <div className={styles.lengthHint}>
              {nearNext ? (
                <span className={styles.lengthCost} key={`n${units}`}>
                  <span className={styles.lengthCostNum}>{nextBoundary}자</span>부터 대화 {units + 1}회
                </span>
              ) : (
                <span className={styles.lengthCost} key={units}>
                  대화 <span className={styles.lengthCostNum}>{units}회</span> 소진
                </span>
              )}
              <span className={styles.lengthCount}>
                {input.length}/{MAX_LENGTH}
              </span>
            </div>
          );
        })()}
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
                text: '오른쪽 위 진단 버튼에서 재회 가능성을 확인할 수 있습니다.',
              },
            ]}
          />
        )}
      </div>
    </PhoneFrame>
  );
}
