import { useCallback } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';

// 결제로 들어가기 직전의 자리. 토스 결제는 외부 페이지로 나갔다가 새 문서로 돌아오기 때문에
// 히스토리도 라우터 state도 남지 않는다 — 돌아올 자리를 탭에 적어두는 수밖에 없다.
// sessionStorage라 탭을 닫으면 함께 사라진다(다음 방문에 남은 자리로 끌려가지 않는다).
const ORIGIN_KEY = 'paymentOrigin';

// 이용권 화면은 대화방, 분석, 서랍 여러 곳에서 열린다. 들어온 자리를 적고 이동한다.
export function useGoPayment() {
  const navigate = useNavigate();
  const location = useLocation();
  return useCallback(() => {
    sessionStorage.setItem(ORIGIN_KEY, location.pathname);
    navigate('/payment');
  }, [navigate, location.pathname]);
}

// 적어둔 자리가 없으면 통로(/stories)로 — 최근 방이 열린다.
export function paymentOrigin(): string {
  return sessionStorage.getItem(ORIGIN_KEY) || '/stories';
}
