import { useEffect, useState } from 'react';
import { searchDevices } from '../../api/devices';
import { Badge } from '../../ui/Badge';

/** Device total for the nav badge: one cheap search (pageSize 1) per shell mount. */
function useDeviceTotal(): number | null {
  const [total, setTotal] = useState<number | null>(null);
  useEffect(() => {
    let on = true;
    searchDevices({ pageSize: 1 })
      .then((r) => {
        if (on) setTotal(r.devices?.totalItemsCount ?? null);
      })
      .catch(() => {
        /* the badge is optional */
      });
    return () => {
      on = false;
    };
  }, []);
  return total;
}

/** The Devices nav item's badge: the fleet size, once known. */
export function DeviceCountBadge() {
  const total = useDeviceTotal();
  return total !== null ? <Badge tone="count">{total}</Badge> : null;
}
