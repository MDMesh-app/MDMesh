import { describe, expect, it } from 'vitest';
import type { Configuration, ConfigApp } from '../api/configurations';
import { configuredKiosk, kioskTheme } from './kiosk';
import type { CommandHistoryItem } from '../api/commands';
import { configuredKioskState } from './kiosk';

const IMAGE_SHA256 = 'a'.repeat(64);

const configuration = (extra: Record<string, unknown> = {}): Configuration => ({
  name: 'Managed kiosk',
  kioskMode: true,
  kioskExit: true,
  backgroundColor: '#9141ac',
  ...extra,
});

const apps: ConfigApp[] = [
  { id: 1, name: 'Reader', pkg: 'example.reader', action: 1, showIcon: true },
];

describe('kioskTheme', () => {
  it('carries the managed image descriptor into a configured kiosk command', () => {
    const cfg = configuration({
      backgroundImageFileId: 42,
      backgroundImageUrl: 'https://mdmesh.example/files/background.jpg',
      backgroundImageSha256: IMAGE_SHA256.toUpperCase(),
    });

    expect(configuredKiosk(cfg, apps)?.payload.theme).toEqual({
      backgroundColor: '#9141ac',
      backgroundImage: {
        url: 'https://mdmesh.example/files/background.jpg',
        sha256: IMAGE_SHA256,
      },
    });
  });

  it('does not pass an unpinned legacy image URL to the agent', () => {
    expect(kioskTheme(configuration({ backgroundImageUrl: 'https://example.test/legacy.jpg' }))).toEqual({
      backgroundColor: '#9141ac',
    });
  });

  it('sends only a managed image: an uploaded file with the SHA-256 the server computed', () => {
    // A digest without the file it belongs to isn't a managed image (the server sets both, or neither).
    expect(kioskTheme(configuration({
      backgroundImageUrl: 'https://example.test/x.jpg',
      backgroundImageSha256: IMAGE_SHA256,
    }))?.backgroundImage).toBeUndefined();
    expect(kioskTheme(configuration({
      backgroundImageFileId: 42,
      backgroundImageUrl: 'https://example.test/x.jpg',
      backgroundImageSha256: 'not-a-digest',
    }))?.backgroundImage).toBeUndefined();
  });

  // Saved while the server was still on http://: the stored address goes out as it is, and the server builds the
  // current one (https:// after a move) as it queues kiosk.enter, as config.apply does. Dropped here, the device would
  // lose the image config.apply gave it.
  it('sends a managed image saved under an http:// server address, for the server to send its current address', () => {
    expect(kioskTheme(configuration({
      backgroundImageFileId: 42,
      backgroundImageUrl: 'http://mdm.local:8080/files/acme/kiosk-0123456789ab-beach.jpg',
      backgroundImageSha256: IMAGE_SHA256,
    }))?.backgroundImage).toEqual({
      url: 'http://mdm.local:8080/files/acme/kiosk-0123456789ab-beach.jpg',
      sha256: IMAGE_SHA256,
    });
  });
});

const command = (type: string, createdAt: number): CommandHistoryItem => ({
  id: createdAt,
  type,
  status: 'done',
  createdAt,
});

describe('configuredKioskState', () => {
  const state = (
    generation: number | null,
    returnGeneration: number | null,
    history: CommandHistoryItem[] = [],
    kioskActive = true,
    inSync = true,
  ) => configuredKioskState({
    history,
    kioskActive,
    inSync,
    kioskGeneration: generation,
    kioskReturnGeneration: returnGeneration,
    configuredIds: new Set(),
  });

  it('recognizes an on-device return at the current server-owned generation', () => {
    expect(state(2, 2, [command('kiosk.exit', 100)])).toBe('active');
  });

  it('keeps Return available after a later kiosk command advances the generation', () => {
    expect(state(3, 2, [command('kiosk.enter', 200)])).toBe('ready');
  });

  it('requires current kiosk telemetry and configuration reconciliation', () => {
    expect(state(2, 2, [], false)).toBe('ready');
    expect(state(2, 2, [], true, false)).toBe('ready');
  });

  it('keeps recognizing a configured kiosk command sent by this console', () => {
    expect(configuredKioskState({
      history: [command('kiosk.enter', 200)],
      kioskActive: true,
      inSync: true,
      kioskGeneration: null,
      kioskReturnGeneration: null,
      configuredIds: new Set(['200']),
    })).toBe('active');
  });
});


describe('kioskTheme fit and focal point (protocol 1.10)', () => {
  const base = {
    name: 'k',
    backgroundImageFileId: 7,
    backgroundImageUrl: 'https://mdm.example/files/a.jpg',
    backgroundImageSha256: '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
  };
  it('sends them only when not the default, and no focal point with fit', async () => {
    const { kioskTheme } = await import('./kiosk');
    expect(kioskTheme({ ...base })?.backgroundImage).toEqual({ url: base.backgroundImageUrl, sha256: base.backgroundImageSha256 });
    expect(kioskTheme({ ...base, backgroundImageFit: 'fill', backgroundImageFocalX: 0.5, backgroundImageFocalY: 0.5 })?.backgroundImage)
      .toEqual({ url: base.backgroundImageUrl, sha256: base.backgroundImageSha256 });
    expect(kioskTheme({ ...base, backgroundImageFocalX: 0.2, backgroundImageFocalY: 0.9 })?.backgroundImage)
      .toMatchObject({ focalX: 0.2, focalY: 0.9 });
    const fit = kioskTheme({ ...base, backgroundImageFit: 'fit', backgroundImageFocalX: 0.2 })?.backgroundImage;
    expect(fit).toMatchObject({ fit: 'fit' });
    expect(fit?.focalX).toBeUndefined();
  });
});
