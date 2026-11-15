import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, mkdir, writeFile, readFile, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import {resolvePublication, parseManifest, readSourceVersion, createGitHubClient, preparePublication, publishPublication, verifyArtifactSet, parseSigningCertificate} from './automatic-apk-release.mjs';

const sha = 'a'.repeat(40), otherSha = 'b'.repeat(40), cert = 'c'.repeat(64);
const base = {channel: 'version', sourceVersion: '0.8.0', sourceSha: sha, beijingDate: '20261116', runNumber: 1, runAttempt: 1, releases: []};
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
function manifest(tag, code = 100000101, sourceSha = sha) {
  const nightly = tag.startsWith('nightly-');
  return {ref_name: tag, safe_ref_name: tag, commit: sourceSha, channel: nightly ? 'nightly' : tag.split('.').length === 3 ? 'stable' : 'beta',
    application_id: 'cn.com.omnimind.bot', version_name: nightly ? `0.8.0-nightly.${tag.slice(8)}.1` : tag.slice(1), version_code: String(code), signing_certificate_sha256: cert};
}
function release(tag, code, extras = {}) { return {tag, draft: false, targetSha: sha, manifest: manifest(tag, code), ...extras}; }
const env = {GITHUB_REPOSITORY: 'ZipperCode/OmniBot', GITHUB_SHA: sha, GITHUB_RUN_NUMBER: '1', GITHUB_RUN_ATTEMPT: '1', RELEASE_CERT_SHA256: cert, GH_TOKEN: 'fixture'};

