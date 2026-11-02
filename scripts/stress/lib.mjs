// Shared helpers for the cloud-storage stress scripts.
// All output is ASCII: the Windows console here is code page 936.
import http from 'node:http';
import crypto from 'node:crypto';
import { performance } from 'node:perf_hooks';

export function parseArgs(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i];
    if (!token.startsWith('--')) {
      out._.push(token);
      continue;
    }
    const eq = token.indexOf('=');
    let key;
    let value;
    if (eq >= 0) {
      key = token.slice(2, eq);
      value = token.slice(eq + 1);
    } else {
      key = token.slice(2);
      const next = argv[i + 1];
      if (next !== undefined && !next.startsWith('--')) {
        value = next;
        i++;
      } else {
        value = 'true';
      }
    }
    out[key.replace(/-([a-z])/g, (_, c) => c.toUpperCase())] = value;
  }
  return out;
}

export const num = (value, fallback) =>
  value === undefined || value === '' ? fallback : Number(value);

export const int = (value, fallback) => Math.trunc(num(value, fallback));

export const api = (baseUrl) => baseUrl.replace(/\/+$/, '');

export function newAgent(maxSockets) {
  return new http.Agent({
    keepAlive: true,
    maxSockets,
    maxFreeSockets: maxSockets,
    scheduling: 'fifo'
  });
}

// Single HTTP call; never throws on HTTP status, only on transport errors.
export function request(agent, options) {
  const { url, method = 'GET', headers = {}, body = null, timeoutMs = 30000 } = options;
  return new Promise((resolve, reject) => {
    const started = performance.now();
    const req = http.request(url, { method, agent, headers }, (res) => {
      const chunks = [];
      res.on('data', (chunk) => chunks.push(chunk));
      res.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        let json = null;
        try {
          // Snowflake ids (19 digits) exceed Number.MAX_SAFE_INTEGER, so plain
          // JSON.parse would silently corrupt them (uploadId/fileId/taskId).
          // Quote long integer literals before parsing; the same trick the
          // frontend uses in src/api/http.js.
          json = JSON.parse(text.replace(/:\s*(-?\d{16,})/g, ': "$1"'));
        } catch {
          json = null;
        }
        resolve({ status: res.statusCode, headers: res.headers, text, json, ms: performance.now() - started });
      });
    });
    req.setTimeout(timeoutMs, () => req.destroy(new Error('request timeout')));
    req.on('error', reject);
    if (body !== null) {
      req.write(body);
    }
    req.end();
  });
}

export const identityHeaders = (userId, deviceId) => ({
  'X-User-Id': String(userId),
  'X-Device-Id': String(deviceId)
});

export async function health(agent, base, userId, deviceId) {
  const res = await request(agent, {
    url: `${base}/health`,
    headers: identityHeaders(userId, deviceId)
  });
  if (res.status !== 200 || !res.json || res.json.code !== 0) {
    throw new Error(`health check failed: HTTP ${res.status} ${res.text.slice(0, 200)}`);
  }
  return res.json.data;
}

export async function taskStats(agent, base, userId, deviceId) {
  const res = await request(agent, {
    url: `${base}/tasks/stats`,
    headers: identityHeaders(userId, deviceId)
  });
  if (res.status !== 200 || !res.json || res.json.code !== 0) {
    throw new Error(`task stats failed: HTTP ${res.status} ${res.text.slice(0, 200)}`);
  }
  return res.json.data;
}

export function percentile(sortedAsc, p) {
  if (sortedAsc.length === 0) {
    return 0;
  }
  const idx = Math.min(sortedAsc.length - 1, Math.max(0, Math.ceil((p / 100) * sortedAsc.length) - 1));
  return sortedAsc[idx];
}

export const round = (value, digits = 2) => Number(value.toFixed(digits));

// Uploads a small random file so the caller owns a fileId / objectId it may read.
export async function ensureTestFile(agent, base, userId, deviceId, options = {}) {
  const size = options.size ?? 65536;
  const chunkSize = 5242880;
  const payload = crypto.randomBytes(size);
  const sha256 = crypto.createHash('sha256').update(payload).digest('hex');
  const jsonHeaders = { ...identityHeaders(userId, deviceId), 'Content-Type': 'application/json' };

  const init = await request(agent, {
    url: `${base}/uploads`,
    method: 'POST',
    headers: jsonHeaders,
    body: JSON.stringify({
      fileName: `stress-${Date.now()}.bin`,
      fileSize: size,
      sha256,
      chunkSize,
      contentType: 'application/octet-stream'
    })
  });
  if (init.status !== 200 || !init.json || init.json.code !== 0) {
    throw new Error(`stress file init failed: HTTP ${init.status} ${init.text.slice(0, 200)}`);
  }
  const uploadId = init.json.data.uploadId;

  const part = await request(agent, {
    url: `${base}/uploads/${uploadId}/parts/1?size=${size}`,
    method: 'PUT',
    headers: { ...identityHeaders(userId, deviceId), 'Content-Type': 'application/octet-stream' },
    body: payload
  });
  if (part.status !== 200 || !part.json || part.json.code !== 0) {
    throw new Error(`stress file part failed: HTTP ${part.status} ${part.text.slice(0, 200)}`);
  }

  const done = await request(agent, {
    url: `${base}/uploads/${uploadId}/complete`,
    method: 'POST',
    headers: identityHeaders(userId, deviceId)
  });
  if (done.status !== 200 || !done.json || done.json.code !== 0) {
    throw new Error(`stress file complete failed: HTTP ${done.status} ${done.text.slice(0, 200)}`);
  }
  return { fileId: done.json.data.fileId, objectId: done.json.data.objectId, size, costMs: done.json.data.costMs };
}

export function banner(title) {
  console.log(`\n=== ${title} ===`);
}

export function kv(label, value) {
  console.log(`  ${label} = ${value}`);
}

export function result(tag, fields) {
  console.log(`RESULT ${tag} ${JSON.stringify(fields)}`);
}
