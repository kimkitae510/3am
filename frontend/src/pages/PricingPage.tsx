import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { BusinessInfo } from '../components/BusinessInfo';
import { getPaymentConfig, type PaymentItemView } from '../api/payment';
import styles from './PricingPage.module.css';

const KIND_LABEL: Record<string, string> = { CHAT: '대화', ASSESSMENT: '분석' };

// 로그인 없이 무엇을 얼마에 파는지 보여주는 공개 페이지.
// 결제 대행사 심사자는 계정을 만들지 않고 사이트를 보기 때문에, 가격이 결제 화면(로그인 뒤)에만
// 있으면 확인할 방법이 없다. 첫 화면에 가격을 박는 대신 이 페이지를 따로 두는 이유는,
// 이별 직후에 들어온 사람에게 인사보다 가격표를 먼저 보이고 싶지 않아서다.
export function PricingPage() {
  const navigate = useNavigate();
  const [items, setItems] = useState<PaymentItemView[] | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let alive = true;
    getPaymentConfig()
      .then((config) => alive && setItems(config.items))
      .catch(() => alive && setFailed(true));
    return () => {
      alive = false;
    };
  }, []);

  return (
    <PhoneFrame>
      <div className={styles.wrap}>
        <div className={styles.topbar}>
          <button className={styles.backButton} onClick={() => navigate(-1)} aria-label="뒤로">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
              <path d="M15 5l-7 7 7 7" stroke="#ebebee" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>
          <div className={styles.topTitle}>이용권과 환불 정책</div>
        </div>

        <div className={styles.body}>
          <p className={styles.lead}>
            새벽 세시는 지난 연애를 정리하려는 사람을 위한 소프트웨어입니다. 이별까지의 상황을
            적으면 이별의 유형과 작용한 요인을 정리한 분석 리포트를 글로 돌려드립니다. 소프트웨어가
            자동으로 만드는 결과이며 상담사가 응대하지 않습니다. 대화와 분석은 횟수 단위로
            충전해서 사용합니다.
          </p>

          {failed && <div className={styles.state}>가격 정보를 불러오지 못했습니다.</div>}
          {!failed && items === null && <div className={styles.state}>불러오는 중…</div>}

          {items?.map((item) => (
            <div className={styles.card} key={item.code}>
              <div className={styles.itemName}>{item.name}</div>
              <div className={styles.price}>{item.amount.toLocaleString()}원</div>
              <ul className={styles.grantList}>
                {item.grants.map((grant) => (
                  <li key={grant.kind}>
                    {KIND_LABEL[grant.kind] ?? grant.kind} {grant.count}회
                  </li>
                ))}
                <li>사용 기한 없음</li>
                <li>결제 즉시 계정에 지급</li>
              </ul>
            </div>
          ))}

          <div className={styles.sectionTitle}>환불 정책</div>
          <p className={styles.para}>
            이용권은 결제 즉시 지급되는 디지털 콘텐츠입니다. 한 번도 사용하지 않은 이용권은 기간
            제한 없이 전액 환불받을 수 있으며, 1:1 문의로 접수하면 처리해 드립니다. 사용을 시작한
            이용권은 결제 시 이 내용을 안내받고 동의한 경우 전자상거래 등에서의 소비자보호에 관한
            법률 제17조 제2항에 따라 청약철회가 제한됩니다.
          </p>

          <div className={styles.sectionTitle}>문의</div>
          <p className={styles.para}>
            결제와 환불 문의는 아래 사업자정보의 전자우편주소로 연락해 주세요.
          </p>

          <div className={styles.docLinks}>
            {/* IntroPage와 같은 이유로 a — 크롤러가 따라갈 href가 있어야 한다 */}
            <a
              className={styles.docLink}
              href="/terms"
              onClick={(e) => { e.preventDefault(); navigate('/terms'); }}
            >
              이용약관
            </a>
            <a
              className={styles.docLink}
              href="/privacy"
              onClick={(e) => { e.preventDefault(); navigate('/privacy'); }}
            >
              개인정보처리방침
            </a>
          </div>

          <BusinessInfo defaultOpen />
        </div>
      </div>
    </PhoneFrame>
  );
}
