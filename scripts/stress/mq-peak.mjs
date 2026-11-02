// Async pipeline peak / drain test.
//
// Setup (SQL, all rows belong to a throw-away user):
//   1. pick a READY image object in file_objects (run smoke-upload.ps1 first);
//   2. insert N file rows pointing at that object and 2N PENDING tasks
//      (THUMBNAIL + EXIF) - the same rows a real upload would create;
//   3. burst-publish one message per task with a standalone producer that uses
//      the same rocketmq-client as the application (scripts/stress/MqBurstProducer.java);
//   4. sample GET /api/v1/tasks/stats to watch the backlog build and drain.
//
// next_retry_time is pushed one day out so the reconcile job does not double
// publish the seeded rows while the burst is measured; the consumer path itself
// (DB -> MinIO -> handler) is the production one.
//
// Usage:
//   node scripts/stress/mq-peak.mjs --tasks 3000
import crypto from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {
  api, banner, int, kv, newAgent, parseArgs, result, round, taskStats
} from './lib.mjs';

const args = parseArgs(process.argv.slice(2));
const base = api(args.baseUrl ?? 'http://localhost:8080/api/v1');
const tasks = int(args.tasks, 3000);
const threads = int(args.threads, 32);
const deviceId = args.deviceId ?? 'stress-mq-device';
const intervalMs = int(args.intervalMs, 250);
const drainTimeoutMs = int(args.drainTimeoutMs, 240000);
const keepRows = args.keep === 'true';
const mysqlExe = args.mysqlExe ?? 'D:\\MySQL\\mysql-9.6.0-winx64\\bin\\mysql.exe';
const mysqlUser = args.mysqlUser ?? 'root';
const mysqlPassword = args.mysqlPassword ?? process.env.CS_MYSQL_PASSWORD ?? '123456';
const mysqlSchema = args.mysqlSchema ?? 'cloud_storage';
const fatJar = args.jar ?? 'cloud-storage-web\\target\\cloud-storage-web.jar';
const libDir = args.libDir ?? 'target\\stress-libs';
const userId = int(args.userId, 99100000 + crypto.randomInt(1, 9999));
const files = Math.ceil(tasks / 2);
// Both bases stay below 2^53 (9007199254740992): above that a JS integer step of
// 1 is swallowed by double precision and every synthetic id collapses to the same
// value. They are far below the snowflake ids the application generates.
const fileIdBase = 8100000000000000;
const taskIdBase = 8200000000000000;
// Reserved id space for seeded rows. Kept as strings: as JS numbers they would
// already be rounded, and the range delete must match the literals exactly.
const fileIdRange = '8100000000000000 AND 8199999999999999';
const taskIdRange = '8200000000000000 AND 8299999999999999';
const cleanupSeeded = () =>
  `DELETE FROM processing_tasks WHERE id BETWEEN ${taskIdRange};\n` +
  `DELETE FROM files WHERE id BETWEEN ${fileIdRange};\n`;

