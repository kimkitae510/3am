import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { HelpModal } from '../components/HelpModal';
import { CHARACTER_AVATAR, CHARACTER_NAME, GREETING, CharacterProfile } from '../components/CharacterProfile';
import { StoryDrawer } from '../components/StoryDrawer';
import { QuestionActions } from '../components/QuestionCard';
import { ChipCatalogSheet, ChipInputSheet, ChipRow } from '../components/ChipPanel';
import type { ChipView } from '../api/chip';
import {
  getMessages,
  getMessagesSince,
  retryLastReply,
  sendMessage,
  type MessageResponse,
} from '../api/story';
import { extractErrorCode, extractErrorMessage, extractRetryAfterSeconds } from '../api/client';
import { getAssessments } from '../api/assessment';
import { getUsage } from '../api/usage';
import { formatClock, formatDateDivider, isSameCalendarDate } from '../utils/datetime';
import { useGoPayment } from '../utils/paymentOrigin';
import styles from './ChatPage.module.css';

const MAX_LENGTH = 2000; // 서버 검증(@Size)과 동일 값 — 600자에서는 사연이 끊겼고(실측), 3000자는 한 턴에 담아 읽을 양을 넘었다
// 질문 하나에 달 수 있는 답의 길이에 씌우는 뚜껑. 실제 상한은 질문 수로 나눈 예산이고
// 이 값은 그 위에 얹는다 — 질문이 하나뿐이어도 답 한 칸에 사연만큼 쓰게 두지는 않는다.
const ANSWER_MAX = 1000;
// 이 길이를 넘으면 Enter는 전송이 아니라 줄바꿈이다. 사연을 쓰는 중에 문단을 나누려다
// 반쯤 쓴 글이 날아가는 쪽이, 다 쓰고 버튼을 못 찾는 쪽보다 훨씬 비싸다(전송은 되돌릴 수 없고
// 대화 1회가 차감된다). 그래서 애매하면 줄바꿈으로 기운다.
const ENTER_SENDS_UNDER = 150;
// 질문을 전부 건너뛴 턴에 대신 보내는 말. 안 보내면 서버 호출이 없어 상담자 답도 안 오고
// 대화가 질문에서 끊긴다. 빈 값으로는 못 보낸다 — 서버가 @NotBlank다.
// 건너뛰기를 누른 것 자체가 "지금은 못 답하겠다"는 말이라 그 뜻을 문장으로 옮긴다.
const SKIPPED_ALL_MESSAGE = '지금은 답하기 어려워요';

// 방의 첫 화면에 깔리는 상담자의 첫 마디. 저장하지 않고 화면에서만 만든다 — 기록이 아니라
// 인사라서 대화 횟수도, LLM 호출도, 프롬프트 맥락도 건드리지 않는다.
// "사용법 안내"를 쓰지 않는 이유: 설명을 읽히는 순간 상담이 아니라 도구가 된다. 대신 분석이
// 필요로 하는 축(이별 시점, 사유, 이후 연락)을 상담자가 묻는 형태로 담는다.
// 짐작한 감정으로 말을 열지 않는다(persona의 금지 항목이다) — 첫 줄부터 남의 일처럼 들린다.
// 마침표를 찍지 않는 것도 페르소나의 호흡이다 — 문장을 닫으면 상담이 아니라 안내문이 된다.
// 길이 허락("길게 써도 돼")은 넣지 않는다. 입력창이 쓰는 만큼 늘어나니 화면이 이미 하는 말이다.
// 대신 자세할수록 정확해진다는 인과를 말한다 — 이건 화면이 못 하고, 유저도 모르는 정보다.
// 예로 드는 셋은 실제로 판을 가장 크게 흔드는 축이다 — 사유는 대역을 정하고, 교제와 이별 후
// 경과는 유형과 상대신호 판정의 기준이며(1~3개월 구간, 3개월 무반응), 연락 상황은 점프를 가른다.
// 빈 줄이 아니라 배열 항목이 말풍선 경계이고, 항목 안의 줄바꿈은 한 풍선 안의 줄이다.
// 문구 자체는 CharacterProfile로 옮겼다 — 첫 화면과 이 화면이 반드시 같은 말을 해야 해서
// 한 곳에서만 고칠 수 있게 뒀다(따로 두면 한쪽만 고쳐 두 화면이 어긋난다).
const POLL_INTERVAL = 1500;
// 이 시간을 넘기면 폴링 간격을 성기게 늦춘다(포기가 아니다). 백엔드 LLM 타임아웃(50초) 안에
// 답 또는 폴백이 저장되는 게 정상이라, 이 뒤는 지연이 아니라 이상 상황 — 그래도 끝까지 기다린다.
const POLL_SLOWDOWN_AFTER = 75000;
const POLL_SLOW_INTERVAL = 5000;
// 재입장 시 마지막 유저 메시지가 이보다 어리면 아직 답을 기다리는 판으로 본다.
// 서버가 답이든 폴백이든 저장하고도 남는 시간 — 이보다 늙었으면 과거에 끝난(실패한) 방이다.
const RESUME_WAIT_WINDOW = 180000;
// 마지막 말풍선이 뜬 뒤 질문 카드가 나오기까지의 뜸. 예전엔 조각 공개가 끝나는 순간이
// 곧 질문이 뜨는 순간이라, 마지막 줄을 읽기도 전에 답할 칸이 올라왔다. 상담자가 말을
// 마치고 잠깐 기다리는 간격이다 — 조각 사이 간격(최대 2200)보다 짧게 둬서 말이 아직
// 이어지는 중으로 읽히지 않게 한다.
const ASK_DELAY = 1400;
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

// **로 감싼 구간만 굵게. 짝이 안 맞는 별표는 글자 그대로 둔다 — 모델 실수가 화면 깨짐이 되지 않게.
function renderEmphasis(line: string): ReactNode {
  const parts = line.split(/\*\*([^*]+)\*\*/g);
  if (parts.length === 1) return line;
  return parts.map((p, i) => (i % 2 === 1 ? <strong key={i}>{p}</strong> : p));
}

