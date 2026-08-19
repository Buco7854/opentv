import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { SearchField } from './Primitives';

describe('SearchField', () => {
  it('owns one clear action without requesting the browser-native cancel control', () => {
    const onChange = vi.fn();
    render(<SearchField placeholder="Find content" value="movie" onChange={onChange} />);

    const input = screen.getByRole('searchbox');
    expect(input.getAttribute('type')).toBe('text');
    expect(input.getAttribute('inputmode')).toBe('search');
    expect(screen.getAllByRole('button')).toHaveLength(1);

    fireEvent.click(screen.getByRole('button'));
    expect(onChange).toHaveBeenCalledWith('');
  });
});
