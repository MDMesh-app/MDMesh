// The kiosk background image picker: what the console checks before uploading, the hosted name it commits the
// upload under, and readable text for the upload's errors. The server checks the image again on save
// (KioskBackgroundImageAssets): PNG, JPEG or WebP, at most 10 MB and 16 megapixels, the agent's own limits.

/** The agent refuses a larger image file (KioskBackgroundImageStore.MAX_BYTES). */
export const KIOSK_IMAGE_MAX_BYTES = 10 * 1024 * 1024;

/** The image types the agent shows. */
export const KIOSK_IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/webp'];

/** The agent release that shows a kiosk background image; older agents keep the color. */
export const KIOSK_IMAGE_AGENT = '0.5.0';

/** Why a picked file can't be a background image, or null. An unknown type is left to the server's check. */
export function kioskImageProblem(file: { type: string; size: number }): string | null {
  if (file.type && !KIOSK_IMAGE_TYPES.includes(file.type)) return 'Choose a PNG, JPEG or WebP image.';
  if (file.size > KIOSK_IMAGE_MAX_BYTES) return 'The image is larger than 10 MB. Choose a smaller one.';
  return null;
}

/**
 * The name the image is hosted under in Files: `kiosk-<id>-<name>`, with the id taken from the content. Picking an
 * edited image with the same file name then gets a new name instead of clashing with the old file, and picking the
 * same image again reuses the hosted one.
 */
