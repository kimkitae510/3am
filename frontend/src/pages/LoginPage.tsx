import { useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { login, oauthLogin, type OAuthProvider } from '../api/auth';
import { extractErrorMessage } from '../api/client';
import { redirectUriFor, startSocialLogin } from '../utils/socialAuth';
import styles from './LoginPage.module.css';

export function LoginPage() {
  const navigate = useNavigate();
  // 가입 직후 진입이면 선물 안내를 보여준다(새로고침하면 state가 사라져 자연 소멸).
  const welcomeGift = Boolean((useLocation().state as { welcomeGift?: boolean } | null)?.welcomeGift);
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

  return (
    <PhoneFrame>
      <form className={styles.body} onSubmit={handleLogin}>
        <div className={styles.brand}>
          <div className={styles.logo}>
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none">
              <circle cx="12" cy="12" r="8.5" stroke="#ECEAF0" strokeWidth="1.6" />
              <path d="M12 12V7.5M12 12l3 1.8" stroke="#ECEAF0" strokeWidth="1.6" strokeLinecap="round" />
            </svg>
          </div>
          <div className={styles.title}>새벽 세시</div>
          <div className={styles.subtitle}>
            이별한 마음, 밤에 혼자
            <br />
            삼키지 않게 도와주는 채팅
          </div>
        </div>

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
        <button className={styles.kakao} type="button" onClick={() => handleSocial('kakao')}>
          카카오로 시작하기
        </button>
        <button className={styles.naver} type="button" onClick={() => handleSocial('naver')}>
          네이버로 시작하기
        </button>
        <button
          className={styles.secondary}
          type="button"
          onClick={() => navigate('/signup')}
        >
          이메일로 가입하기
        </button>

        <div className={styles.footer}>잠 안 오는 밤에 열어보세요.</div>
      </form>
    </PhoneFrame>
  );
}
