import { createContext, useContext, type ReactNode } from 'react';
import type { DeviceContextValue } from './types';

const DeviceContext = createContext<DeviceContextValue | null>(null);

/** The device page hands its device to the tabs and rail groups below (pages/DeviceDetailPage). */
export function DeviceProvider({ value, children }: { value: DeviceContextValue; children: ReactNode }) {
  return <DeviceContext.Provider value={value}>{children}</DeviceContext.Provider>;
}

/**
 * The open device, inside a `device.tab` component: its data, `refresh()` and `sendCommand()`. A new object on every
 * render: depend on the fields an effect reads (`device.number`), not on the whole value.
 */
export function useDevice(): DeviceContextValue {
  const device = useContext(DeviceContext);
  if (!device) throw new Error('useDevice() works only inside a device.tab component');
  return device;
}
