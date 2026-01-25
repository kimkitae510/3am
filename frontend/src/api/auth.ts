import { api } from './client';
import { tokenStore } from './tokenStore';

export interface LoginRequest {
  email: string;
  password: string;
}

export interface TokenResponse {
  grantType: string;
  accessToken: string;
  refreshToken: string;
}

// 서버 ConsentType 이름과 1:1. 가입이 성립하려면 전부 필요하다(서버가 다시 검증).
export const SIGNUP_CONSENTS = ['TERMS', 'PRIVACY', 'SENSITIVE', 'DISCLAIMER'] as const;

export interface SignupRequest {
  email: string;
  password: string;
  verificationCode: string;
  consents: string[];
}

export type OAuthProvider = 'kakao' | 'naver';

export interface OAuthLoginRequest {
  code: string;
  state?: string;
  redirectUri: string;
  // 소셜은 첫 로그인이 곧 가입이라 동의를 같이 싣는다. 기존 계정 로그인이면 서버가 무시한다.
  consents?: string[];
}

export interface SignupResponse {
  id: number;
  email: string;
}

export async function login(body: LoginRequest): Promise<TokenResponse> {
  const { data } = await api.post<TokenResponse>('/api/auth/login', body);
  tokenStore.set(data.accessToken, data.refreshToken);
  return data;
}

export async function signup(body: SignupRequest): Promise<SignupResponse> {
  const { data } = await api.post<SignupResponse>('/api/users/signup', body);
  return data;
}

export async function requestEmailVerification(email: string): Promise<void> {
  await api.post('/api/users/email-verifications', { email });
}

export async function oauthLogin(provider: OAuthProvider, body: OAuthLoginRequest): Promise<TokenResponse> {
  const { data } = await api.post<TokenResponse>(`/api/auth/oauth/${provider}`, body);
  tokenStore.set(data.accessToken, data.refreshToken);
  return data;
}

export async function logout(): Promise<void> {
  try {
    await api.post('/api/auth/logout');
  } finally {
    // 서버 응답과 무관하게 클라이언트 토큰은 반드시 비운다.
    tokenStore.clear();
  }
}