export function ChatPage() {
  const { storyId: storyIdParam } = useParams();
  const storyId = Number(storyIdParam);
  // 분석 안내를 이 방에서 이미 지나쳤는지. 키 이름을 바꾼 건 뜻이 바뀌어서다 —
  // 예전 diagHintShown은 "띄웠다", 지금은 "유저가 넘어갔다"라 옛 값을 그대로 읽으면
  // 넘어간 적 없는 방까지 막힌다. 옛 키는 아무도 안 읽으니 그대로 둔다.
  const diagHintKey = `diagHintDone:${storyId}`;
  const navigate = useNavigate();
  const location = useLocation();
  const goPayment = useGoPayment();

  const [messages, setMessages] = useState<MessageResponse[]>([]);
  const [cursor, setCursor] = useState<number | null>(null);
  const [hasOlder, setHasOlder] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false); // 첫 조회 실패 — 빈 대화와 구분해야 한다
  const [error, setError] = useState('');
  const [input, setInput] = useState('');
  const [waiting, setWaiting] = useState(false); // 어시스턴트 답 대기(타이핑)
  // 방금 도착한 답만 조각을 순차 공개한다. null이면 전부 표시(과거 메시지 포함).
  const [reveal, setReveal] = useState<{ id: number; shown: number } | null>(null);
  const [chatRemaining, setChatRemaining] = useState<number | null>(null); // 남은 대화 횟수(이용권)
  const [quotaOver, setQuotaOver] = useState(false); // 무료+이용권 모두 소진(Q001) → 구매 유도
  const [isGuest, setIsGuest] = useState(false); // 게스트면 충전 대신 '계정 연결' 동선
  const [guestBlocked, setGuestBlocked] = useState(false); // 게스트 대화 소진(U010) → 계정 연결 유도
  // 연속 실패 쿨다운(Q003)의 남은 초. 서버가 내려준 값에서 시작해 매 초 줄고, 0이면 다시 보낼 수 있다.
  const [cooldown, setCooldown] = useState(0);
  const [retrying, setRetrying] = useState(false); // 재시도 접수 대기(버튼 연타 방지)
  const [showHelp, setShowHelp] = useState(false);
  const [showProfile, setShowProfile] = useState(false);
  const [showDrawer, setShowDrawer] = useState(false);
  // 초상 파일이 없을 때 말풍선마다 깨진 이미지가 뜨지 않게 — 한 번 실패하면 전부 접는다
  const [hasAvatar, setHasAvatar] = useState(true);
  // 분석 입구 안내 줄 — 헤더 버튼만으로는 분석이 있는 줄 모른 채 이탈하는 사람들이 있다
  const [showDiagHint, setShowDiagHint] = useState(false);
  // 상담자가 물은 것을 하나씩 받는다. 몇 번째를 묻는 중인지와, 지금까지 받아둔 답.
  const [askIndex, setAskIndex] = useState(0);
  const [askAnswers, setAskAnswers] = useState<string[]>([]);
  // 답이 다 뜬 뒤에도 질문을 잠깐 붙들어 둔다. 조각 공개(reveal)와 따로 두는 이유는
  // 조각이 없는 짧은 답(한 덩어리)에는 reveal 자체가 안 걸려서다 — 그 판에선 답과 질문이
  // 같은 프레임에 떴다.
  const [askHold, setAskHold] = useState(false);

  // 추천 질문 칩. 눌러야 하는 것이 아니라 다음 상담으로 들어가는 지름길이라, 자유입력을
  // 막지 않는다. INPUT 칩은 바로 보내지 않고 시트를 먼저 띄운다(누르자마자 label을 보내면
  // 상담자가 "무슨 일이 있었나요?"를 되묻느라 한 턴이 통째로 날아간다).
  const [chipInput, setChipInput] = useState<ChipView | null>(null);
  const [chipCatalog, setChipCatalog] = useState(false);

  // 기본 정보(나이, 성별, 기간)는 홈의 질문 단계에서 받는다. 여기서 안 받는 이유는 자리다 —
  // 사연을 다 쓰고 보내는 순간에 폼이 떨어지면, 털어놓은 사람에게 접수증을 내미는 꼴이 된다.

  // 마지막 메시지에 질문이 실려 있으면 그게 지금 답할 것이다.
  const pendingQuestions = (() => {
    const last = messages[messages.length - 1];
    return last && last.role !== 'USER' ? (last.questions ?? []) : [];
  })();

  // 말풍선이 다 나오기 전에는 묻지 않는다 — 읽는 중에 질문이 끼면 말을 하다 만 것이 된다
  const asking =
    pendingQuestions.length > 0 && askIndex < pendingQuestions.length && reveal == null && !askHold;

  // 지금 입력창에 허용되는 길이. 답들은 마지막에 하나로 이어 붙여 메시지 한 건으로 나가므로,
  // 개당 상한을 고정값으로 두면 다 채운 사람만 마지막에 거절당한다 — 그것도 셋을 다 쓴 뒤에.
  // 그래서 남은 예산(줄바꿈 몫을 뺀 MAX_LENGTH)을 질문 수로 나눠 잡는다. 하나면 1000자,
  // 둘이면 900자, 셋이면 600자이고 넷을 물어도 알아서 줄어든다.
  // 백 자 단위로 내리는 이유: 666 같은 수가 카운터에 뜨면 상한이 아니라 잔량으로 읽힌다.
  const limit = asking
    ? Math.min(
        ANSWER_MAX,
        Math.floor((MAX_LENGTH - (pendingQuestions.length - 1)) / pendingQuestions.length / 100) * 100,
      )
    : MAX_LENGTH;

  // 상담자가 물은 것을 진짜 메시지처럼 목록에 얹는다. 따로 그리면 프사, 이름, 꼬리,
  // 묶음 규칙을 두 곳에서 관리하게 되고 실제로 넷이 어긋났다(실측) — 같은 렌더를 태운다.
  // 묻는 중일 때만이 아니라 지나간 턴에도 그린다: 답을 보내고 나면 마지막 메시지가 유저
  // 것이 되는데, 그때 질문이 사라지면 대화에 답만 세 줄 남아 유저 혼자 말한 것이 된다.
  // id는 음수라 진짜 메시지와 안 겹치고, 시각도 이 값으로 가려낸다(아직 안 보낸 말이다).
  // 질문 말풍선의 id 모음. 여기 있는 것은 앞 답변에 묶지 않고 프사와 이름을 새로 단다 —
  // 앞은 읽어준 것이고 질문부터는 답해야 하는 자리라, 그 전환이 화면에 보여야 한다.
  const askHeads = new Set<number>();
  const viewMessages = (() => {
    const out: MessageResponse[] = [];
    messages.forEach((m, mi) => {
      const qs = m.role !== 'USER' ? (m.questions ?? []) : [];
      // 질문에 답한 유저 메시지는 한 덩어리로 저장돼 있다. 그 자리에 통째로 그리면
      // 답만 여러 줄 뭉쳐 보이니, 앞 질문과 줄 단위로 짝지어 되돌린다(전송 때 한 답이 한 줄).
      const prevQs = mi > 0 && messages[mi - 1].role !== 'USER' ? (messages[mi - 1].questions ?? []) : [];
      // 짝짓기와 같은 조건으로 걸러야 한다 — 여기만 둘 이상으로 잡았을 때, 질문이 하나인
      // 턴은 아래에서 답 말풍선을 만들고 여기서는 원문도 남겨 같은 답이 두 번 그려졌다
      if (m.role === 'USER' && prevQs.length > 0 && m.content.split('\n').length === prevQs.length) {
        return;
      }
      out.push(m);
      const isLast = mi === messages.length - 1;
      // 말풍선이 다 나오기 전에는 질문을 안 붙인다 — 읽는 중에 끼면 말을 하다 만 것이 된다
      if (qs.length === 0 || (isLast && (reveal != null || askHold))) return;
      // 아직 묻는 중이면 받아둔 답, 이미 보냈으면 다음 유저 메시지를 줄로 갈라 짝짓는다
      const sent = messages[mi + 1];
      const answers =
        isLast && asking
          ? askAnswers
          : sent && sent.role === 'USER' && sent.content.split('\n').length === qs.length
            ? sent.content.split('\n')
            : [];
      const upto = isLast && asking ? askIndex : qs.length - 1;
      for (let i = 0; i <= upto; i++) {
        // 몇 번째인지는 말풍선에 안 적는다 — 상담자가 "(1/3)"이라고 말하는 꼴이라
        // 사람 말투가 서식으로 바뀐다. 진행은 아래 답변 줄이 숫자로만 표시한다
        askHeads.add(-(mi * 100 + i * 2 + 1));
        out.push({
          id: -(mi * 100 + i * 2 + 1),
          role: 'ASSISTANT',
          content: qs[i],
          createdAt: m.createdAt,
          failed: false,
          questions: [],
          chips: [],
        });
        // 건너뛴 자리는 빈 줄이라 답 말풍선을 그리지 않는다 — "넘어갔습니다"를 띄우면
        // 화면이 안 답한 것을 지적하는 꼴이 된다. 비어 있는 것으로 충분하다.
        if (answers[i]?.trim()) {
          out.push({
            id: -(mi * 100 + i * 2 + 2),
            role: 'USER',
            content: answers[i].trim(),
            createdAt: (sent ?? m).createdAt,
            failed: false,
            questions: [],
            chips: [],
          });
        }
      }
    });
    return out;
  })();

  // 새 답변이 오면 처음부터 다시 묻는다
  useEffect(() => {
    setAskIndex(0);
    setAskAnswers([]);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [messages[messages.length - 1]?.id]);

  // 질문이 뜨면 입력창에 커서를 넣는다 — 답할 자리가 어디인지 말로 설명하지 않아도
  // 커서와 (모바일에서) 올라오는 키보드가 알려준다. 키보드가 화면을 반쯤 먹으니 끝으로 붙인다.
  useLayoutEffect(() => {
    if (!asking) return;
    inputRef.current?.focus();
    scrollToBottom();
  }, [asking, askIndex]);

  // 답은 모아뒀다 마지막에 한 번에 보낸다 — 하나씩 보내면 무료 3턴이 질문에 답하다 끝난다.
  // 전부 건너뛰었으면 보낼 것이 없으니 그냥 평소 입력창으로 돌아간다.
  // 질문은 되붙이지 않는다 — 답만 이어도 유저가 세 가지를 말한 것으로 읽히고,
  // 상담자는 저장 원문에 남은 자기 질문을 그대로 보고 있다.
  function answerAsk(answer: string) {
    // 답 안의 줄바꿈은 공백으로 — 한 줄이 한 답이어야 화면이 질문과 짝지어 되돌릴 수 있다
    const collected = [...askAnswers, answer.trim().replace(/\s*\n+\s*/g, ' ')];
    setInput('');
    setAskAnswers(collected);
    setAskIndex(askIndex + 1);
    if (askIndex + 1 < pendingQuestions.length) return;
    // 건너뛴 자리는 빈 줄로 남긴다 — 지우면 줄 수가 어긋나 답이 엉뚱한 질문에 붙는다
    void handleSend(collected.some(Boolean) ? collected.join('\n') : SKIPPED_ALL_MESSAGE);
  }

  // 한 칸 뒤로. 아직 아무것도 안 보낸 말이라 고쳐 쓸 수 있어야 한다 — 마지막 질문까지
  // 모아뒀다 한 번에 보내는 구조라, 오타를 본 유저가 지금 할 수 있는 일이 없으면
  // 대화 1회를 정정에 쓰게 된다. 그때 쓴 답은 입력창에 되살려 처음부터 다시 치지 않게 한다.
  function undoAnswer() {
    if (askIndex === 0) return;
    setInput(askAnswers[askIndex - 1] ?? '');
    setAskAnswers(askAnswers.slice(0, -1));
    setAskIndex(askIndex - 1);
  }

  // 이름도 묶음의 첫 풍선 위에만. 프사와 같은 높이에서 시작해야 누구의 덩어리인지가 한눈에 잡힌다.
  function withName(show: boolean, bubble: React.ReactNode) {
    if (!show) return bubble;
    return (
      <div className={styles.named}>
        <div className={styles.senderName}>{CHARACTER_NAME}</div>
        {bubble}
      </div>
    );
  }

  // 카톡식으로 시현이 연달아 말한 묶음의 첫 풍선에만 프사를 단다. 나머지 줄은 빈 자리로 왼끝을 맞춘다.
  function assistantAvatar(show: boolean) {
    return (
      <div className={styles.avatarSlot}>
        {show && hasAvatar && (
          <button
            className={styles.avatarBtn}
            onClick={() => setShowProfile(true)}
            aria-label={`${CHARACTER_NAME} 프로필`}
          >
            <img className={styles.avatarImg} src={CHARACTER_AVATAR} alt="" onError={() => setHasAvatar(false)} />
          </button>
        )}
      </div>
    );
  }

  function refreshUsage() {
    getUsage()
      .then((u) => {
        if (!aliveRef.current) return;
        setChatRemaining(u.chatRemaining);
        setIsGuest(u.guest);
        // 재입장해도 쿨다운이 그대로 보이게 — 서버가 남은 초를 알고 있으니 화면이 새로 받아간다.
        // 이게 없으면 입력창이 멀쩡해 보이는데 다 쓰고 보내는 순간에야 거절당한다.
        setCooldown(u.chatCooldownSeconds);
      })
      .catch(() => {}); // 표시용 정보라 실패는 조용히 무시
  }

  const aliveRef = useRef(true);
  // 스크롤 컨테이너 자체. 위치를 잴 때도, 내릴 때도 이 요소만 만진다.
  const messagesRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  // 마지막으로 본 메시지 id. 내가 보낸 메시지인지 가리는 데 쓴다(이전 대화 더 보기와 구분).
  const lastMsgIdRef = useRef<number | null>(null);

  // 분석 화면의 "대화로 물어보기"로 넘어온 경우 질문을 입력창에 미리 채워준다.
  useEffect(() => {
    const prefill = (location.state as { prefill?: string } | null)?.prefill;
    if (prefill) setInput(prefill);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 첫 화면에서 적은 문장은 그대로 이어 보낸다 — 여기서 다시 치게 하면 시작이 두 번이 된다
  const autoSentRef = useRef(false);
  useEffect(() => {
    const auto = (location.state as { autoSend?: boolean } | null)?.autoSend;
    if (!auto || autoSentRef.current || !input.trim()) return;
    autoSentRef.current = true;
    void handleSend();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [input]);

  useEffect(() => {
    aliveRef.current = true;
    void loadInitial();
    return () => {
      aliveRef.current = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storyId]);

  // 첫 진입 조회. 실패하면 목록이 아니라 실패 화면 하나만 띄워야 해서 loadFailed로 갈라둔다 —
  // 예전엔 실패해도 messages가 빈 배열이라 "첫 대화를 시작해 보세요"와 에러가 같이 떴다.
  async function loadInitial() {
    setLoadFailed(false);
    setError('');
    setAskHold(false);
    setLoading(true);
    try {
      const page = await getMessages(storyId);
      if (!aliveRef.current) return;
      setMessages(page.messages);
      // 불러온 마지막 메시지를 "이미 본 것"으로 찍어둔다 — 안 그러면 방에 들어오는 순간
      // 처음 보는 id라 방금 도착한 메시지로 잡힌다.
      lastMsgIdRef.current = page.messages[page.messages.length - 1]?.id ?? null;
      setCursor(page.nextCursor);
      setHasOlder(page.hasNext);
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
      if (!aliveRef.current) return;
      setError(extractErrorMessage(e, '대화를 불러오지 못했습니다.'));
      setLoadFailed(true);
    } finally {
      if (aliveRef.current) setLoading(false);
    }
  }

  // 방에 들어오면 맨 아래(최근 대화)부터 보여준다. 아래 효과는 "맨 아래를 보고 있을 때만"
  // 따라 내려가는데, 첫 렌더는 scrollTop이 0이라 그 조건에 안 걸린다 — 여기서 한 번 내린다.
  useEffect(() => {
    if (loading) return;
    scrollToBottom();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loading]);

  // 새 메시지, 타이핑 표시, 조각 공개 시 맨 아래로 — 단, 유저가 맨 아래를 보고 있을 때만.
  // 위로 올려 읽는 중이면 따라 내려가지 않는다(읽던 자리를 뺏지 않는다). 내가 보낸 메시지는
  // 예외로 항상 내려간다 — 내가 방금 쳤는데 안 내려가면 전송이 안 된 것처럼 보인다.
  useEffect(() => {
    const last = messages[messages.length - 1];
    const lastChanged = last != null && last.id !== lastMsgIdRef.current;
    if (lastChanged) lastMsgIdRef.current = last.id;
    if (isAtBottom() || (lastChanged && last.role === 'USER')) scrollToBottom();
  }, [messages, waiting, reveal]);

  // 맨 아래에 있는지 그때그때 직접 잰다. 스크롤 이벤트로 갱신하는 값을 캐시해 두면,
  // 이벤트가 한 번도 안 뜬 판에서 초기값이 그대로 남아 엉뚱하게 판정된다.
  // 목록을 못 잡았을 때만 맨 아래로 간주한다 — 근거가 없으면 따라 내려가는 쪽이 안전하다.
  function isAtBottom(): boolean {
    const el = messagesRef.current;
    if (!el) return true;
    return el.scrollHeight - el.scrollTop - el.clientHeight < 60;
  }

  // scrollIntoView는 조상 스크롤 컨테이너까지 같이 움직여서, 폰 목업이 브라우저 창보다 큰
  // 데스크톱에서는 페이지 전체가 딸려 내려갔다. 목록만 직접 내린다.
  function scrollToBottom() {
    const el = messagesRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }

  // 입력창을 내용만큼 키운다. textarea는 rows대로 높이가 고정이라 이게 없으면 2000자 상한을
  // 한 줄짜리 칸에 밀어 넣게 된다 — 방금 쓴 문장이 안 보이니 고쳐 쓸 수도 없다.
  // 상한(CSS max-height)에 닿으면 그때부터 안에서 스크롤한다.
  // border-box라 scrollHeight에는 테두리가 빠져 있다. 그대로 넣으면 매 입력마다 2px씩 모자란다.
  useLayoutEffect(() => {
    const el = inputRef.current;
    if (!el) return;
    const stick = isAtBottom(); // 입력창이 커지면 목록이 줄어든다 — 보던 자리가 맨 아래였는지 먼저 잰다
    el.style.height = 'auto';
    el.style.height = `${el.scrollHeight + el.offsetHeight - el.clientHeight}px`;
    if (stick) scrollToBottom();
  }, [input]);

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

  // 조각 공개가 끝나면(또는 애초에 조각이 없었으면) 그때부터 뜸을 재고 질문을 푼다.
  // 도착 시점이 아니라 다 뜬 시점부터 재야, 긴 답일수록 더 오래 기다리는 게 아니라
  // 어떤 답이든 마지막 줄 뒤에 같은 간격이 붙는다.
  useEffect(() => {
    if (!askHold || reveal != null) return;
    const timer = window.setTimeout(() => setAskHold(false), ASK_DELAY);
    return () => clearTimeout(timer);
  }, [askHold, reveal]);

  // 쿨다운 카운트다운. 매 초 setTimeout을 새로 걸어 interval이 어긋나 쌓이지 않게 하고,
  // 0이 되면 입력창과 보내기 버튼이 새로고침 없이 스스로 살아난다.
  useEffect(() => {
    if (cooldown <= 0) return;
    const timer = window.setTimeout(() => aliveRef.current && setCooldown(cooldown - 1), 1000);
    return () => clearTimeout(timer);
  }, [cooldown]);

  // 완결 턴(답을 받은 턴) 2회가 쌓이면 분석 입구를 안내한다. 2회인 이유: 첫 인사가
  // 분석의 3축(사유, 시점, 연락)을 물어 첫 사연에 핵심이 담기고, 보강 1턴이면 분석할 최소
  // 재료가 된다 — 더 이르면 얇은 근거로 이용권 1회를 쓰게 만든다.
  // 표시는 "띄운 순간"이 아니라 "유저가 넘어간 순간"에 남긴다(dismissDiagHint). 띄울 때 남기면
  // 다른 방에 잠깐 갔다 오는 것만으로 사라진다 — 방을 나가면 이 화면이 죽으니 안내는 못 읽고
  // 표시만 남는 꼴이다. 대신 아직 안 넘어간 방은 입장마다 이력 조회(GET)가 한 번 나간다.
  // 이력이 있는 방은 그 자리에서 표시를 남겨 다음 입장부터 조회를 건너뛴다.
  useEffect(() => {
    if (showDiagHint) return;
    const replied = messages.filter((m) => m.role !== 'USER' && !m.failed).length;
    if (replied < 2) return;
    if (localStorage.getItem(diagHintKey)) return;
    getAssessments(storyId)
      .then((all) => {
        if (!aliveRef.current) return;
        if (all.length === 0) setShowDiagHint(true);
        else localStorage.setItem(diagHintKey, '1');
      })
      .catch(() => {}); // 부가 안내라 실패하면 조용히 안 띄운다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [messages]);

  // 안내를 내리고 이 방에서 다시 안 뜨게 못 박는다. 유저가 입구를 찾은 순간(버튼을 눌렀거나)
  // 다음 말로 넘어간 순간에만 부른다.
  // 떠 있을 때만 쓴다 — handleSend는 전송마다 이걸 부르므로, 안 떠 있어도 쓰면 유저의
  // 첫 메시지가 표시를 남겨 안내가 영영 안 뜬다(조건이 갖춰지기도 전에 막힌다).
  function dismissDiagHint() {
    if (!showDiagHint) return;
    localStorage.setItem(diagHintKey, '1');
    setShowDiagHint(false);
  }

  async function loadOlder() {
    if (cursor == null) return;
    try {
      const page = await getMessages(storyId, cursor);
      setMessages((prev) => [...page.messages, ...prev]);
      setCursor(page.nextCursor);
      setHasOlder(page.hasNext);
    } catch (e) {
      setError(extractErrorMessage(e, '이전 대화를 불러오지 못했습니다.'));
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
            if ((lastReply.questions ?? []).length > 0) setAskHold(true);
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

  // 마지막 답변에 붙은 추천 질문. 상담자가 물은 것(asking)이 있으면 안 그린다 —
  // 답할 질문과 물을 질문이 한 화면에 같이 있으면 무엇을 하라는 건지 알 수 없다.
  const lastMessage = messages[messages.length - 1];
  const suggestedChips =
    lastMessage && lastMessage.role !== 'USER' && !lastMessage.failed && !asking
      ? (lastMessage.chips ?? [])
      : [];
  // 입력창에 이미 쓰던 말이 있으면 안 그린다. 뒤늦게 뜬 칩이 타이핑 중인 사람의 손을 가로챈다.
  const showChips =
    suggestedChips.length > 0 && !waiting && reveal == null && input.trim().length === 0;

  // 칩을 눌렀을 때. DIRECT는 label이 그대로 유저 메시지가 되고, INPUT은 시트를 먼저 띄운다.
  function pickChip(chip: ChipView) {
    setChipCatalog(false);
    if (chip.interaction === 'INPUT' && chip.inputPreset) {
      setChipInput(chip);
      return;
    }
    void handleSend(chip.label, chip.id);
  }

  // 길이는 회수와 무관하다 — 한 턴이 1회다. 남은 건 상한뿐이라 넘치면 전송만 막는다.
  const blocked = input.length > limit;

  // chipId는 추천 질문에서 온 말일 때만 실린다 — 서버가 그 칩의 전문 프롬프트로 답을 만든다.
  async function handleSend(preset?: string, chipId?: string) {
    // 질문 답 합본은 trim하지 않는다 — 빈 줄이 "그 질문엔 답하지 않았다"는 자리표라, 앞뒤가
    // 잘리면 줄 수가 질문 수와 어긋난다. 그러면 화면이 답을 마지막 질문 아래에 통째로 붙이고
    // (첫 답이 세 번째 자리로 간다), 모델도 몇 번째 질문의 답인지를 잘못 읽는다.
    const content = preset ?? input.trim();
    // 상한 검사는 입력창(blocked)이 아니라 실제로 나가는 글자로 한다 — 합본은 입력창을
    // 거치지 않아, 여기서 안 재면 세 답을 합친 뒤 서버 검증에서만 거절당한다.
    if (!content.trim() || waiting || cooldown > 0 || content.length > MAX_LENGTH) return;
    if (!preset) setInput('');
    setError('');
    setQuotaOver(false);
    setGuestBlocked(false);
    try {
      const userMsg = await sendMessage(storyId, content, chipId);
      setMessages((prev) => [...prev, userMsg]);
      // 유저가 다음 말을 시작했으면 안내는 할 일을 다 했다. 여기서 안 내리면 답을 기다리는
      // 동안만 숨었다가 새 말풍선 아래로 다시 올라온다 — 한 번 뜨고 마는 줄이 턴마다 재등장한다.
      // 보내기 성공에만 건다: 소진, 쿨다운으로 못 보낸 판은 유저가 넘어간 게 아니다.
      dismissDiagHint();
      setWaiting(true);
      pollForReply(userMsg.id);
    } catch (e) {
      setInput(content); // 실패 시 입력 복구 — 카드로 보낸 것도 입력창에 되살려 다시 치지 않게 한다
      const code = extractErrorCode(e);
      // 게스트 소진(U010)은 충전이 아니라 계정 연결로 푼다 — 배너를 다르게 띄운다.
      if (code === 'U010') {
        setGuestBlocked(true);
      } else if (code === 'Q003') {
        // 연속 실패 쿨다운 — 배너로 사유와 남은 시간을 띄우고 입력을 잠근다.
        // 사라지는 에러 문구로 처리하면 유저는 왜 안 보내지는지 모른 채 계속 누른다.
        setCooldown(extractRetryAfterSeconds(e) ?? 60);
      } else if (code === 'L001') {
        // 생성 실패의 백엔드 문구는 기계 티가 난다 — 입력은 복구돼 있으니 다시 보내라고만
        setError('죄송합니다, 지금 답을 정리하기가 어렵습니다 조금 뒤에 다시 보내주시겠습니까?');
      } else {
        setError(extractErrorMessage(e, '메시지를 보내지 못했습니다. 다시 시도해 주세요.'));
        setQuotaOver(code === 'Q001');
      }
    }
  }

  // 답을 못 받은 턴의 재시도. 같은 말을 다시 치게 하지 않는 게 목적이라 입력창은 건드리지 않고,
  // 서버가 폴백을 지우고 답만 새로 만든다. 화면에서도 폴백을 걷어내고 대기 상태로 돌아간다.
  async function handleRetry() {
    if (retrying || waiting || cooldown > 0) return;
    setRetrying(true);
    setError('');
    try {
      const { pollAfterId } = await retryLastReply(storyId);
      setMessages((prev) => (prev[prev.length - 1]?.failed ? prev.slice(0, -1) : prev));
      setWaiting(true);
      pollForReply(pollAfterId);
    } catch (e) {
      const code = extractErrorCode(e);
      if (code === 'Q003') {
        setCooldown(extractRetryAfterSeconds(e) ?? 60);
      } else if (code === 'S002') {
        // 두 번 눌렀거나 그새 답이 붙었다 — 다시 그리면 해소되므로 조용히 최신 상태를 당겨온다.
        getMessages(storyId)
          .then((page) => aliveRef.current && setMessages(page.messages))
          .catch(() => {});
      } else {
        setError(extractErrorMessage(e, '다시 시도하지 못했습니다. 잠시 후 다시 눌러 주세요.'));
        setQuotaOver(code === 'Q001');
        setGuestBlocked(code === 'U010');
      }
    } finally {
      if (aliveRef.current) setRetrying(false);
    }
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key !== 'Enter' || e.shiftKey) return;
    // 한글 조합 중의 Enter는 글자를 확정하는 키다 — 여기서 가로채면 마지막 글자가 씹힌다.
    if (e.nativeEvent.isComposing) return;
    // 사연 길이로 넘어갔거나 이미 줄을 나눈 글이면 Enter는 줄바꿈. 모바일 키보드도 이 키로
    // 줄을 바꾸는데, 전송으로 잡히면 장문에서 문단 자체를 만들 수가 없다.
    if (input.length >= ENTER_SENDS_UNDER || input.includes('\n')) return;
    e.preventDefault();
    if (asking) {
      if (input.trim()) answerAsk(input);
      return;
    }
    handleSend();
  }

  return (
    <PhoneFrame>
      <div className={styles.wrap}>
        <div className={styles.topbar}>
          <div className={styles.topLeft}>
            {/* 카톡 대화방 헤더 문법 — 프사와 이름이 한 덩어리로 프로필 입구가 된다.
                꺾쇠는 뺐다. 프사가 이미 누를 수 있는 것으로 읽힌다 */}
            <button
              className={styles.identity}
              onClick={() => setShowProfile(true)}
              aria-label={`${CHARACTER_NAME} 프로필`}
            >
              {hasAvatar && (
                <span className={styles.headAvatar}>
                  <img
                    className={styles.avatarImg}
                    src={CHARACTER_AVATAR}
                    alt=""
                    onError={() => setHasAvatar(false)}
                  />
                </span>
              )}
              <span className={styles.charName}>{CHARACTER_NAME}</span>
            </button>
          </div>
          <div className={styles.topRight}>
            {/* 아이콘 단독은 뜻이 안 읽혀 기각됐던 이력 — 글자는 유지하고 아이콘을 곁들인다.
                반원 게이지 축소판은 뜻이 안 살았고(실측), 펄스 라인이 '분석'과 바로 이어진다 */}
            <button className={styles.diagButton} onClick={() => navigate(`/stories/${storyId}/assessment`)}>
              <svg
                className={styles.diagIcon}
                width="15"
                height="15"
                viewBox="0 0 24 24"
                fill="none"
                aria-hidden="true"
              >
                <path
                  d="M3.5 12h4l2.5-6.5 4 13 2.5-6.5h4"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </svg>
              분석
            </button>
            {/* 이용권 진입점은 서랍 헤더가 상시로 맡고, 소진이 닥친 순간은 입력창 위 충전하기가
                맡는다 — 채팅 헤더까지 넣으면 같은 곳으로 가는 문이 셋이 된다 */}
            {/* 아이콘만으로는 서랍인 줄 모르니 분석과 같은 칩 문법으로 이름을 함께 쓴다.
                그림은 삼선 — 토스를 비롯해 쓰는 앱마다 여는 문으로 굳어서 설정 메뉴로 오독될
                일이 없다. 상자 그림은 15px에서 곡선이 뭉개졌고 여는 것이 상자도 아니었다 */}
            {/* 게스트에겐 안 연다 — 서랍이 여는 것 중 게스트에게 쓸모 있는 게 없다. 체험
                횟수는 계정 단위라 새 방을 파면 한 방도 못 끝낸 채 소진되고, 방은 하나뿐이라
                목록도 볼 게 없다. 로그인 입구는 계정 연결 화면이 맡는다.
                usage가 아직 안 왔으면 회원으로 친다 — 조회가 실패해도 회원의 서랍은 살아야 한다 */}
            {!isGuest && (
            <button className={styles.drawerButton} onClick={() => setShowDrawer(true)}>
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path
                  d="M4.5 6.8h15M4.5 12h15M4.5 17.2h15"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                />
              </svg>
              서랍
            </button>
            )}
            {/* 물음표는 맨 끝 구석 — 칩 무리(분석, 서랍) 사이나 앞에 끼면 무리가 갈라져 보인다.
                보조 아이콘은 가장자리가 자리다 */}
            <button className={styles.helpButton} onClick={() => setShowHelp(true)} aria-label="도움말">
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

        <div className={styles.messages} ref={messagesRef}>
          {loading ? (
            <div className={styles.state}>불러오는 중…</div>
          ) : loadFailed ? (
            // 못 불러온 것과 대화가 없는 것은 다르다 — 실패한 판에 "첫 대화를 시작해 보세요"를
            // 띄우면 없는 사실을 말하는 것이고, 에러까지 같이 떠서 화면이 두 갈래로 갈렸다
            <div className={styles.state}>
              <div className={styles.stateTitle}>대화를 불러오지 못했습니다</div>
              <div className={styles.stateBody}>{error || '잠시 후 다시 시도해 주세요.'}</div>
              <button className={styles.retryLoad} onClick={() => void loadInitial()}>
                다시 시도
              </button>
            </div>
          ) : (
            <>
              {hasOlder && (
                <button className={styles.loadMore} onClick={loadOlder}>
                  이전 대화 더 보기
                </button>
              )}
              {/* 빈 방에 "첫 대화를 시작해 보세요"만 두면 무엇을 얼마나 말해야 하는지가 안 보인다 —
                  가장 쓰기 어려운 화면이다. 지시를 늘리는 대신 상담자가 먼저 말을 걸게 한다.
                  방의 처음을 보고 있을 때만(더 볼 이전 대화가 없을 때) 띄운다 — 첫 답을 보낸
                  직후 인사가 사라지면 방금 읽은 말이 지워진 것처럼 보인다 */}
              {/* 날짜는 인사 위에 둔다 — 인사 아래에 두면 첫 메시지를 보내는 순간 인사와 내 말
                  사이에 날짜가 끼어들어, 인사만 다른 날에 온 것처럼 보인다. 인사는 이 방이
                  열린 자리라 첫 메시지와 같은 날 묶음이다(아직 아무 말도 없으면 오늘) */}
              {!hasOlder && (
                <div className={styles.divider}>
                  {formatDateDivider(messages[0]?.createdAt ?? new Date().toISOString())}
                </div>
              )}
              {!hasOlder &&
                GREETING.map((seg, i) => (
                  <div
                    className={`${styles.msgRow} ${i === 0 ? styles.groupStart : ''}`}
                    key={`greeting-${i}`}
                  >
                    {assistantAvatar(i === 0)}
                    {withName(
                      i === 0,
                      <div
                        className={`${styles.bubble} ${styles.assistant} ${i === 0 ? styles.tailAssistant : ''}`}
                      >
                        {seg.split('\n').map((line, li) => (
                          <div className={styles.bubbleLine} key={li}>
                            {line}
                          </div>
                        ))}
                      </div>,
                    )}
                  </div>
                ))}
              {viewMessages.map((m, i) => {
                const prev = viewMessages[i - 1];
                const next = viewMessages[i + 1];
                // 카톡식: 날짜가 바뀌는 첫 메시지 위에 "2026년 7월 3일 금요일" 구분선.
                // 맨 첫 메시지는 인사 위에서 이미 날짜를 달았으므로 건너뛴다(인사가 없는
                // 판, 즉 더 볼 이전 대화가 있는 판에서만 여기가 첫 날짜를 단다).
                const newDay = !prev ? hasOlder : !isSameCalendarDate(prev.createdAt, m.createdAt);
                // 카톡식: 같은 사람이 같은 분(分)에 연달아 보낸 묶음은 마지막 말풍선에만 시각 표시.
                const showTime =
                  !next ||
                  next.role !== m.role ||
                  formatClock(next.createdAt) !== formatClock(m.createdAt) ||
                  !isSameCalendarDate(next.createdAt, m.createdAt);
                // 어시스턴트 답은 문단 단위 말풍선으로. 유저 입력은 쓴 그대로 한 덩어리.
                // 프사와 꼬리가 붙는 자리. 인사 말풍선이 위에 있으면 그쪽이 시현 묶음의 시작이다
                const startsGroup = askHeads.has(m.id)
                  ? true
                  : prev
                    ? prev.role !== m.role
                    : m.role === 'USER' || hasOlder;
                const segs = m.role === 'USER' ? [m.content] : splitParagraphs(m.content);
                const shown = reveal?.id === m.id ? Math.min(reveal.shown, segs.length) : segs.length;
                return (
                  <div key={m.id} style={{ display: 'contents' }}>
                    {newDay && <div className={styles.divider}>{formatDateDivider(m.createdAt)}</div>}
                    {segs.slice(0, shown).map((seg, si) => (
                      <div
                        className={[
                          styles.msgRow,
                          m.role === 'USER' ? styles.msgRowUser : '',
                          si === 0 && startsGroup ? styles.groupStart : '',
                        ].join(' ')}
                        key={si}
                      >
                        {m.role !== 'USER' && assistantAvatar(si === 0 && startsGroup)}
                        {withName(
                          si === 0 && startsGroup && m.role !== 'USER',
                          <div
                            className={[
                              styles.bubble,
                              m.role === 'USER' ? styles.user : styles.assistant,
                              si === 0 && startsGroup
                                ? m.role === 'USER'
                                  ? styles.tailUser
                                  : styles.tailAssistant
                                : '',
                            ].join(' ')}
                          >
                            {/* 한 풍선 안 여러 문장은 줄만 바꾸면 바로 밑에 붙어 답답하다(실측) —
                              문장마다 블록으로 나눠 사이를 띄운다 */}
                            {seg.split('\n').map((line, li) => (
                              <div className={styles.bubbleLine} key={li}>
                                {m.role === 'USER' ? line : renderEmphasis(line)}
                              </div>
                            ))}
                          </div>,
                        )}
                        {/* 시각은 묶음의 마지막 조각에만, 그것도 전부 공개된 뒤에 */}
                        {showTime && m.id > 0 && shown === segs.length && si === shown - 1 && (
                          <span className={styles.msgTime}>{formatClock(m.createdAt)}</span>
                        )}
                      </div>
                    ))}
                    {/* 답을 못 받은 턴에만, 그것도 마지막 턴에만. 과거 실패까지 버튼을 달면
                      지금 눌러야 할 것이 어느 것인지 흐려지고, 서버도 마지막 턴만 재시도한다 */}
                    {m.failed && !next && !waiting && (
                      <button
                        className={styles.retryReply}
                        onClick={handleRetry}
                        disabled={retrying || cooldown > 0}
                      >
                        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                          <path
                            d="M20 12a8 8 0 11-2.3-5.6M20 4v4h-4"
                            stroke="#B89DD1"
                            strokeWidth="2"
                            strokeLinecap="round"
                            strokeLinejoin="round"
                          />
                        </svg>
                        다시 시도
                      </button>
                    )}
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
              {/* 물은 것은 하나씩. 답하면 다음 질문이 이어 나오고, 마지막에 모아 한 번에 보낸다.
                받아둔 답도 말풍선으로 남겨야 한쪽만 말하는 화면이 되지 않는다 */}
              {asking && (
                <QuestionActions
                  step={askIndex + 1}
                  total={pendingQuestions.length}
                  onBack={askIndex > 0 ? undoAnswer : undefined}
                  onSkip={() => answerAsk('')}
                />
              )}
              {/* 다음 상담으로 들어가는 지름길. 질문 카드와 달리 답해야 하는 것이 아니라
                  물을 수 있는 것이라 유저 말풍선 쪽에 둔다. 자유입력을 막지 않는다 */}
              {showChips && (
                <ChipRow
                  chips={suggestedChips}
                  onPick={pickChip}
                  onBrowse={() => setChipCatalog(true)}
                />
              )}
              {/* 답이 다 도착한 숨 고르는 순간에만 — 타이핑 중에 끼어들면 대화를 가로챈다.
                  상담자 입으로 시키지 않고 화면의 시스템 줄이 맡는다(설명을 읽히는 순간
                  상담이 도구가 된다는 인사 원칙과 같은 이유) */}
              {showDiagHint && !waiting && reveal == null && (
                <div className={styles.diagHint}>
                  {/* 구분선은 날짜 줄과 같은 표지 자리라 서술문이 안 어울린다 — 무엇이 나오는지만
                      적는다. 확률 하나가 아니라는 것도 이 나열이 말한다.
                      "충분하다" 류의 단정은 안 쓴다 — 진단이 근거부족으로 돌려보내는 판이 있다 */}
                  <div className={styles.diagHintLine}>이별 유형, 재회 가능성, 비슷한 사례</div>
                  {/* 아이콘은 헤더 분석 칩과 같은 펄스 — 이 줄은 한 번 뜨고 사라지므로,
                      누르는 동안 "저 위에 있는 그것"이라는 연결이 남아야 다음부터 헤더로 간다 */}
                  {/* 누른 순간 못 박는다 — 입구를 찾은 사람에게 안내는 끝난 일이다.
                      분석을 안 돌리고 돌아와도 헤더 칩이 그 자리에 있다 */}
                  <button
                    className={styles.diagHintBtn}
                    onClick={() => {
                      dismissDiagHint();
                      navigate(`/stories/${storyId}/assessment`);
                    }}
                  >
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                      <path
                        d="M3.5 12h4l2.5-6.5 4 13 2.5-6.5h4"
                        stroke="currentColor"
                        strokeWidth="1.8"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                      />
                    </svg>
                    분석 리포트 받기
                  </button>
                </div>
              )}
              {error && <div className={styles.state}>{error}</div>}
            </>
          )}
        </div>

        {/* 게스트도 유료 유저와 같은 잔여 줄 문법을 쓴다 — 아이콘 타일과 테두리를 두른
            전용 카드는 이 화면에 없던 문법이라 광고 배너처럼 겉돌았다. 다른 점은
            충전 대신 계정 연결이라는 것과, 받는 것을 한 줄 덧붙인다는 것뿐이다 */}
        {/* 유료 유저의 잔여 줄과 완전히 같은 한 줄 — 칩이 충전 대신 계정 연결일 뿐이다.
            받는 것(분석 1회)은 연결 페이지와 분석 잠금 화면이 말한다. 대화 위에
            매번 얹으면 안내가 아니라 광고가 된다 */}
        {/* 질문 폼이 올라와 있으면 잔여 줄은 감춘다 — 상담자의 마지막 말과 그에 답하는
          폼 사이를 끊는다. 폼으로 보내도 차감은 같지만 지금 볼 정보는 아니다 */}
        {isGuest && (guestBlocked || chatRemaining != null) && (
          <div className={styles.usageHint}>
            남은 대화 <span className={styles.usageCount}>{chatRemaining ?? 0}회</span>
            <button className={styles.usageTopup} onClick={() => navigate('/guest-link')}>
              {/* 목록 헤더의 계정 연결 칩과 같은 아이콘 — 같은 곳으로 가는 버튼이다 */}
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <circle cx="10" cy="7.8" r="3.6" stroke="#B89DD1" strokeWidth="2.1" />
                <path
                  d="M3.6 19.6c0-3.5 2.9-5.8 6.4-5.8 1.3 0 2.6.3 3.6.9"
                  stroke="#B89DD1"
                  strokeWidth="2.1"
                  strokeLinecap="round"
                />
                <path
                  d="M18.4 13.6v5.8M15.5 16.5h5.8"
                  stroke="#B89DD1"
                  strokeWidth="2.1"
                  strokeLinecap="round"
                />
              </svg>
              계정 연결
            </button>
          </div>
        )}
        {/* 게스트 소진 — 잔여 줄의 0회만으로는 왜 막혔고 풀면 뭐가 되는지가 안 보인다.
            유료 소진 배너와 같은 틀, 색은 아이콘만(막힌 게 아니라 열 수 있는 상태라 라벤더) */}
        {isGuest && (guestBlocked || chatRemaining === 0) && (
          <div className={styles.quotaBanner}>
            <svg
              className={styles.quotaIcon}
              width="16"
              height="16"
              viewBox="0 0 24 24"
              fill="none"
              aria-hidden="true"
            >
              <circle cx="12" cy="12" r="9" stroke="#B89DD1" strokeWidth="1.6" />
              <path d="M12 11v5M12 7.6h.01" stroke="#B89DD1" strokeWidth="1.8" strokeLinecap="round" />
            </svg>
            <div className={styles.quotaText}>
              계정을 연결하면 지금까지의 대화를 그대로 이어가고,
              <br />
              분석 1회가 열려요.
            </div>
          </div>
        )}
        {!isGuest && chatRemaining != null && (
          <div className={styles.usageHint}>
            {/* 잔여가 이용권 하나로 합쳐져 숫자도 하나다 — 예전엔 무료와 이용권을
                나란히 보여줘서 유저가 더해 읽어야 했다 */}
            남은 대화 <span className={styles.usageCount}>{chatRemaining}회</span>
            {/* 남은 횟수를 보는 그 자리에서 바로 살 수 있게 — 소진 배너가 뜨기 전의 진입점 */}
            <button className={styles.usageTopup} onClick={goPayment}>
              <svg width="11" height="11" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M12 4.5v15M4.5 12h15" stroke="#B89DD1" strokeWidth="2.2" strokeLinecap="round" />
              </svg>
              충전하기
            </button>
          </div>
        )}
        {/* 소진 상태(잔여 0 또는 Q001 거절) — 안내만. 구매 버튼은 위 충전하기가 담당(중복 제거) */}
        {!isGuest && (quotaOver || chatRemaining === 0) && (
          <div className={styles.quotaBanner}>
            <svg
              className={styles.quotaIcon}
              width="16"
              height="16"
              viewBox="0 0 24 24"
              fill="none"
              aria-hidden="true"
            >
              <circle cx="12" cy="12" r="9" stroke="#D88B9F" strokeWidth="1.6" />
              <path d="M12 8v5M12 15.8h.01" stroke="#D88B9F" strokeWidth="1.8" strokeLinecap="round" />
            </svg>
            <div className={styles.quotaText}>
              대화 횟수를 모두 사용했습니다. 위의 충전하기로 이어서 대화할 수 있습니다.
            </div>
          </div>
        )}
        {/* 연속 실패 쿨다운 — 남은 시간을 초로 보여준다. 스스로 사라지는 에러 문구와 달리
            0이 될 때까지 남아야 유저가 "왜 안 보내지는지"를 계속 알 수 있다 */}
        {cooldown > 0 && (
          <div className={styles.cooldownBanner}>
            <svg
              className={styles.cooldownIcon}
              width="16"
              height="16"
              viewBox="0 0 24 24"
              fill="none"
              aria-hidden="true"
            >
              <circle cx="12" cy="12" r="9" stroke="#B89DD1" strokeWidth="1.6" />
              <path
                d="M12 7.2V12l3 1.8"
                stroke="#B89DD1"
                strokeWidth="1.8"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
            {/* 남은 시간이 먼저다 — 유저가 이 배너에서 찾는 건 "언제 풀리나" 하나뿐이다.
                왜 막혔는지와 차감 안 됐다는 안심은 그 아래 보조 줄로 내린다 */}
            <div className={styles.cooldownText}>
              <div className={styles.cooldownClock}>{cooldown}초 후 다시 보낼 수 있습니다</div>
              <div className={styles.cooldownReason}>
                답변을 만들지 못하는 상태가 이어지고 있습니다. 이번 대화는 차감되지 않았습니다.
              </div>
            </div>
          </div>
        )}
        {/* 글자수는 상한이 가까울 때만 띄운다 — 길이가 회수를 바꾸지 않으니 평소엔 볼 이유가 없다.
            넘긴 뒤에는 얼마나 줄여야 하는지가 유일하게 쓸모 있는 정보다.
            고정 여유분이 아니라 비율로 잡는다 — 답할 때 상한이 600자로 줄어드는데 300자를
            빼면 답을 반쯤 쓰는 순간부터 카운터가 따라다닌다 */}
        {input.length > limit * 0.85 && (
          <div className={styles.lengthHint}>
            {blocked && (
              <span className={`${styles.lengthCost} ${styles.lengthBlocked}`}>
                <span className={styles.lengthCostNum}>{input.length - limit}자</span>
                만큼 줄여야 보낼 수 있습니다
              </span>
            )}
            <span className={`${styles.lengthCount} ${blocked ? styles.lengthBlocked : ''}`}>
              {input.length}/{limit}
            </span>
          </div>
        )}
        {/* maxLength는 걸지 않는다 — 긴 사연을 붙여넣으면 뒷부분이 말없이 잘린 채 전송돼,
            유저는 다 보낸 줄 알고 모델은 잘린 사연으로 판단한다. 넘치게 두고 얼마나 줄여야
            하는지 알려준 뒤 전송만 막는다 */}
        <div className={styles.inputBar}>
          <textarea
            ref={inputRef}
            className={styles.input}
            placeholder={
              cooldown > 0
                ? `${cooldown}초 후 다시 보낼 수 있습니다`
                : waiting
                  ? '답변을 기다리는 중…'
                  : asking
                    ? '답변 입력'
                    : '메시지 입력'
            }
            rows={1}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={onKeyDown}
            disabled={waiting || cooldown > 0}
          />
          <button
            className={styles.send}
            /* 묻는 중에는 보내지 않고 받아둔다 — 마지막 질문에서 한 번에 나간다 */
            onClick={() => (asking ? answerAsk(input) : void handleSend())}
            disabled={!input.trim() || waiting || cooldown > 0 || blocked}
            aria-label={waiting ? '답변 생성 중' : '보내기'}
          >
            {waiting ? (
              // 생성 중 표시(정지 아님 — 서버가 fire-and-forget이라 중단 API는 아직 없다)
              <svg width="19" height="19" viewBox="0 0 24 24" fill="none">
                <rect x="7" y="7" width="10" height="10" rx="2" fill="#1a1a1d" />
              </svg>
            ) : (
              <svg width="19" height="19" viewBox="0 0 24 24" fill="none">
                <path
                  d="M12 19V6M6 12l6-6 6 6"
                  stroke="#1a1a1d"
                  strokeWidth="2.1"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </svg>
            )}
          </button>
        </div>

        {showProfile && <CharacterProfile onClose={() => setShowProfile(false)} />}

        {showDrawer && <StoryDrawer currentStoryId={storyId} onClose={() => setShowDrawer(false)} />}

        {/* 새 사건은 내용이 있어야 답할 수 있다. 되묻기를 상담자 대신 화면이 먼저 하고
            유저가 쓴 내용만 말풍선으로 들어간다 — 두 턴을 한 턴으로 줄인다 */}
        {chipInput && (
          <ChipInputSheet
            chip={chipInput}
            max={MAX_LENGTH}
            onClose={() => setChipInput(null)}
            onSubmit={(text) => {
              const chip = chipInput;
              setChipInput(null);
              void handleSend(text, chip.id);
            }}
          />
        )}

        {chipCatalog && (
          <ChipCatalogSheet storyId={storyId} onPick={pickChip} onClose={() => setChipCatalog(false)} />
        )}

        {showHelp && (
          <HelpModal
            title="채팅 가이드"
            onClose={() => setShowHelp(false)}
            /* 게스트에겐 서랍과 충전이 없다 — 없는 버튼을 가리키는 안내는 길을 잃게 만든다 */
            sections={[
              {
                heading: '기억',
                text: isGuest
                  ? '이 방에서 나눈 대화로 이야기와 기억이 쌓이고, 분석이 정확해집니다. 한 방에는 한 사람과의 이별 이야기만 담아 주세요. 여러 사람 이야기가 섞이면 분석이 부정확해집니다.'
                  : '이 방에서 나눈 대화로 이야기와 기억이 쌓이고, 분석이 정확해집니다. 기억은 방마다 따로 관리됩니다. 다른 사람 이야기를 하고 싶을 때는 물론, 지금 이야기를 접고 처음부터 새로 시작하고 싶을 때도 오른쪽 위 서랍에서 새 방을 만들어 주세요.',
              },
              {
                heading: '대화 횟수',
                text: isGuest
                  ? '메시지를 보내고 답을 받으면 대화 1회가 차감됩니다. 길이는 상관없어서 사연을 한 번에 길게 적어도 1회입니다(한 번에 2000자까지). 답을 받지 못한 대화는 차감되지 않습니다. 체험 횟수를 다 쓴 뒤에도 계정을 연결하면 지금까지의 대화를 그대로 이어갈 수 있습니다.'
                  : '메시지를 보내고 답을 받으면 대화 1회가 차감됩니다. 길이는 상관없어서 사연을 한 번에 길게 적어도 1회입니다(한 번에 2000자까지). 답을 받지 못한 대화는 차감되지 않습니다. 횟수는 기한 없이 남아 있고, 다 쓰면 위 카드 모양 버튼에서 충전할 수 있습니다.',
              },
              {
                heading: '분석',
                text: isGuest
                  ? '계정을 연결하면 분석이 열립니다. 지금까지의 대화를 근거로 재회 가능성을 백분율로 계산하고, 왜 그 숫자가 나왔는지 유리하게 본 요인과 불리하게 본 요인을 근거와 함께 보여줍니다. 비슷한 상황이었던 실제 사례도 함께 찾아드립니다.'
                  : '오른쪽 위 분석 버튼을 누르면 지금까지의 대화를 근거로 재회 가능성을 백분율로 계산합니다. 왜 그 숫자가 나왔는지 유리하게 본 요인과 불리하게 본 요인을 근거와 함께 보여주고, 비슷한 상황이었던 실제 사례도 함께 찾아드립니다.',
              },
              {
                heading: '분석이 정확해지려면',
                text: '분석은 대화에 나온 사실만 근거로 삼습니다. 이별 후 상대의 반응, 연락이 오갔는지, 상대에게 새로 만나는 사람이 있는지처럼 판단을 뒤집는 사실을 대화에서 말해 주면 결과가 달라집니다. 대화가 쌓인 뒤 다시 분석하면 확률도 다시 계산됩니다.',
              },
            ]}
          />
        )}
      </div>
    </PhoneFrame>
  );
}
