import { useNavigate } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { PrivacyContent } from '../components/PrivacyContent';
import styles from './PrivacyPage.module.css';

export function PrivacyPage() {
  const navigate = useNavigate();
  return (
    <PhoneFrame>
      <div className={styles.wrap}>
        <div className={styles.topbar}>
          <button className={styles.backButton} onClick={() => navigate(-1)} aria-label="뒤로">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
              <path d="M15 5l-7 7 7 7" stroke="#ECEAF0" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>
          <div className={styles.topTitle}>개인정보처리방침</div>
        </div>
        <div className={styles.body}>
          <PrivacyContent />
        </div>
      </div>
    </PhoneFrame>
  );
}
