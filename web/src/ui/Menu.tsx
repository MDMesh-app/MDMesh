import {
  useCallback,
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type KeyboardEvent,
  type ReactNode,
} from 'react';
import { Button, IconButton, type ButtonSize, type ButtonVariant, type IconButtonSize } from './Button';
import { IconMore } from './icons';
import { cx } from './cx';
import './Menu.css';

export interface MenuItem {
  key: string;
  label: ReactNode;
  /** Plain-text label for type-ahead when `label` is not a string. */
  textValue?: string;
  icon?: ReactNode;
  onSelect: () => void;
  /** Destructive action (Delete…, Factory reset…): alert ink. */
  danger?: boolean;
  disabled?: boolean;
}

export type MenuEntry = MenuItem | 'separator';

export interface MenuProps {
  items: ReadonlyArray<MenuEntry>;
  /** Accessible name of the trigger ("More actions for KSK-0151"). */
  label: string;
  /**
   * - `icon` (default): the "…" IconButton (`iconSize` md 32 in table rows, sm 30 on cards)
   * - `button`: a text Button ("More actions", or `buttonText` with a chevron), sizes as Button
   */
  trigger?: 'icon' | 'button';
  iconSize?: IconButtonSize;
  buttonText?: ReactNode;
  buttonVariant?: ButtonVariant;
  buttonSize?: ButtonSize;
  /** Which trigger edge the menu lines up with. */
  align?: 'start' | 'end';
  disabled?: boolean;
  className?: string;
}

/**
 * Dropdown menu for the boards' "…" buttons (02 rows, 03 cards, 04/05 header, 06 configuration cards, 08 library).
 * The boards draw only the trigger; the popup takes the card language (white, 1px border, r12, float shadow).
 * WAI-ARIA menu button: `aria-haspopup`/`aria-expanded` on the trigger, `role="menu"` + `menuitem`s; Enter, Space or
 * ArrowDown opens on the first item, ArrowUp on the last; arrows, Home/End and type-ahead move; Escape and Tab close;
 * a click outside closes. The popup is position: fixed, so cards with overflow: hidden do not clip it.
 */
