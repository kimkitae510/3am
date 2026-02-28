import styles from '../pages/LoginPage.module.css';

// 게스트 → 기존 계정 전환 확인 시트. 게스트 사연은 병합되지 않으므로(초기 정책)
// 로그인으로 넘어가기 전에 데이터를 잃는다는 것을 반드시 확인받는다.
export function SwitchConfirmSheet({
  title,
  message,
  confirmLabel,
  submitting,
  onConfirm,
  onCancel,
}: {
  title: string;
  message: string;
  confirmLabel: string;
  submitting: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <div className={styles.sheetOverlay} onClick={onCancel}>
      <div className={styles.sheet} onClick={(e) => e.stopPropagation()}>
        <div className={styles.sheetTitle}>{title}</div>
        <div className={styles.sheetText}>{message}</div>
        <button type="button" className={styles.sheetAgree} disabled={submitting} onClick={onConfirm}>
          {submitting ? '전환 중…' : confirmLabel}
        </button>
        <button type="button" className={styles.sheetCancel} onClick={onCancel}>
          돌아가기
        </button>
      </div>
    </div>
  );
}
