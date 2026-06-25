import { useEffect, useRef } from 'react';
import QRCode from 'qrcode';

/** Renders [text] as a QR code on a canvas (client-side; no server round-trip). */
export function QrCanvas({ text, size = 320 }: { text: string; size?: number }) {
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    if (!ref.current) return;
    void QRCode.toCanvas(ref.current, text, {
      width: size,
      margin: 2,
      errorCorrectionLevel: 'M',
    }).catch(() => undefined);
  }, [text, size]);
  return <canvas ref={ref} width={size} height={size} />;
}
