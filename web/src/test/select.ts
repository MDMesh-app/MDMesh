import { fireEvent, screen } from '@testing-library/react';

/**
 * Pick an option in a Select (ui/Input, Radix), as a touch or keyboard user would: open it from its trigger (role
 * "combobox"), then press the option. `option` is its visible name, or `{ value }` for the value it stands for (the
 * trigger carries the current one as `data-value`).
 */
export function chooseOption(select: HTMLElement, option: string | RegExp | { value: string | number }) {
  fireEvent.click(select);
  const target =
    typeof option === 'object' && 'value' in option && !(option instanceof RegExp)
      ? document.querySelector<HTMLElement>(`[role="option"][data-value="${String(option.value)}"]`)
      : screen.getByRole('option', { name: option as string | RegExp });
  if (!target) throw new Error(`chooseOption: no option ${JSON.stringify(option)}`);
  fireEvent.click(target);
}

/** The value a Select shows (its trigger's data-value). */
export const selectValue = (select: HTMLElement) => select.getAttribute('data-value');
