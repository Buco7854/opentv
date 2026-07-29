import { cleanup } from '@testing-library/react';
import { afterEach, beforeEach } from 'vitest';

const originalConsoleError = console.error.bind(console);
const originalConsoleWarn = console.warn.bind(console);
const reactMarkupWarning =
  /validateDOMNesting|hydration (?:error|failed)|cannot be a descendant of|cannot contain a nested/iu;

function failOnReactMarkupWarning(
  fallback: (...args: unknown[]) => void,
  args: unknown[],
) {
  const message = args.map(String).join(' ');
  if (reactMarkupWarning.test(message)) {
    throw new Error(`React emitted an invalid-markup warning: ${message}`);
  }
  fallback(...args);
}

if (!window.matchMedia) {
  window.matchMedia = (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  }) as MediaQueryList;
}

beforeEach(() => {
  // React reports invalid DOM nesting and hydration mismatches through the
  // console. Treat either as a test failure so invalid markup cannot hide in CI.
  console.error = (...args: unknown[]) => failOnReactMarkupWarning(originalConsoleError, args);
  console.warn = (...args: unknown[]) => failOnReactMarkupWarning(originalConsoleWarn, args);
});

afterEach(() => {
  cleanup();
  console.error = originalConsoleError;
  console.warn = originalConsoleWarn;
});
