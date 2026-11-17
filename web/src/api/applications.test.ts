import { describe, expect, it } from 'vitest';
import { bundleErrorText } from './applications';
import { ApiError } from './client';

describe('bundleErrorText', () => {
  it('says what a refused bundle upload means', () => {
    expect(bundleErrorText(new ApiError('error.bundle.tooLarge', 'ERROR', 200))).toBe(
      'The bundle’s APKs unpack to more than 4 GB.',
    );
  });

  it('passes other messages through', () => {
    expect(bundleErrorText(new Error('Network error'))).toBe('Network error');
  });
});
