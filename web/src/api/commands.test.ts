import { describe, expect, it } from 'vitest';
import { ApiError } from './client';
import { commandErrorText, isBulkForbidden } from './commands';

describe('isBulkForbidden', () => {
  it('refuses what the server refuses in bulk, in any case', () => {
    for (const type of ['device.wipe', 'device.passcodeReset', 'app.uninstall', ' App.Uninstall ', 'app.setHidden']) {
      expect(isBulkForbidden(type)).toBe(true);
    }
  });

  it('lets the bulk actions through', () => {
    for (const type of ['device.lock', 'device.reboot', 'kiosk.enter', 'kiosk.exit', 'app.install', 'apps.scan']) {
      expect(isBulkForbidden(type)).toBe(false);
    }
  });
});

describe('commandErrorText', () => {
  it('says why a wiped device takes no command', () => {
    expect(commandErrorText(new ApiError('error.agent.command.wiped', 'ERROR', 200))).toBe(
      'The device was wiped, so it takes no more commands.',
    );
  });

  it('says what each refusal means in plain words', () => {
    const text = (key: string) => commandErrorText(new ApiError(key, 'ERROR', 200));
    expect(text('error.agent.command.noConfiguration')).toBe(
      'The device has no configuration, so there is no configured kiosk to return to.',
    );
    expect(text('error.agent.command.serverOwned')).toBe(
      'The server sends the configuration itself. Use Retry apply on the device page.',
    );
    expect(text('error.agent.command.bulkForbidden')).toBe(
      'Wipe, passcode reset and app uninstall are sent from the device’s page only.',
    );
    expect(text('error.agent.device.unknown')).toBe('The server has no device with this number. It may have been deleted.');
  });

  it('passes other messages through', () => {
    expect(commandErrorText(new Error('Network error'))).toBe('Network error');
    expect(commandErrorText('nope')).toBe('');
  });
});
