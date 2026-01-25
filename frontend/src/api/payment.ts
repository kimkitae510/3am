import { api } from './client';

export interface PaymentGrant {
  kind: 'CHAT' | 'ASSESSMENT';
  count: number;
}

export interface PaymentItemView {
  code: string;
  name: string;
  amount: number;
  grants: PaymentGrant[];
}

export interface PaymentConfig {
  provider: string; // mock | toss
  clientKey: string; // 비어 있으면 mock — 위젯 없이 모의 승인 흐름을 탄다
  items: PaymentItemView[];
}

export interface OrderCreateResponse {
  orderId: string;
  item: string;
  orderName: string;
  amount: number;
}

export interface EntitlementView {
  kind: 'CHAT' | 'ASSESSMENT';
  totalCount: number;
  usedCount: number;
  remainingCount: number;
}

export type PaymentStatus =
  | 'READY'
  | 'IN_PROGRESS'
  | 'WAITING_FOR_DEPOSIT'
  | 'DONE'
  | 'FAILED'
  | 'EXPIRED'
  | 'CANCEL_REQUESTED'
  | 'CANCELED';

export interface PaymentResponse {
  orderId: string;
  item: string;
  itemName: string;
  amount: number;
  status: PaymentStatus;
  method: string | null;
  failReason: string | null;
  vbankBank: string | null;
  vbankAccount: string | null;
  vbankDueAt: string | null;
  canceledAmount: number;
  createdAt: string;
  approvedAt: string | null;
  canceledAt: string | null;
  entitlements: EntitlementView[];
  refundableAmount: number | null;
}

export async function getPaymentConfig(): Promise<PaymentConfig> {
  const { data } = await api.get<PaymentConfig>('/api/payments/config');
  return data;
}

// refundPolicyAgreed: 청약철회 제한(디지털 콘텐츠) 고지 동의 — 서버가 이 값 없이는 주문을 안 만든다.
export async function createOrder(item: string, refundPolicyAgreed: boolean): Promise<OrderCreateResponse> {
  const { data } = await api.post<OrderCreateResponse>('/api/payments/orders', { item, refundPolicyAgreed });
  return data;
}

// 위젯 successUrl로 받은 값 그대로 서버 승인에 넘긴다. 금액은 서버가 재검증한다.
export async function confirmPayment(body: {
  paymentKey: string;
  orderId: string;
  amount: number;
}): Promise<PaymentResponse> {
  const { data } = await api.post<PaymentResponse>('/api/payments/confirm', body);
  return data;
}

export async function listPayments(): Promise<PaymentResponse[]> {
  const { data } = await api.get<PaymentResponse[]>('/api/payments');
  return data;
}

export async function getPayment(orderId: string): Promise<PaymentResponse> {
  const { data } = await api.get<PaymentResponse>(`/api/payments/${orderId}`);
  return data;
}

export async function cancelPayment(orderId: string, reason?: string): Promise<PaymentResponse> {
  const { data } = await api.post<PaymentResponse>(`/api/payments/${orderId}/cancel`, {
    reason: reason ?? '',
  });
  return data;
}
