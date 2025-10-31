import axios from 'axios';
import { tokenStore } from './tokenStore';

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL,
  headers: { 'Content-Type': 'application/json' },
});

api.interceptors.request.use((config) => {
  const token = tokenStore.getAccess();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// 백엔드 ErrorResponse에서 사람이 읽을 메시지를 뽑아낸다. 형태 미상이면 기본 문구.
export function extractErrorMessage(err: unknown, fallback = '요청 처리 중 오류가 발생했어요.'): string {
  if (axios.isAxiosError(err)) {
    const data = err.response?.data as { message?: string } | undefined;
    if (data?.message) return data.message;
    if (!err.response) return '서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요.';
  }
  return fallback;
}
