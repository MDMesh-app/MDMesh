// "Choose from uploaded images": the customer's kiosk background images (one per content), to use one without
// uploading it again, or delete one nothing uses.
import { useCallback, useEffect, useState } from 'react';
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

  const load = useCallback(async (page: number) => {
    setLoading(true);
    setError(null);
    try {
      const res = await listKioskBackgroundImages(page, PAGE);
      setItems((prev) => (page === 0 ? res.items : [...prev, ...res.items]));
      setTotal(res.total);
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
      void load(0);
    }
  }, [open, load]);

  async function remove(item: KioskGalleryItem) {
    setError(null);
    try {
      for (const id of item.fileIds) await deleteKioskBackgroundImage(id);
      setItems((prev) => prev.filter((x) => x.sha256 !== item.sha256));
      setTotal((t) => t - 1);
      setStatus(`${item.name} deleted.`);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setConfirming(null);
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
        <ul className="v2-kgal__list" aria-label="Uploaded images">
          {items.map((item) => {
            const current = item.fileIds.includes(currentFileId ?? -1);
            const others = item.usedByCount - item.usedBy.length;
            const uses = item.usedByCount === 0
              ? 'Not used'
              : `Used by ${item.usedBy.map((u) => u.name).join(', ')}${others > 0 ? `${item.usedBy.length ? ' and ' : ''}${others} other${others === 1 ? '' : 's'}` : ''}`;
            return (
              <li key={item.sha256} className="v2-kgal__item">
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
                    <Button size="sm" variant="primary" onClick={() => onChoose(item)} aria-label={`Use ${item.name}`}>
                      Use
                    </Button>
                  )}
                  {canDelete && item.usedByCount === 0 && !current ? (
                    confirming === item.sha256 ? (
                      <>
                        <Button size="sm" variant="danger" onClick={() => void remove(item)} aria-label={`Delete ${item.name} for good`}>
                          Delete for good
                        </Button>
                        <Button size="sm" onClick={() => setConfirming(null)}>
                          Keep
                        </Button>
                      </>
                    ) : (
                      <Button size="sm" onClick={() => setConfirming(item.sha256)} aria-label={`Delete ${item.name}`}>
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
          <Button size="sm" onClick={() => void load(Math.floor(items.length / PAGE))} disabled={loading}>
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
