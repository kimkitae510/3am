import { useState } from 'react';
// public이 아니라 여기서 불러온다 — 빌드가 파일 이름에 내용 해시를 박아주므로,
// 그림을 갈아끼우면 주소가 바뀌어 브라우저가 옛 그림을 계속 들고 있는 일이 없다.
import avatarUrl from '../assets/character-face.png';
import portraitUrl from '../assets/character.png';
import styles from './CharacterProfile.module.css';

export const CHARACTER_NAME = '시현';

// 첫 화면(IntroPage)과 빈 대화방(ChatPage)이 같은 말을 해야 한다.
// 첫 화면에서 답을 적으면 그대로 대화방으로 넘어가는데, 거기서 다른 말이 떠 있거나
// 아무 말도 없으면 방금 답한 상대가 사라진 것처럼 보인다 — 같은 첫 풍선 아래 내 답이 붙어야
// 하나의 대화로 읽힌다. 배열 항목이 말풍선 경계이고, 항목 안의 줄바꿈은 한 풍선 안의 줄이다.
export const GREETING = [
  '두 사람의 이야기를 들려주십시오',
  // 항목을 세우지 않는다 — 나열하면 그것만 적으면 되는 줄 안다. 무엇을 적을지가 아니라
  // 어디까지 적을지("흐름과 현재 상황")를 말하고, 더 적을 이유를 뒤에 붙인다
  '이별까지의 흐름과 현재 상황을 자세히 적어주시면 더 정확하게 분석해 드리겠습니다',
];

// 작은 원에는 얼굴만 잘라낸 파일을, 프로필처럼 크게 보여주는 자리에는 원본을 쓴다.
// 원에 반신을 넣으면 얼굴이 쥐콩만해지고, 큰 자리에 얼굴만 넣으면 그림이 잘려 보인다.
export const CHARACTER_AVATAR = avatarUrl; // 프사, 첫 화면 원형
// 프로필은 그림을 다 보여주는 자리다 — 배경이 투명한 원본을 그대로 띄운다
export const CHARACTER_PORTRAIT = portraitUrl;

export function CharacterProfile({ onClose }: { onClose: () => void }) {
  // 초상은 나중에 교체된다 — 파일이 없을 때 깨진 이미지 아이콘이 뜨지 않게 통째로 숨긴다
  const [hasPortrait, setHasPortrait] = useState(true);

  // 어디를 눌러도 닫힌다 — 인스타의 프사 확대와 같다. 닫기 버튼을 따로 두면
  // "화면"이 되고, 배경을 누르면 닫히는 겹침은 "잠깐 띄운 것"으로 읽힌다.
  return (
    <button className={styles.overlay} onClick={onClose} aria-label="닫기">
      {/* 이름도 소개도 안 띄운다 — 사진 하나만 뜨는 게 인스타의 프사 확대다.
          글자가 붙는 순간 "프로필 화면"이 되고, 대화 중에 들른 곳이 아니라 떠나온 곳이 된다 */}
      {hasPortrait && (
        <span className={styles.frame}>
          <img
            className={styles.portrait}
            src={CHARACTER_PORTRAIT}
            alt={CHARACTER_NAME}
            onError={() => setHasPortrait(false)}
          />
        </span>
      )}
    </button>
  );
}
