// Not exported from the ui barrel: it pulls in the `qrcode` library, which should stay in the Enroll page's chunk.
// Import it directly: `import { QrPanel } from './QrPanel'`.
import { useState, type ReactNode } from 'react';
import { QrCode } from './QrCode';
import { Button } from './Button';
import { Modal } from './Modal';
import { IconSync } from './icons';
import { cx } from './cx';
import './QrPanel.css';

export interface QrPanelProps {
  /** QR payload (the provisioning JSON). */
  value: string;
  /** Accessible description of the code ("Enrollment QR code for Field Phones"). */
  label: string;
  /** "New code" (mint a fresh token). Omit to hide the button. */
  onNewCode?: () => void;
  newCodeBusy?: boolean;
  /** Title of the full-screen view. */
  fullScreenTitle?: ReactNode;
  /** Shown under the full-screen code ("Expires 14 Oct, 16:20"). */
  fullScreenNote?: ReactNode;
  className?: string;
}

/**
 * Enroll's QR column: a square r14 bordered frame (14px padding) holding the code, with "New code" and "Full screen"
 * underneath. The frame stays white in dark mode: scanners need dark modules on a light ground. Full screen opens
 * a viewport-sized Modal with the code as large as fits.
 */
export function QrPanel({ value, label, onNewCode, newCodeBusy, fullScreenTitle = 'Scan to enroll', fullScreenNote, className }: QrPanelProps) {
  const [full, setFull] = useState(false);
  return (
    <div className={cx('v2-qr', className)}>
      <div role="img" aria-label={label} className="v2-qr__frame">
        <QrCode text={value} />
      </div>
      <div className="v2-qr__actions">
        {onNewCode ? (
          <Button size="lg" icon={<IconSync size={14} />} onClick={onNewCode} disabled={newCodeBusy}>
            {newCodeBusy ? 'Making a code…' : 'New code'}
          </Button>
        ) : null}
        <Button size="lg" onClick={() => setFull(true)}>
          Full screen
        </Button>
      </div>
      <Modal open={full} onClose={() => setFull(false)} size="full" title={fullScreenTitle}>
        <div role="img" aria-label={label} className="v2-qr__frame v2-qr__frame--full">
          <QrCode text={value} />
        </div>
        {fullScreenNote ? <p className="v2-qr__note">{fullScreenNote}</p> : null}
      </Modal>
    </div>
  );
}
