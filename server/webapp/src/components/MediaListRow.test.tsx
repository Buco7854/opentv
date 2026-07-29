import { fireEvent, render } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MediaListRow } from './MediaListRow';

describe('MediaListRow', () => {
  it('renders the row action and icon actions as separately named sibling buttons', () => {
    const open = vi.fn();
    const favorite = vi.fn();
    const guide = vi.fn();
    const download = vi.fn();
    const view = render(
      <MediaListRow
        title="Evening news"
        subtitle="Live"
        logo={null}
        kind={1}
        onClick={open}
        onToggleFavorite={favorite}
        onGuide={guide}
        downloadSlot={(
          <button
            type="button"
            aria-label="Download"
            onClick={(event) => {
              event.stopPropagation();
              download();
            }}
          />
        )}
      />,
    );

    const buttons = view.getAllByRole('button');
    expect(buttons.map((button) => button.getAttribute('aria-label'))).toEqual([
      'Evening news',
      'Add to favorites',
      'Guide',
      'Download',
    ]);
    expect(buttons[0]?.tagName).toBe('BUTTON');
    expect(buttons[0]?.getAttribute('type')).toBe('button');
    expect(buttons.every((button) => button.tabIndex === 0)).toBe(true);
    buttons[0]?.focus();
    expect(document.activeElement).toBe(buttons[0]);
    expect(view.container.querySelector(
      'button button, button a[href], a[href] button, [role="button"] button, button [role="button"]',
    )).toBeNull();

    fireEvent.click(buttons[1]!);
    fireEvent.click(buttons[2]!);
    fireEvent.click(buttons[3]!);
    expect(favorite).toHaveBeenCalledOnce();
    expect(guide).toHaveBeenCalledOnce();
    expect(download).toHaveBeenCalledOnce();
    expect(open).not.toHaveBeenCalled();

    fireEvent.click(view.getByRole('button', { name: 'Evening news' }));
    expect(open).toHaveBeenCalledOnce();
  });
});