for (const [sourceVersion, track, prerelease] of [['0.8.0', 'stable', false], ['0.8.0.1', 'beta', true]]) {
  test(`${sourceVersion} 发布到 ${track}`, () => {
    const result = resolvePublication({...base, sourceVersion});
    assert.equal(result.publish, true); assert.equal(result.tag, `v${sourceVersion}`);
    assert.equal(result.track, track); assert.equal(result.prerelease, prerelease);
    assert.equal(result.versionName, sourceVersion); assert.equal(result.versionCode, 100000101);
  });
}
test('无新提交的次日仍生成独立 nightly，四段源码原样保留', () => {
  const first = resolvePublication({...base, sourceVersion: '0.7.0.4', channel: 'nightly'});
  const next = resolvePublication({...base, sourceVersion: '0.7.0.4', channel: 'nightly', beijingDate: '20261117', releases: [release(first.tag, first.versionCode, {manifest: {...manifest(first.tag), version_name: first.versionName}})]});
  assert.equal(first.tag, 'nightly-20261116'); assert.equal(first.versionName, '0.7.0.4-nightly.20261116.1');
  assert.equal(next.tag, 'nightly-20261117'); assert.equal(next.publish, true); assert.equal(next.sourceSha, sha);
});
test('相同公开版本或当天 nightly 幂等，main 新 SHA 不重发版本', () => {
  assert.equal(resolvePublication({...base, sourceSha: otherSha, releases: [release('v0.8.0')]}).reason, 'already_published');
  assert.equal(resolvePublication({...base, channel: 'nightly', releases: [release('nightly-20261116')]}).reason, 'already_published');
});
test('旧同渠道版本跳过，stable 与 beta 独立比较，数字不是字典序', () => {
  assert.equal(resolvePublication({...base, releases: [release('v0.10.0')]}).reason, 'older_version');
  assert.equal(resolvePublication({...base, releases: [release('v0.8.0.99')]}).publish, true);
  assert.equal(resolvePublication({...base, sourceVersion: '0.8.0.1', releases: [release('v9.0.0')]}).publish, true);
});
test('同 SHA draft 可恢复，不同 SHA draft 拒绝', () => {
  assert.equal(resolvePublication({...base, releases: [release('v0.8.0', undefined, {draft: true, manifest: null})]}).publish, true);
  assert.throws(() => resolvePublication({...base, releases: [release('v0.8.0', undefined, {draft: true, targetSha: otherSha, manifest: null})]}), /draft/);
});
test('所有渠道共用已公开 CODE，重跑和旧 run 晚到均继续递增', () => {
  const releases = [release('v0.7.0.4', 100020000), release('nightly-20261115', 100030000)];
  const first = resolvePublication({...base, releases});
  assert.equal(first.versionCode, 100030001);
  const next = resolvePublication({...base, channel: 'nightly', runAttempt: 2, releases: [...releases, release(first.tag, first.versionCode)]});
  assert.equal(next.versionCode, 100030002);
  assert.ok(resolvePublication({...base, runNumber: 400, releases}).versionCode > 100030000);
});
test('旧 manifest 缺 CODE 可跳过其值，损坏自动化 manifest 不猜测', () => {
  const old = release('v0.7.0.4'); delete old.manifest.version_code;
  assert.equal(resolvePublication({...base, releases: [old]}).versionCode, 100000101);
  for (const broken of [{version_code: 'bad'}, {version_code: '2100000001'}, {application_id: 'wrong'}, {channel: 'stable'}, {signing_certificate_sha256: 'x'}, {commit: otherSha}]) {
    assert.throws(() => resolvePublication({...base, releases: [release('v0.7.0.4', 21, {manifest: {...manifest('v0.7.0.4'), ...broken}})]}));
  }
  assert.throws(() => resolvePublication({...base, releases: [release('v0.8.0', 21, {manifest: null})]}), /manifest/);
});
test('缺版本、suffix、非数字、非法日期、运行序号和 Android 溢出拒绝', () => {
  for (const sourceVersion of ['', '0.8', '0.8.0-beta', 'v0.8.0', '0.x.0', '0.8.0.1.2']) assert.throws(() => resolvePublication({...base, sourceVersion}));
  for (const values of [{beijingDate: '20260230'}, {runNumber: 0}, {runAttempt: -1}, {runNumber: 20000000}, {runNumber: NaN}, {sourceSha: ''}]) assert.throws(() => resolvePublication({...base, ...values}));
});
test('源码默认版本恰好一个，动态覆盖不算声明；manifest 拒绝重复和坏行', () => {
  assert.equal(readSourceVersion('  versionName = "0.7.0.4"\r\n versionName = rawVersionName\r\n'), '0.7.0.4');
  for (const text of ['', 'versionName = "0.x.0"', 'versionName = "0.8.0"\nversionName = "0.8.1"']) assert.throws(() => readSourceVersion(text));
  assert.throws(() => parseManifest('channel=beta\nchannel=stable\n'));
  assert.throws(() => parseManifest('bad line'));
});

