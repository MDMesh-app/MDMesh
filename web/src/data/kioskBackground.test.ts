import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client';
import {
  KIOSK_IMAGE_MAX_BYTES,
  KIOSK_IMAGE_MAX_EDGE,
  type KioskImageCodec,
  kioskImagePlan,
  normalizeKioskImage,
  readImageHeader,
  kioskImageContentId,
  kioskImageErrorText,
  kioskImageProblem,
  kioskImageUploadName,
} from './kioskBackground';

describe('kioskImageProblem', () => {
  it('accepts a PNG, JPEG or WebP up to 10 MB', () => {
    expect(kioskImageProblem({ type: 'image/png', size: 1 })).toBeNull();
    expect(kioskImageProblem({ type: 'image/jpeg', size: KIOSK_IMAGE_MAX_BYTES })).toBeNull();
    expect(kioskImageProblem({ type: 'image/webp', size: 5 })).toBeNull();
    // A browser that can't tell the type leaves it to the server's check.
    expect(kioskImageProblem({ type: '', size: 5 })).toBeNull();
  });

  it('refuses a file over 10 MB before uploading it', () => {
    expect(kioskImageProblem({ type: 'image/png', size: KIOSK_IMAGE_MAX_BYTES + 1 })).toMatch(/larger than 10 MB/);
  });

  it('refuses other types, SVG and GIF included', () => {
    expect(kioskImageProblem({ type: 'image/svg+xml', size: 5 })).toMatch(/PNG, JPEG or WebP/);
    expect(kioskImageProblem({ type: 'image/gif', size: 5 })).toMatch(/PNG, JPEG or WebP/);
    expect(kioskImageProblem({ type: 'application/pdf', size: 5 })).toMatch(/PNG, JPEG or WebP/);
  });
});

describe('kioskImageUploadName', () => {
  it('puts the content id in front of a safe version of the name', () => {
    expect(kioskImageUploadName('background.jpg', '0123456789ab')).toBe('kiosk-0123456789ab-background.jpg');
    expect(kioskImageUploadName('Lobby screen (v2).PNG', 'abc')).toBe('kiosk-abc-Lobby-screen-v2.png');
    expect(kioskImageUploadName('../../etc/passwd', 'abc')).toBe('kiosk-abc-passwd');
    expect(kioskImageUploadName('.png', 'abc')).toBe('kiosk-abc-png');
    expect(kioskImageUploadName('ünïcode.webp', 'abc')).toBe('kiosk-abc-n-code.webp');
  });

  it('names an edited image with the same file name differently', async () => {
    const before = await kioskImageContentId(new Blob(['old bytes']));
    const after = await kioskImageContentId(new Blob(['new bytes']));
    expect(before).toMatch(/^[0-9a-f]{12}$/);
    expect(kioskImageUploadName('background.jpg', before)).not.toBe(kioskImageUploadName('background.jpg', after));
  });
});

describe('kioskImageErrorText', () => {
  it('turns server message keys into text', () => {
    expect(kioskImageErrorText(new ApiError('error.duplicate.file', 'ERROR', 200))).toMatch(/already in Files/);
    expect(kioskImageErrorText(new ApiError('error.permission.denied', 'ERROR', 200))).toMatch(/permission/);
    expect(kioskImageErrorText(new ApiError('error.file.save', 'ERROR', 200))).toBe('The server could not save the image.');
    expect(kioskImageErrorText('nope')).toBe('The server could not save the image.');
  });

  it('keeps a readable message', () => {
    expect(kioskImageErrorText(new Error('Network error contacting the server: offline'))).toBe(
      'Network error contacting the server: offline',
    );
  });
});

/* ---- normalizing an image in the browser before it is uploaded ---------------------------------------------- */

const u8 = (...v: number[]) => Array.from(v);
const be16 = (v: number) => [(v >> 8) & 255, v & 255];
const le16 = (v: number) => [v & 255, (v >> 8) & 255];
const be32 = (v: number) => [(v >>> 24) & 255, (v >> 16) & 255, (v >> 8) & 255, v & 255];
const le32 = (v: number) => [v & 255, (v >> 8) & 255, (v >> 16) & 255, (v >>> 24) & 255];
const ascii = (s: string) => Array.from(s, (c) => c.charCodeAt(0));

function png(w: number, h: number): Uint8Array {
  return new Uint8Array([...u8(0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10), ...be32(13), ...ascii('IHDR'), ...be32(w), ...be32(h), 8, 2, 0, 0, 0, 0, 0, 0, 0]);
}

