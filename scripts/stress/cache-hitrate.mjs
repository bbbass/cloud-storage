// File metadata cache hit rate (delta of the /health counters, so earlier runs
// on the same JVM do not pollute the number).
//
// Usage:
//   node scripts/stress/cache-hitrate.mjs --requests 2000 --concurrency 8
import {
  api, banner, ensureTestFile, health, identityHeaders, int, kv, newAgent, parseArgs, request, result, round
} from './lib.mjs';

const args = parseArgs(process.argv.slice(2));
const base = api(args.baseUrl ?? 'http://localhost:8080/api/v1');
const userId = int(args.userId, 1);
const deviceId = args.deviceId ?? 'stress-cache-device';
const requests = int(args.requests, 2000);
const concurrency = int(args.concurrency, 8);

const agent = newAgent(Math.max(concurrency, 4));
let fileId = args.fileId ?? null;
if (fileId === null) {
  const created = await ensureTestFile(agent, base, userId, deviceId);
  fileId = created.fileId;
  kv('stress file created', `fileId=${created.fileId} objectId=${created.objectId}`);
}

const before = await health(agent, base, userId, deviceId);
banner('file metadata cache hit rate');
kv('file', fileId);
kv('reads', requests);
kv('concurrency', concurrency);

const url = `${base}/files/${fileId}`;
// Warmup: a single read so the measured window is the steady state (the cold
// miss / rebuild cost is reported separately by the delta counters).
const warmup = await request(agent, { url, headers: identityHeaders(userId, deviceId) });
if (warmup.status !== 200 || !warmup.json || warmup.json.code !== 0) {
  throw new Error(`warmup read failed: HTTP ${warmup.status} ${warmup.text.slice(0, 200)}`);
}

const startedAt = Date.now();
let ok = 0;
let bad = 0;
let cursor = 0;
async function worker() {
  while (true) {
    const index = cursor++;
    if (index >= requests) {
      return;
    }
    let res;
    try {
      res = await request(agent, { url, headers: identityHeaders(userId, deviceId) });
    } catch {
      bad++;
      continue;
    }
    if (res.status === 200 && res.json && res.json.code === 0) {
      ok++;
    } else {
      bad++;
    }
  }
}
await Promise.all(Array.from({ length: concurrency }, () => worker()));
const elapsedMs = Date.now() - startedAt;

const after = await health(agent, base, userId, deviceId);
const delta = (key) => Number(after.cache[key] ?? 0) - Number(before.cache[key] ?? 0);
const hits = delta('hits');
const misses = delta('misses');
const rebuilds = delta('rebuilds');
const fallbackReads = delta('fallbackReads');
const total = hits + misses;
const hitRate = total === 0 ? 0 : round((hits * 100) / total, 2);

kv('ok', ok);
kv('errors', bad);
kv('qps', round((requests * 1000) / elapsedMs, 1));
kv('cache hits / misses', `${hits} / ${misses}`);
kv('cache rebuilds / fallback reads', `${rebuilds} / ${fallbackReads}`);
kv('hit rate', `${hitRate}%`);

result('cache-hitrate', {
  fileId,
  reads: requests,
  concurrency,
  ok,
  errors: bad,
  hits,
  misses,
  rebuilds,
  fallbackReads,
  hitRatePercent: hitRate,
  elapsedMs
});

agent.destroy();
if (bad > 0) {
  process.exitCode = 1;
}
