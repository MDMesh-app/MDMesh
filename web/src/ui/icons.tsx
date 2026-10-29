// Icon set. Paths are copied from the design boards (stroke = currentColor), so each icon keeps the board's viewBox
// and stroke width; `size` sets the rendered box. Menu and sun, which the boards don't draw, keep the classic
// console's 16px hairline paths.
import type { ReactNode, SVGProps } from 'react';

export interface IconProps extends Omit<SVGProps<SVGSVGElement>, 'children'> {
  /** Rendered width and height in px. */
  size?: number;
}

function make(
  viewBox: string,
  defaultSize: number,
  strokeWidth: number | undefined,
  body: ReactNode,
  extra: SVGProps<SVGSVGElement> = {},
) {
  return function Icon({ size = defaultSize, ...rest }: IconProps) {
    return (
      <svg
        width={size}
        height={size}
        viewBox={viewBox}
        fill="none"
        stroke="currentColor"
        strokeWidth={strokeWidth}
        aria-hidden="true"
        focusable="false"
        {...extra}
        {...rest}
      >
        {body}
      </svg>
    );
  };
}

/* ---- classic hairline set (16px grid): mobile menu button, light-mode switch ---- */
const hairline = { strokeLinecap: 'round', strokeLinejoin: 'round' } as const;
export const IconMenu = make('0 0 16 16', 16, 1.4, <>
  <line x1="2.5" y1="4" x2="13.5" y2="4" />
  <line x1="2.5" y1="8" x2="13.5" y2="8" />
  <line x1="2.5" y1="12" x2="13.5" y2="12" />
</>, hairline);
export const IconSun = make('0 0 16 16', 16, 1.4, <>
  <circle cx="8" cy="8" r="3" />
  <path d="M8 1v1.5M8 13.5V15M1 8h1.5M13.5 8H15M3 3l1 1M12 12l1 1M13 3l-1 1M4 12l-1 1" />
</>, hairline);

/* ---- navigation (18px grid, sidebar) ---- */
export const IconOverview = make('0 0 18 18', 18, 1.5, <>
  <rect x="2.5" y="2.5" width="5.5" height="5.5" rx="1.5" />
  <rect x="10" y="2.5" width="5.5" height="5.5" rx="1.5" />
  <rect x="2.5" y="10" width="5.5" height="5.5" rx="1.5" />
  <rect x="10" y="10" width="5.5" height="5.5" rx="1.5" />
</>);
export const IconDevices = make('0 0 18 18', 18, 1.5, <>
  <rect x="4.5" y="1.5" width="9" height="15" rx="2.5" />
  <path d="M8 13.5h2" />
</>);
export const IconConfigurations = make('0 0 18 18', 18, 1.5, <>
  <path d="M3 5h3.5M9.5 5H15M3 9h6.5M12.5 9H15M3 13h2.5M8.5 13H15" />
  <circle cx="8" cy="5" r="1.5" />
  <circle cx="11" cy="9" r="1.5" />
  <circle cx="7" cy="13" r="1.5" />
</>, { strokeLinecap: 'round' });
export const IconApps = make('0 0 18 18', 18, 1.5, <>
  <path d="M9 2l6 3.5v7L9 16l-6-3.5v-7z" />
  <path d="M3 5.5L9 9l6-3.5M9 9v7" />
</>, { strokeLinejoin: 'round' });
export const IconEnroll = make('0 0 18 18', 18, 1.5, <>
  <rect x="2.5" y="2.5" width="5" height="5" rx="1" />
  <rect x="10.5" y="2.5" width="5" height="5" rx="1" />
  <rect x="2.5" y="10.5" width="5" height="5" rx="1" />
  <path d="M10.5 10.5h2v2h-2zM13.5 13.5h2v2h-2z" />
</>);
export const IconSettings = make('0 0 24 24', 18, 1.7, <>
  <path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z" />
  <circle cx="12" cy="12" r="3" />
</>, { strokeLinecap: 'round', strokeLinejoin: 'round' });

