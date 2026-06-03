import { useState } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { BusinessInfo } from '../components/BusinessInfo';
import { CHARACTER_AVATAR, CHARACTER_NAME, CharacterProfile } from '../components/CharacterProfile';
import { guestStart } from '../api/auth';
import { tokenStore } from '../api/tokenStore';
import { createStory } from '../api/story';
import { extractErrorMessage } from '../api/client';
import styles from './IntroPage.module.css';

// 첫 화면이 로그인 폼이면 이게 뭐 하는 서비스인지 전달이 하나도 안 된다. 이별 서비스는
// 충동적으로 들어오는데 폼이 뜨면 그 자리에서 나간다. 그래서 첫 화면을 대화 시작점으로 둔다.
//
// 계정은 여기서 만들지 않는다 — 진입만으로 계정을 파면 크롤러가 긁을 때마다 계정이 생긴다.
// 실제로 말을 걸었을 때, 즉 첫 전송 시점에 만든다.
export function IntroPage() {
  const navigate = useNavigate();
  const [input, setInput] = useState('');
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState('');
  const [showProfile, setShowProfile] = useState(false);
  const [hasPortrait, setHasPortrait] = useState(true);

  // 토큰을 들고 온 재방문자는 인사를 다시 볼 이유가 없다 — 보던 대화로 바로 보낸다
  if (tokenStore.getAccess()) return <Navigate to="/stories" replace />;

  async function handleStart() {
    const content = input.trim();
    if (!content || starting) return;
    setStarting(true);
    setError('');
    try {
      await guestStart();
      const story = await createStory();
      // 첫 문장을 다시 치게 하면 시작이 두 번이 된다 — 채팅 화면이 받아서 그대로 보낸다
      navigate(`/stories/${story.id}`, { replace: true, state: { prefill: content, autoSend: true } });
    } catch (e) {
      setError(extractErrorMessage(e, '시작하지 못했습니다. 잠시 후 다시 시도해 주세요.'));
      setStarting(false);
    }
  }

  return (
    <PhoneFrame>
      <div className={styles.wrap}>
        <button className={styles.identity} onClick={() => setShowProfile(true)}>
          {hasPortrait && (
            <div className={styles.portraitFrame}>
              <img
                className={styles.portrait}
                src={CHARACTER_AVATAR}
                alt={CHARACTER_NAME}
                onError={() => setHasPortrait(false)}
              />
            </div>
          )}
          <span className={styles.name}>
            {CHARACTER_NAME}
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M9 5l7 7-7 7" stroke="#98989f" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </span>
        </button>

        <div className={styles.bubble}>
          이런 시간에 잠이 안 오는구나.
          <br />
          무슨 일 있었는지 말해줄래?
        </div>

        {error && <div className={styles.error}>{error}</div>}

        <div className={styles.composer}>
          <textarea
            className={styles.input}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="여기에 적어주세요"
            rows={3}
            disabled={starting}
          />
          <button className={styles.send} onClick={handleStart} disabled={!input.trim() || starting}>
            {starting ? '시작하는 중…' : '이야기 시작하기'}
          </button>
        </div>

        {/* 랜딩을 없애면 다른 기기에서 온 회원은 자기 대화가 사라진 걸로 본다 — 입구를 여기 둔다 */}
        <button className={styles.loginLink} onClick={() => navigate('/login')}>
          이미 계정이 있어요
        </button>

        {/* 전자상거래법상 초기화면 표시 의무 — 랜딩을 없앤 뒤로 이 화면이 초기화면이다 */}
        <BusinessInfo />

        {showProfile && <CharacterProfile onClose={() => setShowProfile(false)} />}
      </div>
    </PhoneFrame>
  );
}
