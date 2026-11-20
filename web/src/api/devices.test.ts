import { describe, expect, it } from 'vitest';
import { ApiError } from './client';
import { BULK_DELETE_PARTIAL, bulkDeletePartial, bulkErrorText } from './devices';

describe('bulkDeletePartial', () => {
  it('lists what a partial bulk delete deleted and left', () => {
    const e = new ApiError(BULK_DELETE_PARTIAL, 'ERROR', 200, { deleted: [1, 2, 3], notDeleted: [4] });
    expect(bulkDeletePartial(e)).toEqual({ deleted: [1, 2, 3], notDeleted: [4] });
  });

  it('is null for any other failure', () => {
    expect(bulkDeletePartial(new ApiError('error.permission.denied', 'ERROR', 200))).toBeNull();
    expect(bulkDeletePartial(new Error(BULK_DELETE_PARTIAL))).toBeNull();
    expect(bulkDeletePartial(undefined)).toBeNull();
  });

  it('copes with a missing list', () => {
    expect(bulkDeletePartial(new ApiError(BULK_DELETE_PARTIAL, 'ERROR', 200))).toEqual({ deleted: [], notDeleted: [] });
  });
});

describe('bulkErrorText', () => {
  it('words the server keys a bulk change can answer', () => {
    expect(bulkErrorText(new ApiError('error.permission.denied', 'ERROR', 200))).toBe(
      'You can’t change one or more of these devices, so nothing was changed.',
    );
  });

  it('names the configuration as a cause when the change is to a configuration', () => {
    expect(bulkErrorText(new ApiError('error.permission.denied', 'ERROR', 200), 'configuration')).toBe(
      'You can’t change one or more of these devices, or use this configuration, so nothing was changed.',
    );
    expect(bulkErrorText(new ApiError('error.device.configuration.required', 'ERROR', 200), 'configuration')).toBe(
      'Choose a configuration to move the devices to.',
    );
  });

  it('passes other messages through', () => {
    expect(bulkErrorText(new Error('Network down'))).toBe('Network down');
    expect(bulkErrorText(undefined)).toBe('');
  });
});
