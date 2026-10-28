import { useId, useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import { LinkButton } from '../ui/Button';
import { IconChevronDown } from '../ui/icons';
import { parseInline, type Block, type Inline, type ListItem, type Notes, type NotesSection } from './changelog';

/** Inline marks as React elements (text is always text: nothing here is HTML). */
function InlineText({ text, onNavigate }: { text: string; onNavigate: () => void }) {
  return <>{renderInline(parseInline(text), onNavigate)}</>;
}

function renderInline(nodes: Inline[], onNavigate: () => void): ReactNode[] {
  return nodes.map((n, i) => {
    switch (n.kind) {
      case 'text':
        return n.text;
      case 'code':
        return (
          <code key={i} className="v2-whatsnew__code">
            {n.text}
          </code>
        );
      case 'strong':
        return <strong key={i}>{renderInline(n.children, onNavigate)}</strong>;
      case 'link':
        return n.internal ? (
          <Link
            key={i}
            to={n.href}
            // A modified click opens a new tab (the router leaves it to the browser): this tab's dialog stays open.
            onClick={(e) => {
              if (e.button === 0 && !e.metaKey && !e.ctrlKey && !e.shiftKey && !e.altKey) onNavigate();
            }}
          >
            {renderInline(n.children, onNavigate)}
          </Link>
        ) : (
          <a key={i} href={n.href} target="_blank" rel="noopener noreferrer">
            {renderInline(n.children, onNavigate)}
          </a>
        );
    }
  });
}

function List({ items, onNavigate }: { items: ListItem[]; onNavigate: () => void }) {
  return (
    <ul className="v2-whatsnew__list">
      {items.map((item, i) => (
        <li key={i}>
          {item.parts.map((p, j) =>
            p.kind === 'list' ? (
              <List key={j} items={p.items} onNavigate={onNavigate} />
            ) : j === 0 ? (
              <InlineText key={j} text={p.text} onNavigate={onNavigate} />
            ) : (
              <p key={j} className="v2-whatsnew__more">
                <InlineText text={p.text} onNavigate={onNavigate} />
              </p>
            ),
          )}
        </li>
      ))}
    </ul>
  );
}

function Blocks({ blocks, onNavigate }: { blocks: Block[]; onNavigate: () => void }) {
  return (
    <>
      {blocks.map((b, i) =>
        b.kind === 'list' ? (
          <List key={i} items={b.items} onNavigate={onNavigate} />
        ) : (
          <p key={i} className="v2-whatsnew__p">
            <InlineText text={b.text} onNavigate={onNavigate} />
          </p>
        ),
      )}
    </>
  );
}

const size = (s: NotesSection) => s.blocks.reduce((n, b) => n + (b.kind === 'list' ? b.items.length : 1), 0);

function Section({ section, onNavigate }: { section: NotesSection; onNavigate: () => void }) {
  // A plain block under its heading, not a landmark: a dialog full of regions is noise to a screen reader.
  return (
    <div className="v2-whatsnew__section">
      <h3 className="v2-whatsnew__h">{section.title}</h3>
      <Blocks blocks={section.blocks} onNavigate={onNavigate} />
    </div>
  );
}

/**
 * A release's notes: its opening lines, its sections in the dialog's order (changelog.ts), then its closing lines.
 * Under the hood is behind a toggle when there is anything else to read. `onNavigate` runs when a link into the
 * console is followed (the dialog closes).
 */
export function NotesView({ notes, onNavigate }: { notes: Notes; onNavigate: () => void }) {
  const [more, setMore] = useState(false);
  const moreId = useId();
  const main = notes.sections.filter((s) => s.kind !== 'underTheHood');
  const hood = notes.sections.filter((s) => s.kind === 'underTheHood');
  const fold = hood.length > 0 && (main.length > 0 || notes.intro.length > 0);
  const hoodCount = hood.reduce((n, s) => n + size(s), 0);
  return (
    <div className="v2-whatsnew">
      <Blocks blocks={notes.intro} onNavigate={onNavigate} />
      {main.map((s, i) => (
        <Section key={i} section={s} onNavigate={onNavigate} />
      ))}
      {fold ? (
        <div className="v2-whatsnew__fold">
          {/* One label either way: whether it is open is aria-expanded's to say (and the chevron's, to the eye). */}
          <LinkButton
            className="v2-whatsnew__toggle"
            aria-expanded={more}
            aria-controls={moreId}
            onClick={() => setMore((v) => !v)}
          >
            Under the hood ({hoodCount})
            <IconChevronDown className={more ? 'v2-whatsnew__chev is-open' : 'v2-whatsnew__chev'} />
          </LinkButton>
          <div id={moreId} hidden={!more} className="v2-whatsnew__folded">
            {more ? hood.map((s, i) => <Section key={i} section={s} onNavigate={onNavigate} />) : null}
          </div>
        </div>
      ) : (
        hood.map((s, i) => <Section key={i} section={s} onNavigate={onNavigate} />)
      )}
      <Blocks blocks={notes.outro} onNavigate={onNavigate} />
    </div>
  );
}
