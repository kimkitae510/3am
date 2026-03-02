import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { SwitchConfirmSheet } from '../components/SwitchConfirmSheet';
import {
  confirmOAuthSwitch,
  getMe,
  guestStart,
  login,
  oauthLogin,
  SIGNUP_CONSENTS,
  type OAuthProvider,
} from '../api/auth';
import { extractErrorMessage } from '../api/client';
import { tokenStore } from '../api/tokenStore';
import { redirectUriFor, startSocialLogin } from '../utils/socialAuth';
import styles from './LoginPage.module.css';

// 소셜은 첫 로그인이 곧 가입이라 인가로 넘어가기 전에 동의를 받는다.
// 이 기기에서 한 번 동의했으면 다음부터 시트를 생략한다(서버는 신규 가입일 때만 검사).
const SOCIAL_CONSENT_KEY = 'social-consent-v1';

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
  // 동의 시트가 열려 있으면 어느 소셜로 이어갈지 기억한다
  const [consentFor, setConsentFor] = useState<OAuthProvider | null>(null);
  // 게스트 → 기존 계정 전환 확인. 소셜은 서버가 내린 티켓, 이메일은 프론트 확인만으로 충분하다
  // (로그인 시도 자체가 기존 계정 보유를 뜻하므로 서버 왕복 없이 경고할 수 있다).
  const [switchTicket, setSwitchTicket] = useState<string | null>(null);
  const [switching, setSwitching] = useState(false);
  const [showEmailSwitchWarn, setShowEmailSwitchWarn] = useState(false);
  const [isGuest, setIsGuest] = useState(false);

  // 게스트 토큰을 든 채 랜딩에 온 경우를 감지 — 기존 계정 로그인 시 데이터 유실 경고의 근거.
  useEffect(() => {
    if (!tokenStore.getAccess()) return;
    getMe()
      .then((me) => setIsGuest(me.provider === 'GUEST'))
      .catch(() => setIsGuest(false)); // 만료 토큰 등 — 게스트 아님으로 취급
  }, []);
  const [agreeTerms, setAgreeTerms] = useState(false);
  const [agreePrivacy, setAgreePrivacy] = useState(false);
  const [agreeSensitive, setAgreeSensitive] = useState(false);
  const [agreeDisclaimer, setAgreeDisclaimer] = useState(false);
  const allAgreed = agreeTerms && agreePrivacy && agreeSensitive && agreeDisclaimer;

  const canSubmit = email.trim() !== '' && password !== '' && !submitting;

  function handleSocial(provider: OAuthProvider) {
    setError('');
    if (!localStorage.getItem(SOCIAL_CONSENT_KEY)) {
      setConsentFor(provider);
      return;
    }
    void proceedSocial(provider);
  }

  async function proceedSocial(provider: OAuthProvider) {
    if (startSocialLogin(provider) === 'redirected') return;
    // 키 미설정(개발) — 백엔드 mock 프로바이더로 바로 교환한다. 같은 code라 항상 같은 개발 계정.
    try {
      const result = await oauthLogin(provider, {
        code: `dev-${provider}`,
        redirectUri: redirectUriFor(provider),
        consents: [...SIGNUP_CONSENTS],
      });
      // 게스트가 이미 가입된 소셜 계정으로 로그인 — 게스트 대화를 잃는 전환이라 확인을 거친다
      if (result.switchTicket) {
        setSwitchTicket(result.switchTicket);
        return;
      }
      navigate('/stories');
    } catch (err) {
      setError(extractErrorMessage(err, '소셜 로그인에 실패했어요.'));
    }
  }

  async function handleConfirmSwitch() {
    if (!switchTicket) return;
    setSwitching(true);
    try {
      await confirmOAuthSwitch(switchTicket);
      navigate('/stories');
    } catch (err) {
      setSwitchTicket(null);
      setError(extractErrorMessage(err, '계정 전환에 실패했어요. 다시 시도해 주세요.'));
    } finally {
      setSwitching(false);
    }
  }

  function agreeAndStart() {
    if (!allAgreed || !consentFor) return;
    localStorage.setItem(SOCIAL_CONSENT_KEY, '1');
    const provider = consentFor;
    setConsentFor(null);
    void proceedSocial(provider);
  }

  async function handleGuest() {
    setError('');
    // 게스트는 동의 시트 없이 바로 시작(사용자 확정). 실질적 동의는 계정 연결 시점에 받는다.
    try {
      await guestStart();
      navigate('/stories');
    } catch (err) {
      setError(extractErrorMessage(err, '게스트로 시작하는 데 실패했어요.'));
    }
  }

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
      setError(extractErrorMessage(err, '로그인에 실패했어요. 이메일과 비밀번호를 확인해 주세요.'));
    } finally {
      setSubmitting(false);
    }
  }

  if (mode === 'landing') {
    return (
      <PhoneFrame>
        {/* 디자인 시안(LoginHome.jsx) 기준 — 월페이퍼 대신 라디얼 그라데이션 한 장 */}
        <div className={styles.landBg} />
        <div className={styles.landing}>
          {/* 로고 락업: 화면 세로 중심에 로고, 태그라인은 그 아래(인프런 결) */}
          <div className={styles.brandWrap}>
            <div className={styles.brandLogo}>
              <span className={styles.brandDigit}>3</span>am
            </div>
            <p className={styles.brandTagline}>이별 상담 및 재회진단 서비스</p>
          </div>

          <div className={`${styles.error} ${styles.landError}`}>{error}</div>
          <div className={styles.landButtons}>
            {/* 새벽에 충동적으로 들어온 사람이 계정부터 요구받지 않게 — 게스트가 메인 진입 */}
            <button
              className={`${styles.landBtn} ${styles.landStart}`}
              type="button"
              onClick={handleGuest}
            >
              로그인 없이 시작하기
            </button>

            <div className={styles.orDivider}>
              <span>이미 계정이 있나요?</span>
            </div>

            {/* 로그인 수단은 원형 아이콘 한 줄 — 카카오, 네이버, 이메일 */}
            <div className={styles.socialRow}>
              <button
                className={`${styles.circleBtn} ${styles.circleKakao}`}
                type="button"
                aria-label="카카오로 로그인"
                onClick={() => handleSocial('kakao')}
              >
                <svg width="24" height="22" viewBox="0 0 24 22" aria-hidden="true">
                  <path
                    d="M12 1 C5.9 1 1 4.9 1 9.7 c0 3.1 2 5.8 5.1 7.3 L5 21 l4.7-3 c.7 .1 1.5 .2 2.3 .2 6.1 0 11-3.9 11-8.5 C23 4.9 18.1 1 12 1 Z"
                    fill="#191600"
                  />
                </svg>
              </button>
              <button
                className={`${styles.circleBtn} ${styles.circleNaver}`}
                type="button"
                aria-label="네이버로 로그인"
                onClick={() => handleSocial('naver')}
              >
                <svg width="16" height="16" viewBox="0 0 18 18" aria-hidden="true">
                  <path d="M2 1 h4.6 l4.7 7 V1 H16 v16 h-4.6 L6.7 10 v7 H2 Z" fill="#fff" />
                </svg>
              </button>
              {/* 3am 자체 계정(이메일+비밀번호) — 봉투 아이콘은 메일 보내기로 읽혀서 로고 미니어처로 */}
              <button
                className={`${styles.circleBtn} ${styles.circleEmail}`}
                type="button"
                aria-label="3am 계정으로 로그인"
                onClick={() => { setError(''); setMode('email'); }}
              >
                <span className={styles.circleBrand} aria-hidden="true">
                  <span className={styles.circleBrandDigit}>3</span>am
                </span>
              </button>
            </div>
          </div>

          <div className={styles.docLinks}>
            <button className={styles.docLink} type="button" onClick={() => navigate('/terms')}>
              이용약관
            </button>
            <button className={styles.docLink} type="button" onClick={() => navigate('/privacy')}>
              개인정보처리방침
            </button>
          </div>

          {consentFor && (
            <div className={styles.sheetOverlay} onClick={() => setConsentFor(null)}>
              <div className={styles.sheet} onClick={(e) => e.stopPropagation()}>
                <div className={styles.sheetTitle}>시작하기 전에 확인해 주세요</div>
                <div className={styles.consentBox}>
                  <label className={`${styles.consentRow} ${styles.consentAll}`}>
                    <input
                      type="checkbox"
                      className={styles.consentCheck}
                      checked={allAgreed}
                      onChange={(e) => {
                        setAgreeTerms(e.target.checked);
                        setAgreePrivacy(e.target.checked);
                        setAgreeSensitive(e.target.checked);
                        setAgreeDisclaimer(e.target.checked);
                      }}
                    />
                    <span>모두 동의합니다</span>
                  </label>
                  <label className={styles.consentRow}>
                    <input
                      type="checkbox"
                      className={styles.consentCheck}
                      checked={agreeTerms}
                      onChange={(e) => setAgreeTerms(e.target.checked)}
                    />
                    <span>
                      (필수){' '}
                      <button
                        type="button"
                        className={styles.consentLink}
                        onClick={(e) => { e.preventDefault(); navigate('/terms'); }}
                      >
                        이용약관
                      </button>
                      에 동의합니다
                    </span>
                  </label>
                  <label className={styles.consentRow}>
                    <input
                      type="checkbox"
                      className={styles.consentCheck}
                      checked={agreePrivacy}
                      onChange={(e) => setAgreePrivacy(e.target.checked)}
                    />
                    <span>
                      (필수){' '}
                      <button
                        type="button"
                        className={styles.consentLink}
                        onClick={(e) => { e.preventDefault(); navigate('/privacy'); }}
                      >
                        개인정보 수집, 이용
                      </button>
                      에 동의합니다
                    </span>
                  </label>
                  <label className={styles.consentRow}>
                    <input
                      type="checkbox"
                      className={styles.consentCheck}
                      checked={agreeSensitive}
                      onChange={(e) => setAgreeSensitive(e.target.checked)}
                    />
                    <span>(필수) 이별, 연애 이야기(민감할 수 있는 정보) 수집, 이용에 동의합니다</span>
                  </label>
                  <label className={styles.consentRow}>
                    <input
                      type="checkbox"
                      className={styles.consentCheck}
                      checked={agreeDisclaimer}
                      onChange={(e) => setAgreeDisclaimer(e.target.checked)}
                    />
                    <span>
                      (필수) AI 답변은 참고 정보라는{' '}
                      <button
                        type="button"
                        className={styles.consentLink}
                        onClick={(e) => { e.preventDefault(); navigate('/terms'); }}
                      >
                        면책 고지
                      </button>
                      를 확인했습니다
                    </span>
                  </label>
                </div>
                <button
                  type="button"
                  className={styles.sheetAgree}
                  disabled={!allAgreed}
                  onClick={agreeAndStart}
                >
                  동의하고 시작하기
                </button>
              </div>
            </div>
          )}

          {switchTicket && (
            <SwitchConfirmSheet
              title="이미 가입된 계정이 있어요"
              message="이 소셜 계정은 이미 3am 회원이에요. 이 계정으로 로그인하면 지금까지 게스트로 나눈 대화는 가져올 수 없어요."
              confirmLabel="게스트 대화 포기하고 로그인"
              submitting={switching}
              onConfirm={() => void handleConfirmSwitch()}
              onCancel={() => setSwitchTicket(null)}
            />
          )}
        </div>
      </PhoneFrame>
    );
  }

  return (
    <PhoneFrame>
      <div className={styles.landBg} />
      <form className={`${styles.body} ${styles.aboveSky}`} onSubmit={handleLogin}>
        <button
          type="button"
          className={styles.backLanding}
          onClick={() => { setError(''); setMode('landing'); }}
          aria-label="처음으로"
        >
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
            <path d="M15 5l-7 7 7 7" stroke="#ECEAF0" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </button>

        <div className={styles.spacerTop} />

        {/* 랜딩의 원형 버튼이 "3am 계정으로 로그인"이 되면서 안쪽 제목도 같은 말로 —
            "이메일로 시작하기"는 들어온 문과 다른 이름이라 어긋났다 */}
        <div className={`${styles.brand} ${styles.brandLogin}`}>
          <div className={styles.title}>3am 계정으로 로그인</div>
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
                  stroke="#8B8A92"
                  strokeWidth="1.5"
                />
                <circle cx="12" cy="12" r="2.4" stroke="#8B8A92" strokeWidth="1.5" />
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
        <div className={styles.linkRow}>
          <button className={styles.textLink} type="button" onClick={() => navigate('/signup')}>
            회원가입
          </button>
        </div>

        <div className={styles.spacer} />

        {showEmailSwitchWarn && (
          <SwitchConfirmSheet
            title="게스트로 대화 중이에요"
            message="이 계정으로 로그인하면 지금까지 게스트로 나눈 대화는 가져올 수 없어요. 게스트 대화를 이어가려면 로그인 대신 계정 연결을 이용해 주세요."
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
