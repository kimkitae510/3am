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

export interface SignupRequest {
  email: string;
  password: string;
  nickname: string;
}

export interface SignupResponse {
  id: number;
  email: string;
  nickname: string;
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
