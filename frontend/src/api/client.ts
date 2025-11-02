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

// 401이면 refreshToken으로 1회 자동 재발급 후 원요청 재시도.
// 순환 import를 피하려 auth.reissue 대신 fetch로 직접 호출하고, 동시 401은 하나의 재발급으로 묶는다.
let refreshing: Promise<string> | null = null;

async function reissueAccessToken(): Promise<string> {
  const refreshToken = tokenStore.getRefresh();
  if (!refreshToken) throw new Error('no refresh token');
  const res = await fetch(`${import.meta.env.VITE_API_BASE_URL}/api/auth/reissue`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken }),
  });
  if (!res.ok) throw new Error('reissue failed');
  const data = (await res.json()) as { accessToken: string; refreshToken: string };
  tokenStore.set(data.accessToken, data.refreshToken);
  return data.accessToken;
}

api.interceptors.response.use(
  (res) => res,
  async (error) => {
    const original = error.config;
    const status = error.response?.status;
    const url: string = original?.url ?? '';
    const isAuthCall = url.includes('/api/auth/');

    if (status === 401 && original && !original._retried && !isAuthCall) {
      original._retried = true;
      try {
        refreshing = refreshing ?? reissueAccessToken();
        const newToken = await refreshing;
        refreshing = null;
        original.headers.Authorization = `Bearer ${newToken}`;
        return api(original);
      } catch (e) {
        refreshing = null;
        tokenStore.clear();
        if (window.location.pathname !== '/login') {
          window.location.href = '/login';
        }
        return Promise.reject(e);
      }
    }
    return Promise.reject(error);
  },
);

// 백엔드 ErrorResponse에서 사람이 읽을 메시지를 뽑아낸다. 형태 미상이면 기본 문구.
export function extractErrorMessage(err: unknown, fallback = '요청 처리 중 오류가 발생했어요.'): string {
  if (axios.isAxiosError(err)) {
    const data = err.response?.data as { message?: string } | undefined;
    if (data?.message) return data.message;
    if (!err.response) return '서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요.';
  }
  return fallback;
}
