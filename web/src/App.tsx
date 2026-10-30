import { Suspense } from 'react';
import { createBrowserRouter, Navigate, Route, RouterProvider, Routes, useLocation } from 'react-router';
import { AuthProvider } from './auth/AuthContext';
import { ProtectedRoute } from './auth/ProtectedRoute';
import { ThemeProvider } from './ui/theme';
import { ToastProvider } from './ui/toast';
import { AppErrorBoundary } from './ui/AppErrorBoundary';
import { legacyRedirect, paths } from './routes';
import { AppShell } from './ui/AppShell';
import { RoutePage } from './ext/hosts/RoutePage';
import { registry } from './ext/load';
import { RegistryProvider, useRegistry } from './ext/RegistryContext';
import { lazyComponent } from './ext/types';

// The signed-out pages. Signed-in ones are `route.page` contributions (ext/core/routes.ts), each its own chunk too.
const LoginPage = lazyComponent(() => import('./pages/LoginPage'), 'LoginPage');
const SetPasswordPage = lazyComponent(() => import('./pages/SetPasswordPage'), 'SetPasswordPage');

/** Unknown paths: an old bookmark (`/v2/...`, `/dashboard`, `/configs`) goes to its page now, anything else home. */
function Fallback() {
  const { pathname, search, hash } = useLocation();
  const to = legacyRedirect(pathname);
  return <Navigate to={to ? to + search + hash : paths.overview} replace />;
}

/**
 * The route table. Signed-out users are sent to /login?from=<path> (ProtectedRoute), which returns them there after
 * sign-in (a same-origin path only, auth/safeReturnPath). Signed-in pages are the registry's `route.page` slot, core
 * and extensions alike: each renders inside AppShell, in its own error boundary, or as "You don't have access" when
 * the role lacks the page's permission (ext/hosts/RoutePage).
 */
export function AppRoutes() {
  const pages = useRegistry().entries('route.page');
  return (
    <Routes>
      <Route
        path={paths.login}
        element={
          <Suspense fallback={null}>
            <LoginPage />
          </Suspense>
        }
      />
      <Route
        path={paths.setPassword}
        element={
          <Suspense fallback={null}>
            <SetPasswordPage />
          </Suspense>
        }
      />
      <Route element={<ProtectedRoute />}>
        <Route element={<AppShell />}>
          {pages.map((entry) => (
            <Route key={entry.key} path={entry.contribution.path} element={<RoutePage entry={entry} />} />
          ))}
        </Route>
      </Route>
      <Route path="*" element={<Fallback />} />
    </Routes>
  );
}

// A data router around the route table (one splat route), so a page can hold navigation with useBlocker (the
// configuration editor's "Leave without saving?"). Routing itself is the <Routes> above.
const router = createBrowserRouter([
  {
    path: '*',
    element: (
      <AppErrorBoundary>
        <AppRoutes />
      </AppErrorBoundary>
    ),
  },
]);

export default function App() {
  return (
    <AuthProvider>
      <ThemeProvider>
        <ToastProvider>
          <RegistryProvider registry={registry}>
            <RouterProvider router={router} />
          </RegistryProvider>
        </ToastProvider>
      </ThemeProvider>
    </AuthProvider>
  );
}
