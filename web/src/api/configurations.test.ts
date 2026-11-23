// What the console sends when it saves a configuration.
import { describe, expect, it, vi } from 'vitest';
import { cloneForNew } from '../data/configurationRules';
import { stubApi } from '../test/console';
import { ApiError } from './client';
import { extensionErrorsOf, saveConfiguration } from './configurations';

const sent = (fetch: ReturnType<typeof stubApi>) => {
  const [url, init] = fetch.mock.calls[0];
  expect(String(url)).toMatch(/\/rest\/private\/configurations$/);
  expect(init?.method).toBe('PUT');
  return JSON.parse(String(init?.body)) as Record<string, unknown>;
};

describe('saveConfiguration', () => {
  it('leaves extensions out of an update, so the server keeps what is stored, and sends the rest as given', async () => {
    const fetch = stubApi(() => ({ id: 3, name: 'Warehouse' }));
    await saveConfiguration({ id: 3, name: 'Warehouse', applications: [], kioskMode: false, extensions: { demo: { on: true } } });
    expect(sent(fetch)).toEqual({ id: 3, name: 'Warehouse', applications: [], kioskMode: false });
  });

  it('keeps a template’s extensions in the new configuration made from it, as a copy does', async () => {
    const fetch = stubApi(() => ({ id: 4, name: 'Warehouse copy' }));
    const template = { id: 3, name: 'Warehouse', qrCodeKey: 'k', applications: [], extensions: { demo: { on: true } } };
    await saveConfiguration(cloneForNew(template));
    expect(sent(fetch)).toEqual({
      name: 'Warehouse copy',
      applications: [],
      extensions: { demo: { on: true } },
      locationServicesAction: 'notManaged',
    });
  });

  it('sends an explicit Location-services action when saving a copy, because a new configuration has no stored policy', async () => {
    const fetch = stubApi(() => ({ id: 4, name: 'Warehouse copy' }));
    const template = { id: 3, name: 'Warehouse', applications: [], locationServices: true };
    await saveConfiguration(cloneForNew(template));
    expect(sent(fetch)).toMatchObject({ name: 'Warehouse copy', locationServices: true, locationServicesAction: 'on' });
  });

  it('retains an Off Location-services policy when saving an untouched copy', async () => {
    const fetch = stubApi(() => ({ id: 4, name: 'Warehouse copy' }));
    const template = { id: 3, name: 'Warehouse', applications: [], locationServices: false };
    await saveConfiguration(cloneForNew(template));
    expect(sent(fetch)).toMatchObject({ name: 'Warehouse copy', locationServices: false, locationServicesAction: 'off' });
  });

  it('puts the changed namespaces on top of the loaded extensions, so every other namespace goes back as stored', async () => {
    const fetch = stubApi(() => ({ id: 3, name: 'Warehouse' }));
    const loaded = { demo: { on: true }, netaccess: { port: 443 }, 'netaccess.vpn': { on: false } };
    await saveConfiguration({ id: 3, name: 'Warehouse', applications: [], extensions: loaded }, { demo: { on: false } });
    expect(sent(fetch).extensions).toEqual({ demo: { on: false }, netaccess: { port: 443 }, 'netaccess.vpn': { on: false } });
    expect(loaded.demo).toEqual({ on: true });
  });

  it('refuses to send changes without the loaded extensions, which the server would replace with them alone', async () => {
    const fetch = stubApi(() => ({ id: 3, name: 'Warehouse' }));
    await expect(saveConfiguration({ id: 3, name: 'Warehouse', applications: [] }, { demo: { on: false } })).rejects.toThrow(
      /did not load/,
    );
    // A server that writes the missing value as null: `{ ...null, ...changed }` would be the changes alone too.
    await expect(
      saveConfiguration({ id: 3, name: 'Warehouse', applications: [], extensions: null as never }, { demo: { on: false } }),
    ).rejects.toThrow(/did not load/);
    expect(fetch).not.toHaveBeenCalled();
  });

  it('leaves extensions out of an update with no changed namespace', async () => {
    const fetch = stubApi(() => ({ id: 3, name: 'Warehouse' }));
    await saveConfiguration({ id: 3, name: 'Warehouse', applications: [], extensions: { demo: { on: true } } }, {});
    expect(sent(fetch)).not.toHaveProperty('extensions');
  });

  it('gives a new configuration its template’s extensions with the changes on top', async () => {
    const fetch = stubApi(() => ({ id: 4, name: 'Warehouse copy' }));
    const template = { id: 3, name: 'Warehouse', applications: [], extensions: { demo: { on: true }, other: { a: 1 } } };
    await saveConfiguration(cloneForNew(template), { demo: { on: false } });
    expect(sent(fetch).extensions).toEqual({ demo: { on: false }, other: { a: 1 } });
  });
});

describe('extensionErrorsOf', () => {
  it('reads the problems of a refused save from the error’s data', () => {
    const data = [
      { namespace: 'demo', key: 'error.extension.demo.taken', message: 'The label is taken.' },
      { namespace: null, key: 'error.extensions.size', message: 'Too large.' },
    ];
    expect(extensionErrorsOf(new ApiError('error.extension.demo.taken', 'ERROR', 200, data))).toEqual(data);
  });

  it('shows a problem without a sentence by its key, and skips a row it cannot read', () => {
    const data = [{ namespace: 'net', key: 'error.extension.net.port', message: null }, 7, { message: 'no key' }];
    expect(extensionErrorsOf(new ApiError('error.extension.net.port', 'ERROR', 200, data))).toEqual([
      { namespace: 'net', key: 'error.extension.net.port', message: 'error.extension.net.port' },
    ]);
  });

  it('is null for any other failure', () => {
    expect(extensionErrorsOf(new ApiError('error.duplicate.configuration', 'ERROR', 200))).toBeNull();
    expect(extensionErrorsOf(new ApiError('x', 'ERROR', 200, [{ key: 'error.other', message: 'm' }]))).toBeNull();
    expect(extensionErrorsOf(new ApiError('x', 'ERROR', 200, [null]))).toBeNull();
    expect(extensionErrorsOf(new Error('network'))).toBeNull();
  });
});

describe('a refused configuration save', () => {
  it('shows the message of a 400 that carries the usual envelope, not just the status', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      JSON.stringify({ status: 'ERROR', message: 'The background image is larger than 16 megapixels.', data: null }),
      { status: 400, headers: { 'Content-Type': 'application/json' } },
    )));
    await expect(saveConfiguration({ id: 3, name: 'Warehouse', applications: [] })).rejects.toThrow(
      'The background image is larger than 16 megapixels.',
    );
  });

  it('falls back to the status for a 400 without an envelope', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('<html>Bad Request</html>', { status: 400 })));
    await expect(saveConfiguration({ id: 3, name: 'Warehouse', applications: [] })).rejects.toThrow('Request failed with HTTP 400');
  });
});

describe('a new configuration made from one with a kiosk background image', () => {
  it('sends the image file id, so the server gives the new configuration the same image', async () => {
    const fetch = stubApi(() => ({ id: 4, name: 'Lobby copy' }));
    const template = {
      id: 3, name: 'Lobby', applications: [], backgroundImageFileId: 42,
      backgroundImageUrl: 'https://mdm.example/files/acme/kiosk-0123456789ab-lobby.png', backgroundImageSha256: 'a'.repeat(64),
    };
    await saveConfiguration(cloneForNew(template));
    expect(sent(fetch)).toMatchObject({ name: 'Lobby copy', backgroundImageFileId: 42 });
    expect(sent(fetch)).not.toHaveProperty('backgroundImageAction');
  });
});
