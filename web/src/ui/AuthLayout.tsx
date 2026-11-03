import { useEffect, useState, type FormEventHandler, type ReactNode } from 'react';
import { fetchAuthOptions } from '../api/auth';
import { getUpdateStatus } from '../api/updates';
import { Wordmark } from './Sidebar';
import { cx } from './cx';
import './AuthLayout.css';

/**
 * Sign-in / set-password frame: centred 400px card (32/32/28 padding, r20, faint shadow) with the
 * 18px wordmark, a 24/600 title and a 14px secondary subtitle, and a 13px footer line under the card.
 */
export function AuthLayout({
  title,
  subtitle,
  children,
  footer,
  onSubmit,
  className,
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  children?: ReactNode;
  footer?: ReactNode;
  /** The card is a <form> when this is given. */
  onSubmit?: FormEventHandler<HTMLFormElement>;
  className?: string;
}) {
  const body = (
    <>
      <Wordmark size={18} />
      <div className="v2-auth__titles">
        <h1 className="v2-auth__title">{title}</h1>
        {subtitle ? <p className="v2-auth__sub">{subtitle}</p> : null}
      </div>
      {children}
    </>
  );
  return (
    <div className={cx('v2-auth', className)}>
      {onSubmit ? (
        <form className="v2-auth__card" onSubmit={onSubmit} noValidate>
          {body}
        </form>
      ) : (
        <div className="v2-auth__card">{body}</div>
      )}
      {footer ? <div className="v2-auth__foot">{footer}</div> : null}
    </div>
  );
}

type Conn = 'checking' | 'ok' | 'down';

/** The console's own version for the footer: the running server (public `/update/status`), else the build's. */
const BUILD_VERSION = (import.meta.env as Record<string, string | undefined>).VITE_APP_VERSION;

/**
 * Footer line under the auth card: `host · Connected` (from `GET /public/auth/options`) and,
 * optionally, the version and an extra link.
 */
export function AuthFooter({ showVersion, children }: { showVersion?: boolean; children?: ReactNode }) {
  const [conn, setConn] = useState<Conn>('checking');
  const [version, setVersion] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    fetchAuthOptions().then(
      () => alive && setConn('ok'),
      () => alive && setConn('down'),
    );
    if (showVersion) {
      getUpdateStatus().then((s) => {
        if (alive) setVersion(s?.current || BUILD_VERSION || null);
      });
    }
    return () => {
      alive = false;
    };
  }, [showVersion]);

  const label = conn === 'ok' ? 'Connected' : conn === 'down' ? 'Can’t reach the server' : 'Checking…';
  return (
    <>
      <span className="v2-auth-conn">
        <span className={cx('v2-auth-conn__dot', `is-${conn}`)} aria-hidden="true" />
        <span className="v2-mono">{window.location.host}</span> · {label}
      </span>
      {showVersion && version ? <span className="v2-mono">MDMesh {version}</span> : null}
      {children}
    </>
  );
}
