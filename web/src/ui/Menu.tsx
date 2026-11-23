import { useRef, useState, type MouseEvent, type ReactNode } from 'react';
import * as DM from '@radix-ui/react-dropdown-menu';
import { Button, IconButton, type ButtonSize, type ButtonVariant, type IconButtonSize } from './Button';
import { IconChevronDown, IconMore } from './icons';
import { cx } from './cx';
import './Menu.css';

export interface MenuItem {
  key: string;
  label: ReactNode;
  /** Plain-text label for type-ahead when `label` is not a string. */
  textValue?: string;
  icon?: ReactNode;
  onSelect: () => void;
  /** Destructive action (Delete, Factory reset): alert ink. */
  danger?: boolean;
  disabled?: boolean;
  /** The item moves focus itself (to the part of the page it shows), so the menu doesn't return it to the trigger. */
  movesFocus?: boolean;
}

export type MenuEntry = MenuItem | 'separator';

export interface MenuProps {
  items: ReadonlyArray<MenuEntry>;
  /** Accessible name of the trigger ("More actions for KSK-0151"). */
  label: string;
  /**
   * - `icon` (default): the "…" ghost IconButton (`iconSize`, default `sm`)
   * - `button`: a text Button (`buttonText` with a chevron, or "…" alone), sizes as Button
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
 * Dropdown menu on Radix: a menu button (`aria-haspopup`, `aria-expanded`), `role="menu"` + `menuitem`s, arrow keys,
 * Home/End, type-ahead, Escape returns focus to the trigger, a click outside closes. Pointer opening highlights no
 * item; keyboard opening focuses the first (ArrowUp: the last). Portalled, so a card's `overflow: hidden` never clips
 * it; it flips above the trigger near the bottom of the window. Opens and closes with the shared pop motion.
 * Every item has the same anatomy: an optional 16px icon column, the label.
 * What goes in a menu and what stays a button (one place per surface): the Gallery's Menu section.
 */
export function Menu({
  items,
  label,
  trigger = 'icon',
  iconSize = 'sm',
  buttonText,
  buttonVariant = 'secondary',
  buttonSize = 'md',
  align = 'end',
  disabled,
  className,
}: MenuProps) {
  const withIcons = items.some((it) => it !== 'separator' && !!it.icon);
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  /** The chosen item moves focus itself (MenuItem.movesFocus): closing leaves focus where it put it. */
  const keepFocus = useRef(false);
  // Radix opens on pointerdown and on Enter, Space or ArrowDown (and prevents the click those keys would make). A
  // click with no pointer behind it (detail 0: a screen reader's activation, or a script) toggles it, as a button
  // would; a mouse click's own pointerdown already did.
  const onClick = (e: MouseEvent) => {
    if (e.detail === 0 && !disabled) setOpen((o) => !o);
  };
  return (
    <span className={cx('v2-menu', className)}>
      <DM.Root modal={false} open={open} onOpenChange={setOpen}>
        <DM.Trigger ref={triggerRef} asChild disabled={disabled} onClick={onClick}>
          {trigger === 'icon' ? (
            <IconButton label={label} size={iconSize}>
              <IconMore size={iconSize === 'xs' || iconSize === 'sm' ? 14 : 16} />
            </IconButton>
          ) : buttonText ? (
            <Button variant={buttonVariant} size={buttonSize} iconEnd={<IconChevronDown />}>
              {buttonText}
            </Button>
          ) : (
            <Button variant={buttonVariant} size={buttonSize} aria-label={label} className="v2-menu__iconbtn">
              <IconMore />
            </Button>
          )}
        </DM.Trigger>
        <DM.Portal>
          <DM.Content
            className="v2-menu__popup"
            align={align}
            sideOffset={4}
            collisionPadding={8}
            aria-label={label}
            loop
            onCloseAutoFocus={(e) => {
              if (keepFocus.current) e.preventDefault();
              keepFocus.current = false;
            }}
          >
            {items.map((it, i) =>
              it === 'separator' ? (
                <DM.Separator key={`sep${i}`} className="v2-menu__sep" />
              ) : (
                <DM.Item
                  key={it.key}
                  disabled={it.disabled}
                  textValue={it.textValue ?? (typeof it.label === 'string' ? it.label : undefined)}
                  className={cx('v2-menu__item', it.danger && 'is-danger')}
                  onSelect={() => {
                    // Focus goes back to the trigger first: a dialog the item opens then returns focus there when it
                    // closes (the item itself is gone by then). An item that moves focus itself keeps it there.
                    keepFocus.current = !!it.movesFocus;
                    if (!it.movesFocus) triggerRef.current?.focus();
                    it.onSelect();
                  }}
                >
                  {withIcons ? (
                    <span className="v2-menu__icon" aria-hidden="true">
                      {it.icon}
                    </span>
                  ) : null}
                  {it.label}
                </DM.Item>
              ),
            )}
          </DM.Content>
        </DM.Portal>
      </DM.Root>
    </span>
  );
}
