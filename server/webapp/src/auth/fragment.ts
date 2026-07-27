import { useEffect } from 'react';
import { useLocation, useNavigate } from 'react-router';

const captured = new Map<string, string | null>();

export function fragmentToken(name: string): string | null {
  const known = captured.get(name);
  if (known !== undefined) return known;
  const found = new URLSearchParams(window.location.hash.slice(1)).get(name);
  captured.set(name, found);
  return found;
}

export function useFragmentToken(name: string): string | null {
  const token = fragmentToken(name);
  const navigate = useNavigate();
  const { pathname, search, hash } = useLocation();
  useEffect(() => {
    if (hash) navigate(`${pathname}${search}`, { replace: true });
  }, [hash, navigate, pathname, search]);
  return token;
}
