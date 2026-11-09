import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { Dialog, SearchField, SelectField, Sheet } from './Primitives';

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

describe('modal scrims', () => {
  it('does not dismiss a dialog when a pointer starts inside and ends outside', () => {
    const onDismiss = vi.fn();
    render(
      <Dialog title="Choose" onDismiss={onDismiss}>
        <input aria-label="Choice" />
      </Dialog>,
    );

    const input = screen.getByLabelText('Choice');
    const scrim = screen.getByRole('dialog').parentElement!;
    fireEvent.pointerDown(input, { pointerId: 7 });
    fireEvent.pointerUp(scrim, { pointerId: 7 });
    expect(onDismiss).not.toHaveBeenCalled();

    fireEvent.pointerDown(scrim, { pointerId: 8 });
    fireEvent.pointerUp(scrim, { pointerId: 8 });
    expect(onDismiss).toHaveBeenCalledOnce();
  });

  it('uses the same pointer-origin rule for sheets', () => {
    const onDismiss = vi.fn();
    render(
      <Sheet header="Options" onDismiss={onDismiss}>
        <input aria-label="Setting" />
      </Sheet>,
    );

    const input = screen.getByLabelText('Setting');
    const scrim = document.querySelector<HTMLElement>('.sheet-scrim')!;
    fireEvent.pointerDown(input, { pointerId: 11 });
    fireEvent.pointerUp(scrim, { pointerId: 11 });
    expect(onDismiss).not.toHaveBeenCalled();

    fireEvent.pointerDown(scrim, { pointerId: 12 });
    fireEvent.pointerUp(scrim, { pointerId: 12 });
    expect(onDismiss).toHaveBeenCalledOnce();
  });
});

describe('SelectField in a form', () => {
  it('changes an option without submitting the surrounding account form', () => {
    const onSelect = vi.fn();
    const onSubmit = vi.fn((event: React.FormEvent) => event.preventDefault());
    render(
      <form onSubmit={onSubmit}>
        <SelectField label="Account status" options={[["ACTIVE", "Active"], ["DISABLED", "Disabled"]]}
                     selected="ACTIVE" onSelect={onSelect} />
        <button type="submit">Save account</button>
      </form>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Account status' }));
    fireEvent.click(screen.getByRole('option', { name: 'Disabled' }));
    expect(onSelect).toHaveBeenCalledWith('DISABLED');
    expect(onSubmit).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Save account' }));
    expect(onSubmit).toHaveBeenCalledOnce();
  });
});
