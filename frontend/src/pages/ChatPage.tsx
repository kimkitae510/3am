import { useNavigate, useParams } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import styles from './StoryListPage.module.css';

// 03 채팅 화면 자리. 다음 단계에서 목업 03(말풍선·입력창·재회 진단 버튼)으로 채운다.
export function ChatPage() {
  const { storyId } = useParams();
  const navigate = useNavigate();
  return (
    <PhoneFrame>
      <div className={styles.wrap}>
        <div className={styles.header}>
          <button className={styles.iconButton} onClick={() => navigate('/stories')} aria-label="뒤로">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
              <path d="M15 5l-7 7 7 7" stroke="#ECEAF0" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>
        </div>
        <div className={styles.state}>채팅 화면(#{storyId})은 다음 단계에서 이식할 자리예요.</div>
      </div>
    </PhoneFrame>
  );
}
