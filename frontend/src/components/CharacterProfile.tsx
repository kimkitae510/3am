import { useState } from 'react';
import styles from './CharacterProfile.module.css';

export const CHARACTER_NAME = '시현';

// 큰 화면과 작은 원은 필요한 그림이 다르다. 반신 그림을 원에 넣으면 얼굴이 쥐콩만해지고,
// 얼굴 클로즈업을 전체화면에 깔면 여백이 없어 답답하다 — 자리마다 다른 파일을 쓴다.
export const CHARACTER_PORTRAIT = '/character.png'; // 프로필 전체화면
export const CHARACTER_AVATAR = '/character-face.png'; // 원형 프사. character.png에서 얼굴만 잘라낸 것

export function CharacterProfile({ onClose }: { onClose: () => void }) {
  // 초상은 나중에 교체된다 — 파일이 없을 때 깨진 이미지 아이콘이 뜨지 않게 통째로 숨긴다
  const [hasPortrait, setHasPortrait] = useState(true);

  return (
    <div className={styles.overlay}>
      <div className={styles.stage}>
        {hasPortrait && (
          <img
            className={styles.portrait}
            src={CHARACTER_PORTRAIT}
            alt={CHARACTER_NAME}
            onError={() => setHasPortrait(false)}
          />
        )}
        <div className={styles.fade} />
        <button className={styles.close} onClick={onClose} aria-label="닫기">
          ✕
        </button>
      </div>
      <div className={styles.body}>
        <div className={styles.name}>{CHARACTER_NAME}</div>
        {/* 어떤 사람인지는 대화에서 드러난다 — 특징을 목록으로 늘어놓으면 사람 프로필이 아니라
            제품 기능 소개로 읽힌다. 카톡 프로필도 사진, 이름, 한 줄이 전부다 */}
        <div className={styles.intro}>새벽에 깨어 있는 사람.</div>
      </div>
    </div>
  );
}
