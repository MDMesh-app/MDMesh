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
