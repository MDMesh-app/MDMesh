import { useEffect, useMemo, useState } from 'react';

type Encoder = Pick<typeof import('qrcode'), 'create'>;

/** The encoder once its chunk has loaded: later codes (New code, Full screen) are drawn in the same render. */
let loadedEncoder: Encoder | null = null;

/** Light modules around the code, in modules (the qrcode library's default `margin`). */
const QUIET = 2;

/** The code's side in modules (quiet zone included) and one SVG path for its dark modules (a rect per run in a row). */
function encode(encoder: Encoder, text: string): { box: number; path: string } {
  const m = encoder.create(text, { errorCorrectionLevel: 'M' }).modules;
  let d = '';
  for (let r = 0; r < m.size; r++) {
    for (let c = 0; c < m.size; ) {
      if (!m.get(r, c)) {
        c++;
        continue;
      }
      let w = 1;
      while (c + w < m.size && m.get(r, c + w)) w++;
      d += `M${c + QUIET} ${r + QUIET}h${w}v1h-${w}z`;
      c += w;
    }
  }
  return { box: m.size + 2 * QUIET, path: d };
}

/**
 * Renders [text] as a QR code (client-side; no server round-trip) as an SVG of its modules, so it stays sharp at any
 * size and zoom: a rescaled canvas blurs the modules, and some setup-wizard scanners then fail to read the code. It
 * fills its container's width. The encoder is loaded on first use (its own chunk, see vite.config.ts), so only the
 * pages that draw a code download it.
 */
export function QrCode({ text }: { text: string }) {
  const [encoder, setEncoder] = useState<Encoder | null>(loadedEncoder);
  const [loadFailed, setLoadFailed] = useState(false);
  useEffect(() => {
    if (encoder) return;
    let live = true;
    import('qrcode')
      .then(({ default: QRCode }) => {
        loadedEncoder = QRCode;
        if (live) setEncoder(() => QRCode);
      })
      // The encoder chunk failed to load (console upgraded under an open tab, network).
      .catch(() => {
        if (live) setLoadFailed(true);
      });
    return () => {
      live = false;
    };
  }, [encoder]);
  const code = useMemo(() => {
    if (!encoder) return null;
    try {
      return encode(encoder, text);
    } catch {
      return 'failed' as const; // the text doesn't fit in a QR code
    }
  }, [encoder, text]);

  if (loadFailed || code === 'failed') {
    return (
      <p role="alert" style={{ margin: 0, padding: 16, textAlign: 'center', color: '#1f2328' }}>
        Couldn&rsquo;t draw the QR code. Reload the page and try again.
      </p>
    );
  }
  if (!code) return null;
  return (
    <svg
      className="v2-qr__code"
      viewBox={`0 0 ${code.box} ${code.box}`}
      shapeRendering="crispEdges"
      aria-hidden="true"
      focusable="false"
    >
      <rect width={code.box} height={code.box} fill="#ffffff" />
      <path d={code.path} fill="#000000" />
    </svg>
  );
}
