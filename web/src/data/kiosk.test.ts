import { describe, expect, it } from 'vitest';
import type { Configuration, ConfigApp } from '../api/configurations';
import { configuredKiosk, kioskTheme } from './kiosk';

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
});
