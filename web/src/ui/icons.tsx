// Minimal hairline icon set (stroke = currentColor), 16px grid.
import type { ReactNode } from 'react';

type P = { className?: string };

function Svg({ children, className }: { children: ReactNode } & P) {
  return (
    <svg
      className={className}
      width="16"
      height="16"
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.4"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {children}
    </svg>
  );
}

export const IconDashboard = (p: P) => (
  <Svg {...p}>
    <rect x="2" y="2" width="5" height="5" />
    <rect x="9" y="2" width="5" height="5" />
    <rect x="2" y="9" width="5" height="5" />
    <rect x="9" y="9" width="5" height="5" />
  </Svg>
);

export const IconDevices = (p: P) => (
  <Svg {...p}>
    <rect x="4" y="1.5" width="8" height="13" rx="1.4" />
    <line x1="6.5" y1="12.5" x2="9.5" y2="12.5" />
  </Svg>
);

export const IconEnroll = (p: P) => (
  <Svg {...p}>
    <path d="M8 2v8" />
    <path d="M5 7l3 3 3-3" />
    <path d="M2.5 13.5h11" />
  </Svg>
);

export const IconSettings = (p: P) => (
  <Svg {...p}>
    <circle cx="8" cy="8" r="2.2" />
    <path d="M8 1.5v1.6M8 12.9v1.6M14.5 8h-1.6M3.1 8H1.5M12.6 3.4l-1.1 1.1M4.5 11.5l-1.1 1.1M12.6 12.6l-1.1-1.1M4.5 4.5L3.4 3.4" />
  </Svg>
);

export const IconSignOut = (p: P) => (
  <Svg {...p}>
    <path d="M6 2.5H3.5A1.5 1.5 0 0 0 2 4v8a1.5 1.5 0 0 0 1.5 1.5H6" />
    <path d="M10 4.5L13.5 8 10 11.5" />
    <line x1="13.5" y1="8" x2="6.5" y2="8" />
  </Svg>
);

export const IconCopy = (p: P) => (
  <Svg {...p}>
    <rect x="5.5" y="5.5" width="8" height="8" rx="1.2" />
    <path d="M3.5 10.5H3A1 1 0 0 1 2 9.5V3a1 1 0 0 1 1-1h6.5a1 1 0 0 1 1 1v.5" />
  </Svg>
);

export const IconArrowLeft = (p: P) => (
  <Svg {...p}>
    <path d="M9.5 3.5L5 8l4.5 4.5" />
    <line x1="5" y1="8" x2="13" y2="8" />
  </Svg>
);

export const IconMenu = (p: P) => (
  <Svg {...p}>
    <line x1="2.5" y1="4" x2="13.5" y2="4" />
    <line x1="2.5" y1="8" x2="13.5" y2="8" />
    <line x1="2.5" y1="12" x2="13.5" y2="12" />
  </Svg>
);

export const IconConfig = (p: P) => (
  <Svg {...p}>
    <line x1="2.5" y1="4" x2="13.5" y2="4" />
    <circle cx="6" cy="4" r="1.7" />
    <line x1="2.5" y1="8" x2="13.5" y2="8" />
    <circle cx="10.5" cy="8" r="1.7" />
    <line x1="2.5" y1="12" x2="13.5" y2="12" />
    <circle cx="5" cy="12" r="1.7" />
  </Svg>
);

export const IconApps = (p: P) => (
  <Svg {...p}>
    <path d="M3.5 5h9l-.7 7.8a1 1 0 0 1-1 .9H5.2a1 1 0 0 1-1-.9z" />
    <path d="M5.8 5a2.2 2.2 0 0 1 4.4 0" />
  </Svg>
);

export const IconSun = (p: P) => (
  <Svg {...p}>
    <circle cx="8" cy="8" r="3" />
    <path d="M8 1v1.5M8 13.5V15M1 8h1.5M13.5 8H15M3 3l1 1M12 12l1 1M13 3l-1 1M4 12l-1 1" />
  </Svg>
);

export const IconMoon = (p: P) => (
  <Svg {...p}>
    <path d="M13.5 9.2A5.5 5.5 0 0 1 6.8 2.5 5.5 5.5 0 1 0 13.5 9.2Z" />
  </Svg>
);
