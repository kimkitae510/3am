import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { TermsContent } from '../components/TermsContent';
import { requestEmailVerification, signup } from '../api/auth';
import { extractErrorMessage } from '../api/client';
import styles from './LoginPage.module.css';

const RESEND_COOLDOWN_SECONDS = 60; // 서버 쿨다운과 동일 — UI에서 먼저 눌러볼 일이 없게

export function SignupPage() {
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [code, setCode] = useState('');
  const [codeSent, setCodeSent] = useState(false);
  const [sendingCode, setSendingCode] = useState(false);
  const [cooldown, setCooldown] = useState(0);
  const [password, setPassword] = useState('');
  const [nickname, setNickname] = useState('');
  const [agreeTerms, setAgreeTerms] = useState(false);
  const [agreeDisclaimer, setAgreeDisclaimer] = useState(false);
  const [showTerms, setShowTerms] = useState(false); // 오버레이로 열어 입력값이 안 날아가게 한다
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

  const canSubmit =
    email.trim() !== '' &&
    /^\d{6}$/.test(code) &&
    password.length >= 8 &&
    nickname.trim().length >= 2 &&
    agreeTerms &&
    agreeDisclaimer &&
    !submitting;

  async function handleSignup(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    setError('');
    setSubmitting(true);
    try {
      await signup({
        email: email.trim(),
        password,
        nickname: nickname.trim(),
        verificationCode: code,
      });
      navigate('/login');
    } catch (err) {
      setError(extractErrorMessage(err, '가입에 실패했어요.'));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <PhoneFrame>
      <form className={styles.body} onSubmit={handleSignup}>
        <button type="button" className={styles.backTop} onClick={() => navigate('/login')} aria-label="뒤로">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
            <path d="M15 5l-7 7 7 7" stroke="#ECEAF0" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </button>
        <div className={styles.brand}>
          <div className={styles.title}>가입하기</div>
          <div className={styles.subtitle}>이메일로 새벽 세시를 시작해요.</div>
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
          <div className={styles.field}>
            <input
              className={styles.input}
              type="text"
              placeholder="닉네임 (2~20자)"
              value={nickname}
              onChange={(e) => setNickname(e.target.value)}
            />
          </div>
        </div>

        <div className={styles.consentBox}>
          <label className={styles.consentRow}>
            <input
              type="checkbox"
              className={styles.consentCheck}
              checked={agreeTerms}
              onChange={(e) => setAgreeTerms(e.target.checked)}
            />
            <span>(필수) 이용약관에 동의합니다</span>
          </label>
          <label className={styles.consentRow}>
            <input
              type="checkbox"
              className={styles.consentCheck}
              checked={agreeDisclaimer}
              onChange={(e) => setAgreeDisclaimer(e.target.checked)}
            />
            <span>(필수) 면책 고지를 확인했습니다</span>
          </label>
          <button type="button" className={styles.consentView} onClick={() => setShowTerms(true)}>
            전문 보기
          </button>
        </div>

        <div className={styles.error}>{error}</div>

        <button className={styles.primary} type="submit" disabled={!canSubmit}>
          {submitting ? '가입 중…' : '가입하기'}
        </button>

        {showTerms && (
          <div className={styles.sheetOverlay} onClick={() => setShowTerms(false)}>
            <div className={styles.sheet} onClick={(e) => e.stopPropagation()}>
              <div className={styles.sheetTitle}>이용약관과 면책 고지</div>
              <div className={styles.sheetBody}>
                <TermsContent />
              </div>
              <button
                type="button"
                className={styles.sheetAgree}
                onClick={() => {
                  setAgreeTerms(true);
                  setAgreeDisclaimer(true);
                  setShowTerms(false);
                }}
              >
                모두 확인했으며 동의합니다
              </button>
            </div>
          </div>
        )}
      </form>
    </PhoneFrame>
  );
}
