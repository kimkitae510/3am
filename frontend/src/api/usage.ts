import { api } from './client';

// 잔여는 이용권 하나로 합쳐졌다 — 일일 무료 쿼터가 폐지되면서 "오늘 남은 N회 + 이용권 M회"를
// 유저가 더해 읽어야 하던 두 숫자가 사라졌다.
export interface UsageStatusResponse {
  chatRemaining: number;
  assessmentRemaining: number;
  guest: boolean; // 게스트 계정이면 true — 화면이 충전 대신 '계정 연결' 동선을 보여준다
  chatCooldownSeconds: number; // 연속 실패 쿨다운 남은 초(0이면 없음) — 재입장 시 잠금 복원용
}

// 남은 대화/분석 횟수. 화면 표시용이라 실패해도 치명적이지 않다(호출부에서 무시).
export async function getUsage(): Promise<UsageStatusResponse> {
  const { data } = await api.get<UsageStatusResponse>('/api/usage');
  return data;
}
