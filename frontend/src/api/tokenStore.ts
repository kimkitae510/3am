// 토큰 보관소. 지금은 localStorage 사용 — XSS에 노출되는 트레이드오프가 있으나
// 학습/포폴 단계에서 단순함 우선. 운영 전환 시 refreshToken은 httpOnly 쿠키로 옮길 것.
const ACCESS = 'accessToken';
const REFRESH = 'refreshToken';

export const tokenStore = {
  getAccess: () => localStorage.getItem(ACCESS),
  getRefresh: () => localStorage.getItem(REFRESH),
  set: (accessToken: string, refreshToken: string) => {
    localStorage.setItem(ACCESS, accessToken);
    localStorage.setItem(REFRESH, refreshToken);
  },
  clear: () => {
    localStorage.removeItem(ACCESS);
    localStorage.removeItem(REFRESH);
  },
};
