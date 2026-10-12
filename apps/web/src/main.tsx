import React, { lazy, Suspense } from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigProvider, App as AntApp, Spin } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { AuthProvider, useAuth } from './app/auth';
import Layout from './app/Layout';
import AuthPage from './features/auth/AuthPage';
import { ErrorBoundary } from './app/ErrorBoundary';
import './styles.css';
const Books = lazy(() => import('./features/catalog/BooksPage'));
const Copies = lazy(() => import('./features/catalog/CopiesPage'));
const Categories = lazy(() => import('./features/catalog/CategoriesPage'));
const Readers = lazy(() => import('./features/readers/ReadersPage'));
const Circulation = lazy(() => import('./features/circulation/CirculationPage'));
const Loans = lazy(() => import('./features/loans/LoansPage'));
const Dashboard = lazy(() => import('./features/dashboard/DashboardPage'));
const client = new QueryClient({
  defaultOptions: { queries: { retry: false, staleTime: 15000 }, mutations: { retry: false } },
});
function Admin({ children }: { children: React.ReactNode }) {
  return useAuth().user?.role === 'admin' ? children : <Navigate to="/books" replace />;
}
function Routing() {
  const { user, loading } = useAuth();
  if (loading)
    return (
      <div className="loading">
        <Spin />
      </div>
    );
  if (!user) return <AuthPage />;
  if (user.mustChangePassword) return <AuthPage change />;
  return (
    <Suspense
      fallback={
        <div className="loading">
          <Spin />
        </div>
      }
    >
      <Routes>
        <Route path="/login" element={<Navigate to="/" replace />} />
        <Route path="/change-password" element={<AuthPage change />} />
        <Route element={<Layout />}>
          <Route
            index
            element={user.role === 'admin' ? <Dashboard /> : <Navigate to="/books" replace />}
          />
          <Route path="books" element={<Books />} />
          <Route path="loans" element={<Loans />} />
          <Route
            path="copies"
            element={
              <Admin>
                <Copies />
              </Admin>
            }
          />
          <Route
            path="categories"
            element={
              <Admin>
                <Categories />
              </Admin>
            }
          />
          <Route
            path="readers"
            element={
              <Admin>
                <Readers />
              </Admin>
            }
          />
          <Route
            path="circulation"
            element={
              <Admin>
                <Circulation />
              </Admin>
            }
          />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </Suspense>
  );
}
ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider
      locale={zhCN}
      theme={{
        token: {
          colorPrimary: '#245e4f',
          borderRadius: 6,
          colorBgLayout: '#f6f5f0',
          fontFamily: '"PingFang SC", "Noto Sans CJK SC", sans-serif',
        },
      }}
    >
      <AntApp>
        <ErrorBoundary>
          <QueryClientProvider client={client}>
            <BrowserRouter>
              <AuthProvider>
                <Routing />
              </AuthProvider>
            </BrowserRouter>
          </QueryClientProvider>
        </ErrorBoundary>
      </AntApp>
    </ConfigProvider>
  </React.StrictMode>,
);