export function kioskImageUploadName(path: string, contentId: string): string {
  const fileName = path.slice(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
  const dot = fileName.lastIndexOf('.');
  const ext = dot > 0 ? fileName.slice(dot + 1).toLowerCase().replace(/[^a-z0-9]/g, '').slice(0, 5) : '';
  const stem = (dot > 0 ? fileName.slice(0, dot) : fileName)
    .replace(/[^A-Za-z0-9_-]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 40) || 'image';
  return `kiosk-${contentId}-${stem}${ext ? `.${ext}` : ''}`;
}

const hex = (bytes: Uint8Array) => Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');

/**
 * 12 hex characters naming the content: the start of its SHA-256 where the browser offers it (secure pages only),
 * otherwise random, which still never clashes with an older upload.
 */
export async function kioskImageContentId(file: Blob): Promise<string> {
  try {
    const subtle = globalThis.crypto?.subtle;
    if (subtle && typeof file.arrayBuffer === 'function') {
      return hex(new Uint8Array(await subtle.digest('SHA-256', await file.arrayBuffer()))).slice(0, 12);
    }
  } catch {
    // Fall back to a random id.
  }
  const random = new Uint8Array(6);
  globalThis.crypto.getRandomValues(random);
  return hex(random);
}

/** Readable text for a failed image upload; the server answers some refusals with a message key. */
export function kioskImageErrorText(e: unknown): string {
  const msg = e instanceof Error ? e.message : '';
  if (msg === 'error.duplicate.file') return 'A different file with this name is already in Files. Rename the image and try again.';
  if (msg === 'error.permission.denied') return 'You need the permission to manage files to upload an image.';
  if (!msg || /^error\./.test(msg)) return 'The server could not save the image.';
  return msg;
}

/* ---------------------------------------------------------------------------------------------------------------
   Normalizing a picked image before upload: upright and at most 3840 px along its long edge. The server removes
   whatever metadata is left on every path (KioskImageMetadata); this is the part it can't do without decoding.
   --------------------------------------------------------------------------------------------------------------- */

/** Long edge the console scales a background down to: a 4K screen's, sharp on any kiosk, a fraction of the bytes. */
export const KIOSK_IMAGE_MAX_EDGE = 3840;
/** The browser never decodes a larger image (a decompression bomb would need gigabytes): checked from the header. */
export const KIOSK_IMAGE_DECODE_LIMIT = 64 * 1000 * 1000;

export type KioskImageFormat = 'PNG' | 'JPEG' | 'WebP';

export interface KioskImageHeader {
  format: KioskImageFormat;
  /** As stored, before the orientation. */
  width: number;
  height: number;
  /** EXIF orientation, 1 (upright) to 8; 5 to 8 turn the image a quarter. */
  orientation: number;
}

const u16 = (b: Uint8Array, i: number, little: boolean) => (little ? b[i] | (b[i + 1] << 8) : (b[i] << 8) | b[i + 1]);
const u32 = (b: Uint8Array, i: number, little: boolean) =>
  little ? (b[i] | (b[i + 1] << 8) | (b[i + 2] << 16) | (b[i + 3] << 24)) >>> 0 : ((b[i] << 24) | (b[i + 1] << 16) | (b[i + 2] << 8) | b[i + 3]) >>> 0;
const isAscii = (b: Uint8Array, at: number, s: string) =>
  at + s.length <= b.length && Array.from(s).every((c, k) => b[at + k] === c.charCodeAt(0));

/** Orientation tag of IFD0 of the TIFF structure in b[start, end); 1 when absent or invalid. */
function tiffOrientation(b: Uint8Array, start: number, end: number): number {
  if (start + 8 > end) return 1;
  const little = isAscii(b, start, 'II');
  if (!little && !isAscii(b, start, 'MM')) return 1;
  if (u16(b, start + 2, little) !== 42) return 1;
  let at = start + u32(b, start + 4, little);
  if (at + 2 > end) return 1;
  const count = u16(b, at, little);
  at += 2;
  for (let n = 0; n < count && at + 12 <= end; n++, at += 12) {
    if (u16(b, at, little) !== 0x0112) continue;
    const v = u16(b, at + 8, little);
    return u16(b, at + 2, little) === 3 && v >= 1 && v <= 8 ? v : 1;
  }
  return 1;
}

/**
 * Format, stored size and orientation of a PNG, JPEG or WebP from its first bytes; nothing is decoded. Null for
 * anything else (the server refuses it with a readable message).
 */
export function readImageHeader(b: Uint8Array): KioskImageHeader | null {
  if (b.length >= 24 && b[0] === 0x89 && isAscii(b, 1, 'PNG') && isAscii(b, 12, 'IHDR')) {
    const width = u32(b, 16, false);
    const height = u32(b, 20, false);
    return width > 0 && height > 0 ? { format: 'PNG', width, height, orientation: 1 } : null;
  }
  if (b.length >= 4 && b[0] === 0xff && b[1] === 0xd8) {
    let orientation = 1;
    let i = 2;
    while (i + 4 <= b.length && b[i] === 0xff) {
      const marker = b[i + 1];
      if (marker === 0xd9 || marker === 0xda) return null;
      const length = u16(b, i + 2, false);
      if (length < 2) return null;
      const data = i + 4;
      if (marker === 0xe1 && orientation === 1 && isAscii(b, data, 'Exif\0\0')) {
        orientation = tiffOrientation(b, data + 6, Math.min(b.length, i + 2 + length));
      }
      const sof = marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc;
      if (sof) {
        if (data + 5 > b.length) return null;
        const height = u16(b, data + 1, false);
        const width = u16(b, data + 3, false);
        return width > 0 && height > 0 ? { format: 'JPEG', width, height, orientation } : null;
      }
      i += 2 + length;
    }
    return null;
  }
  if (b.length >= 25 && isAscii(b, 0, 'RIFF') && isAscii(b, 8, 'WEBP')) {
    const d = 20;
    if (isAscii(b, 12, 'VP8X') && b.length >= d + 10) {
      const width = (b[d + 4] | (b[d + 5] << 8) | (b[d + 6] << 16)) + 1;
      const height = (b[d + 7] | (b[d + 8] << 8) | (b[d + 9] << 16)) + 1;
      return { format: 'WebP', width, height, orientation: 1 };
    }
    if (isAscii(b, 12, 'VP8L') && b[d] === 0x2f) {
      const bits = u32(b, d + 1, true);
      return { format: 'WebP', width: (bits & 0x3fff) + 1, height: ((bits >>> 14) & 0x3fff) + 1, orientation: 1 };
    }
    if (isAscii(b, 12, 'VP8 ') && b.length >= d + 10 && b[d + 3] === 0x9d && b[d + 4] === 0x01 && b[d + 5] === 0x2a) {
      const width = u16(b, d + 6, true) & 0x3fff;
      const height = u16(b, d + 8, true) & 0x3fff;
      return width > 0 && height > 0 ? { format: 'WebP', width, height, orientation: 1 } : null;
    }
  }
  return null;
}

export type KioskImagePlan =
  | { reencode: boolean; width: number; height: number; reasons: ('orientation' | 'size' | 'bytes')[] }
  | { refuse: string };

/** What normalizing does to an image: the size it ends up (upright), and whether it must be re-encoded for that. */
export function kioskImagePlan(h: KioskImageHeader, bytes: number): KioskImagePlan {
  if (h.width * h.height > KIOSK_IMAGE_DECODE_LIMIT) {
    return { refuse: `The image is over ${KIOSK_IMAGE_DECODE_LIMIT / 1_000_000} megapixels. Export a smaller version and choose it again.` };
  }
  const turned = h.orientation >= 5;
  const w = turned ? h.height : h.width;
  const ht = turned ? h.width : h.height;
  const scale = Math.min(1, KIOSK_IMAGE_MAX_EDGE / Math.max(w, ht));
  const reasons: ('orientation' | 'size' | 'bytes')[] = [];
  if (h.orientation !== 1) reasons.push('orientation');
  if (scale < 1) reasons.push('size');
  if (bytes > KIOSK_IMAGE_MAX_BYTES) reasons.push('bytes');
  return {
    reencode: reasons.length > 0,
    width: Math.max(1, Math.round(w * scale)),
    height: Math.max(1, Math.round(ht * scale)),
    reasons,
  };
}

/** Decoding and encoding, injectable for tests (jsdom has neither). */
export interface KioskImageCodec {
  /** The file upright (its EXIF orientation applied), scaled to width x height. */
  decode(file: Blob, width: number, height: number): Promise<{ image: CanvasImageSource; width: number; height: number; close(): void }>;
  encode(image: CanvasImageSource, width: number, height: number, type: string, quality: number): Promise<Blob>;
}

/** The browser's: createImageBitmap applies the orientation and scales; a canvas encodes. Null when unsupported. */
export function browserImageCodec(): KioskImageCodec | null {
  if (typeof createImageBitmap !== 'function' || typeof document === 'undefined') return null;
  return {
    async decode(file, width, height) {
      const bitmap = await createImageBitmap(file, {
        imageOrientation: 'from-image',
        resizeWidth: width,
        resizeHeight: height,
        resizeQuality: 'high',
      });
      return { image: bitmap, width: bitmap.width, height: bitmap.height, close: () => bitmap.close() };
    },
    encode(image, width, height, type, quality) {
      const canvas = document.createElement('canvas');
      canvas.width = width;
      canvas.height = height;
      const ctx = canvas.getContext('2d');
      if (!ctx) return Promise.reject(new Error('no canvas'));
      ctx.drawImage(image, 0, 0, width, height);
      return new Promise((resolve, reject) =>
        canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('not encoded'))), type, quality),
      );
    },
  };
}

