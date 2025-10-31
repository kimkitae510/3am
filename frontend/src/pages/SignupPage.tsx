import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { signup } from '../api/auth';
import { extractErrorMessage } from '../api/client';
import styles from './LoginPage.module.css';

export function SignupPage() {
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [nickname, setNickname] = useState('');
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const canSubmit =
    email.trim() !== '' && password.length >= 8 && nickname.trim().length >= 2 && !submitting;

  async function handleSignup(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    setError('');
    setSubmitting(true);
    try {
      await signup({ email: email.trim(), password, nickname: nickname.trim() });
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
          </div>
          <div className={styles.field}>
            <input
              className={styles.input}
              type="password"
              autoComplete="new-password"
              placeholder="비밀번호 (8자 이상)"
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

        <div className={styles.error}>{error}</div>

        <button className={styles.primary} type="submit" disabled={!canSubmit}>
          {submitting ? '가입 중…' : '가입하기'}
        </button>
        <button className={styles.secondary} type="button" onClick={() => navigate('/login')}>
          이미 계정이 있어요
        </button>
      </form>
    </PhoneFrame>
  );
}
