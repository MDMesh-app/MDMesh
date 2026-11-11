// "Choose from uploaded images": the customer's kiosk background images (one per content), to use one without
// uploading it again, or delete one nothing uses.
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  deleteKioskBackgroundImage,
  listKioskBackgroundImages,
  type KioskGalleryItem,
} from '../../api/configurations';
import { Button, Modal } from '../../ui';
import { fmtDateTime } from '../../ui/format';
import { fmtBytes } from '../deviceDetailFormat';
import { hostedPath, shortSha } from './KioskBackground';
import './KioskGallery.css';

const PAGE = 20;
/** Fetched per request; the list keeps the first n of them. */
const FETCH = 100;

const errorText = (e: unknown) =>
  e instanceof Error && e.message && !/^error\./.test(e.message) ? e.message : 'The server could not do that. Try again.';

export function KioskGallery({
  open,
  onClose,
  currentFileId,
  onChoose,
  canDelete,
}: {
  open: boolean;
  onClose: () => void;
  /** The draft's image: marked, and not offered again. */
  currentFileId: number | null | undefined;
  onChoose: (item: KioskGalleryItem) => void;
  /** The user may delete files (edit_files). */
  canDelete: boolean;
}) {
  const [items, setItems] = useState<KioskGalleryItem[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<string | null>(null);
  const [status, setStatus] = useState('');
  const list = useRef<HTMLUListElement>(null);
  /** Where focus goes once the rows have re-rendered (a row's button by sha256 and role, or the list). */
  const [focusNext, setFocusNext] = useState<{ sha?: string; role: 'use' | 'delete' | 'confirm' | 'list' } | null>(null);

  // Always the first n images from the top, never a page appended to a list that has changed since: a delete here or
  // an upload elsewhere shifts the server's pages, and an appended page would skip or repeat images.
  const loadUpTo = useCallback(async (n: number) => {
    setLoading(true);
    setError(null);
    try {
      const out: KioskGalleryItem[] = [];
      let count = 0;
      for (let page = 0; ; page++) {
        const res = await listKioskBackgroundImages(page, FETCH);
        count = res.total;
        for (const it of res.items) if (!out.some((x) => x.sha256 === it.sha256)) out.push(it);
        if (res.items.length < FETCH || out.length >= n) break;
      }
      setItems(out.slice(0, n));
      setTotal(count);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (open) {
      setConfirming(null);
      setStatus('');
      void loadUpTo(PAGE);
    }
  }, [open, loadUpTo]);

  useEffect(() => {
    if (!focusNext) return;
    const root = list.current;
    const el = focusNext.role === 'list' || !focusNext.sha
      ? root
      : root?.querySelector<HTMLElement>(`[data-sha="${focusNext.sha}"] [data-role="${focusNext.role}"]`);
    (el ?? root)?.focus();
    setFocusNext(null);
  }, [focusNext, items, confirming]);

  async function remove(item: KioskGalleryItem) {
    setError(null);
    const index = items.findIndex((x) => x.sha256 === item.sha256);
    const next = items[index + 1] ?? items[index - 1];
    try {
      for (const id of item.fileIds) await deleteKioskBackgroundImage(id);
      setItems((prev) => prev.filter((x) => x.sha256 !== item.sha256));
      setTotal((t) => t - 1);
      setStatus(`${item.name} deleted.`);
      setConfirming(null);
      setFocusNext(next ? { sha: next.sha256, role: 'use' } : { role: 'list' });
    } catch (e) {
      // Some files may be gone already: show what the server has now, not a row that points at deleted files.
      setConfirming(null);
      await loadUpTo(Math.max(PAGE, items.length));
      setError(errorText(e));
      setFocusNext({ role: 'list' });
    }
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      size="lg"
      title="Uploaded images"
      description="Kiosk background images uploaded before. Use one without uploading it again."
      footer={<Button onClick={onClose}>Close</Button>}
    >
      <div className="v2-kgal">
        <p className="v2-kgal__status" role="status" aria-live="polite">
          {status}
        </p>
        {error ? (
          <p className="v2-kgal__error" role="alert">
            {error}
          </p>
        ) : null}
        {!loading && items.length === 0 && !error ? <p className="v2-kgal__empty">No images uploaded yet.</p> : null}
        <ul ref={list} className="v2-kgal__list" aria-label="Uploaded images" tabIndex={-1}>
          {items.map((item) => {
            const current = item.fileIds.includes(currentFileId ?? -1);
            const others = item.usedByCount - item.usedBy.length;
            const uses = item.usedByCount === 0
              ? 'Not used'
              : `Used by ${item.usedBy.map((u) => u.name).join(', ')}${others > 0 ? `${item.usedBy.length ? ' and ' : ''}${others} other${others === 1 ? '' : 's'}` : ''}`;
            return (
              <li key={item.sha256} className="v2-kgal__item" data-sha={item.sha256}>
                <img className="v2-kgal__thumb" src={hostedPath(item.url)} alt="" width={96} height={64} loading="lazy" />
                <div className="v2-kgal__facts">
                  <span className="v2-kgal__name">{item.name}</span>
                  <span className="v2-kgal__meta">
                    {item.width} × {item.height} px · {fmtBytes(item.size)} · {fmtDateTime(item.uploadTime ?? undefined)}
                  </span>
                  <span className="v2-kgal__meta">
                    <span className="v2-mono" title={item.sha256}>
                      {shortSha(item.sha256)}
                    </span>{' '}
                    · {uses}
                  </span>
                </div>
                <div className="v2-kgal__actions">
                  {current ? (
                    <span className="v2-kgal__current">In use here</span>
                  ) : (
                    <Button size="sm" variant="primary" data-role="use" onClick={() => onChoose(item)} aria-label={`Use ${item.name}`}>
                      Use
                    </Button>
                  )}
                  {canDelete && item.usedByCount === 0 && !current ? (
                    confirming === item.sha256 ? (
                      <span className="v2-kgal__confirm" role="group" aria-label={`Delete ${item.name}?`}>
                        <Button size="sm" variant="danger" data-role="confirm" onClick={() => void remove(item)} aria-label={`Delete ${item.name} for good`}>
                          Delete for good
                        </Button>
                        <Button
                          size="sm"
                          onClick={() => {
                            setConfirming(null);
                            setFocusNext({ sha: item.sha256, role: 'delete' });
                          }}
                        >
                          Keep
                        </Button>
                      </span>
                    ) : (
                      <Button
                        size="sm"
                        data-role="delete"
                        onClick={() => {
                          setConfirming(item.sha256);
                          setFocusNext({ sha: item.sha256, role: 'confirm' });
                        }}
                        aria-label={`Delete ${item.name}`}
                      >
                        Delete
                      </Button>
                    )
                  ) : null}
                </div>
              </li>
            );
          })}
        </ul>
        {items.length < total ? (
          <Button size="sm" onClick={() => void loadUpTo(items.length + PAGE)} disabled={loading}>
            {loading ? 'Loading…' : `Show more (${total - items.length})`}
          </Button>
        ) : loading ? (
          <p className="v2-kgal__empty" role="status">
            Loading…
          </p>
        ) : null}
      </div>
    </Modal>
  );
}
