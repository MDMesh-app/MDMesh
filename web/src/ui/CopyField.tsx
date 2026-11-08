import { useEffect, useRef, useState } from 'react';
import { Button } from './Button';
import { useFieldControl } from './Field';
import { IconCheck, IconCopy } from './icons';
import { cx } from './cx';
import './Input.css';
import './CopyField.css';

export interface CopyFieldProps {
  value: string;
  /** Accessible name when not inside a <Field>. */
  'aria-label'?: string;
  /** Called after a successful copy (e.g. to toast). */
  onCopied?: () => void;
  copyLabel?: string;
  className?: string;
}

/**
 * Read-only mono value with a Copy button (enrollment token, server address). Uses the read-only input style
 * (page fill + card border). If the clipboard is unavailable (plain http), the text is selected and the
 * button asks for Ctrl+C instead.
 */
export function CopyField({ value, onCopied, copyLabel = 'Copy', className, 'aria-label': ariaLabel }: CopyFieldProps) {
  const field = useFieldControl();
  const input = useRef<HTMLInputElement>(null);
  const [state, setState] = useState<'idle' | 'copied' | 'manual'>('idle');

  useEffect(() => {
    if (state === 'idle') return;
    const t = setTimeout(() => setState('idle'), 2500);
    return () => clearTimeout(t);
  }, [state]);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(value);
      setState('copied');
      onCopied?.();
    } catch {
      input.current?.select();
      setState('manual');
    }
  };

  return (
    <span className={cx('v2-copy', className)}>
      <input
        ref={input}
        readOnly
        value={value}
        id={field?.id}
        aria-describedby={field?.describedBy}
        aria-label={field ? undefined : ariaLabel}
        className="v2-input v2-input--md v2-input--mono v2-copy__input"
        onFocus={(e) => e.target.select()}
      />
      <Button size="lg" icon={state === 'copied' ? <IconCheck size={14} /> : <IconCopy size={14} />} onClick={() => void copy()}>
        {state === 'copied' ? 'Copied' : state === 'manual' ? 'Press Ctrl+C' : copyLabel}
      </Button>
      <span className="v2-visually-hidden" aria-live="polite">
        {state === 'copied' ? 'Copied to the clipboard' : state === 'manual' ? 'Selected. Press Control+C to copy.' : ''}
      </span>
    </span>
  );
}
