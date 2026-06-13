import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { SwitchConfirmSheet } from '../components/SwitchConfirmSheet';
import { getMe, login } from '../api/auth';
import { SocialLogin } from '../components/SocialLogin';
import { extractErrorMessage } from '../api/client';
import { tokenStore } from '../api/tokenStore';
import styles from './LoginPage.module.css';

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
  // 게스트 → 기존 계정 전환 확인. 소셜은 SocialLogin이 서버 티켓으로 처리하고,
  // 이메일은 로그인 시도 자체가 기존 계정 보유를 뜻하므로 서버 왕복 없이 프론트에서 경고한다.
  const [showEmailSwitchWarn, setShowEmailSwitchWarn] = useState(false);
  const [isGuest, setIsGuest] = useState(false);

  // 게스트 토큰을 든 채 랜딩에 온 경우를 감지 — 기존 계정 로그인 시 데이터 유실 경고의 근거.
  useEffect(() => {
    if (!tokenStore.getAccess()) return;
    getMe()
      .then((me) => setIsGuest(me.provider === 'GUEST'))
      .catch(() => setIsGuest(false)); // 만료 토큰 등 — 게스트 아님으로 취급
  }, []);
  const canSubmit = email.trim() !== '' && password !== '' && !submitting;

  // 게스트 시작은 첫 화면(IntroPage)이 맡는다 — 여기는 재방문자의 로그인 자리다.
  // 되살릴 일이 생기면 guestStart()를 부르고 /stories로 보내면 된다.

  async function handleLogin(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    // 게스트가 기존 이메일 계정으로 로그인 — 로그인 시도 자체가 기존 계정 보유를 뜻하므로
    // 서버 확인 없이도 게스트 대화 유실을 먼저 경고할 수 있다.
    if (isGuest) {
      setShowEmailSwitchWarn(true);
      return;
    }
    await doLogin();
  }

  async function doLogin() {
    setError('');
    setSubmitting(true);
    try {
      await login({ email: email.trim(), password });
      navigate('/stories');
    } catch (err) {
      setError(extractErrorMessage(err, '로그인에 실패했습니다. 이메일과 비밀번호를 확인해 주세요.'));
    } finally {
      setSubmitting(false);
    }
  }

  if (mode === 'landing') {
    return (
      <PhoneFrame>
        {/* 로그인 수단 두 개가 화면의 전부다. 로고 락업과 사업자정보는 첫 화면(IntroPage)이
            이미 하고 있어서, 여기 또 두면 서비스 소개를 두 번 받는 꼴이 된다.
            게스트 진입도 첫 화면 몫이다 — 여기는 "이미 계정이 있어요"로 온 사람의 자리다 */}
        <div className={styles.landing}>
          <div className={`${styles.error} ${styles.landError}`}>{error}</div>
          <div className={styles.landButtons}>
            {/* 이메일 계정 진입은 화면에서 내렸다 — 지메일은 점과 +태그를 무시해 한 편지함으로
                주소를 무한히 변형할 수 있어, 인증 코드가 선물 어뷰징을 못 막는다. 카카오와
                네이버는 뒤에 본인인증이 걸려 있어 계정을 새로 파는 비용이 훨씬 크다.
                이메일 화면과 가입 흐름(mode === 'email', /signup)은 그대로 살아 있다 */}
            <SocialLogin variant="wide" onError={setError} />
          </div>

          <div className={styles.docLinks}>
            <button className={styles.docLink} type="button" onClick={() => navigate('/terms')}>
              이용약관
            </button>
            <button className={styles.docLink} type="button" onClick={() => navigate('/privacy')}>
              개인정보처리방침
            </button>
          </div>
        </div>
      </PhoneFrame>
    );
  }

  return (
    <PhoneFrame>
      <form className={styles.body} onSubmit={handleLogin}>
        <button
          type="button"
          className={styles.backLanding}
          onClick={() => { setError(''); setMode('landing'); }}
          aria-label="처음으로"
        >
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
            <path d="M15 5l-7 7 7 7" stroke="#ebebee" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </button>

        <div className={styles.spacerTop} />

        <div className={`${styles.brand} ${styles.brandLogin}`}>
          <div className={styles.title}>로그인</div>
        </div>

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
                  stroke="#8a8a91"
                  strokeWidth="1.5"
                />
                <circle cx="12" cy="12" r="2.4" stroke="#8a8a91" strokeWidth="1.5" />
              </svg>
            </button>
          </div>
        </div>

        {welcomeGift && !error ? (
          <div className={styles.notice}>
            가입을 환영합니다. 선물로 대화 3회, 분석 1회 이용권을 넣어 두었습니다.
          </div>
        ) : (
          <div className={styles.error}>{error}</div>
        )}

        <button className={styles.primary} type="submit" disabled={!canSubmit}>
          {submitting ? '로그인 중…' : '로그인'}
        </button>
        <div className={styles.linkRow}>
          <button className={styles.textLink} type="button" onClick={() => navigate('/signup')}>
            회원가입
          </button>
        </div>

        <div className={styles.spacer} />

        {showEmailSwitchWarn && (
          <SwitchConfirmSheet
            title="둘러보기로 대화 중입니다"
            message="이 계정으로 로그인하면 지금까지 게스트로 나눈 대화는 가져올 수 없습니다. 게스트 대화를 이어가려면 로그인 대신 계정 연결을 이용해 주세요."
            confirmLabel="게스트 대화 포기하고 로그인"
            submitting={submitting}
            onConfirm={() => { setShowEmailSwitchWarn(false); void doLogin(); }}
            onCancel={() => setShowEmailSwitchWarn(false)}
          />
        )}
      </form>
    </PhoneFrame>
  );
}
