import { api } from './client';

export interface StoryResponse {
  id: number;
  title: string;
  createdAt: string;
  updatedAt: string;
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