test('Release API 翻页直到空页并解析 annotated tag，不读 latest', async () => {
  const requested = [];
  const api = createGitHubClient({token: env.GH_TOKEN, fetchImpl: async (url, options) => {
    requested.push(new URL(url).pathname + new URL(url).search);
    assert.equal(options.method, 'GET');
    const page = new URL(url).searchParams.get('page');
    if (page === '1') return Response.json([{id: 1, tag_name: 'v0.7.0.4', draft: false, assets: []}]);
    if (page === '2') return Response.json([{id: 2, tag_name: 'v0.8.0', draft: false, assets: []}]);
    if (page === '3') return Response.json([]);
    if (url.includes('/git/ref/tags/v0.7.0.4')) return Response.json({object: {type: 'tag', sha: otherSha}});
    if (url.includes('/git/tags/')) return Response.json({object: {type: 'commit', sha}});
    if (url.includes('/git/ref/tags/v0.8.0')) return Response.json({object: {type: 'commit', sha}});
    assert.fail('非预期 API 请求');
  }});
  const all = await api.listReleases();
  assert.equal(all.length, 2); assert.equal(all[0].targetSha, sha);
  assert.ok(requested.some(path => path.includes('page=3'))); assert.ok(!requested.some(path => path.includes('latest')));
});
test('draft 目标提交与已有 tag 冲突时只读失败', async () => {
  const calls = [];
  const api = createGitHubClient({token: env.GH_TOKEN, fetchImpl: async (url, options) => {
    calls.push(options.method);
    if (new URL(url).searchParams.get('page') === '1') return Response.json([{tag_name: 'v0.8.0', draft: true, target_commitish: otherSha, assets: []}]);
    if (url.includes('/git/ref/tags/')) return Response.json({object: {type: 'commit', sha}});
    assert.fail('非预期 API 请求');
  }});
  await assert.rejects(api.listReleases(), /draft 目标/); assert.ok(calls.every(method => method === 'GET'));
});
test('API 权限与网络失败不传播凭据，拒绝外部上传地址', async () => {
  const denied = createGitHubClient({token: env.GH_TOKEN, fetchImpl: async () => new Response('sensitive body ignored', {status: 403})});
  await assert.rejects(denied.request('/releases'), /HTTP 403/);
  await assert.rejects(denied.request('https://example.invalid/upload'), /非目标仓库/);
  const offline = createGitHubClient({token: env.GH_TOKEN, fetchImpl: async () => { throw new Error(env.GH_TOKEN); }});
  await assert.rejects(offline.request('/releases'), error => !error.message.includes(env.GH_TOKEN));
});

