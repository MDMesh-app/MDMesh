import type { HTMLAttributes, ReactNode } from 'react';
import { cx } from './cx';
import './Grid.css';

/** Columns a cell takes out of 12. */
export type GridSpan = 3 | 4 | 6 | 8 | 9 | 12;

export interface GridProps extends HTMLAttributes<HTMLElement> {
  /** Fill holes with later, smaller cells (bento layouts whose order may move). Off: cells keep their order. */
  dense?: boolean;
  /**
   * Stretch each cell's card to its row's height, so a row ends on one line. Only where the cells hold comparable
   * content (a bento of facts, a row of stat cards): a short card stretched beside a tall one is dead space. Off
   * (the default), each card is as tall as its content.
   */
  stretch?: boolean;
  as?: 'div' | 'section' | 'ul';
  children: ReactNode;
}

/**
 * The page grid: 12 columns, one gutter (--grid-gap) on both axes; cards as tall as their content, or stretched to
 * their row (`stretch`) where the row's cards hold comparable content. It answers to its own width (a container query), not the window's: below 960px a 3/4/8/9-span
 * cell takes half or all of the row, below 640px every cell takes the whole row. Pages use it for every card layout,
 * so card edges line up from page to page.
 */
export function Grid({ dense, stretch, as: As = 'div', className, children, ...rest }: GridProps) {
  return (
    <As className={cx('v2-grid', dense && 'v2-grid--dense', stretch && 'v2-grid--stretch', className)} {...rest}>
      {children}
    </As>
  );
}

export interface GridCellProps extends HTMLAttributes<HTMLElement> {
  /** Columns out of 12 at full width (default 12). */
  span?: GridSpan;
  /** Rows to take (a tall cell beside stacked ones). */
  rows?: 1 | 2 | 3;
  as?: 'div' | 'section' | 'li';
  children: ReactNode;
}

/** One cell of a Grid. Its child (usually a Card) fills it. */
export function GridCell({ span = 12, rows = 1, as: As = 'div', className, children, ...rest }: GridCellProps) {
  return (
    <As className={cx('v2-grid__cell', className)} data-span={span} data-rows={rows > 1 ? rows : undefined} {...rest}>
      {children}
    </As>
  );
}
