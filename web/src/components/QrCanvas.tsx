import { useEffect, useRef } from 'react';
import QRCode from 'qrcode';

/**
 * Renders [text] as a QR code on a canvas (client-side; no server round-trip), [size] CSS px wide, or the
 * parent's content width when that is narrower.
 */
export function QrCanvas({ text, size = 320 }: { text: string; size?: number }) {
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const c = ref.current;
    const box = c?.parentElement;
    if (!c || !box) return;
    let drawn = '';
    const draw = () => {
      // Draw one canvas pixel per device pixel and show the canvas at exactly that size: when the browser
      // rescales it, the modules blur, and some setup-wizard scanners then fail to read the code.
      const st = getComputedStyle(box);
      const avail = box.clientWidth - parseFloat(st.paddingLeft) - parseFloat(st.paddingRight);
      const dpr = window.devicePixelRatio || 1;
      const px = Math.round(Math.max(1, Math.min(size, Math.floor(avail))) * dpr);
      const key = `${px}@${dpr}`;
      if (key === drawn) return;
      drawn = key;
      void QRCode.toCanvas(c, text, { width: px, margin: 2, errorCorrectionLevel: 'M' })
        .then(() => {
          // qrcode sets its own inline px size; replace it with the CSS size that maps 1:1 to the canvas.
          c.style.width = `${px / dpr}px`;
          c.style.height = `${px / dpr}px`;
        })
        .catch(() => undefined);
    };
    draw();
    // Redraw when the frame narrows or widens, and on zoom (which changes devicePixelRatio).
    const ro = new ResizeObserver(draw);
    ro.observe(box);
    window.addEventListener('resize', draw);
    return () => {
      ro.disconnect();
      window.removeEventListener('resize', draw);
    };
  }, [text, size]);
  return <canvas ref={ref} width={size} height={size} style={{ maxWidth: '100%', display: 'block' }} />;
}
