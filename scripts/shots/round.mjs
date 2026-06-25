// Round the corners of a PNG (transparent corners) + add a thin border and a soft drop shadow on a
// transparent canvas, so the docs screenshots look sleek. Pure sharp; no browser needed.
import sharp from 'sharp';

/**
 * @param {Buffer} input  raw screenshot PNG bytes
 * @param {object} [opts]
 * @returns {Promise<Buffer>} processed PNG bytes
 */
export async function roundCorners(input, opts = {}) {
  const radius = opts.radius ?? 16;
  const pad = opts.pad ?? 28;          // room for the shadow
  const blur = opts.blur ?? 18;
  const border = opts.border ?? 'rgba(255,255,255,0.07)';

  const base = sharp(input);
  const { width, height } = await base.metadata();

  // 1) mask the screenshot to a rounded rect.
  const mask = Buffer.from(
    `<svg width="${width}" height="${height}"><rect x="0" y="0" width="${width}" height="${height}" rx="${radius}" ry="${radius}"/></svg>`,
  );
  const rounded = await base
    .composite([{ input: mask, blend: 'dest-in' }])
    .png()
    .toBuffer();

  // 2) build a shadow: a black rounded rect, blurred, on a transparent canvas.
  const shadow = await sharp(
    Buffer.from(
      `<svg width="${width}" height="${height}"><rect x="0" y="0" width="${width}" height="${height}" rx="${radius}" ry="${radius}" fill="rgba(0,0,0,0.55)"/></svg>`,
    ),
  ).blur(blur).png().toBuffer();

  // 3) a hairline border overlay so the panel reads as a framed card.
  const frame = Buffer.from(
    `<svg width="${width}" height="${height}"><rect x="0.5" y="0.5" width="${width - 1}" height="${height - 1}" rx="${radius}" ry="${radius}" fill="none" stroke="${border}" stroke-width="1"/></svg>`,
  );
  const card = await sharp(rounded).composite([{ input: frame, blend: 'over' }]).png().toBuffer();

  // 4) compose shadow + card onto a padded transparent canvas.
  return sharp({
    create: {
      width: width + pad * 2,
      height: height + pad * 2,
      channels: 4,
      background: { r: 0, g: 0, b: 0, alpha: 0 },
    },
  })
    .composite([
      { input: shadow, left: pad, top: pad + 6 },
      { input: card, left: pad, top: pad },
    ])
    .png()
    .toBuffer();
}
