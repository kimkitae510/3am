import { api } from './client';

export interface StoryResponse {
  id: number;
  title: string;
  createdAt: string;
  updatedAt: string;
  unread: boolean; // 마지막으로 읽은 뒤 새 답이 있음 — 목록 배지용
  lastMessage: string | null; // 마지막 메시지 미리보기(한 줄, 서버에서 잘림). 대화 없으면 null
}

export async function listStories(): Promise<StoryResponse[]> {
  const { data } = await api.get<StoryResponse[]>('/api/stories');
  return data;
}

export async function createStory(title?: string): Promise<StoryResponse> {
  const { data } = await api.post<StoryResponse>('/api/stories', title ? { title } : {});
  return data;
}

export async function deleteStory(storyId: number): Promise<void> {
  await api.delete(`/api/stories/${storyId}`);
}

export type MessageRole = 'USER' | 'ASSISTANT';

export interface MessageResponse {
  id: number;
  role: MessageRole;
  content: string;
  createdAt: string;
}

export interface MessagePageResponse {
  messages: MessageResponse[];
  nextCursor: number | null;
  hasNext: boolean;
}

// 과거→현재 순으로 최근 size개. cursor보다 과거를 이어서 로드(위로 스크롤).
export async function getMessages(
  storyId: number,
  cursor?: number,
  size = 30,
): Promise<MessagePageResponse> {
  const { data } = await api.get<MessagePageResponse>(`/api/stories/${storyId}/messages`, {
    params: { cursor, size },
  });
  return data;
}

// 폴링 방식: 유저 메시지만 저장하고 즉시 반환(202). 어시스턴트 답은 이후 since로 받아온다.
export async function sendMessage(storyId: number, content: string): Promise<MessageResponse> {
  const { data } = await api.post<MessageResponse>(`/api/stories/${storyId}/messages`, { content });
  return data;
}

// afterId 이후 새로 생긴 메시지(주로 어시스턴트 답)를 시간순으로.
export async function getMessagesSince(storyId: number, afterId: number): Promise<MessageResponse[]> {
  const { data } = await api.get<MessageResponse[]>(`/api/stories/${storyId}/messages/since`, {
    params: { after: afterId },
  });
  return data;
}
