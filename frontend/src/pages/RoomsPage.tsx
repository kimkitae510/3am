import { useNavigate } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { tokenStore } from '../api/tokenStore';
import styles from './LoginPage.module.css';

// 02 대화방 목록 자리. 지금은 로그인 연결 확인용 플레이스홀더 —
// 목업 02 화면은 다음 단계에서 이식한다.
export function RoomsPage() {
  const navigate = useNavigate();

  function handleLogout() {
    tokenStore.clear();
    navigate('/login');
  }

  return (
    <PhoneFrame>
      <div className={styles.body}>
        <div className={styles.brand}>
          <div className={styles.title}>로그인 성공</div>
          <div className={styles.subtitle}>대화방 목록(02)은 다음 단계에서 이식할 자리예요.</div>
        </div>
        <div className={styles.spacer} />
        <button className={styles.secondary} type="button" onClick={handleLogout}>
          로그아웃
        </button>
      </div>
    </PhoneFrame>
  );
}
