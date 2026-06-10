import { api } from './client';

// 진단 평가. 대상은 항상 그 사연의 최신 진단이라 진단 id 없이 storyId로만 부른다.
// 점수 제출과 텍스트 제출이 나뉜 이유: 텍스트를 쓰다 이탈해도 점수는 이미 저장돼 있다.
export interface ReviewStatus {
  reviewed: boolean;
  score: number | null;
  rewardAvailable: boolean; // 보상은 유저당 1회 — false면 보상 문구를 띄우지 않는다
}

export async function getReviewStatus(storyId: number): Promise<ReviewStatus> {
  const { data } = await api.get<ReviewStatus>(`/api/stories/${storyId}/review`);
  return data;
}

export interface ReviewSubmitResult {
  chatBonus: number; // 지급된 대화 이용권 수 — 문구가 서버 설정과 어긋나지 않게 값으로 받는다
}

export async function submitReviewScore(storyId: number, score: number): Promise<ReviewSubmitResult> {
  const { data } = await api.post<ReviewSubmitResult>(`/api/stories/${storyId}/review`, { score });
  return data;
}

export async function submitReviewComment(storyId: number, comment: string): Promise<void> {
  await api.put(`/api/stories/${storyId}/review/comment`, { comment });
}
