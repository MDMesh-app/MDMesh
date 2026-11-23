// The kiosk background image in the console: the configuration editor's viewer (what is set, with Replace and
// Remove) and the device page's line naming the image the device's configuration specifies. The preview of how the
// launcher will look is KioskPreview.
import { useEffect, useId, useRef, useState } from 'react';
import { Link } from 'react-router';
import { getKioskBackgroundImage, type KioskBackgroundImageInfo } from '../../api/configurations';
import { KIOSK_IMAGE_TYPES } from '../../data/kioskBackground';
import { paths } from '../../routes';
import { Button, Card, IconInfo, KeyValueList } from '../../ui';
import { fmtDateTime } from '../../ui/format';
import { fmtBytes } from '../deviceDetailFormat';
import './KioskBackground.css';

/** The same-origin path of a hosted file's URL: the browser loads it from the console's own server (no CORS taint). */
export function hostedPath(url: string): string {
  try {
    const u = new URL(url, window.location.href);
    return `${u.pathname}${u.search}`;
  } catch {
    return url;
  }
}

/** The SHA-256 as people compare it: its first 12 hex characters. */
export const shortSha = (sha: string) => sha.slice(0, 12);


/* ---------------------------------------------------------------------------------------------------------------
   Configuration editor: the field
   --------------------------------------------------------------------------------------------------------------- */

/** The facts of the draft's image file, read from the server (null while there is none or it is loading). */
export function useKioskBackgroundInfo(fileId: number | null | undefined): {
  info: KioskBackgroundImageInfo | null;
  error: string | null;
} {
  const [state, setState] = useState<{ fileId: number; info: KioskBackgroundImageInfo | null; error: string | null } | null>(null);
  useEffect(() => {
    if (fileId == null) return;
    let cancelled = false;
    getKioskBackgroundImage(fileId)
      .then((info) => !cancelled && setState({ fileId, info, error: null }))
      .catch((e: unknown) => {
        if (cancelled) return;
        const msg = e instanceof Error && e.message && !/^error\./.test(e.message) ? e.message : 'The image could not be read.';
        setState({ fileId, info: null, error: msg });
      });
    return () => {
      cancelled = true;
    };
  }, [fileId]);
  return fileId != null && state?.fileId === fileId ? { info: state.info, error: state.error } : { info: null, error: null };
}

