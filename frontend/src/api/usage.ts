import { api } from './client';

export interface UsageStatusResponse {
  chatRemaining: number;
  chatDailyLimit: number;
  chatPaidRemaining: number; // 결제 이용권 잔여(일일 한도와 별개, 이월됨)
  assessmentRemaining: number;
  assessmentDailyLimit: number;
  assessmentPaidRemaining: number;
}

// 오늘 남은 대화/진단 횟수. 화면 표시용이라 실패해도 치명적이지 않다(호출부에서 무시).
export async function getUsage(): Promise<UsageStatusResponse> {
  const { data } = await api.get<UsageStatusResponse>('/api/usage');
  return data;
}
