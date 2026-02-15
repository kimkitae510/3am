import { Navigate, Route, Routes } from 'react-router-dom';
import { LoginPage } from './pages/LoginPage';
import { SignupPage } from './pages/SignupPage';
import { GuestLinkPage } from './pages/GuestLinkPage';
import { StoryListPage } from './pages/StoryListPage';
import { ChatPage } from './pages/ChatPage';
import { AssessmentPage } from './pages/AssessmentPage';
import { HistoryPage } from './pages/HistoryPage';
import { PaymentPage } from './pages/PaymentPage';
import { PaymentResultPage } from './pages/PaymentResultPage';
import { TermsPage } from './pages/TermsPage';
import { PrivacyPage } from './pages/PrivacyPage';
import { OAuthCallbackPage } from './pages/OAuthCallbackPage';

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/login" replace />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/signup" element={<SignupPage />} />
      {/* 게스트 → 계정 연결(소셜 또는 이메일). 대화를 그대로 이어간다 */}
      <Route path="/guest-link" element={<GuestLinkPage />} />
      {/* 카카오/네이버 인가 리다이렉트 도착지 */}
      <Route path="/oauth/callback/:provider" element={<OAuthCallbackPage />} />
      <Route path="/terms" element={<TermsPage />} />
      <Route path="/privacy" element={<PrivacyPage />} />
      <Route path="/stories" element={<StoryListPage />} />
      <Route path="/stories/:storyId" element={<ChatPage />} />
      <Route path="/stories/:storyId/assessment" element={<AssessmentPage />} />
      <Route path="/stories/:storyId/history" element={<HistoryPage />} />
      <Route path="/payment" element={<PaymentPage />} />
      {/* 토스 위젯 리다이렉트 도착지 — success는 여기서 서버 승인까지 마친다 */}
      <Route path="/payment/success" element={<PaymentResultPage />} />
      <Route path="/payment/fail" element={<PaymentResultPage />} />
      <Route path="*" element={<Navigate to="/login" replace />} />
    </Routes>
  );
}
