import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, SessionCommand } from '../api';
import { nextSessionCommandSequence, sessionCommandGeneration } from './sessionProtocol';

const command = (overrides: Partial<SessionCommand> = {}): SessionCommand => ({
  type: 'room-audio',
  sequence: 1,
  text: null,
  peerId: null,
  peerName: null,
  requestId: null,
  accepted: null,
  quiet: false,
  sync: null,
  members: null,
  audioIndex: 0,
  generation: 1,
  ...overrides,
});

describe('watch-together protocol guards', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('only advances for a newer positive per-lease command sequence', () => {
    expect(nextSessionCommandSequence(4, command({ sequence: 5 }))).toBe(5);
    expect(nextSessionCommandSequence(5, command({ sequence: 5 }))).toBeNull();
    expect(nextSessionCommandSequence(5, command({ sequence: 4 }))).toBeNull();
    expect(nextSessionCommandSequence(5, command({ sequence: null }))).toBeNull();
  });

  it('does not let an unknown or incomplete frame poison the command high-water mark', () => {
    expect(nextSessionCommandSequence(
      4,
      command({ type: 'junk' as SessionCommand['type'], sequence: 99 }),
    )).toBeNull();
    expect(nextSessionCommandSequence(
      4,
      command({ sequence: 99, generation: 0 }),
    )).toBeNull();
  });

  it('requires a positive generation and rejects stale room-go generations', () => {
    const waiting = sessionCommandGeneration(command({ generation: 8 }));

    expect(waiting).toBe(8);
    expect(sessionCommandGeneration(command({ generation: null }))).toBeNull();
    expect(sessionCommandGeneration(command({ generation: 0 }))).toBeNull();
    expect(sessionCommandGeneration(command({ type: 'room-go', generation: 7 }))).not.toBe(waiting);
    expect(sessionCommandGeneration(command({ type: 'room-go', generation: 8 }))).toBe(waiting);
  });

  it('posts the barrier generation in ready requests', async () => {
    const fetch = vi.fn(async () => new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetch);

    await api.sessionReady('lease-1', 12);

    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/playback/lease-1/ready',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ generation: 12 }),
        keepalive: true,
      }),
    );
  });
});