async function fixture(t, overrides = {}) {
  const directory = await mkdtemp(join(tmpdir(), 'omnibot-publication-test-'));
  t.after(() => rm(directory, {recursive: true, force: true}));
  const artifacts = join(directory, 'assets'); await mkdir(artifacts);
  const plan = {...resolvePublication(base), repository: env.GITHUB_REPOSITORY, certificateSha256: cert, ...overrides};
  const name = `OpenOmniBot-${plan.tag}-standard.apk`, apk = Buffer.from('独立测试资产，内容摘要必须严格匹配');
  const mf = {...manifest(plan.tag, plan.versionCode), version_name: plan.versionName};
  mf.asset = `${name} sha256=${digest(apk)} size=${apk.length}`;
  await writeFile(join(artifacts, name), apk);
  await writeFile(join(artifacts, name + '.sha256'), `${digest(apk)}  ${name}\n`);
  await writeFile(join(artifacts, 'manifest.txt'), Object.entries(mf).map(([k, v]) => `${k}=${v}\n`).join(''));
  return {directory, artifacts, plan, name, mf, inspect: async () => mf};
}
function fakeApi({initial = [], target = null, corruptDownload = false} = {}) {
  let tag = target, raw = initial[0]?.raw ?? null;
  const assets = new Map(), writes = [], state = {publications: 0};
  const api = {
    listReleases: async () => initial,
    tagSha: async () => tag,
    request: async (path, options = {}) => {
      const method = options.method ?? 'GET';
      if (method !== 'GET') writes.push({path, method, body: options.body});
      if (path === '/git/refs') { tag = options.body.sha; return {}; }
      if (path === '/releases' && method === 'POST') { raw = {id: 7, tag_name: options.body.tag_name, draft: true, prerelease: options.body.prerelease, assets: []}; return raw; }
      if (path.startsWith('https://uploads.github.com/')) {
        const name = new URL(path).searchParams.get('name'), id = assets.size + 1;
        assets.set(id, Buffer.from(options.binary)); raw.assets.push({name, id, size: options.binary.length}); return {};
      }
      if (path.startsWith('/releases/assets/')) {
        const id = Number(path.split('/').at(-1));
        if (method === 'DELETE') { raw.assets = raw.assets.filter(asset => asset.id !== id); assets.delete(id); return null; }
        return corruptDownload ? Buffer.from('损坏') : assets.get(id);
      }
      if (path === '/releases/7' && method === 'GET') return structuredClone(raw);
      if (path === '/releases/7' && method === 'PATCH') { state.publications++; raw = {...raw, ...options.body, html_url: 'https://github.com/ZipperCode/OmniBot/releases/tag/' + raw.tag_name}; return structuredClone(raw); }
      assert.fail(`非预期 API ${method} ${path}`);
    },
  };
  return {api, writes, state};
}
test('prepare 同公开版本 main push 零写，输出固定 skip 与原计划字段', async t => {
  const f = await fixture(t); await mkdir(join(f.directory, 'app'));
  await writeFile(join(f.directory, 'app/build.gradle.kts'), 'versionName = "0.8.0"\n');
  const transport = fakeApi({initial: [release('v0.8.0')]});
  const output = join(f.directory, 'plan.json'), ghOutput = join(f.directory, 'github-output');
  const result = await preparePublication({channel: 'version', output, env: {...env, GITHUB_SHA: otherSha, GITHUB_OUTPUT: ghOutput}, cwd: f.directory, now: new Date('2026-11-15T22:00:00Z'), client: transport.api});
  assert.equal(result.publish, false); assert.equal(result.reason, 'already_published'); assert.deepEqual(transport.writes, []);
  assert.equal(JSON.parse(await readFile(output, 'utf8')).repository, env.GITHUB_REPOSITORY);
  assert.match(await readFile(ghOutput, 'utf8'), /publish=false\nreason=already_published/);
});
test('prepare 北京时间跨日、tag 冲突和签名冲突在零写阶段处理', async t => {
  const f = await fixture(t); await mkdir(join(f.directory, 'app'));
  await writeFile(join(f.directory, 'app/build.gradle.kts'), 'versionName = "0.8.0"\n');
  const output = join(f.directory, 'plan.json');
  const fresh = fakeApi();
  const plan = await preparePublication({channel: 'nightly', output, env, cwd: f.directory, now: new Date('2026-11-15T22:00:00Z'), client: fresh.api});
  assert.equal(plan.tag, 'nightly-20261116'); assert.deepEqual(fresh.writes, []);
  for (const api of [fakeApi({target: otherSha}), fakeApi({initial: [release('v0.7.0.4', 21, {manifest: {...manifest('v0.7.0.4'), signing_certificate_sha256: 'd'.repeat(64)}})]})]) {
    await assert.rejects(preparePublication({channel: 'version', output, env, cwd: f.directory, client: api.api})); assert.deepEqual(api.writes, []);
  }
});
test('发布本地身份、SHA 或计划冲突时零写请求', async t => {
  for (const damage of ['hash', 'identity', 'plan']) {
    const f = await fixture(t), api = fakeApi();
    if (damage === 'hash') await writeFile(join(f.artifacts, f.name + '.sha256'), '0'.repeat(64));
    if (damage === 'identity') f.inspect = async () => ({...f.mf, application_id: 'wrong'});
    if (damage === 'plan') f.plan.sourceSha = otherSha;
    await assert.rejects(publishPublication({...f, env, client: api.api})); assert.deepEqual(api.writes, []);
  }
});
test('draft 不同 SHA 和 tag 冲突不产生写请求', async t => {
  const f = await fixture(t);
  for (const api of [fakeApi({initial: [release(f.plan.tag, undefined, {draft: true, targetSha: otherSha, manifest: null, raw: {id: 7}})]}), fakeApi({target: otherSha})]) {
    await assert.rejects(publishPublication({...f, env, client: api.api})); assert.deepEqual(api.writes, []);
  }
});
for (const [sourceVersion, track] of [['0.8.0', 'stable'], ['0.8.0.1', 'beta'], ['0.8.0-nightly.20261116.1', 'nightly']]) {
  test(`${track} 必须下载核验3资产后才公开，latest标志准确`, async t => {
    const nightly = track === 'nightly';
    const f = await fixture(t, {tag: nightly ? 'nightly-20261116' : `v${sourceVersion}`, versionName: sourceVersion, track, prerelease: track !== 'stable'});
    const api = fakeApi();
    const result = await publishPublication({...f, env, client: api.api});
    assert.equal(result.published, true);
    assert.equal(api.writes.filter(write => write.path.includes('uploads.github.com')).length, 3);
    assert.equal(api.writes.at(-1).method, 'PATCH'); assert.equal(api.writes.at(-1).body.make_latest, track === 'stable' ? 'true' : 'false');
  });
}
test('远端资产损坏不公开，保留 draft 可重跑', async t => {
  const f = await fixture(t), api = fakeApi({corruptDownload: true});
  await assert.rejects(publishPublication({...f, env, client: api.api}), /内容校验失败/);
  assert.equal(api.state.publications, 0); assert.ok(!api.writes.some(write => write.method === 'PATCH'));
});
test('同 SHA draft 重跑复用且可重传；已公开同计划零上传', async t => {
  const f = await fixture(t), oldAsset = {id: 99, name: 'failed.apk', size: 1};
  const draft = release(f.plan.tag, undefined, {draft: true, manifest: null, raw: {id: 7, tag_name: f.plan.tag, draft: true, assets: [oldAsset]}});
  const retry = fakeApi({initial: [draft], target: sha});
  await publishPublication({...f, env, client: retry.api});
  assert.ok(retry.writes.some(write => write.method === 'DELETE')); assert.ok(!retry.writes.some(write => write.path === '/releases'));
  const existing = fakeApi({initial: [release(f.plan.tag, f.plan.versionCode)], target: sha});
  assert.equal((await publishPublication({...f, env, client: existing.api})).reason, 'already_published'); assert.deepEqual(existing.writes, []);
});
test('旧 run 晚到的计划 CODE 不得覆盖更晚已发布渠道', async t => {
  const f = await fixture(t), api = fakeApi({initial: [release('nightly-20261115', f.plan.versionCode + 1)]});
  await assert.rejects(publishPublication({...f, env, client: api.api}), /CODE 已过时/); assert.deepEqual(api.writes, []);
});
test('缺资产或额外资产均不能上传', async t => {
  const f = await fixture(t);
  await writeFile(join(f.artifacts, 'unexpected.log'), '不属于发布集合');
  await assert.rejects(verifyArtifactSet(f.plan, f.artifacts, {inspect: f.inspect}), /三个资产/);
  await rm(join(f.artifacts, 'unexpected.log')); await rm(join(f.artifacts, f.name));
  await assert.rejects(verifyArtifactSet(f.plan, f.artifacts, {inspect: f.inspect}), /三个资产/);
});