/** SOI, APP1 EXIF with the orientation (in either byte order), SOF0 with the size. */
function jpeg(w: number, h: number, orientation?: number, little = false): Uint8Array {
  const out = [0xff, 0xd8];
  if (orientation != null) {
    const s16 = little ? le16 : be16;
    const s32 = little ? le32 : be32;
    const tiff = [...ascii(little ? 'II' : 'MM'), ...s16(42), ...s32(8), ...s16(1), ...s16(0x0112), ...s16(3), ...s32(1), ...s16(orientation), 0, 0, ...s32(0)];
    const data = [...ascii('Exif'), 0, 0, ...tiff];
    out.push(0xff, 0xe1, ...be16(data.length + 2), ...data);
  }
  out.push(0xff, 0xc0, ...be16(17), 8, ...be16(h), ...be16(w), 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1);
  out.push(0xff, 0xd9);
  return new Uint8Array(out);
}

function webpVp8x(w: number, h: number): Uint8Array {
  const body = [...ascii('WEBP'), ...ascii('VP8X'), ...le32(10), 0, 0, 0, 0, w - 1 & 255, (w - 1) >> 8 & 255, (w - 1) >> 16 & 255, h - 1 & 255, (h - 1) >> 8 & 255, (h - 1) >> 16 & 255];
  return new Uint8Array([...ascii('RIFF'), ...le32(body.length), ...body]);
}

function webpVp8l(w: number, h: number): Uint8Array {
  const bits = (w - 1) | ((h - 1) << 14);
  const body = [...ascii('WEBP'), ...ascii('VP8L'), ...le32(5), 0x2f, bits & 255, (bits >> 8) & 255, (bits >> 16) & 255, (bits >>> 24) & 255, 0];
  return new Uint8Array([...ascii('RIFF'), ...le32(body.length), ...body]);
}

describe('readImageHeader', () => {
  it('reads format, size and orientation from the header only', () => {
    expect(readImageHeader(png(1920, 1080))).toEqual({ format: 'PNG', width: 1920, height: 1080, orientation: 1 });
    expect(readImageHeader(jpeg(4032, 3024))).toEqual({ format: 'JPEG', width: 4032, height: 3024, orientation: 1 });
    expect(readImageHeader(jpeg(4032, 3024, 6))).toEqual({ format: 'JPEG', width: 4032, height: 3024, orientation: 6 });
    expect(readImageHeader(jpeg(4032, 3024, 8, true))?.orientation).toBe(8);
    expect(readImageHeader(webpVp8x(3000, 2000))).toEqual({ format: 'WebP', width: 3000, height: 2000, orientation: 1 });
    expect(readImageHeader(webpVp8l(64, 48))).toEqual({ format: 'WebP', width: 64, height: 48, orientation: 1 });
  });

  it('is null for anything else, and for a cut-off header', () => {
    expect(readImageHeader(new Uint8Array(ascii('GIF89a......')))).toBeNull();
    expect(readImageHeader(new Uint8Array(ascii('<svg xmlns="http://www.w3.org/2000/svg"/>')))).toBeNull();
    expect(readImageHeader(png(10, 10).slice(0, 20))).toBeNull();
    expect(readImageHeader(jpeg(10, 10).slice(0, 8))).toBeNull();
    expect(readImageHeader(new Uint8Array())).toBeNull();
  });

  it('ignores an orientation outside 1 to 8', () => {
    expect(readImageHeader(jpeg(10, 10, 9))?.orientation).toBe(1);
  });
});

