import { useNavigate, useParams } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import styles from './StoryListPage.module.css';

// 05 진단 히스토리 자리. 다음 단계에서 확률 변화 추이 + 지난 진단 목록으로 채운다.
export function HistoryPage() {
  const { storyId } = useParams();
  const navigate = useNavigate();
  return (
    <PhoneFrame>
      <div className={styles.wrap}>
        <div className={styles.header}>
          <button
            className={styles.iconButton}
            onClick={() => navigate(`/stories/${storyId}/assessment`)}
            aria-label="뒤로"
          >
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
              <path d="M15 5l-7 7 7 7" stroke="#ECEAF0" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>
        </div>
        <div className={styles.state}>진단 히스토리(05)는 다음 단계에서 이식할 자리예요.</div>
      </div>
    </PhoneFrame>
  );
}
