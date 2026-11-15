import { createRef, useState } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { Select } from './Input';
import { chooseOption, selectValue } from '../test/select';

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
