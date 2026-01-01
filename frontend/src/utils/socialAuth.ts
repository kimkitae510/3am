import type { OAuthProvider } from '../api/auth';

// 인가 요청에 쓴 값들을 콜백 페이지가 다시 써야 해서(state 대조, redirect_uri 동일성) 세션에 남긴다.
const STATE_KEY = 'oauth_state';
const REDIRECT_KEY = 'oauth_redirect_uri';

const CLIENT_IDS: Record<OAuthProvider, string | undefined> = {
  kakao: import.meta.env.VITE_KAKAO_CLIENT_ID as string | undefined,
  naver: import.meta.env.VITE_NAVER_CLIENT_ID as string | undefined,
};

export function redirectUriFor(provider: OAuthProvider): string {
  return `${window.location.origin}/oauth/callback/${provider}`;
}

// 키가 설정돼 있으면 인가 페이지로 이동(redirected), 없으면 개발 mock 흐름(mock)을 알린다.
export function startSocialLogin(provider: OAuthProvider): 'redirected' | 'mock' {
  const clientId = CLIENT_IDS[provider];
  if (!clientId) {
    return 'mock';
  }

  const state = crypto.randomUUID();
  sessionStorage.setItem(STATE_KEY, state);
  const redirectUri = redirectUriFor(provider);
  sessionStorage.setItem(REDIRECT_KEY, redirectUri);

  const params = new URLSearchParams({
    response_type: 'code',
    client_id: clientId,
    redirect_uri: redirectUri,
    state,
  });
  const base =
    provider === 'kakao'
      ? 'https://kauth.kakao.com/oauth/authorize'
      : 'https://nid.naver.com/oauth2.0/authorize';
  window.location.href = `${base}?${params.toString()}`;
  return 'redirected';
}

export function consumeStoredState(): { state: string | null; redirectUri: string | null } {
  const state = sessionStorage.getItem(STATE_KEY);
  const redirectUri = sessionStorage.getItem(REDIRECT_KEY);
  sessionStorage.removeItem(STATE_KEY);
  sessionStorage.removeItem(REDIRECT_KEY);
  return { state, redirectUri };
}