/* ---- sidebar footer (16px) ---- */
export const IconMoon = make('0 0 16 16', 16, 1.5, <path d="M13 9.5A5.5 5.5 0 0 1 6.5 3a5.5 5.5 0 1 0 6.5 6.5z" />);
export const IconSignOut = make('0 0 16 16', 16, 1.5, <path d="M6 2.5H3.5v11H6M10 5l3 3-3 3M13 8H6.5" />);

/* ---- actions (16px grid) ---- */
export const IconPlus = make('0 0 16 16', 15, 1.8, <path d="M8 2.5v11M2.5 8h11" />);
export const IconCheck = make('0 0 16 16', 14, 1.8, <path d="M3 8.3l3 3 7-7" />);
export const IconSearch = make('0 0 16 16', 15, 1.6, <>
  <circle cx="7" cy="7" r="4.8" />
  <path d="M10.5 10.5L14 14" />
</>);
export const IconSync = make('0 0 16 16', 15, 1.5, <path d="M13 6A5.2 5.2 0 0 0 3.3 5M3 10a5.2 5.2 0 0 0 9.7 1M3.2 2v3h3M12.8 14v-3h-3" />);
export const IconMore = make('0 0 16 16', 15, undefined, <>
  <circle cx="3.5" cy="8" r="1.3" />
  <circle cx="8" cy="8" r="1.3" />
  <circle cx="12.5" cy="8" r="1.3" />
</>, { fill: 'currentColor', stroke: 'none' });
export const IconPlay = make('0 0 16 16', 15, 1.5, <path d="M4 2.5l9 5.5-9 5.5z" />);
export const IconSliders = make('0 0 16 16', 15, 1.5, <>
  <path d="M2.5 4.5h2.5M8 4.5h5.5M2.5 8h6M11.5 8h2M2.5 11.5h1.5M7 11.5h6.5" />
  <circle cx="6.5" cy="4.5" r="1.4" />
  <circle cx="10" cy="8" r="1.4" />
  <circle cx="5.5" cy="11.5" r="1.4" />
</>, { strokeLinecap: 'round' });
export const IconTrash = make('0 0 16 16', 15, 1.5, <path d="M2.5 4h11M6 4V2.5h4V4M4 4l.7 9.5h6.6L12 4" />);
export const IconFactoryReset = make('0 0 16 16', 16, 1.6, <path d="M2.5 4h11M6 4V2.5h4V4M4 4l.7 9.5h6.6L12 4M6.8 7v4M9.2 7v4" />, { strokeLinecap: 'round' });
export const IconKey = make('0 0 16 16', 16, 1.6, <>
  <circle cx="5" cy="8" r="2.6" />
  <path d="M7.6 8H14M12 8v2.5M14 8v2" />
</>, { strokeLinecap: 'round' });
export const IconPencil = make('0 0 14 14', 13, 1.5, <path d="M2 12l1-3.5L9.5 2 12 4.5 5.5 11z" />);
export const IconCopy = make('0 0 16 16', 14, 1.5, <>
  <rect x="5" y="5" width="8.5" height="8.5" rx="1.5" />
  <path d="M11 5V3.5A1 1 0 0 0 10 2.5H3.5a1 1 0 0 0-1 1V10a1 1 0 0 0 1 1H5" />
</>);
export const IconLock = make('0 0 16 16', 15, 1.6, <path d="M3 7h10v7H3zM5.5 7V5a2.5 2.5 0 0 1 5 0v2" />, { strokeLinecap: 'round', strokeLinejoin: 'round' });
export const IconLockSmall = make('0 0 16 16', 11, 1.8, <>
  <rect x="3" y="7" width="10" height="7" rx="1.6" />
  <path d="M5.5 7V5a2.5 2.5 0 0 1 5 0v2" />
</>);
export const IconClose = make('0 0 12 12', 12, 1.6, <path d="M3 3l6 6M9 3l-6 6" />);
export const IconChevronDown = make('0 0 12 12', 12, 1.6, <path d="M3 4.5l3 3 3-3" />);
export const IconEye = make('0 0 16 16', 15, 1.5, <>
  <path d="M1.5 8s2.4-4.5 6.5-4.5S14.5 8 14.5 8 12.1 12.5 8 12.5 1.5 8 1.5 8z" />
  <circle cx="8" cy="8" r="2" />
</>);
export const IconEyeOff = make('0 0 16 16', 15, 1.5, <>
  <path d="M1.5 8s2.4-4.5 6.5-4.5S14.5 8 14.5 8 12.1 12.5 8 12.5 1.5 8 1.5 8z" />
  <circle cx="8" cy="8" r="2" />
  <path d="M2.5 2.5l11 11" />
</>, { strokeLinecap: 'round' });
export const IconDownload = make('0 0 16 16', 14, 1.6, <path d="M8 2v8M5 7.5L8 10.5l3-3M2.5 11v2.5h11V11" />, { strokeLinecap: 'round', strokeLinejoin: 'round' });
export const IconUpload = make('0 0 16 16', 15, 1.5, <path d="M8 10.5V2.5M5 5.5l3-3 3 3M2.5 10v3.5h11V10" />, { strokeLinecap: 'round', strokeLinejoin: 'round' });

