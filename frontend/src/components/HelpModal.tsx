import styles from './HelpModal.module.css';

export interface HelpSection {
  heading: string;
  text: string;
}

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
        <button className={styles.close} onClick={onClose}>
          확인
        </button>
      </div>
    </div>
  );
}
