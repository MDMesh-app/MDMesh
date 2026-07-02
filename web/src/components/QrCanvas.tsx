import { useEffect, useRef } from 'react';
import QRCode from 'qrcode';

/** Renders [text] as a QR code on a canvas (client-side; no server round-trip). */
export function QrCanvas({ text, size = 320 }: { text: string; size?: number }) {
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const c = ref.current;
    if (!c) return;
    void QRCode.toCanvas(c, text, {
      width: size,
      margin: 2,
      errorCorrectionLevel: 'M',
    })
      .then(() => {
        // qrcode sets an inline width/height in px on the canvas, which overrides the stylesheet and
        // overflows narrow containers. Re-set it responsively (inline wins) so it always stays contained.
        c.style.width = '100%';
        c.style.height = 'auto';
        c.style.maxWidth = `${size}px`;
        c.style.display = 'block';
      })
      .catch(() => undefined);
  }, [text, size]);
  return <canvas ref={ref} width={size} height={size} style={{ maxWidth: '100%', height: 'auto', display: 'block' }} />;
}