/* ---- views ---- */
export const IconTable = make('0 0 16 16', 15, 1.5, <>
  <rect x="2" y="2.5" width="12" height="11" rx="2" />
  <path d="M2 6h12M2 9.5h12" />
</>);
export const IconCards = make('0 0 16 16', 15, 1.5, <>
  <rect x="2" y="2" width="5" height="5" rx="1.2" />
  <rect x="9" y="2" width="5" height="5" rx="1.2" />
  <rect x="2" y="9" width="5" height="5" rx="1.2" />
  <rect x="9" y="9" width="5" height="5" rx="1.2" />
</>);

/* ---- status / notices ---- */
export const IconWarning = make('0 0 18 18', 17, 1.6, <>
  <path d="M9 2.5L16 15H2z" />
  <path d="M9 7.5v3.5M9 13v.01" />
</>);
export const IconInfo = make('0 0 16 16', 15, 1.5, <>
  <circle cx="8" cy="8" r="6" />
  <path d="M8 7v4M8 5h.01" />
</>, { strokeLinecap: 'round' });
export const IconClock = make('0 0 16 16', 16, 1.5, <>
  <circle cx="8" cy="8" r="6" />
  <path d="M8 4.5V8l2.5 1.5" />
</>);
export const IconCheckSmall = make('0 0 12 12', 10, 2, <path d="M2.5 6.2l2.3 2.3 4.7-5" />);
export const IconCrossSmall = make('0 0 12 12', 12, 1.8, <path d="M3 3l6 6M9 3l-6 6" />, { strokeLinecap: 'round' });
export const IconDashSmall = make('0 0 12 12', 12, 1.8, <path d="M3 6h6" />, { strokeLinecap: 'round' });

