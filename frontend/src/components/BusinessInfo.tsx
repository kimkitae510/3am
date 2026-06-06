import { useState } from 'react';
import styles from './BusinessInfo.module.css';

// 전자상거래법 제10조 — 상호, 대표자, 주소, 전화번호, 전자우편주소, 사업자등록번호,
// 호스팅서비스 제공자 상호는 사이버몰 초기화면 표시 의무다(약관만 링크로 대체 가능).
// 같은 법에 따라 공정위 사업자정보 공개페이지도 초기화면에 연결해야 한다.
const BIZ = {
  name: '케이케이티랩스',
  ceo: '김도아',
  bizNo: '281-20-02774',
  mailOrderNo: '', // 통신판매업 신고 후 기재
  address: '대구광역시 달성군 다사읍 대실역북로1길 6, 106동 301호',
  tel: '', // 연락처 — 소비자가 실제로 닿을 수 있는 번호여야 한다
  email: 'rlarlxo516@gmail.com',
  host: '오라클 클라우드 인프라스트럭처(OCI)',
};

// 공정위 공개페이지는 하이픈 없는 사업자등록번호를 받는다
const FTC_URL = `https://www.ftc.go.kr/bizCommPop.do?wrkr_no=${BIZ.bizNo.replace(/-/g, '')}`;

// 빈 값을 조용히 감추면 표시 의무를 안 지킨 채로 배포된다 — 눈에 띄게 남긴다
function orPending(value: string, label: string) {
  return value || `(${label})`;
}

// 로고 하나로 서는 화면이라 다섯 줄을 상시 노출하면 히어로가 죽는다.
// 접어두되 초기화면에서 펼칠 수 있게 둔다.
export function BusinessInfo({ defaultOpen = false }: { defaultOpen?: boolean }) {
  const [open, setOpen] = useState(defaultOpen);

  return (
    <div className={styles.wrap}>
      <button
        type="button"
        className={styles.toggle}
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        사업자정보 <span className={styles.caret}>{open ? '⌃' : '⌄'}</span>
      </button>

      {open && (
        <div className={styles.panel}>
          <p className={styles.line}>
            {BIZ.name} / 대표 {BIZ.ceo} / 사업자등록번호 {BIZ.bizNo}
          </p>
          <p className={styles.line}>
            통신판매업 신고 {orPending(BIZ.mailOrderNo, '신고 후 기재')} /{' '}
            <a className={styles.link} href={FTC_URL} target="_blank" rel="noreferrer">
              사업자정보 확인
            </a>
          </p>
          <p className={styles.line}>{orPending(BIZ.address, '주소 기재')}</p>
          <p className={styles.line}>
            {orPending(BIZ.tel, '연락처 기재')} / {orPending(BIZ.email, '이메일 기재')}
          </p>
          <p className={styles.line}>호스팅 제공: {orPending(BIZ.host, '배포처 확정 후 기재')}</p>
        </div>
      )}
    </div>
  );
}
