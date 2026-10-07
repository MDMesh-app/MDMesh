const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const { transformSync } = require('rolldown/utils');

// Use the console's existing TypeScript transformer. Only the HTTP boundary is
// stubbed; verdict tests run the actual helper without a browser.
const source = fs.readFileSync(require('node:path').join(__dirname, '../../src/api/configSync.ts'), 'utf8')
  .replace("import { apiClient } from './client';", 'const apiClient = {};');
const result = transformSync('configSync.ts', source, { lang: 'ts' });
assert.deepEqual(result.errors, []);
const context = {};
vm.runInNewContext(result.code.replace(/^export /gm, ''), context);
const { summarizeStatus } = context;
const status = (outcomes, revision = 'current') => ({
  configurationId: 1, supported: true, inSync: true, currentRevision: 'current', appliedRevision: 'current',
  lastCommand: { status: 'done', detail: JSON.stringify({ revision, outcomes }) },
});

test('unsupported policies cannot be shown as fully in sync', () => {
  assert.equal(summarizeStatus(status({ 'policies.userAppInstall': 'unsupported' })).label, 'Partially supported');
});
test('applied policies retain the in-sync verdict', () => {
  assert.equal(summarizeStatus(status({ 'policies.userAppInstall': 'applied' })).label, 'In sync');
});
test('an old result does not mask the current revision', () => {
  assert.equal(summarizeStatus(status({ 'policies.userAppInstall': 'unsupported' }, 'old')).label, 'In sync');
});
test('a failed restriction takes priority over unsupported fields', () => {
  assert.equal(summarizeStatus(status({ 'policies.userAppInstall': 'failed: OEM refused', wifi: 'unsupported' })).label, 'Apply failed');
});
test('older agents and unconfigured devices keep their existing verdicts', () => {
  assert.equal(summarizeStatus({ configurationId: 1, supported: false }).label, 'Agent too old');
  assert.equal(summarizeStatus(null).label, 'No configuration');
});

test('malformed outcomes do not crash the verdict helper', () => {
  assert.equal(summarizeStatus(status({ 'policies.userAppInstall': 123 })).label, 'In sync');
});
