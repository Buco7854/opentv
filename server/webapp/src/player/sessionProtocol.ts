import type { SessionCommand } from '../api';

/** Return the new per-lease high-water mark, or null when this command is stale/invalid. */
export function nextSessionCommandSequence(
  lastApplied: number,
  command: SessionCommand,
): number | null {
  if (!isSessionProtocolCommand(command)) return null;
  const { sequence } = command;
  return typeof sequence === 'number' &&
    Number.isSafeInteger(sequence) &&
    sequence > lastApplied
    ? sequence
    : null;
}

function isSessionProtocolCommand(command: SessionCommand): boolean {
  switch (command.type) {
    case 'pause':
    case 'play':
    case 'room-ended':
      return true;
    case 'message':
      return command.text != null;
    case 'sync':
      return command.sync != null;
    case 'join-request':
      return command.peerId != null && command.requestId != null;
    case 'join-response':
    case 'control-response':
      return command.accepted != null;
    case 'control-request':
      return command.peerId != null;
    case 'room-state':
      return command.members != null;
    case 'room-audio':
      return command.audioIndex != null && sessionCommandGeneration(command) != null;
    case 'room-go':
      return sessionCommandGeneration(command) != null;
    default:
      return false;
  }
}

/** Barrier commands without a positive protocol generation are invalid and ignored. */
export function sessionCommandGeneration(command: SessionCommand): number | null {
  const { generation } = command;
  return typeof generation === 'number' &&
    Number.isSafeInteger(generation) &&
    generation > 0
    ? generation
    : null;
}
