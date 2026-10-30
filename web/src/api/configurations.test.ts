// What the console sends when it saves a configuration.
import { describe, expect, it } from 'vitest';
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
    expect(sent(fetch)).toEqual({ name: 'Warehouse copy', applications: [], extensions: { demo: { on: true } } });
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
