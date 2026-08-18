import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { Link, MemoryRouter, Route, Routes } from 'react-router';
import { RouteErrorBoundary } from './ErrorBoundary';

function Broken(): never {
  throw new Error('route exploded');
}

describe('RouteErrorBoundary', () => {
  it('lets the dock leave a crashed route without reloading the tab', () => {
    const reported = vi.spyOn(console, 'error').mockImplementation(() => {});
    render(
      <MemoryRouter initialEntries={['/broken']}>
        <Link to="/healthy">Leave broken route</Link>
        <RouteErrorBoundary>
          <Routes>
            <Route path="/broken" element={<Broken />} />
            <Route path="/healthy" element={<div>Healthy route</div>} />
          </Routes>
        </RouteErrorBoundary>
      </MemoryRouter>,
    );

    expect(screen.getByRole('heading', { name: 'This screen stopped working' })).toBeTruthy();
    fireEvent.click(screen.getByRole('link', { name: 'Leave broken route' }));
    expect(screen.getByText('Healthy route')).toBeTruthy();
    reported.mockRestore();
  });
});
