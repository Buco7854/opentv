import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { api } from '../api';
import { WatchChannelScreen } from './WatchScreen';

describe('watch route loading', () => {
  it('announces content resolution instead of exposing a silent empty player', () => {
    vi.spyOn(api, 'channel').mockReturnValue(new Promise(() => {}));
    render(
      <MemoryRouter initialEntries={['/watch/movie']}>
        <Routes><Route path="/watch/:channelId" element={<WatchChannelScreen />} /></Routes>
      </MemoryRouter>,
    );

    expect(screen.getByRole('status', { name: 'Working…' })).toBeTruthy();
  });
});
