import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { confirmPayment, type PaymentResponse } from '../api/payment';
import { extractErrorCode, extractErrorMessage } from '../api/client';
import { paymentOrigin } from '../utils/paymentOrigin';
import styles from './PaymentResultPage.module.css';

const KIND_LABEL: Record<string, string> = { CHAT: '대화', ASSESSMENT: '분석' };

// 토스 위젯의 successUrl/failUrl 도착지. 성공 리다이렉트여도 "인증 완료"일 뿐이라
// 여기서 서버 승인(confirm)을 마쳐야 돈이 움직이고 이용권이 지급된다.
export function PaymentResultPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const isSuccessPath = location.pathname.endsWith('/success');
  const params = new URLSearchParams(location.search);

  const [phase, setPhase] = useState<'confirming' | 'done' | 'pending' | 'failed'>(
    isSuccessPath ? 'confirming' : 'failed',
  );
  const [payment, setPayment] = useState<PaymentResponse | null>(null);
  const [message, setMessage] = useState(
    isSuccessPath ? '' : params.get('message') || '결제가 진행되지 않았습니다.',
  );
  const requested = useRef(false); // StrictMode 이중 실행 방어(서버도 멱등이지만 요청 자체를 줄인다)

  useEffect(() => {
    if (!isSuccessPath || requested.current) return;
    requested.current = true;
    const paymentKey = params.get('paymentKey');
    const orderId = params.get('orderId');
    const amount = Number(params.get('amount'));
    if (!paymentKey || !orderId || !amount) {
      setPhase('failed');
      setMessage('결제 정보가 올바르지 않습니다. 처음부터 다시 시도해 주세요.');
      return;
    }
    confirmPayment({ paymentKey, orderId, amount })
      .then((res) => {
        setPayment(res);
        setPhase('done');
      })
      .catch((e) => {
        // P007 = 결과 불명. 실패가 아니라 "확인 지연" — 서버 재동기화가 곧 확정한다.
        if (extractErrorCode(e) === 'P007') {
          setPhase('pending');
          setMessage(extractErrorMessage(e));
        } else {
          setPhase('failed');
          setMessage(extractErrorMessage(e, '결제 승인에 실패했습니다.'));
        }
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <PhoneFrame>
      <div className={styles.wrap}>
        <div className={styles.center}>
          {phase === 'confirming' && (
            <>
              <div className={styles.kicker}>결제 확인 중</div>
              <div className={styles.bigMsg}>
                결제를 확정하고 있습니다.
                <br />
                잠시만 기다려 주세요.
              </div>
            </>
          )}
          {phase === 'done' && payment && (
            <>
              <div className={styles.kicker}>충전 완료</div>
              <div className={styles.bigMsg}>{payment.itemName}</div>
              <div className={styles.subMsg}>
                {payment.status === 'WAITING_FOR_DEPOSIT'
                  ? '입금이 확인되면 이용권이 채워집니다. 입금 계좌는 이용권 화면에서 확인할 수 있습니다.'
                  : payment.entitlements
                      .map((e) => `${KIND_LABEL[e.kind] ?? e.kind} ${e.totalCount}회`)
                      .join(', ') + '가 채워졌습니다.'}
              </div>
            </>
          )}
          {phase === 'pending' && (
            <>
              <div className={styles.kicker}>확인 지연</div>
              <div className={styles.bigMsg}>
                결제 결과를
                <br />
                확인하고 있습니다.
              </div>
              <div className={styles.subMsg}>{message}</div>
            </>
          )}
          {phase === 'failed' && (
            <>
              <div className={styles.kicker}>결제 실패</div>
              <div className={styles.bigMsg}>
                결제가
                <br />
                완료되지 않았습니다.
              </div>
              <div className={styles.subMsg}>{message}</div>
            </>
          )}
        </div>
        {phase !== 'confirming' && (
          <div className={styles.footer}>
            {/* 토스를 다녀오면 히스토리가 끊겨 뒤로가 없다 — 결제로 들어간 자리를 적어둔 걸로 되돌린다 */}
            <button className={styles.btnGhost} onClick={() => navigate(paymentOrigin())}>
              돌아가기
            </button>
            <button className={styles.btnPrimary} onClick={() => navigate('/payment')}>
              이용권 화면으로
            </button>
          </div>
        )}
      </div>
    </PhoneFrame>
  );
}
