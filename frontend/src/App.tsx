import { Navigate, Route, Routes } from 'react-router-dom';
import { LoginPage } from './pages/LoginPage';
import { SignupPage } from './pages/SignupPage';
import { StoryListPage } from './pages/StoryListPage';
import { ChatPage } from './pages/ChatPage';
import { AssessmentPage } from './pages/AssessmentPage';

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/login" replace />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/signup" element={<SignupPage />} />
      <Route path="/stories" element={<StoryListPage />} />
      <Route path="/stories/:storyId" element={<ChatPage />} />
      <Route path="/stories/:storyId/assessment" element={<AssessmentPage />} />
      <Route path="*" element={<Navigate to="/login" replace />} />
    </Routes>
  );
}
