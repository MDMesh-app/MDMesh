#!/usr/bin/env node
// mdmesh-recovery: the updater's recovery, from the host. The supervisor answers it on its own port with the recovery
// token (only the host can read it), so it works while the server is down and the console with it:
//   docker compose exec supervisor mdmesh-recovery status     the running version, the last update and its error
//   docker compose exec supervisor mdmesh-recovery rollback   roll back to the version before the last update, and
//                                                             follow it to the end (exit 0 rolled back, 1 failed)
const fs = require('fs');
const { isTerminal } = require('./lib');

const BASE = `http://127.0.0.1:${+(process.env.SUPERVISOR_PORT || 9000)}`;
const TOKEN_FILE = process.env.RECOVERY_TOKEN_FILE || '/backups/recovery.token';
const POLL_MS = 1000;
const ROLLBACK_BOUND_MS = 60 * 60 * 1000;

function fail(msg, code = 1) {
  console.error('mdmesh-recovery: ' + msg);
  process.exit(code);
}

function token() {
  try { return fs.readFileSync(TOKEN_FILE, 'utf8').trim(); } catch (e) { fail(`cannot read the recovery token ${TOKEN_FILE}: ${e.code || e}`); }
  return '';
}

async function call(method, path, headers = {}) {
  let r;
  try {
    r = await fetch(BASE + path, { method, headers: { 'X-Recovery-Token': token(), Accept: 'application/json', ...headers },
      signal: AbortSignal.timeout(30_000) });
  } catch (e) { fail(`the supervisor does not answer on ${BASE}: ${(e.cause && e.cause.code) || e.message}`); }
  const body = await r.json().catch(() => null);
  return { status: r.status, body: body || {} };
}

async function status() {
  const r = await call('GET', '/update/status');
  if (r.status !== 200) fail(`status refused (HTTP ${r.status}): ${r.body.error || 'no reason given'}`);
  return r.body;
}

function describe(s) {
  const a = s.apply;
  console.log('version   ' + s.current);
  if (!a) { console.log('update    none since the supervisor started'); return; }
  console.log(`update    ${a.phase} (${a.fromVersion || '?'} -> ${a.toVersion || '?'})`);
  if (a.error) console.log('error     ' + a.error);
}

async function rollback() {
  const r = await call('POST', '/update/rollback', { 'X-MDMesh-Console': '1' });
  if (r.status !== 202) fail(`rollback refused (HTTP ${r.status}): ${r.body.error || 'no reason given'}`);
  console.log('rollback started');
  const end = Date.now() + ROLLBACK_BOUND_MS;
  let last = null;
  for (;;) {
    const s = await status();
    const phase = s.apply && s.apply.phase;
    if (phase !== last) { console.log('phase     ' + phase); last = phase; }
    if (isTerminal(phase)) {
      describe(s);
      process.exit(phase === 'rolled_back' ? 0 : 1);
    }
    if (Date.now() > end) fail('the rollback has not finished after an hour; see: docker compose logs supervisor');
    await new Promise((res) => setTimeout(res, POLL_MS));
  }
}

const cmd = process.argv[2];
if (cmd === 'status') status().then(describe);
else if (cmd === 'rollback') rollback();
else fail('usage: mdmesh-recovery status | rollback', 2);
