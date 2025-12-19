import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import {
  cancelPayment,
  createOrder,
  confirmPayment,
  getPaymentConfig,
  listPayments,
  type OrderCreateResponse,
  type PaymentConfig,
  type PaymentResponse,
} from '../api/payment';
import { getUsage, type UsageStatusResponse } from '../api/usage';
import { extractErrorMessage } from '../api/client';
import { formatListTime } from '../utils/datetime';
import styles from './PaymentPage.module.css';

const STATUS_LABEL: Record<string, string> = {
  READY: '결제 대기',
  IN_PROGRESS: '확인 중',
  WAITING_FOR_DEPOSIT: '입금 대기',
  DONE: '결제 완료',
  FAILED: '실패',
  EXPIRED: '만료',
  CANCEL_REQUESTED: '환불 처리 중',
  CANCELED: '환불 완료',
};

const KIND_LABEL: Record<string, string> = { CHAT: '대화', ASSESSMENT: '진단' };

// 토스 SDK는 외부 스크립트라 필요할 때(실결제 모드) 한 번만 끼워 넣는다.
function loadTossSdk(): Promise<any> {
  return new Promise((resolve, reject) => {
    const w = window as any;
    if (w.TossPayments) return resolve(w.TossPayments);
    const script = document.createElement('script');
    script.src = 'https://js.tosspayments.com/v2/standard';
    script.onload = () => resolve((window as any).TossPayments);
    script.onerror = () => reject(new Error('결제 모듈을 불러오지 못했어요.'));
    document.head.appendChild(script);
  });
}

// 토스 위젯이 요구하는 구매자 식별자. 브라우저별 랜덤 발급으로 충분하다(회원 정보 노출 없음).
function customerKey(): string {
  const saved = localStorage.getItem('toss-customer-key');
  if (saved) return saved;
  const fresh = crypto.randomUUID();
  localStorage.setItem('toss-customer-key', fresh);
  return fresh;
}

