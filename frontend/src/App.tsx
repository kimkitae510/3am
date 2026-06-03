import { Navigate, Route, Routes, useParams } from 'react-router-dom';
import { IntroPage } from './pages/IntroPage';
import { LoginPage } from './pages/LoginPage';
import { SignupPage } from './pages/SignupPage';
import { GuestLinkPage } from './pages/GuestLinkPage';
import { StoryEntryPage } from './pages/StoryEntryPage';
import { ChatPage } from './pages/ChatPage';
import { AssessmentPage } from './pages/AssessmentPage';
import { HistoryPage } from './pages/HistoryPage';
import { PaymentPage } from './pages/PaymentPage';
import { PaymentResultPage } from './pages/PaymentResultPage';
import { TermsPage } from './pages/TermsPage';
import { PrivacyPage } from './pages/PrivacyPage';
import { OAuthCallbackPage } from './pages/OAuthCallbackPage';

// 서랍에서 다른 사연으로 갈아타면 경로만 바뀌고 컴포넌트는 그대로 살아 있어 이전 방의
// 메시지와 입력이 잠깐 남는다. 예전엔 목록 화면을 거쳐서 이 전환 자체가 없었다.
function ChatRoute() {
  const { storyId } = useParams();
  return <ChatPage key={storyId} />;
}

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<IntroPage />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/signup" element={<SignupPage />} />
      {/* 게스트 → 계정 연결(소셜 또는 이메일). 대화를 그대로 이어간다 */}
      <Route path="/guest-link" element={<GuestLinkPage />} />
      {/* 카카오/네이버 인가 리다이렉트 도착지 */}
      <Route path="/oauth/callback/:provider" element={<OAuthCallbackPage />} />
      <Route path="/terms" element={<TermsPage />} />
      <Route path="/privacy" element={<PrivacyPage />} />
      <Route path="/stories" element={<StoryEntryPage />} />
      <Route path="/stories/:storyId" element={<ChatRoute />} />
      <Route path="/stories/:storyId/assessment" element={<AssessmentPage />} />
      <Route path="/stories/:storyId/history" element={<HistoryPage />} />
      <Route path="/payment" element={<PaymentPage />} />
      {/* 토스 위젯 리다이렉트 도착지 — success는 여기서 서버 승인까지 마친다 */}
      <Route path="/payment/success" element={<PaymentResultPage />} />
      <Route path="/payment/fail" element={<PaymentResultPage />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
