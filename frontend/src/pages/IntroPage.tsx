import { useState } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { BusinessInfo } from '../components/BusinessInfo';
import { SocialLogin } from '../components/SocialLogin';
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
        {/* 첫 화면은 캐릭터가 말을 거는 대화창이 아니라 무엇을 파는지 밝히는 자리다.
            결제 대행사 심사는 이 화면만 보고 제품을 분류하는데, 캐릭터와 말풍선이 먼저 뜨면
            리포트를 파는 소프트웨어가 아니라 대화 상대를 파는 것으로 읽힌다.
            캐릭터는 '이야기 시작하기' 뒤 대화방에서 그대로 만난다 */}
        <div className={styles.intro}>
          <div className={styles.logo}>3am</div>
          <h1 className={styles.headline}>지난 연애를 정리하는 분석 리포트</h1>
          <p className={styles.lead}>
            이별까지의 상황을 적으면, 이별의 유형과 무엇이 작용했는지를 정리해 글로
            돌려드립니다.
          </p>
          <ul className={styles.points}>
            <li>이별의 유형과 유리하게, 불리하게 작용한 요인</li>
            <li>비슷한 상황의 익명 사례</li>
            <li>이어서 상황을 정리해 나가는 대화</li>
          </ul>
        </div>

        {error && <div className={styles.error}>{error}</div>}

        <div className={styles.composer}>
          <div className={styles.composerLabel}>
            이별까지의 흐름과 현재 상황을 자세히 적어주시면 더 정확하게 분석해 드립니다
          </div>
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

        {/* 랜딩을 없애면 다른 기기에서 온 회원은 자기 대화가 사라진 걸로 본다 — 입구를 여기 둔다.
            글자 링크로 한 번 더 넘기는 대신 심볼을 바로 놓아 재방문자의 홉을 하나 줄인다.
            선을 하나 긋는다 — 위는 처음 온 사람의 자리, 아래는 이미 계정이 있는 사람의 자리다.
            구분이 없으면 소셜 아이콘이 '이야기 시작하기'의 다른 방법처럼 읽힌다 */}
        <div className={styles.divider} />
        <SocialLogin variant="icon" onError={setError} />

        {/* 결제 대행사 심사와 전자상거래법이 요구하는 상시 링크. 심사자는 계정을 만들지 않고
            이 화면만 보므로, 로그인 뒤에만 있으면 없는 것과 같다. 히어로를 해치지 않게
            로그인 화면과 같은 결로 아래에 조용히 둔다 */}
        <div className={styles.docLinks}>
          {/* 라벨에 "환불"을 넣어둔다 — 심사자는 약관, 환불정책, 개인정보처리방침 세 가지를
              찾는데, "이용권 안내"만 적혀 있으면 그 안에 환불정책이 있는 걸 모르고 지나친다 */}
          {/* button이 아니라 a로 둔다 — 심사는 사람이 아니라 크롤러가 하고, onClick만 있는
              button은 따라갈 링크가 없어 정책 페이지를 못 찾는다. href로 발견되게 하되
              이동은 라우터가 처리해 전체 새로고침을 막는다 */}
          <a
            className={styles.docLink}
            href="/pricing"
            onClick={(e) => { e.preventDefault(); navigate('/pricing'); }}
          >
            이용권과 환불
          </a>
          <a
            className={styles.docLink}
            href="/terms"
            onClick={(e) => { e.preventDefault(); navigate('/terms'); }}
          >
            이용약관
          </a>
          <a
            className={styles.docLink}
            href="/privacy"
            onClick={(e) => { e.preventDefault(); navigate('/privacy'); }}
          >
            개인정보처리방침
          </a>
        </div>

        {/* 전자상거래법상 초기화면 표시 의무 — 랜딩을 없앤 뒤로 이 화면이 초기화면이다 */}
        <BusinessInfo />

      </div>
    </PhoneFrame>
  );
}
