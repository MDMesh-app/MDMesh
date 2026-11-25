// The agent's wake socket, as the agent opens it: tests/proxy-e2e/run.sh runs this in a client container.
//   node ws-probe.js URL SECRET SECONDS [WAKE_AFTER]
// URL is http(s)://host[:port]/agent/ws/<device>. It sends the WebSocket handshake with Authorization: Bearer SECRET,
// keeps the socket for SECONDS (answering pings, as the agent does), and prints one line:
//   status=<the handshake's status> texts=<the text frames, JSON> close=<the close frame's code and reason, or ->
//   open=<1 when the socket was still open at the end>
// With WAKE_AFTER, it prints "ready" on its own line WAKE_AFTER seconds in (the caller then queues a command, and the
// server's wake signal must arrive as a text frame). Trusts NODE_EXTRA_CA_CERTS for https.
'use strict';
const net = require('net');
const tls = require('tls');
const crypto = require('crypto');

const [url, secret, seconds, wakeAfter] = process.argv.slice(2);
const u = new URL(url);
const port = Number(u.port || (u.protocol === 'https:' ? 443 : 80));
const sock = u.protocol === 'https:'
  ? tls.connect({ host: u.hostname, port, servername: u.hostname, ALPNProtocols: ['http/1.1'] })
  : net.connect({ host: u.hostname, port });

let status = '-';
let headerDone = false;
let buf = Buffer.alloc(0);
const texts = [];
let close = '-';
let open = true;

sock.on(u.protocol === 'https:' ? 'secureConnect' : 'connect', () => {
  sock.write([
    `GET ${u.pathname} HTTP/1.1`, `Host: ${u.host}`, 'Connection: Upgrade', 'Upgrade: websocket',
    'Sec-WebSocket-Version: 13', `Sec-WebSocket-Key: ${crypto.randomBytes(16).toString('base64')}`,
    `Authorization: Bearer ${secret}`, '', '',
  ].join('\r\n'));
});

// A client frame (masked, as RFC 6455 requires of a client).
function send(opcode, payload) {
  const mask = crypto.randomBytes(4);
  const data = Buffer.from(payload.map((b, i) => b ^ mask[i % 4]));
  sock.write(Buffer.concat([Buffer.from([0x80 | opcode, 0x80 | payload.length]), mask, data]));
}

sock.on('data', (chunk) => {
  buf = Buffer.concat([buf, chunk]);
  if (!headerDone) {
    const end = buf.indexOf('\r\n\r\n');
    if (end < 0) return;
    status = buf.slice(0, end).toString().split('\r\n')[0].split(' ')[1];
    buf = buf.slice(end + 4);
    headerDone = true;
  }
  // Server frames are unmasked; the wake signals and a close are short (no 64-bit lengths here).
  while (buf.length >= 2) {
    const opcode = buf[0] & 0x0f;
    let len = buf[1] & 0x7f;
    let off = 2;
    if (len === 126) { if (buf.length < 4) return; len = buf.readUInt16BE(2); off = 4; }
    if (buf.length < off + len) return;
    const payload = buf.slice(off, off + len);
    buf = buf.slice(off + len);
    if (opcode === 0x1) texts.push(payload.toString());
    else if (opcode === 0x8) close = `${payload.length >= 2 ? payload.readUInt16BE(0) : ''}:${payload.slice(2).toString()}`;
    else if (opcode === 0x9) send(0xa, [...payload]);
  }
});
sock.on('close', () => { open = false; });
sock.on('error', (e) => { status = status === '-' ? `error:${e.code || e.message}` : status; open = false; });

if (wakeAfter) setTimeout(() => console.log('ready'), Number(wakeAfter) * 1000);
setTimeout(() => {
  console.log(`status=${status} texts=${JSON.stringify(texts)} close=${close} open=${open ? 1 : 0}`);
  sock.destroy();
  process.exit(0);
}, Number(seconds) * 1000);
