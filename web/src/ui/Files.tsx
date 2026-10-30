import { useEffect, useId, useRef, useState, type DragEvent, type ReactNode } from 'react';
import { Button } from './Button';
import { IconUpload } from './icons';
import { cx } from './cx';
import './Files.css';

export interface FileDropZoneProps {
  /** Called with the chosen or dropped file. */
  onFile: (file: File) => void;
  /** `accept` for the file input (".apk,.apks,.xapk,.apkm,.zip"). */
  accept?: string;
  title?: ReactNode;
  /** Line under the title ("APK or split bundle, up to 500 MB"). */
  hint?: ReactNode;
  buttonLabel?: string;
  disabled?: boolean;
  /** Upload in progress: shows `busyLabel` and blocks input. */
  busy?: boolean;
  busyLabel?: ReactNode;
  /**
   * Once a file is read: what shows in place of the empty zone (a FileReadPanel). It stays a drop target, and
   * `choose` opens the same file picker, for its "Choose another file" button.
   */
  result?: (choose: () => void) => ReactNode;
  className?: string;
}

/**
 * Drop zone for the Custom APK flow. The board (09) draws only the "file read" result, so this is the same panel in
 * its empty state: 1.5px dashed control border on the page fill, r14, the 44px tile, and a real button (the zone is
 * not a keyboard trap: the button opens the file picker; dropping is a pointer shortcut).
 */
export function FileDropZone({
  onFile,
  accept,
  title = 'Drop a file here, or choose one',
  hint,
  buttonLabel = 'Choose a file',
  disabled,
  busy,
  busyLabel = 'Reading the file…',
  result,
  className,
}: FileDropZoneProps) {
  const input = useRef<HTMLInputElement>(null);
  const zone = useRef<HTMLDivElement>(null);
  const [over, setOver] = useState(false);
  const hintId = useId();
  const off = disabled || busy;
  const choose = () => {
    if (!off) input.current?.click();
  };
  const showResult = !!result && !busy;
  /**
   * Keyboard focus follows the file: choosing one replaces the focused button (busy, then the result panel), which
   * would drop focus to <body>. While that is under way, focus is kept on the zone's button, and once the read
   * finishes it moves to the result's first control ("Choose another file"). Only when focus was in the zone.
   */
  const keepFocus = useRef(false);
  const take = (f: File) => {
    keepFocus.current = !!zone.current?.contains(document.activeElement);
    onFile(f);
  };
  useEffect(() => {
    if (!keepFocus.current || !zone.current) return;
    const active = document.activeElement;
    // The user moved on (clicked or tabbed elsewhere): leave focus alone.
    if (active && active !== document.body && !zone.current.contains(active)) {
      keepFocus.current = false;
      return;
    }
    const target = zone.current.querySelector<HTMLElement>('button, a[href], input:not([type="file"]), select, textarea');
    target?.focus();
    if (!busy) keepFocus.current = false;
  }, [busy, showResult]);

  const drop = {
    onDragOver: (e: DragEvent<HTMLDivElement>) => {
      e.preventDefault();
      if (!off) setOver(true);
    },
    onDragLeave: () => setOver(false),
    onDrop: (e: DragEvent<HTMLDivElement>) => {
      e.preventDefault();
      setOver(false);
      if (off) return;
      const f = e.dataTransfer.files?.[0];
      if (f) take(f);
    },
  };
  const picker = (
    <input
      ref={input}
      type="file"
      accept={accept}
      hidden
      tabIndex={-1}
      onChange={(e) => {
        const f = e.target.files?.[0];
        if (f) take(f);
        e.target.value = '';
      }}
    />
  );

  // Announces "Reading app.apk…". Keyed and in both layouts, so it stays the same node (the root div is reused)
  // when a file chosen from the result panel turns it back into the busy zone.
  const live = (
    <span key="live" className="v2-visually-hidden" aria-live="polite">
      {busy ? busyLabel : ''}
    </span>
  );

  if (result && showResult) {
    return (
      <div ref={zone} className={cx('v2-dropresult', over && 'is-over', className)} {...drop}>
        {result(choose)}
        {picker}
        {live}
      </div>
    );
  }

  return (
    <div ref={zone} className={cx('v2-drop', over && 'is-over', off && 'is-disabled', className)} {...drop}>
      <span className="v2-filepanel__tile" aria-hidden="true">
        <IconUpload size={20} />
      </span>
      <span className="v2-filepanel__text">
        <span className="v2-drop__title">{busy ? busyLabel : title}</span>
        {hint ? (
          <span id={hintId} className="v2-drop__hint">
            {hint}
          </span>
        ) : null}
      </span>
      {/* aria-disabled while busy (not disabled): a disabled button would drop keyboard focus to <body>. */}
      <Button
        disabled={disabled}
        aria-disabled={busy || undefined}
        aria-describedby={hint ? hintId : undefined}
        onClick={choose}
      >
        {buttonLabel}
      </Button>
      {picker}
      {live}
    </div>
  );
}

export interface FileReadPanelProps {
  /** File name (mono 500). */
  name: ReactNode;
  /** "Read successfully · split bundle with 3 parts · 41.0 MB" (13px ink of the tone). */
  detail: ReactNode;
  /** `ok` or `alert` (the file could not be read). */
  tone?: 'ok' | 'alert';
  /** "Choose another file". */
  action?: ReactNode;
  className?: string;
}

/** Custom APK "file read" panel: 1.5px dashed ok-line, ok-wash, 16×18, r14, 44px r12 file tile. */
export function FileReadPanel({ name, detail, tone = 'ok', action, className }: FileReadPanelProps) {
  return (
    <div className={cx('v2-filepanel', `is-${tone}`, className)} role={tone === 'alert' ? 'alert' : 'status'}>
      <span className="v2-filepanel__tile" aria-hidden="true">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round">
          <path d="M6 2.5h8l4 4V21.5H6z" />
          <path d="M14 2.5v4h4" />
          <rect className="v2-filepanel__bar" x="8.5" y="11" width="7" height="2.5" rx="0.6" stroke="none" />
          <rect className="v2-filepanel__bar" x="8.5" y="15" width="7" height="2.5" rx="0.6" stroke="none" />
        </svg>
      </span>
      <span className="v2-filepanel__text">
        <span className="v2-filepanel__name">{name}</span>
        <span className="v2-filepanel__detail">{detail}</span>
      </span>
      {action}
    </div>
  );
}
