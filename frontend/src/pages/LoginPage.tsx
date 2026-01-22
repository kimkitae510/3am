import { useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { NightSky } from '../components/NightSky';
import { login, oauthLogin, type OAuthProvider } from '../api/auth';
import { extractErrorMessage } from '../api/client';
import { redirectUriFor, startSocialLogin } from '../utils/socialAuth';
import styles from './LoginPage.module.css';

function Clock() {
  // 월페이퍼 컨셉: 새벽 세시를 가리키는 시계 하나, 초침만 흐른다
  return (
    <div className={styles.clockBrand}>
      <svg viewBox="0 0 120 120" width="132" height="132" aria-label="새벽 세시">
        <circle cx="60" cy="60" r="56" fill="none" stroke="#2A2833" strokeWidth="1.5" />
        <line x1="60" y1="9" x2="60" y2="17" stroke="#4A4754" strokeWidth="2" strokeLinecap="round" />
        <line x1="111" y1="60" x2="103" y2="60" stroke="#4A4754" strokeWidth="2" strokeLinecap="round" />
        <line x1="60" y1="111" x2="60" y2="103" stroke="#4A4754" strokeWidth="2" strokeLinecap="round" />
        <line x1="9" y1="60" x2="17" y2="60" stroke="#4A4754" strokeWidth="2" strokeLinecap="round" />
        <line x1="60" y1="60" x2="84" y2="60" stroke="#ECEAF0" strokeWidth="3.4" strokeLinecap="round" />
        <line x1="60" y1="60" x2="60" y2="24" stroke="#ECEAF0" strokeWidth="2.4" strokeLinecap="round" />
        <g className={styles.secondHand}>
          <line x1="60" y1="67" x2="60" y2="15" stroke="#B89DD1" strokeWidth="1.3" strokeLinecap="round" />
        </g>
        <circle cx="60" cy="60" r="2.8" fill="#B89DD1" />
      </svg>
      <div className={styles.titleAm}>3AM</div>
    </div>
  );
}

export function LoginPage() {
  const navigate = useNavigate();
  // 가입 직후 진입이면 선물 안내를 보여준다(새로고침하면 state가 사라져 자연 소멸).
  const welcomeGift = Boolean((useLocation().state as { welcomeGift?: boolean } | null)?.welcomeGift);
  // 첫 화면은 폼 없는 랜딩 — 서비스가 뭔지 보기 전에 계정부터 요구하지 않는다.
  // 가입 직후엔 이메일 로그인이 목적이므로 이메일 모드로 바로 연다(선물 안내도 거기 있다).
  const [mode, setMode] = useState<'landing' | 'email'>(welcomeGift ? 'email' : 'landing');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const canSubmit = email.trim() !== '' && password !== '' && !submitting;

  async function handleSocial(provider: OAuthProvider) {
    setError('');
    if (startSocialLogin(provider) === 'redirected') return;
    // 키 미설정(개발) — 백엔드 mock 프로바이더로 바로 교환한다. 같은 code라 항상 같은 개발 계정.
    try {
      await oauthLogin(provider, { code: `dev-${provider}`, redirectUri: redirectUriFor(provider) });
      navigate('/stories');
    } catch (err) {
      setError(extractErrorMessage(err, '소셜 로그인에 실패했어요.'));
    }
  }

  async function handleLogin(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    setError('');
    setSubmitting(true);
    try {
      await login({ email: email.trim(), password });
      navigate('/stories');
    } catch (err) {
      setError(extractErrorMessage(err, '로그인에 실패했어요. 이메일과 비밀번호를 확인해 주세요.'));
    } finally {
      setSubmitting(false);
    }
  }

  if (mode === 'landing') {
    return (
      <PhoneFrame>
        <NightSky />
        <div className={`${styles.body} ${styles.aboveSky}`}>
          <Clock />
          <p className={styles.tagline}>잠이 안 오는 새벽, 이야기가 필요한 시간</p>

          <div className={styles.spacer} />

          <div className={styles.error}>{error}</div>
          <p className={styles.giftNote}>처음 시작하면 무료 대화 5회와 진단 1회를 드려요</p>
          <button className={styles.kakao} type="button" onClick={() => handleSocial('kakao')}>
            카카오로 3초 만에 시작하기
          </button>
          <button className={styles.naver} type="button" onClick={() => handleSocial('naver')}>
            네이버로 시작하기
          </button>
          <p className={styles.socialNote}>카카오와 네이버에 아무것도 공유되지 않아요</p>
          <button className={styles.emailEntry} type="button" onClick={() => { setError(''); setMode('email'); }}>
            이메일로 계속하기
          </button>
        </div>
      </PhoneFrame>
    );
  }

  return (
    <PhoneFrame>
      <NightSky />
      <form className={`${styles.body} ${styles.aboveSky}`} onSubmit={handleLogin}>
        <button
          type="button"
          className={styles.backLanding}
          onClick={() => { setError(''); setMode('landing'); }}
          aria-label="처음으로"
        >
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
            <path d="M15 5l-7 7 7 7" stroke="#9B98A3" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </button>

        <Clock />

        <div className={styles.spacer} />

        <div className={styles.fields}>
          <div className={styles.field}>
            <input
              className={styles.input}
              type="email"
              inputMode="email"
              autoComplete="email"
              placeholder="이메일"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
          </div>
          <div className={styles.field}>
            <input
              className={styles.input}
              type={showPassword ? 'text' : 'password'}
              autoComplete="current-password"
              placeholder="비밀번호"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
            <button
              type="button"
              className={styles.eyeButton}
              onClick={() => setShowPassword((v) => !v)}
              aria-label={showPassword ? '비밀번호 숨기기' : '비밀번호 표시'}
            >
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
                <path
                  d="M2 12s3.5-6.5 10-6.5S22 12 22 12s-3.5 6.5-10 6.5S2 12 2 12z"
                  stroke="#6E6B76"
                  strokeWidth="1.5"
                />
                <circle cx="12" cy="12" r="2.4" stroke="#6E6B76" strokeWidth="1.5" />
              </svg>
            </button>
          </div>
        </div>

        {welcomeGift && !error ? (
          <div className={styles.notice}>
            가입을 환영해요. 선물로 대화 5회, 진단 1회 이용권을 담아뒀어요.
          </div>
        ) : (
          <div className={styles.error}>{error}</div>
        )}

        <button className={styles.primary} type="submit" disabled={!canSubmit}>
          {submitting ? '로그인 중…' : '로그인'}
        </button>
        <button
          className={styles.secondary}
          type="button"
          onClick={() => navigate('/signup')}
        >
          이메일로 가입하기
        </button>

      </form>
    </PhoneFrame>
  );
}
