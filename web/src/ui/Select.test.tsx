import { createRef, useState } from 'react';
import { fireEvent, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { Select, type SelectHandle } from './Input';
import { chooseOption, selectValue } from '../test/select';
import { render } from '../test/render';

describe('Select (Radix) with a native select’s props', () => {
  it('hands onChange a change-shaped event: target.value and name, preventDefault and stopPropagation', () => {
    const onChange = vi.fn((e: { target: { value: string; name: string }; preventDefault(): void; stopPropagation(): void }) => {
      e.preventDefault();
      e.stopPropagation();
      return e.target;
    });
    render(
      <Select aria-label="Size" name="size" value="10" onChange={onChange}>
        <option value="10">10</option>
        <option value="50">50</option>
      </Select>,
    );
    chooseOption(screen.getByRole('combobox', { name: 'Size' }), '50');
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange.mock.results[0].value).toEqual({ value: '50', name: 'size' });
  });

  it('round-trips an empty option ("All"): picking it reports "" and shows its label', () => {
    function Filter() {
      const [v, setV] = useState('kiosk');
      return (
        <>
          <Select aria-label="Type" value={v} onChange={(e) => setV(e.target.value)}>
            <option value="">All</option>
            <option value="kiosk">Kiosk</option>
          </Select>
          <output>{JSON.stringify(v)}</output>
        </>
      );
    }
    render(<Filter />);
    const select = screen.getByRole('combobox', { name: 'Type' });
    chooseOption(select, 'All');
    expect(screen.getByRole('status').textContent).toBe('""');
    expect(selectValue(select)).toBe('');
    expect(select.textContent).toContain('All');
  });

  it('treats an empty option as a real choice: shown, and checked in the list', () => {
    render(
      <Select aria-label="Default configuration" value="" onChange={() => {}}>
        <option value="">No default</option>
        <option value="3">Warehouse</option>
      </Select>,
    );
    const select = screen.getByRole('combobox', { name: 'Default configuration' });
    expect(select.hasAttribute('data-placeholder')).toBe(false);
    fireEvent.click(select);
    expect(screen.getByRole('option', { name: 'No default' }).getAttribute('data-state')).toBe('checked');
  });

  it('shows the first option for a value no option has, as a native select does', () => {
    render(
      <Select aria-label="Rows" value="7" onChange={() => {}}>
        <option value="10">10</option>
        <option value="50">50</option>
      </Select>,
    );
    expect(screen.getByRole('combobox', { name: 'Rows' }).textContent).toContain('10');
  });

  it('uncontrolled: starts on defaultValue (else the first option) and keeps what is picked', () => {
    render(
      <Select aria-label="Mode" defaultValue="b">
        <option value="a">A</option>
        <option value="b">B</option>
      </Select>,
    );
    const select = screen.getByRole('combobox', { name: 'Mode' });
    expect(selectValue(select)).toBe('b');
    chooseOption(select, 'A');
    expect(selectValue(select)).toBe('a');
  });

  it('passes the rest to the trigger (handlers, data-*, style) and its ref keeps value, name and focus()', () => {
    const onBlur = vi.fn();
    const ref = createRef<HTMLSelectElement>();
    render(
      <Select ref={ref} aria-label="Pick" name="pick" value="b" onChange={() => {}} onBlur={onBlur} data-test="x" style={{ width: 120 }}>
        <option value="a">A</option>
        <option value="b">B</option>
      </Select>,
    );
    const select = screen.getByRole('combobox', { name: 'Pick' });
    expect(select.getAttribute('data-test')).toBe('x');
    expect(select.style.width).toBe('120px');
    expect(ref.current?.value).toBe('b');
    expect(ref.current?.name).toBe('pick');
    ref.current?.focus();
    expect(document.activeElement).toBe(select);
    fireEvent.blur(select);
    expect(onBlur).toHaveBeenCalled();
  });

  it('in a form: a required select on its empty option blocks submission, and FormData carries the value', () => {
    const { container, rerender } = render(
      <form>
        <Select aria-label="Role" name="role" required value="" onChange={() => {}}>
          <option value="">Select a role</option>
          <option value="7">Operator</option>
        </Select>
      </form>,
    );
    const form = container.querySelector('form') as HTMLFormElement;
    expect(form.checkValidity()).toBe(false);
    rerender(
      <form>
        <Select aria-label="Role" name="role" required value="7" onChange={() => {}}>
          <option value="">Select a role</option>
          <option value="7">Operator</option>
        </Select>
      </form>,
    );
    expect(form.checkValidity()).toBe(true);
    expect(new FormData(form).get('role')).toBe('7');
  });
});

describe('Select: the deprecated native path (until 0.6.0)', () => {
  it('`multiple` draws a native select with a real element ref and native change events', () => {
    const ref = createRef<HTMLSelectElement>();
    const seen: string[][] = [];
    render(
      <Select
        ref={ref}
        multiple
        aria-label="Days"
        defaultValue={['mon']}
        onChange={(e) => seen.push(Array.from(e.target.selectedOptions, (o) => o.value))}
      >
        <option value="mon">Monday</option>
        <option value="tue">Tuesday</option>
      </Select>,
    );
    const el = screen.getByRole('listbox', { name: 'Days' });
    expect(ref.current).toBe(el);
    expect(ref.current?.options.length).toBe(2);
    (el as HTMLSelectElement).options[1].selected = true;
    fireEvent.change(el);
    expect(seen).toEqual([['mon', 'tue']]);
  });

  it('`native` keeps a single native select', () => {
    const ref = createRef<HTMLSelectElement>();
    render(
      <Select ref={ref} native aria-label="Fit" defaultValue="cover">
        <option value="fill">Fill</option>
        <option value="cover">Cover</option>
      </Select>,
    );
    expect(ref.current?.tagName).toBe('SELECT');
    expect(ref.current?.value).toBe('cover');
  });

  it('a listbox Select’s ref is a SelectHandle', () => {
    const ref = createRef<SelectHandle>();
    render(
      <Select ref={ref} aria-label="Fit" defaultValue="cover">
        <option value="fill">Fill</option>
        <option value="cover">Cover</option>
      </Select>,
    );
    expect(ref.current?.value).toBe('cover');
    expect(ref.current?.type).toBe('select-one');
  });

  it('still compiles with a ref typed as the old element (until 0.6.0)', () => {
    const old = createRef<HTMLSelectElement>();
    render(
      <Select ref={old} aria-label="Old" defaultValue="a">
        <option value="a">A</option>
      </Select>,
    );
    expect(old.current?.value).toBe('a');
    // As on 0.4's element, assigning compiles; it warns and does nothing rather than throwing.
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    expect(() => {
      old.current!.value = 'b';
    }).not.toThrow();
    expect(old.current?.value).toBe('a');
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });
});

describe('Select: groups', () => {
  it('an <optgroup disabled> disables its options', () => {
    render(
      <Select aria-label="App" defaultValue="a">
        <option value="a">Alpha</option>
        <optgroup label="Retired" disabled>
          <option value="b">Beta</option>
        </optgroup>
      </Select>,
    );
    fireEvent.click(screen.getByRole('combobox', { name: 'App' }));
    expect(screen.getByRole('option', { name: 'Beta' }).getAttribute('aria-disabled')).toBe('true');
    expect(screen.getByRole('option', { name: 'Alpha' }).getAttribute('aria-disabled')).toBeNull();
  });
});
