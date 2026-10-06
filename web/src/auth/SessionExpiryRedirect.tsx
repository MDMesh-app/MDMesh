import { useEffect, useRef } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { onSessionExpired } from '../api/client';
import { useAuth } from './AuthContext';

/** Bridges an expired API session to client-side auth state and sign-in routing. */
export function SessionExpiryRedirect() {
  const { isAuthenticated, expireSession } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const handled = useRef(false);

  useEffect(() => {
    if (isAuthenticated) handled.current = false;
  }, [isAuthenticated]);

  useEffect(() => onSessionExpired(() => {
    if (!isAuthenticated || handled.current) return;
    handled.current = true;
    const from = `${location.pathname}${location.search}${location.hash}`;
    expireSession();
    navigate('/login', { replace: true, state: { from, sessionExpired: true } });
  }), [expireSession, isAuthenticated, location.hash, location.pathname, location.search, navigate]);

  return null;
}
