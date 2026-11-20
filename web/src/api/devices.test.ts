import { describe, expect, it } from 'vitest';
import { ApiError } from './client';
import { BULK_DELETE_PARTIAL, bulkDeletePartial } from './devices';

describe('bulkDeletePartial', () => {
  it('counts what a partial bulk delete deleted and left', () => {
    const e = new ApiError(BULK_DELETE_PARTIAL, 'ERROR', 200, { deleted: [1, 2, 3], notDeleted: [4] });
    expect(bulkDeletePartial(e)).toEqual({ deleted: 3, notDeleted: 1 });
  });

  it('is null for any other failure', () => {
    expect(bulkDeletePartial(new ApiError('error.permission.denied', 'ERROR', 200))).toBeNull();
    expect(bulkDeletePartial(new Error(BULK_DELETE_PARTIAL))).toBeNull();
    expect(bulkDeletePartial(undefined)).toBeNull();
  });

  it('copes with a missing list', () => {
    expect(bulkDeletePartial(new ApiError(BULK_DELETE_PARTIAL, 'ERROR', 200))).toEqual({ deleted: 0, notDeleted: 0 });
  });
});
