import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom'
import { useSelector } from 'react-redux'
import { ThemeProvider, CssBaseline } from '@mui/material'
import theme from './theme'
import LayoutWrapper from './components/layout/LayoutWrapper'
import ServerStatusGate from './components/system/ServerStatusGate'
import { ProfilePhotoProvider } from './contexts/ProfilePhotoContext'

// Auth pages
import LoginPage from './pages/auth/LoginPage'
import RegisterPage from './pages/auth/RegisterPage'
import ForgotPasswordPage from './pages/auth/ForgotPasswordPage'
import ResetPasswordPage from './pages/auth/ResetPasswordPage'

// User pages
import DashboardPage from './pages/user/DashboardPage'
import ProfilePage from './pages/user/ProfilePage'
import UserExamReportPageEnhanced from './pages/user/UserExamReportPageEnhanced'

// Exam pages
import ExamApplicationPage from './pages/exam/ExamApplicationPage'
import ExamSchedulePage from './pages/exam/ExamSchedulePage'
import ExamPage from './pages/exam/ExamPage'
import PaymentPage from './pages/exam/PaymentPage'
import ResultPage from './pages/exam/ResultPage'

// Certificate pages
import CertificatePage from './pages/certificate/CertificatePage'

// Certification pages
import CertificationJourneyPage from './pages/certification/CertificationJourneyPage'

// Admin pages
import AdminDashboardPage from './pages/admin/AdminDashboardPage'
import AdminUsersPage from './pages/admin/AdminUsersPage'
import AdminExamsPage from './pages/admin/AdminExamsPage'
import AdminBookingWindowsPage from './pages/admin/AdminBookingWindowsPage'
import AdminQuestionsPage from './pages/admin/AdminQuestionsPage'
import AdminViolationsPage from './pages/admin/AdminViolationsPage'
import AdminPaymentsPage from './pages/admin/AdminPaymentsPage'
import ExamReportPageEnhanced from './pages/admin/ExamReportPageEnhanced'

// Report pages
import ReportPage from './pages/report/ReportPage'
import UserReportPage from './pages/report/UserReportPage'

function App() {
  const { isAuthenticated, user } = useSelector((state) => state.auth)
  const isAdmin = user?.role === 'ADMIN' || user?.roles?.includes('ADMIN')

  // Where a signed-in visitor belongs. `/` is the public home (the sign-in
  // screen), so authentication moves the address bar to /dashboard rather than
  // leaving the app parked on the marketing route.
  const homePath = isAdmin ? '/admin/dashboard' : '/dashboard'

  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      {/*
        * Opted in early: the protected-route tree hangs off a `/*` splat, and
        * v7 changes how relative paths resolve inside one. Safe here because
        * nothing in the app navigates relatively — every navigate() and Link
        * target is absolute — so this only silences the deprecation notice
        * rather than moving any route.
        */}
      <Router future={{ v7_relativeSplatPath: true }}>
        {/*
          * Inside the Router, and wrapping every route: while the API is
          * unreachable or deliberately closed for a deploy, this replaces the
          * whole tree with the maintenance screen and puts it back on its own
          * once the service answers. It needs the router context to recognise
          * the one route it must not take over — a live exam.
          */}
        <ServerStatusGate>
          <Routes>
            {/*
              * Home — the sign-in screen. Signed-out visitors land here; signed-in
              * ones are forwarded straight to their dashboard.
              *
              * Declared as an index route rather than path="/" deliberately: the
              * protected tree below hangs off a `/*` splat, and a plain path="/"
              * loses the ranking contest against that splat, which silently
              * swallows the home screen. An index route outranks it.
              */}
            <Route
              index
              element={isAuthenticated ? <Navigate to={homePath} replace /> : <LoginPage />}
            />

            {/* Public Auth Routes */}
            <Route
              path="/login"
              element={isAuthenticated ? <Navigate to={homePath} replace /> : <LoginPage />}
            />
            <Route path="/register" element={<RegisterPage />} />
            <Route path="/forgot-password" element={<ForgotPasswordPage />} />
            <Route path="/reset-password/:token" element={<ResetPasswordPage />} />

            {/* Exam route - no drawer/layout during exam */}
            <Route
              path="/exam/:applicationId"
              element={isAuthenticated ? <ExamPage /> : <Navigate to="/" replace />}
            />

            {/* Protected User Routes */}
            <Route
              path="/*"
              element={
                isAuthenticated ? (
                  <ProfilePhotoProvider>
                    <LayoutWrapper />
                  </ProfilePhotoProvider>
                ) : (
                  <Navigate to="/" replace />
                )
              }
            >
              {isAdmin ? (
                <>
                  <Route path="dashboard" element={<Navigate to="/admin/dashboard" replace />} />
                  <Route path="admin/dashboard" element={<AdminDashboardPage />} />
                  <Route path="admin/users" element={<AdminUsersPage />} />
                  <Route path="admin/exams" element={<AdminExamsPage />} />
                  <Route path="admin/booking-windows" element={<AdminBookingWindowsPage />} />
                  <Route path="admin/questions" element={<AdminQuestionsPage />} />
                  <Route path="admin/payments" element={<AdminPaymentsPage />} />
                  <Route path="admin/violations" element={<AdminViolationsPage />} />
                  <Route path="admin/exam-reports" element={<ExamReportPageEnhanced />} />
                  <Route path="admin/reports" element={<ReportPage />} />
                  <Route path="profile" element={<ProfilePage />} />
                  <Route path="*" element={<Navigate to="/admin/dashboard" replace />} />
                </>
              ) : (
                <>
                  <Route path="dashboard" element={<DashboardPage />} />
                  <Route path="profile" element={<ProfilePage />} />
                  <Route path="certifications" element={<CertificationJourneyPage />} />
                  <Route path="exams" element={<ExamApplicationPage />} />
                  <Route path="exam/schedule/:applicationId" element={<ExamSchedulePage />} />
                  <Route path="exam/payment/:applicationId" element={<PaymentPage />} />
                  <Route path="exam/result/:sessionId" element={<ResultPage />} />
                  <Route path="certificates" element={<CertificatePage />} />
                  <Route path="exam-reports" element={<UserExamReportPageEnhanced />} />
                  <Route path="reports" element={<UserReportPage />} />
                  <Route path="*" element={<Navigate to="/dashboard" replace />} />
                </>
              )}
            </Route>
          </Routes>
        </ServerStatusGate>
      </Router>
    </ThemeProvider>
  )
}

export default App
