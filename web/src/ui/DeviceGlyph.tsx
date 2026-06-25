// Thin device-type outline. Tablets get a wide body; phones a narrow one.
// Kind is inferred from the model/description string.

export type DeviceKind = 'tablet' | 'phone';

export function deviceKind(name?: string): DeviceKind {
  const s = (name ?? '').toLowerCase();
  return /tab|pad|book|sm-[xt]|signage|kiosk|display/.test(s) ? 'tablet' : 'phone';
}

export function DeviceGlyph({
  name,
  size = 16,
  className,
}: {
  name?: string;
  size?: number;
  className?: string;
}) {
  const tablet = deviceKind(name) === 'tablet';
  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      aria-hidden="true"
    >
      {tablet ? (
        <rect x="4" y="4" width="16" height="16" rx="2" />
      ) : (
        <rect x="7" y="3" width="10" height="18" rx="2" />
      )}
    </svg>
  );
}