/* ---- device detail: tabs and remote actions (16px, stroke 1.5-1.6) ---- */
export const IconControl = make('0 0 16 16', 15, 1.5, <path d="M2.5 4.5h2.5M8 4.5h5.5M2.5 8h6M11.5 8h2M2.5 11.5h1.5M7 11.5h6.5M6.5 3.1a1.4 1.4 0 1 0 0 2.8 1.4 1.4 0 1 0 0-2.8M10 6.6a1.4 1.4 0 1 0 0 2.8 1.4 1.4 0 1 0 0-2.8M5.5 10.1a1.4 1.4 0 1 0 0 2.8 1.4 1.4 0 1 0 0-2.8" />, { strokeLinecap: 'round', strokeLinejoin: 'round' });
export const IconPulse = make('0 0 16 16', 15, 1.5, <path d="M1.5 8.5h3l2-5 3 9 2-4h3" />, { strokeLinecap: 'round', strokeLinejoin: 'round' });
export const IconHistory = make('0 0 16 16', 15, 1.5, <path d="M8 2.2a5.8 5.8 0 1 0 0 11.6 5.8 5.8 0 1 0 0-11.6M8 4.5V8l2.5 1.5" />, { strokeLinecap: 'round', strokeLinejoin: 'round' });
export const IconPin = make('0 0 16 16', 15, 1.5, <path d="M8 14.5s4.5-4.2 4.5-7.5a4.5 4.5 0 0 0-9 0c0 3.3 4.5 7.5 4.5 7.5zM8 5.4a1.6 1.6 0 1 0 0 3.2 1.6 1.6 0 1 0 0-3.2" />, { strokeLinecap: 'round', strokeLinejoin: 'round' });
export const IconAlert = make('0 0 16 16', 16, 1.6, <path d="M8 2.5L14 13H2zM8 6.5v3M8 11.2v.01" />, { strokeLinecap: 'round', strokeLinejoin: 'round' });
export const IconScreenMessage = make('0 0 16 16', 16, 1.6, <path d="M4.5 1.5h7v13h-7zM6.5 5h3M6.5 7.5h3M7.3 12.5h1.4" />, { strokeLinecap: 'round', strokeLinejoin: 'round' });
export const IconRing = make('0 0 16 16', 16, 1.6, <path d="M4 11V7.5a4 4 0 0 1 8 0V11l1.2 1.5H2.8zM6.8 14.5h2.4" />, { strokeLinecap: 'round', strokeLinejoin: 'round' });
export const IconStopRing = make('0 0 16 16', 16, 1.6, <path d="M4 11V7.5a4 4 0 0 1 6.5-3.1M12 7.5V11l1.2 1.5H5M6.8 14.5h2.4M2.5 2.5l11 11" />, { strokeLinecap: 'round', strokeLinejoin: 'round' });
export const IconEnterKiosk = make('0 0 16 16', 16, 1.6, <path d="M9.5 2.5h4v11h-4M2.5 8h7M7 5.5L9.5 8 7 10.5" />, { strokeLinecap: 'round', strokeLinejoin: 'round' });
export const IconExitKiosk = make('0 0 16 16', 16, 1.6, <path d="M6.5 2.5h-4v11h4M6 8h7.5M11 5.5L13.5 8 11 10.5" />, { strokeLinecap: 'round', strokeLinejoin: 'round' });
export const IconPower = make('0 0 16 16', 16, 1.6, <path d="M8 2v5.5M4.6 4.2a5.2 5.2 0 1 0 6.8 0" />, { strokeLinecap: 'round', strokeLinejoin: 'round' });
export const IconNetwork = make('0 0 16 16', 15, 1.6, <path d="M2 6.5a9 9 0 0 1 12 0M4.3 9a5.6 5.6 0 0 1 7.4 0M6.6 11.5a2.2 2.2 0 0 1 2.8 0" />, { strokeLinecap: 'round', strokeLinejoin: 'round' });
export const IconBattery = make('0 0 16 16', 15, 1.6, <path d="M2 5h10v6H2zM12 7v2h1.5V7z" />, { strokeLinecap: 'round', strokeLinejoin: 'round' });

/* ---- apps sources ---- */
export const IconLibrary = make('0 0 16 16', 15, 1.5, <path d="M2.5 3h3v10h-3zM6.5 3h3v10h-3zM10.5 3.6l2.8-.8 2.2 9.7-2.8.7z" />, { strokeLinejoin: 'round' });
export const IconAndroid = make('0 0 16 16', 15, 1.5, <>
  <rect x="2.5" y="5" width="11" height="8.5" rx="2" />
  <path d="M5 5l-1.2-2M11 5l1.2-2M6 9h.01M10 9h.01" />
</>, { strokeLinecap: 'round' });