/** What is set (thumbnail and facts, or the legacy URL, or nothing) with Choose/Replace and Remove. */
export function KioskBackgroundField({
  fileId,
  info,
  error,
  legacyUrl,
  readOnly,
  uploading,
  onFile,
  onRemove,
  onBrowse,
  labelId,
}: {
  /** The managed image (draft): an uploaded file id. */
  fileId: number | null | undefined;
  info: KioskBackgroundImageInfo | null;
  error: string | null;
  /** A legacy launcher wallpaper URL, shown when there is no managed image. */
  legacyUrl?: string | null;
  readOnly: boolean;
  uploading: boolean;
  onFile: (file: File) => void;
  onRemove: () => void;
  /** Opens "Uploaded images"; left out, the button isn't shown. */
  onBrowse?: () => void;
  labelId?: string;
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  const has = fileId != null || !!legacyUrl;

  return (
    <div className="v2-kbg">
      <input
        ref={inputRef}
        type="file"
        accept={KIOSK_IMAGE_TYPES.join(',')}
        hidden
        aria-hidden="true"
        tabIndex={-1}
        onChange={(e) => {
          const file = e.currentTarget.files?.[0];
          e.currentTarget.value = '';
          if (file) onFile(file);
        }}
      />
      {info ? (
        <KioskBackgroundSummary info={info} />
      ) : fileId != null ? (
        <p className="v2-kbg__state" role={error ? 'alert' : 'status'}>
          {error ?? 'Reading the image…'}
        </p>
      ) : legacyUrl ? (
        <p className="v2-kbg__state">
          Legacy wallpaper URL <span className="v2-mono v2-kbg__url">{legacyUrl}</span>. Agents ignore it; choose an image
          to show one.
        </p>
      ) : (
        <p className="v2-kbg__state">No image: the kiosk shows its background color.</p>
      )}
      <div className="v2-kbg__actions" role="group" aria-labelledby={labelId}>
        <Button size="sm" onClick={() => inputRef.current?.click()} disabled={readOnly || uploading}>
          {uploading ? 'Uploading…' : has ? 'Replace image' : 'Choose image'}
        </Button>
        {onBrowse ? (
          <Button size="sm" onClick={onBrowse} disabled={readOnly || uploading}>
            Choose from uploaded
          </Button>
        ) : null}
        {has ? (
          <Button size="sm" onClick={onRemove} disabled={readOnly || uploading}>
            Remove
          </Button>
        ) : null}
      </div>
      <details className="v2-kbg__how">
        <summary>How it works</summary>
        <ul>
          <li>At most 10 MB and 16 megapixels. The console scales larger photos to 3840 px before uploading.</li>
          <li>The server removes location, camera and other details before devices get the image.</li>
          <li>Devices download it only over HTTPS and check it by its SHA-256; on any problem they keep the color.</li>
          <li>Text gets a plate of the background color where the image behind it is busy, so it stays readable.</li>
          <li>It is hosted in Files, where anyone with its link can open it: don't use a confidential image.</li>
          <li>Remove also clears the legacy Background image URL.</li>
        </ul>
      </details>
    </div>
  );
}

/**
 * Whether the thumbnail at `url` failed to load. Tied to the URL that failed, so another image (a replace, or a
 * gallery pick) gets its own thumbnail instead of "Preview unavailable" from the one before.
 */
function useBrokenImage(url: string): [boolean, () => void] {
  const [brokenUrl, setBrokenUrl] = useState<string | null>(null);
  return [brokenUrl === url, () => setBrokenUrl(url)];
}

/** Thumbnail and facts of the current image. */
export function KioskBackgroundSummary({ info }: { info: KioskBackgroundImageInfo }) {
  const [broken, setBroken] = useBrokenImage(info.url);
  const dims = `${info.width} × ${info.height}`;
  return (
    <div className="v2-kbg__summary">
      {broken ? (
        <div className="v2-kbg__thumb v2-kbg__thumb--missing">Preview unavailable</div>
      ) : (
        <img
          className="v2-kbg__thumb"
          src={hostedPath(info.url)}
          alt={`Current kiosk background: ${info.name}, ${dims} pixels`}
          width={160}
          height={100}
          loading="lazy"
          onError={setBroken}
        />
      )}
      <div className="v2-kbg__facts">
        <KeyValueList
          variant="rail"
          items={[
            { key: 'name', label: 'File', value: <span className="v2-kbg__name">{info.name}</span> },
            { key: 'size', label: 'Size', value: `${dims} px · ${fmtBytes(info.size)} · ${info.format}` },
            { key: 'uploaded', label: 'Uploaded', value: fmtDateTime(info.uploadTime ?? undefined) },
            {
              key: 'sha',
              label: 'SHA-256',
              mono: true,
              value: (
                <span title={info.sha256} aria-label={`SHA-256 starting ${shortSha(info.sha256)}`}>
                  {shortSha(info.sha256)}
                </span>
              ),
            },
          ]}
        />
        {info.fromAnotherAccount ? (
          <p className="v2-kbg__note">
            <IconInfo size={14} /> An image of the main account, which this configuration was copied from. Replace it
            to use one of this account’s images.
          </p>
        ) : info.hasMetadata ? (
          <p className="v2-kbg__note">
            <IconInfo size={14} /> Its location and camera details are removed when you save.
          </p>
        ) : null}
      </div>
    </div>
  );
}

/* ---------------------------------------------------------------------------------------------------------------
   Device page
   --------------------------------------------------------------------------------------------------------------- */

/** The background image the device's configuration specifies (what the device shows isn't reported yet). */
export function DeviceKioskBackground({
  image,
  configurationId,
  configName,
}: {
  image: { url: string; sha256: string };
  configurationId: number | null | undefined;
  configName: string | undefined;
}) {
  const titleId = useId();
  const [broken, setBroken] = useBrokenImage(image.url);
  const name = configName ?? 'Its configuration';
  return (
    <Card aria-labelledby={titleId} className="v2-kbg-device">
      {broken ? null : (
        <img
          className="v2-kbg-device__thumb"
          src={hostedPath(image.url)}
          alt="Kiosk background image"
          width={72}
          height={48}
          loading="lazy"
          onError={setBroken}
        />
      )}
      <div className="v2-kbg-device__body">
        <h2 id={titleId} className="v2-kbg-device__title">
          Kiosk background
        </h2>
        <p className="v2-kbg-device__text">
          {configurationId != null ? <Link to={paths.configuration(configurationId)}>{name}</Link> : name} sets the image{' '}
          <span className="v2-mono" title={image.sha256}>
            {shortSha(image.sha256)}
          </span>
          . Whether the device shows it isn&rsquo;t reported yet.
        </p>
      </div>
    </Card>
  );
}
