import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { listStories, createStory } from '../api/story';
import { extractErrorMessage } from '../api/client';
import styles from './StoryEntryPage.module.css';

// 목록 화면이 서랍으로 내려가면서 /stories 는 머무는 화면이 아니라 통로가 됐다.
// 가장 최근 사연으로 보내고, 하나도 없으면 만들어서 보낸다 — 갈 곳이 여기밖에 없다.
export function StoryEntryPage() {
  const navigate = useNavigate();
  const [error, setError] = useState('');
  const ran = useRef(false); // 개발 모드의 이펙트 두 번 실행으로 빈 방이 두 개 생기는 것을 막는다

  useEffect(() => {
    if (ran.current) return;
    ran.current = true;
    (async () => {
      try {
        const stories = await listStories();
        const target = stories[0] ?? (await createStory());
        navigate(`/stories/${target.id}`, { replace: true });
      } catch (e) {
        setError(extractErrorMessage(e, '대화를 여는 데 실패했습니다.'));
      }
    })();
  }, [navigate]);

  return (
    <PhoneFrame>
      <div className={styles.wrap}>{error || '여는 중…'}</div>
    </PhoneFrame>
  );
}