export function Menu({
  items,
  label,
  trigger = 'icon',
  iconSize = 'md',
  buttonText,
  buttonVariant = 'secondary',
  buttonSize = 'xl',
  align = 'end',
  disabled,
  className,
}: MenuProps) {
  const [open, setOpen] = useState(false);
  const [pos, setPos] = useState<{ top: number; left: number; up: boolean } | null>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const itemRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const focusOnOpen = useRef<'first' | 'last'>('first');
  const typed = useRef({ text: '', at: 0 });
  const menuId = useId();

  const enabled = items
    .map((it, i) => (it !== 'separator' && !it.disabled ? i : -1))
    .filter((i) => i >= 0);

  const place = useCallback(() => {
    const t = triggerRef.current;
    const m = menuRef.current;
    if (!t || !m) return;
    const r = t.getBoundingClientRect();
    const w = m.offsetWidth;
    const h = m.offsetHeight;
    const gap = 4;
    const up = r.bottom + gap + h > window.innerHeight - 8 && r.top - gap - h > 8;
    let left = align === 'end' ? r.right - w : r.left;
    left = Math.max(8, Math.min(left, window.innerWidth - w - 8));
    setPos({ top: up ? r.top - gap - h : r.bottom + gap, left, up });
  }, [align]);

  const close = useCallback((refocus: boolean) => {
    setOpen(false);
    setPos(null);
    if (refocus) triggerRef.current?.focus();
  }, []);

  useLayoutEffect(() => {
    if (!open) return;
    place();
  }, [open, place]);

  useEffect(() => {
    if (!open || !pos) return;
    const target = focusOnOpen.current === 'last' ? enabled[enabled.length - 1] : enabled[0];
    if (target !== undefined) itemRefs.current[target]?.focus();
    // Focus once per opening.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, pos === null]);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: PointerEvent) => {
      const t = e.target as Node;
      if (!menuRef.current?.contains(t) && !triggerRef.current?.contains(t)) close(false);
    };
    const onMove = () => place();
    document.addEventListener('pointerdown', onDown);
    window.addEventListener('scroll', onMove, true);
    window.addEventListener('resize', onMove);
    return () => {
      document.removeEventListener('pointerdown', onDown);
      window.removeEventListener('scroll', onMove, true);
      window.removeEventListener('resize', onMove);
    };
  }, [open, place, close]);

  const openWith = (where: 'first' | 'last') => {
    focusOnOpen.current = where;
    setOpen(true);
  };

  const onTriggerKey = (e: KeyboardEvent<HTMLButtonElement>) => {
    if (e.key === 'ArrowDown' || e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      openWith('first');
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      openWith('last');
    }
  };

  const focusItem = (i: number) => itemRefs.current[i]?.focus();

  const onMenuKey = (e: KeyboardEvent<HTMLDivElement>) => {
    const current = itemRefs.current.findIndex((el) => el === document.activeElement);
    const at = enabled.indexOf(current);
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      focusItem(enabled[(at + 1) % enabled.length]);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      focusItem(enabled[(at - 1 + enabled.length) % enabled.length]);
    } else if (e.key === 'Home') {
      e.preventDefault();
      focusItem(enabled[0]);
    } else if (e.key === 'End') {
      e.preventDefault();
      focusItem(enabled[enabled.length - 1]);
    } else if (e.key === 'Escape') {
      e.preventDefault();
      close(true);
    } else if (e.key === 'Tab') {
      close(false);
    } else if (e.key.length === 1 && /\S/.test(e.key)) {
      const now = Date.now();
      typed.current = { text: (now - typed.current.at < 600 ? typed.current.text : '') + e.key.toLowerCase(), at: now };
      const match = enabled.find((i) => {
        const it = items[i] as MenuItem;
        const text = it.textValue ?? (typeof it.label === 'string' ? it.label : '');
        return text.toLowerCase().startsWith(typed.current.text);
      });
      if (match !== undefined) focusItem(match);
    }
  };

  const triggerProps = {
    ref: triggerRef,
    'aria-haspopup': 'menu' as const,
    'aria-expanded': open,
    'aria-controls': open ? menuId : undefined,
    disabled,
    onClick: () => (open ? close(false) : openWith('first')),
    onKeyDown: onTriggerKey,
  };

  return (
    <span className={cx('v2-menu', className)}>
      {trigger === 'icon' ? (
        <IconButton label={label} size={iconSize} {...triggerProps}>
          <IconMore size={iconSize === 'sm' ? 14 : 15} />
        </IconButton>
      ) : buttonText ? (
        <Button variant={buttonVariant} size={buttonSize} {...triggerProps}>
          {buttonText}
        </Button>
      ) : (
        <Button variant={buttonVariant} size={buttonSize} aria-label={label} className="v2-menu__iconbtn" {...triggerProps}>
          <IconMore />
        </Button>
      )}
      {open ? (
        <div
          ref={menuRef}
          id={menuId}
          role="menu"
          aria-label={label}
          className={cx('v2-menu__popup', pos?.up && 'is-up')}
          style={pos ? { top: pos.top, left: pos.left } : { visibility: 'hidden', top: 0, left: 0 }}
          onKeyDown={onMenuKey}
        >
          {items.map((it, i) =>
            it === 'separator' ? (
              <div key={`sep${i}`} role="separator" className="v2-menu__sep" />
            ) : (
              <button
                key={it.key}
                ref={(el) => {
                  itemRefs.current[i] = el;
                }}
                type="button"
                role="menuitem"
                tabIndex={-1}
                disabled={it.disabled}
                className={cx('v2-menu__item', it.danger && 'is-danger')}
                onClick={() => {
                  close(true);
                  it.onSelect();
                }}
              >
                {it.icon ? <span className="v2-menu__icon">{it.icon}</span> : null}
                {it.label}
              </button>
            ),
          )}
        </div>
      ) : null}
    </span>
  );
}