describe('kioskImagePlan', () => {
  it('leaves an upright image within the limits alone', () => {
    expect(kioskImagePlan({ format: 'JPEG', width: 1920, height: 1080, orientation: 1 }, 500_000)).toEqual({
      reencode: false, width: 1920, height: 1080, reasons: [],
    });
  });

  it('turns a phone photo upright, as the browser shows it', () => {
    const plan = kioskImagePlan({ format: 'JPEG', width: 3000, height: 2000, orientation: 6 }, 2_000_000);
    expect(plan).toMatchObject({ reencode: true, width: 2000, height: 3000 });
    expect('reasons' in plan && plan.reasons).toContain('orientation');
  });

  it(`scales the long edge down to ${KIOSK_IMAGE_MAX_EDGE} px`, () => {
    const plan = kioskImagePlan({ format: 'JPEG', width: 8000, height: 6000, orientation: 1 }, 9_000_000);
    expect(plan).toMatchObject({ reencode: true, width: KIOSK_IMAGE_MAX_EDGE, height: 2880 });
    const portrait = kioskImagePlan({ format: 'PNG', width: 2000, height: 5000, orientation: 1 }, 1_000);
    expect(portrait).toMatchObject({ width: 1536, height: KIOSK_IMAGE_MAX_EDGE });
  });

  it('re-encodes a file over 10 MB even at a size it may keep', () => {
    expect(kioskImagePlan({ format: 'PNG', width: 3000, height: 2000, orientation: 1 }, KIOSK_IMAGE_MAX_BYTES + 1)).toMatchObject({
      reencode: true, width: 3000, height: 2000,
    });
  });

  it('refuses to decode an image over the decode limit (decompression bomb)', () => {
    const plan = kioskImagePlan({ format: 'PNG', width: 50_000, height: 50_000, orientation: 1 }, 100_000);
    expect(plan).toEqual({ refuse: expect.stringMatching(/megapixels/) });
  });
});

describe('normalizeKioskImage', () => {
  const file = (bytes: Uint8Array, name: string, type: string) => new File([bytes as BlobPart], name, { type });

  function fakeCodec(result?: { width: number; height: number }) {
    const calls: { decode: unknown[]; encode: unknown[] } = { decode: [], encode: [] };
    const codec: KioskImageCodec = {
      async decode(f, width, height) {
        calls.decode.push([f, width, height]);
        return { image: {} as CanvasImageSource, width: result?.width ?? width, height: result?.height ?? height, close() {} };
      },
      async encode(_image, width, height, type) {
        calls.encode.push([width, height, type]);
        return new Blob([new Uint8Array(1000)], { type });
      },
    };
    return { codec, calls };
  }

  it('uploads an upright image within the limits as it is (the server strips its metadata)', async () => {
    const original = file(jpeg(1920, 1080), 'lobby.jpg', 'image/jpeg');
    const { codec, calls } = fakeCodec();
    const out = await normalizeKioskImage(original, codec);
    expect(out.file).toBe(original);
    expect(out.changed).toBe(false);
    expect(calls.decode).toHaveLength(0);
  });

  it('re-encodes a sideways phone photo upright, in its own format', async () => {
    const { codec, calls } = fakeCodec();
    const out = await normalizeKioskImage(file(jpeg(4000, 3000, 6), 'IMG_0042.JPG', 'image/jpeg'), codec);
    expect(calls.decode[0]).toEqual([expect.anything(), 2880, KIOSK_IMAGE_MAX_EDGE]);
    expect(calls.encode[0]).toEqual([2880, KIOSK_IMAGE_MAX_EDGE, 'image/jpeg']);
    expect(out.changed).toBe(true);
    expect(out.file.type).toBe('image/jpeg');
    expect(out.file.name).toBe('IMG_0042.jpg');
    expect(out.width).toBe(2880);
    expect(out.height).toBe(KIOSK_IMAGE_MAX_EDGE);
  });

  it('keeps the original when the browser did not apply the orientation', async () => {
    const original = file(jpeg(4000, 3000, 6), 'IMG_0042.JPG', 'image/jpeg');
    const { codec } = fakeCodec({ width: 3840, height: 2880 });
    const out = await normalizeKioskImage(original, codec);
    expect(out.file).toBe(original);
    expect(out.changed).toBe(false);
  });

  it('keeps the original when the browser cannot decode or encode it', async () => {
    const original = file(jpeg(4000, 3000, 6), 'a.jpg', 'image/jpeg');
    const broken: KioskImageCodec = {
      decode: async () => { throw new Error('no'); },
      encode: async () => new Blob(),
    };
    expect((await normalizeKioskImage(original, broken)).file).toBe(original);
    expect((await normalizeKioskImage(original, null)).file).toBe(original);
  });

  it('refuses a decompression bomb before decoding anything', async () => {
    const { codec, calls } = fakeCodec();
    await expect(normalizeKioskImage(file(png(20_000, 20_000), 'bomb.png', 'image/png'), codec)).rejects.toThrow(/megapixels/);
    expect(calls.decode).toHaveLength(0);
  });

  it('leaves what is not a PNG, JPEG or WebP to the checks that refuse it', async () => {
    const original = file(new Uint8Array(ascii('GIF89a......')), 'a.gif', 'image/gif');
    const { codec } = fakeCodec();
    const out = await normalizeKioskImage(original, codec);
    expect(out.file).toBe(original);
    expect(out.width).toBeUndefined();
  });
});
