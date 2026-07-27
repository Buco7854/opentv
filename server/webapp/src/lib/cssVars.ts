import { useLayoutEffect, useRef } from 'react';

export function useCssVars<E extends HTMLElement = HTMLDivElement>(
  vars: Record<string, string | number>,
) {
  const ref = useRef<E>(null);
  const serialized = JSON.stringify(vars);
  useLayoutEffect(() => {
    const element = ref.current;
    if (!element) return;
    Object.entries(vars).forEach(([name, value]) => {
      element.style.setProperty(name, String(value));
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [serialized]);
  return ref;
}
