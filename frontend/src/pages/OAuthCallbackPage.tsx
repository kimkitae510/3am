import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { oauthLogin, SIGNUP_CONSENTS, type OAuthProvider } from '../api/auth';
import { extractErrorMessage } from '../api/client';
import { consumeStoredState } from '../utils/socialAuth';
import styles from './LoginPage.module.css';

// 카카오/네이버 인가 후 도착지. 코드를 서버로 넘겨 토큰을 받고 목록으로 보낸다.
export function OAuthCallbackPage() {
  const navigate = useNavigate();
  const { provider } = useParams();
  const [searchParams] = useSearchParams();
  const [error, setError] = useState('');
  // 인가 코드는 1회용이라 StrictMode의 이펙트 이중 실행이 곧 이중 교환 실패 — ref로 한 번만 돌린다.
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;

    if (provider !== 'kakao' && provider !== 'naver') {
      setError('지원하지 않는 로그인 방식이에요.');
      return;
    }
    const denied = searchParams.get('error');
    if (denied) {
      setError('로그인이 취소됐어요.');
      return;
    }
    const code = searchParams.get('code');
    const state = searchParams.get('state');
    const stored = consumeStoredState();
    if (!code) {
      setError('인가 코드를 받지 못했어요. 다시 시도해 주세요.');
      return;
    }
    if (stored.state && state !== stored.state) {
      setError('요청 확인에 실패했어요. 다시 시도해 주세요.');
      return;
    }

    oauthLogin(provider as OAuthProvider, {
      code,
      state: state ?? undefined,
      redirectUri: stored.redirectUri ?? window.location.origin + window.location.pathname,
      // 인가 페이지로 넘어왔다는 것 자체가 로그인 화면의 동의 시트를 통과했다는 뜻 —
      // 신규 가입이면 서버가 이 동의를 기록하고, 기존 계정이면 무시한다.
      consents: [...SIGNUP_CONSENTS],
    })
      .then(() => navigate('/stories', { replace: true }))
      .catch((err) => setError(extractErrorMessage(err, '소셜 로그인에 실패했어요.')));
  }, [provider, searchParams, navigate]);

  return (
    <PhoneFrame>
      <div className={styles.landBg} />
      <div className={`${styles.body} ${styles.aboveSky}`}>
        <div className={styles.brand}>
          <div className={styles.title}>새벽 세시</div>
          <div className={styles.subtitle}>{error ? '로그인에 문제가 생겼어요.' : '로그인하는 중이에요…'}</div>
        </div>
        <div className={styles.spacer} />
        <div className={styles.error}>{error}</div>
        {error && (
          <button className={styles.primary} type="button" onClick={() => navigate('/login', { replace: true })}>
            로그인 화면으로
          </button>
        )}
      </div>
    </PhoneFrame>
  );
}
