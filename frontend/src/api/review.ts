import { api } from './client';

// 분석 평가. 대상은 항상 그 사연의 최신 분석이라 분석 id 없이 storyId로만 부른다.
// 점수는 업서트(다시 누르면 바뀜), 후기도 언제든 고칠 수 있다.
// 보상은 후기까지 완성했을 때 유저당 1회 — 지급량은 후기 제출 응답으로 돌아온다.
export interface ReviewStatus {
  reviewed: boolean;
  score: number | null;
  comment: string | null;
  rewardAvailable: boolean; // false면 보상 문구를 띄우지 않는다(이미 받은 유저)
}

export async function getReviewStatus(storyId: number): Promise<ReviewStatus> {
  const { data } = await api.get<ReviewStatus>(`/api/stories/${storyId}/review`);
  return data;
}

export async function submitReviewScore(storyId: number, score: number): Promise<void> {
  await api.post(`/api/stories/${storyId}/review`, { score });
}

export interface ReviewSubmitResult {
  chatBonus: number; // 이번 제출로 지급된 대화 이용권 수(미지급이면 0)
}

export async function submitReviewComment(storyId: number, comment: string): Promise<ReviewSubmitResult> {
  const { data } = await api.put<ReviewSubmitResult>(`/api/stories/${storyId}/review/comment`, { comment });
  return data;
}