export function PaymentPage() {
  const navigate = useNavigate();
  const [config, setConfig] = useState<PaymentConfig | null>(null);
  const [usage, setUsage] = useState<UsageStatusResponse | null>(null);
  const [payments, setPayments] = useState<PaymentResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [buying, setBuying] = useState(false);
  // 실결제(toss) 모드에서만: 주문을 만들고 위젯을 펼친 상태
  const [widgetOrder, setWidgetOrder] = useState<OrderCreateResponse | null>(null);
  const [widgetReady, setWidgetReady] = useState(false);
  const widgetsRef = useRef<any>(null);
  const [refundTarget, setRefundTarget] = useState<PaymentResponse | null>(null);
  const [refunding, setRefunding] = useState(false);
  const aliveRef = useRef(true);

  useEffect(() => {
    if (!error) return;
    const timer = window.setTimeout(() => aliveRef.current && setError(''), 6000);
    return () => clearTimeout(timer);
  }, [error]);

  const refresh = useCallback(() => {
    getUsage().then((u) => aliveRef.current && setUsage(u)).catch(() => {});
    listPayments().then((p) => aliveRef.current && setPayments(p)).catch(() => {});
  }, []);

  useEffect(() => {
    aliveRef.current = true;
    getPaymentConfig()
      .then((c) => aliveRef.current && setConfig(c))
      .catch((e) => aliveRef.current && setError(extractErrorMessage(e, '상품 정보를 불러오지 못했어요.')))
      .finally(() => aliveRef.current && setLoading(false));
    refresh();
    return () => {
      aliveRef.current = false;
    };
  }, [refresh]);

  // 주문이 만들어지면 위젯을 그 자리에서 렌더링한다(결제수단 선택 + 약관).
  useEffect(() => {
    if (!widgetOrder || !config?.clientKey) return;
    let alive = true;
    (async () => {
      try {
        const TossPayments = await loadTossSdk();
        const widgets = TossPayments(config.clientKey).widgets({ customerKey: customerKey() });
        await widgets.setAmount({ currency: 'KRW', value: widgetOrder.amount });
        await Promise.all([
          widgets.renderPaymentMethods({ selector: '#pay-methods' }),
          widgets.renderAgreement({ selector: '#pay-agreement' }),
        ]);
        if (alive) {
          widgetsRef.current = widgets;
          setWidgetReady(true);
        }
      } catch (e) {
        if (alive) {
          setWidgetOrder(null);
          setError(e instanceof Error ? e.message : '결제 화면을 여는 데 실패했어요.');
        }
      }
    })();
    return () => {
      alive = false;
    };
  }, [widgetOrder, config]);

  async function buy(itemCode: string) {
    if (buying || !config) return;
    setBuying(true);
    setError('');
    setNotice('');
    try {
      const order = await createOrder(itemCode);
      if (!config.clientKey) {
        // mock 모드: PG 없이 서버 mock 게이트웨이가 즉시 승인한다 — 키 없이 전체 흐름 확인용.
        const done = await confirmPayment({
          paymentKey: `mock-${order.orderId}`,
          orderId: order.orderId,
          amount: order.amount,
        });
        if (aliveRef.current) {
          setNotice(`충전 완료! ${done.itemName}`);
          refresh();
        }
      } else {
        setWidgetReady(false);
        setWidgetOrder(order);
      }
    } catch (e) {
      if (aliveRef.current) setError(extractErrorMessage(e, '구매를 시작하지 못했어요.'));
    } finally {
      if (aliveRef.current) setBuying(false);
    }
  }

  // successUrl/failUrl로 리다이렉트되므로 이 함수는 정상 흐름에선 돌아오지 않는다.
  async function payWithWidget() {
    if (!widgetOrder || !widgetsRef.current) return;
    try {
      await widgetsRef.current.requestPayment({
        orderId: widgetOrder.orderId,
        orderName: widgetOrder.orderName,
        successUrl: `${window.location.origin}/payment/success`,
        failUrl: `${window.location.origin}/payment/fail`,
      });
    } catch (e) {
      // 유저가 결제창을 닫은 경우 등 — 주문(READY)은 서버가 30분 뒤 알아서 만료시킨다.
      setError(e instanceof Error && e.message ? e.message : '결제가 진행되지 않았어요.');
    }
  }

  async function confirmRefund() {
    if (!refundTarget) return;
    setRefunding(true);
    try {
      await cancelPayment(refundTarget.orderId);
      if (aliveRef.current) {
        setNotice('환불이 완료됐어요. 남은 이용권은 회수됐어요.');
        setRefundTarget(null);
        refresh();
      }
    } catch (e) {
      if (aliveRef.current) {
        setRefundTarget(null);
        setError(extractErrorMessage(e, '환불하지 못했어요. 잠시 후 다시 시도해 주세요.'));
        refresh(); // 처리 중(P007)일 수 있으니 목록 상태를 갱신해 보여준다
      }
    } finally {
      if (aliveRef.current) setRefunding(false);
    }
  }

  function grantsText(p: { grants: { kind: string; count: number }[] }): string {
    return p.grants.map((g) => `${KIND_LABEL[g.kind] ?? g.kind} ${g.count}회`).join(' + ');
  }

  return (
    <PhoneFrame>
      <div className={styles.wrap}>
        <div className={styles.topbar}>
          <button className={styles.backButton} onClick={() => navigate('/stories')} aria-label="뒤로">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
              <path d="M15 5l-7 7 7 7" stroke="#ECEAF0" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>
          <div className={styles.topTitle}>이용권</div>
          <div className={styles.topSpacer} />
        </div>

        {error && <div className={styles.errorBanner}>{error}</div>}
        {notice && <div className={styles.noticeBanner}>{notice}</div>}

        {loading ? (
          <div className={styles.state}>불러오는 중…</div>
        ) : (
          <div className={styles.body}>
            {usage && (
              <div className={styles.balanceCard}>
                <div className={styles.balanceRow}>
                  <span className={styles.balanceKey}>대화</span>
                  <span className={styles.balanceValue}>
                    오늘 무료 {usage.chatRemaining}/{usage.chatDailyLimit}회
                    {usage.chatPaidRemaining > 0 && (
                      <span className={styles.paidBadge}>이용권 {usage.chatPaidRemaining}회</span>
                    )}
                  </span>
                </div>
                <div className={styles.balanceRow}>
                  <span className={styles.balanceKey}>진단</span>
                  <span className={styles.balanceValue}>
                    오늘 무료 {usage.assessmentRemaining}/{usage.assessmentDailyLimit}회
                    {usage.assessmentPaidRemaining > 0 && (
                      <span className={styles.paidBadge}>이용권 {usage.assessmentPaidRemaining}회</span>
                    )}
                  </span>
                </div>
                <div className={styles.balanceHint}>무료 횟수를 먼저 쓰고, 다 쓰면 이용권에서 차감돼요.</div>
              </div>
            )}

            {config && !widgetOrder && (
              <>
                <div className={styles.sectionTitle}>이용권 구매</div>
                {config.items.map((item) => (
                  <div className={styles.itemCard} key={item.code}>
                    <div className={styles.itemInfo}>
                      <div className={styles.itemName}>{item.name}</div>
                      <div className={styles.itemGrants}>{grantsText(item)} / 기한 없음</div>
                    </div>
                    <button className={styles.buyButton} onClick={() => buy(item.code)} disabled={buying}>
                      {buying ? '진행 중…' : `${item.amount.toLocaleString()}원`}
                    </button>
                  </div>
                ))}
              </>
            )}

            {widgetOrder && (
              <div className={styles.widgetCard}>
                <div className={styles.itemName}>{widgetOrder.orderName}</div>
                <div id="pay-methods" />
                <div id="pay-agreement" />
                <button className={styles.btnPrimary} onClick={payWithWidget} disabled={!widgetReady}>
                  {widgetReady ? `${widgetOrder.amount.toLocaleString()}원 결제하기` : '결제 수단 불러오는 중…'}
                </button>
                <button className={styles.btnGhost} onClick={() => setWidgetOrder(null)}>
                  취소
                </button>
              </div>
            )}

            <div className={styles.sectionTitle}>구매 내역</div>
            {payments.length === 0 ? (
              <div className={styles.emptyHistory}>아직 구매 내역이 없어요.</div>
            ) : (
              payments.map((p) => (
                <div className={styles.historyCard} key={p.orderId}>
                  <div className={styles.historyTop}>
                    <span className={styles.historyName}>{p.itemName}</span>
                    <span className={`${styles.statusBadge} ${styles[`st${p.status}`] ?? ''}`}>
                      {STATUS_LABEL[p.status] ?? p.status}
                    </span>
                  </div>
                  <div className={styles.historyMeta}>
                    {formatListTime(p.createdAt)}, {p.amount.toLocaleString()}원
                    {p.method ? ` (${p.method})` : ''}
                    {p.status === 'CANCELED' && ` / 환불 ${p.canceledAmount.toLocaleString()}원`}
                  </div>
                  {p.entitlements.length > 0 && p.status === 'DONE' && (
                    <div className={styles.historyMeta}>
                      {p.entitlements
                        .map((e) => `${KIND_LABEL[e.kind] ?? e.kind} ${e.usedCount}/${e.totalCount}회 사용`)
                        .join(', ')}
                    </div>
                  )}
                  {p.status === 'WAITING_FOR_DEPOSIT' && p.vbankAccount && (
                    <div className={styles.vbankBox}>
                      입금 계좌: {p.vbankBank} {p.vbankAccount}
                      {p.vbankDueAt && (
                        <>
                          <br />
                          {formatListTime(p.vbankDueAt)}까지 입금해 주세요.
                        </>
                      )}
                    </div>
                  )}
                  {p.status === 'FAILED' && p.failReason && (
                    <div className={styles.failReason}>{p.failReason}</div>
                  )}
                  {p.status === 'DONE' && (p.refundableAmount ?? 0) > 0 && (
                    <button className={styles.refundLink} onClick={() => setRefundTarget(p)}>
                      환불하기 (예상 {p.refundableAmount!.toLocaleString()}원)
                    </button>
                  )}
                </div>
              ))
            )}
          </div>
        )}

        {refundTarget && (
          <div className={styles.overlay} onClick={() => !refunding && setRefundTarget(null)}>
            <div className={styles.dialog} onClick={(e) => e.stopPropagation()}>
              <div className={styles.dialogTitle}>환불할까요?</div>
              <div className={styles.dialogText}>
                쓰지 않은 만큼인 {refundTarget.refundableAmount?.toLocaleString()}원을 돌려받아요.
                <br />
                남은 이용권은 모두 사라지고, 되돌릴 수 없어요.
              </div>
              <div className={styles.dialogButtons}>
                <button className={styles.cancelBtn} onClick={() => setRefundTarget(null)} disabled={refunding}>
                  취소
                </button>
                <button className={styles.confirmBtn} onClick={confirmRefund} disabled={refunding}>
                  {refunding ? '환불 중…' : '환불'}
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </PhoneFrame>
  );
}
