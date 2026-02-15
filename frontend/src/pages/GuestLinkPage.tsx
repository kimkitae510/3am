import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { TermsContent } from '../components/TermsContent';
import { PrivacyContent } from '../components/PrivacyContent';
import {
  linkGuestEmail,
  oauthLogin,
  requestEmailVerification,
  SIGNUP_CONSENTS,
  type OAuthProvider,
} from '../api/auth';
import { extractErrorMessage } from '../api/client';
import { redirectUriFor, startSocialLogin } from '../utils/socialAuth';
import styles from './LoginPage.module.css';

const RESEND_COOLDOWN_SECONDS = 60;

// 게스트 → 계정 연결. 소셜은 로그인 상태(토큰)로 그대로 인가를 태우면 서버가 게스트 행을 승격한다.
// 이메일은 가입과 같은 관문(인증 코드, 동의)을 거쳐 같은 계정을 이메일 계정으로 바꾼다.
// 어느 쪽이든 새 계정을 만들지 않아 지금까지의 대화가 그대로 이어진다.
export function GuestLinkPage() {
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [code, setCode] = useState('');
  const [codeSent, setCodeSent] = useState(false);
  const [sendingCode, setSendingCode] = useState(false);
  const [cooldown, setCooldown] = useState(0);
  const [password, setPassword] = useState('');
  const [agreeTerms, setAgreeTerms] = useState(false);
  const [agreePrivacy, setAgreePrivacy] = useState(false);
  const [agreeSensitive, setAgreeSensitive] = useState(false);
  const [agreeDisclaimer, setAgreeDisclaimer] = useState(false);
  const [showDoc, setShowDoc] = useState<'terms' | 'privacy' | null>(null);
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const cooldownTimer = useRef<number | null>(null);

  useEffect(() => {
    return () => {
      if (cooldownTimer.current !== null) window.clearInterval(cooldownTimer.current);
    };
  }, []);

  function startCooldown() {
    setCooldown(RESEND_COOLDOWN_SECONDS);
    cooldownTimer.current = window.setInterval(() => {
      setCooldown((s) => {
        if (s <= 1 && cooldownTimer.current !== null) {
          window.clearInterval(cooldownTimer.current);
          cooldownTimer.current = null;
        }
        return Math.max(0, s - 1);
      });
    }, 1000);
  }

  async function proceedSocial(provider: OAuthProvider) {
    // 인가 페이지로 리다이렉트되면 토큰이 localStorage에 남아 콜백에서 그대로 승격된다.
    if (startSocialLogin(provider) === 'redirected') return;
    // 키 미설정(개발) — 토큰을 지닌 채 mock 교환하면 서버가 게스트를 승격한다.
    try {
      await oauthLogin(provider, {
        code: `dev-${provider}`,
        redirectUri: redirectUriFor(provider),
        consents: [...SIGNUP_CONSENTS],
      });
      navigate('/stories');
    } catch (err) {
      setError(extractErrorMessage(err, '계정 연결에 실패했어요.'));
    }
  }

  const canRequestCode = email.trim() !== '' && cooldown === 0 && !sendingCode;

  async function handleRequestCode() {
    if (!canRequestCode) return;
    setError('');
    setSendingCode(true);
    try {
      await requestEmailVerification(email.trim());
      setCodeSent(true);
      startCooldown();
    } catch (err) {
      setError(extractErrorMessage(err, '인증 메일 발송에 실패했어요.'));
    } finally {
      setSendingCode(false);
    }
  }

  const allAgreed = agreeTerms && agreePrivacy && agreeSensitive && agreeDisclaimer;

  function setAllAgreed(value: boolean) {
    setAgreeTerms(value);
    setAgreePrivacy(value);
    setAgreeSensitive(value);
    setAgreeDisclaimer(value);
  }

  const canSubmit =
    email.trim() !== '' &&
    /^\d{6}$/.test(code) &&
    password.length >= 8 &&
    allAgreed &&
    !submitting;

  async function handleLink(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    setError('');
    setSubmitting(true);
    try {
      await linkGuestEmail({
        email: email.trim(),
        password,
        verificationCode: code,
        consents: [...SIGNUP_CONSENTS],
      });
      navigate('/stories');
    } catch (err) {
      setError(extractErrorMessage(err, '계정 연결에 실패했어요.'));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <PhoneFrame>
      <div className={styles.landBg} />
      <form className={`${styles.body} ${styles.aboveSky}`} onSubmit={handleLink}>
        <button type="button" className={styles.backTop} onClick={() => navigate(-1)} aria-label="뒤로">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
            <path d="M15 5l-7 7 7 7" stroke="#ECEAF0" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </button>
        <div className={styles.brand}>
          <div className={styles.title}>계정 연결하기</div>
          <div className={styles.subtitle}>지금까지의 대화를 그대로 이어가요</div>
        </div>

        <div className={styles.socialRow}>
          <button
            className={`${styles.circleBtn} ${styles.circleKakao}`}
            type="button"
            aria-label="카카오로 연결하기"
            onClick={() => proceedSocial('kakao')}
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
            aria-label="네이버로 연결하기"
            onClick={() => proceedSocial('naver')}
          >
            <svg width="16" height="16" viewBox="0 0 18 18" aria-hidden="true">
              <path d="M2 1 h4.6 l4.7 7 V1 H16 v16 h-4.6 L6.7 10 v7 H2 Z" fill="#fff" />
            </svg>
          </button>
        </div>

        <div className={styles.orDivider}>
          <span>또는 이메일로</span>
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
            <button
              type="button"
              className={styles.fieldButton}
              onClick={handleRequestCode}
              disabled={!canRequestCode}
            >
              {sendingCode ? '발송 중…' : cooldown > 0 ? `재발송 ${cooldown}초` : codeSent ? '재발송' : '인증코드 받기'}
            </button>
          </div>
          {codeSent && (
            <>
              <div className={styles.field}>
                <input
                  className={styles.input}
                  type="text"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  placeholder="메일로 받은 6자리 코드"
                  maxLength={6}
                  value={code}
                  onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
                />
              </div>
              <div className={styles.hint}>메일이 안 보이면 스팸함도 확인해 주세요. 코드는 10분 동안 유효해요.</div>
            </>
          )}
          <div className={styles.field}>
            <input
              className={styles.input}
              type="password"
              autoComplete="new-password"
              placeholder="비밀번호 (영문, 숫자 포함 8자 이상)"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </div>
        </div>

        <div className={styles.consentBox}>
          <label className={`${styles.consentRow} ${styles.consentAll}`}>
            <input
              type="checkbox"
              className={styles.consentCheck}
              checked={allAgreed}
              onChange={(e) => setAllAgreed(e.target.checked)}
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
                onClick={(e) => { e.preventDefault(); setShowDoc('terms'); }}
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
                onClick={(e) => { e.preventDefault(); setShowDoc('privacy'); }}
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
                onClick={(e) => { e.preventDefault(); setShowDoc('terms'); }}
              >
                면책 고지
              </button>
              를 확인했습니다
            </span>
          </label>
        </div>

        <div className={styles.error}>{error}</div>

        <button className={styles.primary} type="submit" disabled={!canSubmit}>
          {submitting ? '연결 중…' : '이메일로 연결하기'}
        </button>

        {showDoc && (
          <div className={styles.sheetOverlay} onClick={() => setShowDoc(null)}>
            <div className={styles.sheet} onClick={(e) => e.stopPropagation()}>
              <div className={styles.sheetTitle}>
                {showDoc === 'terms' ? '이용약관과 면책 고지' : '개인정보처리방침'}
              </div>
              <div className={styles.sheetBody}>
                {showDoc === 'terms' ? <TermsContent /> : <PrivacyContent />}
              </div>
              <button type="button" className={styles.sheetAgree} onClick={() => setShowDoc(null)}>
                확인했어요
              </button>
            </div>
          </div>
        )}
      </form>
    </PhoneFrame>
  );
}