function runSql(sql) {
  const res = spawnSync(mysqlExe, [
    '-u', mysqlUser, `-p${mysqlPassword}`, '--default-character-set=utf8mb4', '-N', '-B', mysqlSchema
  ], { input: sql, encoding: 'utf8', maxBuffer: 128 * 1024 * 1024 });
  if (res.status !== 0) {
    throw new Error(`mysql failed (${res.status}): ${res.stderr || res.error}`);
  }
  return res.stdout.trim();
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

banner('async pipeline peak / drain');
kv('user', userId);
kv('tasks', tasks);
kv('producer threads', threads);

// ---------------------------------------------------------------- 1. pick object
const objectRow = runSql(
  // Pick an image whose THUMBNAIL already succeeded: a corrupt file would only
  // exercise the retry / dead-letter path instead of the normal pipeline.
  'SELECT o.id, o.size, o.sha256, o.content_type FROM file_objects o ' +
  "JOIN processing_tasks t ON t.object_id = o.id AND t.task_type = 'THUMBNAIL' AND t.status = 'DONE' " +
  "WHERE o.status = 'READY' AND o.content_type LIKE 'image/%' " +
  'ORDER BY t.update_time DESC LIMIT 1;'
);
if (!objectRow) {
  throw new Error('no READY image object found: run scripts/smoke-upload.ps1 first (it uploads photo.jpg)');
}
const [objectId, objectSize, sha256, contentType] = objectRow.split('\t');
kv('source object', `objectId=${objectId} size=${objectSize} contentType=${contentType}`);

// Clear leftovers from an aborted earlier run before taking the baseline.
runSql(cleanupSeeded());

// Baseline is taken BEFORE seeding: the seeded PENDING rows are the load itself,
// so counting them as backlog baseline would hide the whole peak.
const agent = newAgent(4);
const baseline = await taskStats(agent, base, userId, deviceId);
const baselineDone = Number(baseline.DONE ?? 0);
const baselineBacklog = Number(baseline.PENDING ?? 0) + Number(baseline.RUNNING ?? 0);
kv('baseline DONE / backlog', `${baselineDone} / ${baselineBacklog}`);

// ---------------------------------------------------------------- 2. seed rows
const fileIds = [];
const taskIds = [];
// Leftovers from an aborted earlier run share the reserved id space, so clear the
// whole range, not just this run's throw-away user.
let sql = cleanupSeeded() + `DELETE FROM processing_tasks WHERE user_id = ${userId};\n` +
  `DELETE FROM files WHERE user_id = ${userId};\n`;
for (let batch = 0; batch < files; batch += 250) {
  const end = Math.min(files, batch + 250);
  const fileValues = [];
  for (let i = batch; i < end; i++) {
    fileIds.push(fileIdBase + i);
    fileValues.push(`(${fileIdBase + i}, ${userId}, ${objectId}, 'mq-${i}.jpg', 0, ${objectSize}, ` +
      `'${sha256}', '${contentType}', NOW(3), NOW(3), 0)`);
  }
  sql += 'INSERT INTO files (id, user_id, object_id, file_name, parent_id, size, sha256, content_type, ' +
    'create_time, update_time, deleted) VALUES\n' + fileValues.join(',\n') + ';\n';
}
let nextTaskId = taskIdBase;
const taskRows = [];
for (const fileId of fileIds) {
  for (const taskType of ['THUMBNAIL', 'EXIF']) {
    const taskId = nextTaskId++;
    taskIds.push(taskId);
    taskRows.push(`(${taskId}, ${fileId}, ${objectId}, ${userId}, '${taskType}', 'PENDING', 0, ` +
      `DATE_ADD(NOW(3), INTERVAL 1 DAY), NOW(3), NOW(3), 0)`);
  }
}
for (let batch = 0; batch < taskRows.length; batch += 250) {
  sql += 'INSERT INTO processing_tasks (id, file_id, object_id, user_id, task_type, status, retry_count, ' +
    'next_retry_time, create_time, update_time, deleted) VALUES\n' +
    taskRows.slice(batch, batch + 250).join(',\n') + ';\n';
}
const seedSqlFile = path.join(os.tmpdir(), 'cs-mq-seed.sql');
fs.writeFileSync(seedSqlFile, sql, 'utf8');
kv('seed sql', seedSqlFile);
runSql(sql);
kv('seeded', `${fileIds.length} files + ${taskIds.length} PENDING tasks`);

const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'cs-mq-peak-'));
const idsFile = path.join(tmpDir, 'task-ids.txt');
const idsText = runSql(
  `SELECT id, file_id, task_type FROM processing_tasks WHERE user_id = ${userId} ` +
  'AND deleted = 0 ORDER BY id;'
);
fs.writeFileSync(idsFile, idsText + '\n', 'utf8');

// ---------------------------------------------------------------- 3. producer
function ensureLibs() {
  if (fs.existsSync(libDir) && fs.readdirSync(libDir).some((f) => f.endsWith('.jar'))) {
    return;
  }
  const javaHome = process.env.JAVA_HOME;
  const jarExe = javaHome ? path.join(javaHome, 'bin', 'jar.exe') : 'jar';
  const extractDir = path.join(path.dirname(path.resolve(libDir)), 'stress-extract');
  fs.mkdirSync(extractDir, { recursive: true });
  const res = spawnSync(jarExe, ['xf', path.resolve(fatJar), 'BOOT-INF/lib'], { cwd: extractDir, encoding: 'utf8' });
  if (res.status !== 0) {
    throw new Error(`jar extract failed: ${res.stderr || res.error}`);
  }
  fs.mkdirSync(libDir, { recursive: true });
  const libSource = path.join(extractDir, 'BOOT-INF', 'lib');
  for (const file of fs.readdirSync(libSource)) {
    fs.copyFileSync(path.join(libSource, file), path.join(libDir, file));
  }
}
ensureLibs();

// ---------------------------------------------------------------- 4. burst + sample
const timeline = [];
let sampling = true;
let peakBacklog = 0;
let producerLine = null;

async function sampler() {
  while (sampling) {
    try {
      const stats = await taskStats(agent, base, userId, deviceId);
      const pending = Number(stats.PENDING ?? 0);
      const running = Number(stats.RUNNING ?? 0);
      const done = Number(stats.DONE ?? 0);
      const backlog = pending + running;
      peakBacklog = Math.max(peakBacklog, backlog);
      timeline.push({ t: Date.now(), pending, running, done, backlog });
    } catch {
      // transient sampling error: keep the measurement running
    }
    await sleep(intervalMs);
  }
}

