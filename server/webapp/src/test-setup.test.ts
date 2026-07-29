import { describe, expect, it } from 'vitest';

describe('React markup warning guard', () => {
  it('turns DOM-nesting and hydration warnings into test failures', () => {
    expect(() => {
      console.error('In HTML, <button> cannot contain a nested <button>. This will cause a hydration error.');
    }).toThrow(/invalid-markup warning/);
  });
});
