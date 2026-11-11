import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client';
import {
  KIOSK_IMAGE_MAX_BYTES,
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
