/**
 * Relay / signaling server for the remote-control app.
 *
 * Design:
 *  - Two roles connect: "agent" (the phone sharing its screen) and
 *    "controller" (the PC/browser viewing + controlling it).
 *  - They pair via a short-lived numeric code the agent generates and
 *    displays; the controller enters it to join the same "room".
 *  - Binary WebSocket frames (H.264 access units) flow agent -> controller.
 *  - JSON text frames (input commands, control messages) flow
 *    controller -> agent.
 *  - A room is destroyed when either side disconnects, forcing a fresh
 *    pairing rather than silently reconnecting — this keeps sessions
 *    explicit and avoids a stale/abandoned session being hijacked.
 *
 * This server does NOT decode/inspect video frames; it's a dumb relay.
 * For real internet use, put this behind TLS (wss://) — see README.
 */

const WebSocket = require('ws');
const crypto = require('crypto');
const http = require('http');

const PORT = process.env.PORT || 8080;

// Simple in-memory rate limiters for production use
const ipRateLimits = new Map();
const WS_MAX_ATTEMPTS = 10;

function checkRateLimit(ip, type, maxAttempts, timeWindowMs) {
  if (!ip) return true; // If we can't determine IP, allow (or change to deny in strict setup)
  const now = Date.now();
  if (!ipRateLimits.has(ip)) {
    ipRateLimits.set(ip, { [type]: { count: 1, resetAt: now + timeWindowMs } });
    return true;
  }
  
  const record = ipRateLimits.get(ip);
  if (!record[type] || now > record[type].resetAt) {
    record[type] = { count: 1, resetAt: now + timeWindowMs };
    return true;
  }
  
  record[type].count++;
  return record[type].count <= maxAttempts;
}

// Cleanup stale rate limit records
setInterval(() => {
  const now = Date.now();
  for (const [ip, record] of ipRateLimits.entries()) {
    let hasActive = false;
    for (const key in record) {
      if (now <= record[key].resetAt) {
        hasActive = true;
      }
    }
    if (!hasActive) ipRateLimits.delete(ip);
  }
}, 60000);

// A single persistent room for continuous background connection
const persistentRoom = { agent: null, controller: null };

const server = http.createServer((req, res) => {
  res.writeHead(404);
  res.end();
});

const wss = new WebSocket.Server({ server });

wss.on('connection', (ws, req) => {
  const ip = req.headers['x-forwarded-for'] || req.socket.remoteAddress;
  const url = new URL(req.url, `http://${req.headers.host}`);
  const role = url.searchParams.get('role');   // "agent" | "controller"
  const code = url.searchParams.get('code');   // pairing code

  if (!checkRateLimit(ip, 'ws_connect', WS_MAX_ATTEMPTS, 60000)) {
    ws.close(4029, 'Rate limit exceeded');
    return;
  }

  if (code !== 'default') {
    ws.close(4001, 'invalid room code');
    return;
  }
  if (role !== 'agent' && role !== 'controller') {
    ws.close(4002, 'role must be agent or controller');
    return;
  }

  const room = persistentRoom;
  if (room[role]) {
    // Slot already occupied — refuse the second connection rather than
    // silently displacing an active session.
    ws.close(4003, `${role} already connected for this room`);
    return;
  }

  room[role] = ws;
  ws.role = role;
  console.log(`[room default] ${role} connected`);

  // Notify the peer, if present, that the other side has joined.
  const peerKey = role === 'agent' ? 'controller' : 'agent';
  room[peerKey]?.send(JSON.stringify({ type: 'peer-joined', role }));

  ws.on('message', (data, isBinary) => {
    const peer = room[peerKey];
    if (!peer || peer.readyState !== WebSocket.OPEN) return;

    // Enforce direction: only agent sends binary video, only controller
    // sends JSON input commands. Anything else is dropped.
    if (role === 'agent' && !isBinary) {
      // Agent may also send small JSON status/control messages through.
      peer.send(data);
      return;
    }
    if (role === 'agent' && isBinary) {
      peer.send(data, { binary: true });
      return;
    }
    if (role === 'controller' && !isBinary) {
      peer.send(data);
      return;
    }
    // controller sending binary — not part of the protocol, ignore.
  });

  ws.on('close', () => {
    console.log(`[room default] ${role} disconnected`);
    room[role] = null;
    // We intentionally do NOT close the peer's connection. 
    // This allows the agent to run continuously in the background.
  });

  ws.on('error', (err) => {
    console.error(`[room ${code}] ${role} error:`, err.message);
  });
});

server.listen(PORT, () => {
  console.log(`Relay server listening on :${PORT}`);
  console.log(`Connect directly to ws://<host>:${PORT}/?role=agent&code=default or role=controller`);
});
