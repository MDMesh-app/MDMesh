// Configuration sections (proto/config-sections.json) in the console's words.
import { defineMessages, type IntlShape } from 'react-intl';

const sections = defineMessages({
  policies: { id: 'configSection.policies', defaultMessage: 'Policies' },
  kiosk: { id: 'configSection.kiosk', defaultMessage: 'Kiosk mode' },
  location: { id: 'configSection.location', defaultMessage: 'Location reporting' },
  connectivity: { id: 'configSection.connectivity', defaultMessage: 'Connectivity' },
  appPermissions: { id: 'configSection.appPermissions', defaultMessage: 'App permissions' },
});

/** "Connectivity and Kiosk mode". An unknown name is shown as is. */
export function sectionList(intl: IntlShape, names: ReadonlyArray<string>): string {
  return intl.formatList(
    names.map((n) => (n in sections ? intl.formatMessage(sections[n as keyof typeof sections]) : n)),
  );
}

/** "2 agent update needed": a count line's part. */
export function agentUpdateCount(intl: IntlShape, count: number): string {
  return intl.formatMessage(
    {
      id: 'configSync.agentUpdate.count',
      defaultMessage: '{count} agent update needed',
      description: 'How many devices need an agent update, in a line of counts such as "3 in sync · 1 agent update needed"',
    },
    { count },
  );
}