const javaHome = process.env.JAVA_HOME;
const javaExe = javaHome ? path.join(javaHome, 'bin', 'java.exe') : 'java';
const classpath = `${path.resolve(libDir)}\\*`;
const producer = spawn(javaExe, [
  '-cp', classpath, 'scripts/stress/MqBurstProducer.java',
  `--ids=${idsFile}`, `--threads=${threads}`
], { encoding: 'utf8' });

let producerOutput = '';
let producerError = '';
producer.stdout.on('data', (chunk) => {
  producerOutput += chunk.toString();
  for (const line of chunk.toString().split('\n')) {
    if (line.startsWith('RESULT mq-burst-producer')) {
      producerLine = line;
    }
  }
});
producer.stderr.on('data', (chunk) => {
  producerError += chunk.toString();
});

const samplingPromise = sampler();
const burstStartedAt = Date.now();
const producerExit = new Promise((resolve) => producer.on('close', resolve));
await producerExit;
const burstEndedAt = Date.now();

if (!producerLine) {
  sampling = false;
  await samplingPromise;
  throw new Error('producer failed, see stderr above');
}

// ---------------------------------------------------------------- 5. drain
const drainDeadline = Date.now() + drainTimeoutMs;
let drainedAt = null;
while (Date.now() < drainDeadline) {
  const last = timeline[timeline.length - 1];
  if (last && last.backlog <= baselineBacklog && last.t > burstEndedAt) {
    drainedAt = last.t;
    break;
  }
  await sleep(intervalMs);
}
sampling = false;
await samplingPromise;

// ---------------------------------------------------------------- 6. report
const injected = parseProducer(producerOutput);
const after = await taskStats(agent, base, userId, deviceId);
const doneTotal = Number(after.DONE ?? 0) - baselineDone;
const drainMs = (drainedAt ?? Date.now()) - burstEndedAt;

let peakConsumeRate = 0;
for (let i = 1; i < timeline.length; i++) {
  const dt = (timeline[i].t - timeline[i - 1].t) / 1000;
  const dDone = timeline[i].done - timeline[i - 1].done;
  if (dt > 0 && dDone > 0) {
    peakConsumeRate = Math.max(peakConsumeRate, dDone / dt);
  }
}

const consumeAvg = drainMs > 0 ? (injected / drainMs) * 1000 : 0;

kv('injected messages', injected);
kv('producer elapsed', `${burstEndedAt - burstStartedAt} ms`);
kv('peak backlog', `${peakBacklog} (${peakBacklog - baselineBacklog} above baseline)`);
kv('peak consume rate', `${round(peakConsumeRate, 1)} tasks/s`);
kv('drain time', `${round(drainMs / 1000, 2)} s`);
kv('average consume rate', `${round(consumeAvg, 1)} tasks/s`);
kv('tasks DONE during test', doneTotal);

// Downsampled timeline (about one line per second).
console.log('  timeline (1s buckets): t=seconds since burst start, backlog = PENDING + RUNNING');
const buckets = new Map();
for (const sample of timeline) {
  const second = Math.floor((sample.t - burstStartedAt) / 1000);
  buckets.set(second, sample);
}
for (const [second, sample] of [...buckets.entries()].sort((a, b) => a[0] - b[0])) {
  console.log(`    t=${String(second).padStart(3)}s backlog=${String(sample.backlog).padStart(5)} ` +
    `pending=${String(sample.pending).padStart(5)} running=${String(sample.running).padStart(4)} done=${sample.done}`);
}

result('mq-peak', {
  userId,
  injected,
  peakBacklog,
  peakBacklogAboveBaseline: peakBacklog - baselineBacklog,
  peakConsumePerSec: round(peakConsumeRate, 1),
  drainSeconds: round(drainMs / 1000, 2),
  avgConsumePerSec: round(consumeAvg, 1),
  doneDuringTest: doneTotal
});

if (!keepRows) {
  runSql(cleanupSeeded());
  kv('cleanup', 'seeded rows removed (use --keep true to inspect them)');
}
fs.rmSync(tmpDir, { recursive: true, force: true });
agent.destroy();

function parseProducer(output) {
  const match = output.match(/RESULT mq-burst-producer (\{.*\})/);
  if (!match) {
    console.log(output.trim().split('\n').slice(-20).join('\n'));
    if (producerError.trim()) {
      console.log('producer stderr (tail):');
      console.log(producerError.trim().split('\n').slice(-20).join('\n'));
    }
    throw new Error('producer did not report a RESULT line');
  }
  const parsed = JSON.parse(match[1]);
  kv('producer rate', `${parsed.ratePerSec} msg/s (failed=${parsed.failed})`);
  return parsed.produced;
}
