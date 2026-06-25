import { apiClient } from './client';

// Server-side F-Droid catalogue proxy — see com.hmdm.rest.resource.FDroidResource.
//   GET /rest/private/fdroid/search?q=&limit=  -> FDroidApp[]
// The server fetches/caches the F-Droid index; the APK is deployed straight from
// f-droid.org's public URL (the agent downloads it directly).

export interface FDroidApp {
  packageName: string;
  name: string;
  summary?: string;
  iconUrl?: string | null;
  versionName?: string | null;
  versionCode: number;
  apkUrl: string;
  /** Hex SHA-256 from the F-Droid index. */
  sha256?: string | null;
  size?: number | null;
}

export async function searchFdroid(q: string, limit = 50): Promise<FDroidApp[]> {
  const params = new URLSearchParams();
  if (q.trim()) params.set('q', q.trim());
  params.set('limit', String(limit));
  return apiClient.get<FDroidApp[]>(`/private/fdroid/search?${params.toString()}`);
}
