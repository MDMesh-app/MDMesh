import { Suspense } from 'react';
import { defineMessages, FormattedMessage, useIntl } from 'react-intl';
import { createBrowserRouter, Navigate, Route, RouterProvider, Routes, useLocation } from 'react-router';
import { AuthProvider } from './auth/AuthContext';
import { ProtectedRoute } from './auth/ProtectedRoute';
import { ThemeProvider } from './ui/theme';
import { ToastProvider } from './ui/toast';
import { TooltipProvider } from './ui/Tooltip';
import { AppErrorBoundary } from './ui/AppErrorBoundary';
import { IntlRoot } from './i18n/IntlRoot';
import { legacyRedirect, paths } from './routes';
import { AppShell } from './ui/AppShell';
import { ButtonLink } from './ui/Button';
import { Card } from './ui/Card';
import { EmptyState } from './ui/EmptyState';
import { PageHeader } from './ui/PageHeader';
import { RoutePage } from './ext/hosts/RoutePage';
import { registry } from './ext/load';
import { RegistryProvider, useRegistry } from './ext/RegistryContext';
import { lazyComponent } from './ext/types';

// The signed-out pages. Signed-in ones are `route.page` contributions (ext/core/routes.ts), each its own chunk too.
const LoginPage = lazyComponent(() => import('./pages/LoginPage'), 'LoginPage');
const SetPasswordPage = lazyComponent(() => import('./pages/SetPasswordPage'), 'SetPasswordPage');

const messages = defineMessages({
  title: { id: 'app.notFound.title', defaultMessage: 'Page not found' },
  subtitle: {
    id: 'app.notFound.subtitle',
    defaultMessage: 'There is nothing at this address. Check the link, or start from the Overview.',
  },
  empty: { id: 'app.notFound.empty', defaultMessage: 'No page here' },
  home: { id: 'app.notFound.home', defaultMessage: 'Go to the Overview', description: 'Button back to the Overview page' },
});

/**
 * Unknown paths (old bookmarks were moved on in AppRoutes): the page doesn't exist, said inside the shell (a mistyped
 * device link no longer lands on the Overview without a word).
 */
function Fallback() {
  const { pathname } = useLocation();
  const intl = useIntl();
  return (
    <>
      <PageHeader title={intl.formatMessage(messages.title)} subtitle={intl.formatMessage(messages.subtitle)} />
      <Card>
        <EmptyState
          title={intl.formatMessage(messages.empty)}
          action={
            <ButtonLink to={paths.overview} variant="primary">
              <FormattedMessage {...messages.home} />
            </ButtonLink>
          }
        >
          <span className="v2-mono">{pathname}</span>
        </EmptyState>
      </Card>
    </>
  );
}

/**
 * The route table. Signed-out users are sent to /login?from=<path> (ProtectedRoute), which returns them there after
 * sign-in (a same-origin path only, auth/safeReturnPath). Signed-in pages are the registry's `route.page` slot, core
 * and extensions alike: each renders inside AppShell, in its own error boundary, or as "You don't have access" when
 * the role lacks the page's permission (ext/hosts/RoutePage).
 */
export function AppRoutes() {
  const pages = useRegistry().entries('route.page');
  const { pathname, search, hash } = useLocation();
  // Old bookmarks move to their page before anything else, signed in or not (so sign-in returns to the new path).
  const legacy = legacyRedirect(pathname);
  if (legacy) return <Navigate to={legacy + search + hash} replace />;
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
          <Route path="*" element={<Fallback />} />
        </Route>
      </Route>
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
    <IntlRoot>
      <AuthProvider>
        <ThemeProvider>
          <ToastProvider>
            <TooltipProvider>
              <RegistryProvider registry={registry}>
                <RouterProvider router={router} />
              </RegistryProvider>
            </TooltipProvider>
          </ToastProvider>
        </ThemeProvider>
      </AuthProvider>
    </IntlRoot>
  );
}