const MIME: Record<KioskImageFormat, string> = { PNG: 'image/png', JPEG: 'image/jpeg', WebP: 'image/webp' };
const EXT: Record<string, string> = { 'image/png': 'png', 'image/jpeg': 'jpg', 'image/webp': 'webp' };
/** Visually lossless for a photo behind a launcher, and a fraction of a phone camera's file. */
const QUALITY = 0.92;

export interface NormalizedKioskImage {
  file: File;
  /** False: the picked file as it is. */
  changed: boolean;
  /** Upright size of the result; undefined when the header couldn't be read. */
  width?: number;
  height?: number;
}

/**
 * The picked image upright and at most KIOSK_IMAGE_MAX_EDGE along its long edge, in its own format. Unchanged when it
 * already is (the server strips its metadata), when the browser can't decode or encode it, or when it didn't apply
 * the orientation. The size is checked from the header before anything is decoded.
 *
 * @throws Error with a message for the admin when the image is too large to decode safely
 */
export async function normalizeKioskImage(
  file: File,
  codec: KioskImageCodec | null = browserImageCodec(),
): Promise<NormalizedKioskImage> {
  const head = new Uint8Array(await file.slice(0, 256 * 1024).arrayBuffer());
  const header = readImageHeader(head);
  if (!header) return { file, changed: false };
  const plan = kioskImagePlan(header, file.size);
  if ('refuse' in plan) throw new Error(plan.refuse);
  const unchanged = { file, changed: false, width: plan.width, height: plan.height };
  if (!plan.reencode || !codec) return plan.reencode ? { ...unchanged, width: undefined, height: undefined } : unchanged;
  let decoded: Awaited<ReturnType<KioskImageCodec['decode']>> | null = null;
  try {
    decoded = await codec.decode(file, plan.width, plan.height);
    // A browser that ignores the orientation hands back the image on its side: keep the original then.
    if (decoded.width !== plan.width || decoded.height !== plan.height) return { file, changed: false };
    let type = MIME[header.format];
    let blob = await codec.encode(decoded.image, plan.width, plan.height, type, QUALITY);
    if (blob.size > KIOSK_IMAGE_MAX_BYTES && type === 'image/png') {
      type = 'image/webp';
      blob = await codec.encode(decoded.image, plan.width, plan.height, type, QUALITY);
    }
    // A browser without a WebP encoder answers PNG.
    const outType = EXT[blob.type] ? blob.type : type;
    const dot = file.name.lastIndexOf('.');
    const stem = dot > 0 ? file.name.slice(0, dot) : file.name;
    return {
      file: new File([blob], `${stem}.${EXT[outType]}`, { type: outType }),
      changed: true,
      width: plan.width,
      height: plan.height,
    };
  } catch {
    return { file, changed: false };
  } finally {
    decoded?.close();
  }
}
