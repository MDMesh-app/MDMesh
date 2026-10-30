// `@mdmesh/console`: everything an extension may import from the console. Nothing else in web/src is public, and
// any of it may change in any release.
//
// Stability: removing or changing an export below is a breaking change. It is announced in the
// CHANGELOG ("Console extension API") and kept, marked @deprecated, for one release first. Adding is free. The
// export names, and the design tokens (the `.v2` custom properties of tokens.css, used as `var(--accent)` in an
// extension's CSS), are snapshotted in sdk/surface.txt, and the declarations behind them (every public type's
// shape, every component's props) in sdk/surface.d.ts.txt: any change fails the tests until the snapshots are
// updated (`npm test -- -u`) on purpose. Extension classes take an `x-<extension id>-` prefix; `v2-` classes are
// core's.
//
// Kept to what the slots need: their types, the UI kit, the API client, and the hooks a contribution calls. Adding
// an export later is free and removing one is not, so internals stay out (`cx`, the field and tab id helpers, or
// `useLeaveGuard`: a config section gets the editor's guard from its host) until a fork needs one.
//
// Only `export { ... } from` and `export type { ... } from` statements here: the snapshot test reads this file.

// ---- extension API ----
export { EXTENSION_API_VERSION, defineExtension, configSection, lazyComponent } from '../ext/types';
export type {
  ConfigSectionContribution,
  ConfigSectionProps,
  CurrentUser,
  DeviceActionHelpers,
  DeviceCommand,
  DeviceContextValue,
  DeviceRailGroupContribution,
  DeviceTabContribution,
  ExtensionDefinition,
  LazyComponent,
  ListBulkActionContribution,
  ListColumnContribution,
  ListHelpers,
  ListRowActionContribution,
  ListRows,
  NavItemContribution,
  OverviewCardContribution,
  Requirement,
  RoutePageContribution,
  RowActionHelpers,
  SettingsSectionContribution,
  SettingsSectionProps,
} from '../ext/types';
export { ORDER } from '../ext/core/order';

// ---- hooks ----
export { useCurrentUser, usePermission } from '../ext/permissions';
export { useDevice } from '../ext/useDevice';
export { useToast } from '../ui/toast';
export type { ToastApi, ToastKind } from '../ui/toast';

// ---- routing (the console's react-router, so extensions and core agree on one router) ----
export { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
export { paths } from '../routes';

// ---- API ----
export { apiClient, ApiError, API_BASE } from '../api/client';
export type { ApiEnvelope } from '../api/client';
// The row and configuration types the slots hand over, as core has them: fields may be added,
// never removed or changed without the deprecation above.
export type { DeviceView } from '../api/devices';
export type { Configuration } from '../api/configurations';
export type { Application } from '../api/applications';
// What useDevice() hands a device tab, and the request a DeviceCommand queues.
export type { TelemetrySnapshot } from '../api/telemetry';
export type { DeviceState, QueueCommandRequest } from '../api/commands';
export type { ConfigStatus } from '../api/configSync';

// ---- UI components ----
export { Button, ButtonLink, ButtonAnchor, IconButton, LinkButton } from '../ui/Button';
export type { ButtonVariant, ButtonSize, ButtonProps, IconButtonProps, IconButtonSize, LinkButtonProps } from '../ui/Button';
export { Card, CardHeader, Notice, Callout } from '../ui/Card';
export type { CardPadding, CardTone } from '../ui/Card';
export { Field, FieldRow, ChangedChip, Eyebrow } from '../ui/Field';
export { Input, Textarea, Select, SearchInput, Checkbox } from '../ui/Input';
export type { InputSize, InputTone, SelectSize } from '../ui/Input';
export { Segmented, TriState } from '../ui/Segmented';
export type { SegmentOption, SegmentValue, SegmentedProps, SegmentedSize } from '../ui/Segmented';
export { Toggle } from '../ui/Toggle';
export { Tabs, TabPanel, LinkTabs } from '../ui/Tabs';
export type { TabChange, TabItem, TabsProps, TabsSize, LinkTabItem } from '../ui/Tabs';
export { Table } from '../ui/Table';
export type { Column, SortDir, TableProps } from '../ui/Table';
export { KeyValueList, KeyValueGroup } from '../ui/KeyValueList';
export type { KeyValueItem } from '../ui/KeyValueList';
export { SectionNav, SectionLayout } from '../ui/SectionNav';
export type { SectionNavItem } from '../ui/SectionNav';
export { Badge } from '../ui/Badge';
export type { BadgeTone, BadgeSize } from '../ui/Badge';
export { StatusPill, StatusText, StatusDot } from '../ui/Status';
export type { StatusKind, StatusTone, StatusShape } from '../ui/Status';
export { StackedBar, ProgressBar } from '../ui/Bars';
export type { BarSegment, BarTone } from '../ui/Bars';
export { EmptyState } from '../ui/EmptyState';
export { Modal } from '../ui/Modal';
export { Menu } from '../ui/Menu';
export type { MenuItem, MenuEntry, MenuProps } from '../ui/Menu';
export { PageHeader, Breadcrumb } from '../ui/PageHeader';
export type { Crumb } from '../ui/PageHeader';
export { Pagination } from '../ui/Pagination';
export { RelativeTime } from '../ui/RelativeTime';
export { CopyField } from '../ui/CopyField';
// An Overview `stat` tile renders one.
export { StatCell } from '../ui/Stats';
export type { StatCellProps, StatNote } from '../ui/Stats';

// ---- icons ----
export {
  IconAlert,
  IconAndroid,
  IconApps,
  IconBattery,
  IconCards,
  IconCheck,
  IconCheckSmall,
  IconChevronDown,
  IconClock,
  IconClose,
  IconConfigurations,
  IconControl,
  IconCopy,
  IconCrossSmall,
  IconDashSmall,
  IconDevices,
  IconDownload,
  IconEnroll,
  IconEnterKiosk,
  IconExitKiosk,
  IconEye,
  IconEyeOff,
  IconFactoryReset,
  IconHistory,
  IconInfo,
  IconKey,
  IconLibrary,
  IconLock,
  IconLockSmall,
  IconMenu,
  IconMoon,
  IconMore,
  IconNetwork,
  IconOverview,
  IconPencil,
  IconPin,
  IconPlay,
  IconPlus,
  IconPower,
  IconPulse,
  IconRing,
  IconScreenMessage,
  IconSearch,
  IconSettings,
  IconSignOut,
  IconSliders,
  IconStopRing,
  IconSun,
  IconSync,
  IconTable,
  IconTrash,
  IconUpload,
  IconWarning,
} from '../ui/icons';
export type { IconProps } from '../ui/icons';
