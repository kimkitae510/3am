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
      // 애초에 토큰이 없던 방문자는 세션이 끊긴 게 아니다. 공개 화면(이용권 안내 등)이
      // 인증 필요한 API를 건드린 것뿐이라, 로그인으로 밀어내지 않고 호출한 화면이 처리하게 둔다.
      // 밀어내면 로그인 없이 볼 수 있어야 하는 화면이 통째로 로그인 폼으로 바뀐다.
      if (!tokenStore.getRefresh()) {
        return Promise.reject(error);
      }
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

// 쿨다운 거절(Q003 등)이 실어 보낸 남은 초. 화면이 카운트다운으로 보여준다.
export function extractRetryAfterSeconds(err: unknown): number | null {
  if (axios.isAxiosError(err)) {
    const data = err.response?.data as { retryAfterSeconds?: number | null } | undefined;
    return data?.retryAfterSeconds ?? null;
  }
  return null;
}

// 백엔드 에러 코드(Q001, P007 등). 코드별 분기(쿼터 소진 → 구매 유도 등)에 쓴다.
export function extractErrorCode(err: unknown): string | null {
  if (axios.isAxiosError(err)) {
    const data = err.response?.data as { code?: string } | undefined;
    return data?.code ?? null;
  }
  return null;
}
