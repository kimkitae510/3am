import type { ReactNode } from 'react';
import styles from './PhoneFrame.module.css';

export function PhoneFrame({ children }: { children: ReactNode }) {
  return (
    <div className={styles.stage}>
      <div className={styles.frame}>
        <div className={styles.statusBar}>
          3:00
          <div className={styles.statusIcons}>
            <svg width="18" height="11" viewBox="0 0 18 11">
              <rect x="0" y="6" width="3" height="5" rx="1" fill="#ECEAF0" />
              <rect x="5" y="3" width="3" height="8" rx="1" fill="#ECEAF0" />
              <rect x="10" y="0" width="3" height="11" rx="1" fill="#ECEAF0" />
              <rect x="15" y="0" width="3" height="11" rx="1" fill="#ECEAF0" opacity=".35" />
            </svg>
            <div className={styles.battery}>
              <div className={styles.batteryFill} />
            </div>
          </div>
        </div>
        <div className={styles.content}>{children}</div>
      </div>
    </div>
  );
}
