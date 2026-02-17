// 진단 수치 표시 기준 — 진단 페이지와 기록 페이지가 같은 값을 쓰도록 한곳에 모은다.
// (페이지마다 따로 두던 시절 게이지 만점(70/80)과 밴드 라벨이 서로 어긋났던 전례가 있다.)

export const GAUGE_MAX = 95; // 확률 상한(정책, 백엔드 클램프와 동일). 96~100은 재회 제안 확정 몫.

// 확률은 3~95 사이로 나온다(백엔드 클램프). BASE 50(중립)이 "보통"에 오도록 경계를 잡았다.
// 상한이 넓어져(80→95) 강한 긍정도 표현되므로 "매우 높음" 밴드를 추가했다.
export function bandLabel(prob: number): string {
  if (prob >= 100) return '상대의 재회 제안 유효';
  if (prob < 25) return '매우 낮음';
  if (prob < 45) return '낮음';
  if (prob < 65) return '보통';
  if (prob < 82) return '높음';
  return '매우 높음';
}
