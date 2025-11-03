import { useNavigate, useParams } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import styles from './StoryListPage.module.css';

// 04 재회 진단 결과 자리. 다음 단계에서 목업 04/04b(확률·감점·놓아주기)로 채운다.
export function AssessmentPage() {
  const { storyId } = useParams();
  const navigate = useNavigate();
  return (
    <PhoneFrame>
      <div className={styles.wrap}>
        <div className={styles.header}>
          <button
            className={styles.iconButton}
            onClick={() => navigate(`/stories/${storyId}`)}
            aria-label="뒤로"
          >
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
              <path d="M15 5l-7 7 7 7" stroke="#ECEAF0" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>
        </div>
        <div className={styles.state}>재회 진단 결과(04)는 다음 단계에서 이식할 자리예요.</div>
      </div>
    </PhoneFrame>
  );
}
