// End-to-end QPS / latency of a rate-limited read path.
//
// What it measures: GET /api/v1/files/{fileId} through the whole stack
//   (Tomcat -> RateLimitAspect: 2x Redis ZSET+Lua -> FileMetaCache hit -> Jackson).
// Thresholds must be raised for the run so nothing is rejected; the point is the
// capacity of the limiter judgement, not its rejection behaviour (that is asserted
// by scripts/smoke-upload.ps1 step 12).
//
// Usage:
//   node scripts/stress/limiter-qps.mjs --file-id 123 --connections 64 --duration 15
import { performance } from 'node:perf_hooks';
import {
  api, banner, ensureTestFile, identityHeaders, int, kv, newAgent, parseArgs, percentile, request, result, round
} from './lib.mjs';

const args = parseArgs(process.argv.slice(2));
const base = api(args.baseUrl ?? 'http://localhost:8080/api/v1');
const userId = int(args.userId, 1);
const deviceId = args.deviceId ?? 'stress-qps-device';
const connections = int(args.connections, 64);
const durationSeconds = Number(args.duration ?? 15);
const label = args.label ?? 'limiter';

const bootstrap = newAgent(4);
let fileId = args.fileId ?? null;
if (fileId === null) {
  const created = await ensureTestFile(bootstrap, base, userId, deviceId);
  fileId = created.fileId;
  kv('stress file created', `fileId=${created.fileId} objectId=${created.objectId} size=${created.size}`);
}

const url = `${base}/files/${fileId}`;
const agent = newAgent(connections);

// Warmup: fill the metadata cache so the measured path is the steady-state one.
const warmup = await request(agent, { url, headers: identityHeaders(userId, deviceId) });
if (warmup.status !== 200 || !warmup.json || warmup.json.code !== 0) {
  throw new Error(`warmup read failed: HTTP ${warmup.status} ${warmup.text.slice(0, 200)}`);
}

banner(`rate-limited read QPS (${label})`);
kv('endpoint', `GET /files/${fileId}`);
kv('connections', connections);
kv('duration', `${durationSeconds}s`);

const deadline = performance.now() + durationSeconds * 1000;
let ok = 0;
let limited = 0;
let failed = 0;
const latencies = [];

async function worker() {
  const local = [];
  while (performance.now() < deadline) {
    let res;
    try {
      res = await request(agent, { url, headers: identityHeaders(userId, deviceId) });
    } catch {
      failed++;
      continue;
    }
    local.push(res.ms);
    if (res.status === 200 && res.json && res.json.code === 0) {
      ok++;
    } else if (res.status === 429) {
      limited++;
    } else {
      failed++;
    }
  }
  latencies.push(...local);
}

const startedAt = performance.now();
await Promise.all(Array.from({ length: connections }, () => worker()));
const elapsedMs = performance.now() - startedAt;

latencies.sort((a, b) => a - b);
const total = ok + limited + failed;
const qps = round((total * 1000) / elapsedMs, 1);

kv('requests', total);
kv('ok', ok);
kv('http 429', limited);
kv('errors', failed);
kv('elapsed', `${round(elapsedMs, 0)} ms`);
kv('qps', qps);
kv('latency p50/p95/p99/max (ms)',
  `${round(percentile(latencies, 50), 2)} / ${round(percentile(latencies, 95), 2)} / ` +
  `${round(percentile(latencies, 99), 2)} / ${round(latencies[latencies.length - 1] ?? 0, 2)}`);

result('limiter-qps', {
  label,
  fileId,
  connections,
  durationSeconds,
  requests: total,
  ok,
  limited,
  errors: failed,
  qps,
  p50Ms: round(percentile(latencies, 50), 2),
  p95Ms: round(percentile(latencies, 95), 2),
  p99Ms: round(percentile(latencies, 99), 2)
});

agent.destroy();
bootstrap.destroy();
if (limited > 0) {
  console.log('  [WARN] some requests were rejected with 429: raise app.limiter.scenes.*.user-limit/device-limit for this run');
}
if (failed > 0) {
  process.exitCode = 1;
}
