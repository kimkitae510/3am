import { useEffect, useState } from 'react';
import { getMe } from '../api/auth';
import styles from './HelpModal.module.css';

export interface HelpSection {
  heading: string;
  text: string;
}

// 문의 창구는 오픈채팅 하나로 모은다. 링크가 바뀌면 여기만 고치면 된다.
export const CONTACT_OPENCHAT_URL = 'https://open.kakao.com/o/szFHl2Ci';

// 모든 화면의 ? 도움말이 같은 모양, 같은 톤이 되도록 하나로 모은다.
export function HelpModal({
  title,
  sections,
  onClose,
}: {
  title: string;
  sections: HelpSection[];
  onClose: () => void;
}) {
  // 오픈채팅은 익명이라 유저를 특정할 열쇠가 없다 — 문의 안내에 회원번호를 같이 보여준다.
  // 조회 실패(비로그인 등)면 조용히 생략한다.
  const [memberId, setMemberId] = useState<number | null>(null);
  useEffect(() => {
    let alive = true;
    getMe()
      .then((me) => alive && setMemberId(me.id))
      .catch(() => {});
    return () => {
      alive = false;
    };
  }, []);

  return (
    <div className={styles.overlay} onClick={onClose}>
      <div className={styles.dialog} onClick={(e) => e.stopPropagation()}>
        <div className={styles.title}>{title}</div>
        {sections.map((s) => (
          <div className={styles.block} key={s.heading}>
            <div className={styles.heading}>{s.heading}</div>
            {s.text}
          </div>
        ))}
        <div className={styles.contact}>
          궁금한 점이나 불편한 점은{' '}
          <a href={CONTACT_OPENCHAT_URL} target="_blank" rel="noreferrer">
            카카오 오픈채팅 1:1 문의
          </a>
          로 보내주세요.
          {memberId !== null && (
            <>
              {' '}문의하실 때 <span className={styles.memberId}>회원번호 {memberId}번</span>을
              알려주시면 확인이 빨라요.
            </>
          )}
        </div>
        <button className={styles.close} onClick={onClose}>
          확인
        </button>
      </div>
    </div>
  );
}
