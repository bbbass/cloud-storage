// Concurrent quota reservation: N parallel upload inits must never oversell.
//
// Setup: a dedicated user row in quota_usage with a small quota, then fire
// `--concurrency` init requests at once. Expected: accepted * fileBytes <= quota,
// every extra request fails with business code 50002 and no bytes are reserved.
//
// Usage:
//   node scripts/stress/quota-concurrency.mjs --concurrency 100
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import {
  api, banner, identityHeaders, int, kv, newAgent, num, parseArgs, request, result, round
} from './lib.mjs';

const args = parseArgs(process.argv.slice(2));
const base = api(args.baseUrl ?? 'http://localhost:8080/api/v1');
const concurrency = int(args.concurrency, 100);
const fileBytes = num(args.fileBytes, 1048576);
const quotaBytes = num(args.quotaBytes, 10485760);
const deviceId = args.deviceId ?? 'stress-quota-device';
const mysqlExe = args.mysqlExe ?? 'D:\\MySQL\\mysql-9.6.0-winx64\\bin\\mysql.exe';
const mysqlUser = args.mysqlUser ?? 'root';
const mysqlPassword = args.mysqlPassword ?? process.env.CS_MYSQL_PASSWORD ?? '123456';
const mysqlSchema = args.mysqlSchema ?? 'cloud_storage';
// Fresh user per run: no stale Redis counter, no leftover files.
const userId = int(args.userId, 99000000 + crypto.randomInt(1, 9999));

function runSql(sql) {
  const res = spawnSync(mysqlExe, [
    '-u', mysqlUser, `-p${mysqlPassword}`, '--default-character-set=utf8mb4', '-N', '-B', mysqlSchema
  ], { input: sql, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  if (res.status !== 0) {
    throw new Error(`mysql failed (${res.status}): ${res.stderr || res.error}`);
  }
  return res.stdout.trim();
}

banner('quota concurrency (oversell check)');
kv('user', userId);
kv('quota', `${round(quotaBytes / 1048576, 2)} MiB`);
kv('each reservation', `${round(fileBytes / 1048576, 2)} MiB`);
kv('concurrency', concurrency);

runSql(
  `DELETE FROM files WHERE user_id = ${userId};\n` +
  `DELETE FROM upload_sessions WHERE user_id = ${userId};\n` +
  `INSERT INTO quota_usage (id, user_id, quota_bytes, used_bytes, create_time, update_time, deleted) ` +
  `VALUES (${userId}, ${userId}, ${quotaBytes}, 0, NOW(3), NOW(3), 0) ` +
  `ON DUPLICATE KEY UPDATE quota_bytes = ${quotaBytes}, used_bytes = 0;\n`
);

const agent = newAgent(concurrency);
const headers = { ...identityHeaders(userId, deviceId), 'Content-Type': 'application/json' };

async function initOnce(index) {
  const sha256 = crypto.createHash('sha256').update(`${userId}-${index}-${Date.now()}`).digest('hex');
  const res = await request(agent, {
    url: `${base}/uploads`,
    method: 'POST',
    headers,
    body: JSON.stringify({
      fileName: `quota-${index}.bin`,
      fileSize: fileBytes,
      sha256,
      chunkSize: 5242880,
      contentType: 'application/octet-stream'
    })
  });
  return res;
}

const startedAt = Date.now();
const responses = await Promise.all(Array.from({ length: concurrency }, (_, i) => initOnce(i)));
const elapsedMs = Date.now() - startedAt;

const accepted = [];
let rejected = 0;
let limited = 0;
let other = 0;
for (const res of responses) {
  const code = res.json ? res.json.code : null;
  if (res.status === 200 && code === 0) {
    accepted.push(res.json.data.uploadId);
  } else if (code === 50002) {
    rejected++;
  } else if (res.status === 429) {
    limited++;
  } else {
    other++;
  }
}

const reservedBytes = accepted.length * fileBytes;
const oversellBytes = Math.max(0, reservedBytes - quotaBytes);

// Cross-check the Redis fast-path counter through the public quota API.
const quotaRes = await request(agent, { url: `${base}/quota`, headers: identityHeaders(userId, deviceId) });
const quotaView = quotaRes.json && quotaRes.json.code === 0 ? quotaRes.json.data : null;

kv('accepted', accepted.length);
kv('rejected (50002 QUOTA_EXCEEDED)', rejected);
kv('http 429', limited);
kv('other failures', other);
kv('elapsed', `${elapsedMs} ms`);
kv('reserved bytes', reservedBytes);
kv('quota api usedBytes', quotaView ? quotaView.usedBytes : 'n/a');
kv('oversell bytes', oversellBytes);

// Release the reservations so the run leaves no in-flight sessions behind.
for (const uploadId of accepted) {
  await request(agent, {
    url: `${base}/uploads/${uploadId}`,
    method: 'DELETE',
    headers: identityHeaders(userId, deviceId)
  });
}
const afterAbort = await request(agent, { url: `${base}/quota`, headers: identityHeaders(userId, deviceId) });
const usedAfterAbort = afterAbort.json && afterAbort.json.code === 0 ? afterAbort.json.data.usedBytes : null;
kv('used bytes after abort', usedAfterAbort);

const expectedAccepted = Math.floor(quotaBytes / fileBytes);
const ok = oversellBytes === 0
  && accepted.length <= expectedAccepted
  && (quotaView === null || quotaView.usedBytes === reservedBytes);

kv('expected max accepted', expectedAccepted);
kv('verdict', ok ? 'PASS (no oversell)' : 'FAIL');

result('quota-concurrency', {
  userId,
  concurrency,
  quotaBytes,
  fileBytes,
  accepted: accepted.length,
  rejected,
  reservedBytes,
  oversellBytes,
  redisUsedBytes: quotaView ? quotaView.usedBytes : null,
  usedBytesAfterAbort: usedAfterAbort,
  elapsedMs,
  pass: ok
});

agent.destroy();
if (!ok) {
  process.exitCode = 1;
}