test('官方 apksigner 编号与 SDK 范围格式均核验唯一证书，拒绝歧义', () => {
  const numbered = `Signer #1 certificate SHA-256 digest: ${cert}\n`;
  const ranged = `Signer (minSdkVersion=29, maxSdkVersion=2147483647) certificate SHA-256 digest: ${cert}\n`;
  assert.equal(parseSigningCertificate(numbered), cert);
  assert.equal(parseSigningCertificate(ranged), cert);
  assert.equal(parseSigningCertificate(`Signer (minSdkVersion=33 (dev release=true), maxSdkVersion=2147483647) certificate SHA-256 digest: ${cert}\n`), cert);
  assert.equal(parseSigningCertificate(`V3.0 Signer: certificate SHA-256 digest: ${cert}\n`), cert);
  assert.equal(parseSigningCertificate(`V3.1 Signer (minSdkVersion=33): certificate SHA-256 digest: ${cert}\n`), cert);
  assert.equal(parseSigningCertificate(numbered + ranged), cert);
  assert.throws(() => parseSigningCertificate(numbered + `Signer #2 certificate SHA-256 digest: ${'d'.repeat(64)}\n`));
  assert.throws(() => parseSigningCertificate(`Source Stamp Signer certificate SHA-256 digest: ${cert}\n`));
  assert.throws(() => parseSigningCertificate(''));
});
