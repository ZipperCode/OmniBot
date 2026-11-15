import {readFile, writeFile, appendFile, readdir, access} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {join, resolve} from 'node:path';
import {pathToFileURL} from 'node:url';

const REPOSITORY = 'ZipperCode/OmniBot';
const APPLICATION_ID = 'cn.com.omnimind.bot';
const MAX_CODE = 2100000000;
const SHA = /^[0-9a-f]{40}$/;
const DIGEST = /^[0-9a-f]{64}$/;
const VERSION = /^[0-9]+\.[0-9]+\.[0-9]+(?:\.[0-9]+)?$/;
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const fail = message => { throw new Error(message); };
const requireValue = (condition, message) => { if (!condition) fail(message); };

function versionParts(version) {
  requireValue(typeof version === 'string' && VERSION.test(version), '源码版本必须是精确三段或四段数字');
  return version.split('.').map(part => BigInt(part));
}
function compareVersions(left, right) {
  const a = versionParts(left), b = versionParts(right);
  for (let i = 0; i < a.length; i++) {
    if (a[i] !== b[i]) return a[i] > b[i] ? 1 : -1;
  }
  return 0;
}
function positiveInteger(value, name) {
  requireValue(Number.isSafeInteger(value) && value > 0, `${name} 必须为正安全整数`);
}
function validDate(value) {
  if (!/^[0-9]{8}$/.test(value)) return false;
  const iso = `${value.slice(0, 4)}-${value.slice(4, 6)}-${value.slice(6, 8)}`;
  const time = Date.parse(`${iso}T00:00:00Z`);
  return Number.isFinite(time) && new Date(time).toISOString().startsWith(iso);
}
function tagTrack(tag) {
  if (/^nightly-[0-9]{8}$/.test(tag)) return 'nightly';
  if (!tag.startsWith('v') || !VERSION.test(tag.slice(1))) return null;
  return tag.slice(1).split('.').length === 3 ? 'stable' : 'beta';
}
function automated(manifest) {
  return manifest != null && ['version_name', 'version_code', 'channel', 'application_id', 'signing_certificate_sha256'].some(key => key in manifest);
}
function validateManifest(manifest, tag, targetSha, {allowLegacyCode = true} = {}) {
  requireValue(manifest && typeof manifest === 'object' && !Array.isArray(manifest), '自动发布 manifest 缺失或格式错误');
  const track = tagTrack(tag);
  requireValue(track && manifest.ref_name === tag && manifest.safe_ref_name === tag, 'manifest 标签身份不匹配');
  requireValue(manifest.application_id === APPLICATION_ID, 'manifest 包名不匹配');
  requireValue(typeof manifest.commit === 'string' && SHA.test(manifest.commit) && manifest.commit === targetSha, 'manifest 源码 SHA 与标签不匹配');
  requireValue(manifest.channel === track, 'manifest 渠道不匹配');
  requireValue(typeof manifest.signing_certificate_sha256 === 'string' && DIGEST.test(manifest.signing_certificate_sha256), 'manifest 证书摘要格式错误');
  if (track === 'nightly') {
    const match = /^([0-9]+\.[0-9]+\.[0-9]+(?:\.[0-9]+)?)-nightly\.([0-9]{8})\.([0-9]+)$/.exec(manifest.version_name);
    requireValue(match && validDate(match[2]) && tag === `nightly-${match[2]}` && BigInt(match[3]) > 0n, 'manifest nightly 版本格式错误');
  } else {
    versionParts(manifest.version_name);
    requireValue(`v${manifest.version_name}` === tag, 'manifest 版本与标签不匹配');
  }
  if (manifest.version_code === undefined && allowLegacyCode) return;
  const rawCode = String(manifest.version_code);
  requireValue(/^[0-9]+$/.test(rawCode), 'manifest version_code 格式错误');
  const code = Number(rawCode);
  requireValue(Number.isSafeInteger(code) && code >= 1 && code <= MAX_CODE, 'manifest version_code 超出 Android 范围');
}

export function resolvePublication({channel, sourceVersion, sourceSha, beijingDate, runNumber, runAttempt, releases}) {
  requireValue(channel === 'version' || channel === 'nightly', '发布 channel 必须为 version 或 nightly');
  const parts = versionParts(sourceVersion);
  requireValue(typeof sourceSha === 'string' && SHA.test(sourceSha), '源码 SHA 格式错误');
  requireValue(typeof beijingDate === 'string' && validDate(beijingDate), '北京时间日期格式错误');
  positiveInteger(runNumber, 'runNumber'); positiveInteger(runAttempt, 'runAttempt');
  requireValue(Array.isArray(releases), 'Release 列表格式错误');
  const track = channel === 'nightly' ? 'nightly' : parts.length === 3 ? 'stable' : 'beta';
  const tag = channel === 'nightly' ? `nightly-${beijingDate}` : `v${sourceVersion}`;
  const versionName = channel === 'nightly' ? `${sourceVersion}-nightly.${beijingDate}.${runNumber}` : sourceVersion;
  const matches = releases.filter(release => release.tag === tag);
  requireValue(matches.length <= 1, '相同标签存在多个 Release');
  let maxPublishedCode = 0;
  let latestVersion = null;
  for (const release of releases) {
    requireValue(typeof release.tag === 'string' && typeof release.draft === 'boolean', 'Release 元数据格式错误');
    if (automated(release.manifest)) {
      validateManifest(release.manifest, release.tag, release.targetSha);
      if (!release.draft && release.manifest.version_code !== undefined) maxPublishedCode = Math.max(maxPublishedCode, Number(release.manifest.version_code));
    }
    if (!release.draft && tagTrack(release.tag) === track && track !== 'nightly') {
      const version = release.tag.slice(1);
      if (latestVersion === null || compareVersions(version, latestVersion) > 0) latestVersion = version;
    }
  }
  const candidate = 100000000 + runNumber * 100 + runAttempt;
  const versionCode = Math.max(candidate, maxPublishedCode + 1);
  requireValue(Number.isSafeInteger(versionCode) && versionCode >= 1 && versionCode <= MAX_CODE, '自动安装 versionCode 超出 Android 上限');
  const base = {tag, track, prerelease: track !== 'stable', versionName, versionCode, sourceSha};
  const existing = matches[0];
  if (existing) {
    if (existing.draft) {
      requireValue(existing.targetSha === sourceSha, '现有 draft 属于不同源码 SHA，不能接管');
    } else {
      validateManifest(existing.manifest, tag, existing.targetSha);
      requireValue(existing.manifest.channel === track, '公开 Release 渠道冲突');
      if (track !== 'nightly') requireValue(existing.manifest.version_name === sourceVersion, '公开 Release 版本冲突');
      return {publish: false, reason: 'already_published', ...base};
    }
  }
  if (latestVersion !== null && compareVersions(sourceVersion, latestVersion) < 0) return {publish: false, reason: 'older_version', ...base};
  return {publish: true, reason: 'publish', ...base};
}

export function parseManifest(text) {
  requireValue(typeof text === 'string' && text.length > 0, 'manifest 内容为空');
  const manifest = {};
  for (const line of text.split(/\r?\n/)) {
    if (!line) continue;
    const match = /^([a-z][a-z0-9_]*)=(.*)$/.exec(line);
    requireValue(match && !Object.hasOwn(manifest, match[1]), 'manifest 行格式错误或重复字段');
    manifest[match[1]] = match[2];
  }
  return manifest;
}
export function readSourceVersion(text) {
  const declarations = [...text.matchAll(/^\s*versionName\s*=\s*"([^"\r\n]*)"\s*(?:\/\/[^\r\n]*)?$/gm)];
  requireValue(declarations.length === 1, '必须恰好存在一个源码默认 versionName 字面量');
  versionParts(declarations[0][1]);
  return declarations[0][1];
}
function context(env) {
  requireValue(env.GITHUB_REPOSITORY === REPOSITORY, '自动发布仅允许 ZipperCode/OmniBot');
  requireValue(SHA.test(env.GITHUB_SHA ?? ''), 'GITHUB_SHA 格式错误');
  requireValue(/^[0-9a-fA-F]{64}$/.test(env.RELEASE_CERT_SHA256 ?? ''), '缺少合法 RELEASE_CERT_SHA256');
  requireValue(typeof env.GH_TOKEN === 'string' && env.GH_TOKEN.length > 0, '缺少 GH_TOKEN');
  return {repository: REPOSITORY, sourceSha: env.GITHUB_SHA, certificateSha256: env.RELEASE_CERT_SHA256.toLowerCase()};
}

export function createGitHubClient({token, fetchImpl = fetch}) {
  async function request(path, {method = 'GET', body, bytes = false, binary, contentType} = {}) {
    const url = new URL(path.startsWith('https://') ? path : `https://api.github.com/repos/${REPOSITORY}${path}`);
    requireValue((url.origin === 'https://api.github.com' && url.pathname.startsWith(`/repos/${REPOSITORY}/`)) || (url.origin === 'https://uploads.github.com' && url.pathname.startsWith(`/repos/${REPOSITORY}/releases/`)), '拒绝非目标仓库 API 地址');
    const headers = {'Accept': bytes ? 'application/octet-stream' : 'application/vnd.github+json', 'Authorization': `Bearer ${token}`, 'X-GitHub-Api-Version': '2022-11-28'};
    if (body !== undefined || binary !== undefined) headers['Content-Type'] = contentType ?? 'application/json';
    let response;
    try {
      response = await fetchImpl(url.toString(), {method, headers, body: binary ?? (body === undefined ? undefined : JSON.stringify(body))});
    } catch {
      fail(`GitHub ${method} 请求失败`);
    }
    if (response.status === 404 && method === 'GET') return null;
    requireValue(response.ok, `GitHub ${method} 请求失败：HTTP ${response.status}`);
    if (response.status === 204) return null;
    return bytes ? Buffer.from(await response.arrayBuffer()) : response.json();
  }
  async function tagSha(tag) {
    let object = (await request(`/git/ref/tags/${encodeURIComponent(tag)}`))?.object;
    if (!object) return null;
    const seen = new Set();
    while (object.type === 'tag') {
      requireValue(SHA.test(object.sha) && !seen.has(object.sha) && seen.size < 16, 'annotated tag 引用损坏');
      seen.add(object.sha);
      object = (await request(`/git/tags/${object.sha}`))?.object;
      requireValue(object, 'annotated tag 对象缺失');
    }
    requireValue(object.type === 'commit' && SHA.test(object.sha), '标签不是有效 commit');
    return object.sha;
  }
  async function listReleases() {
    const all = [];
    for (let page = 1;; page++) {
      const items = await request(`/releases?per_page=100&page=${page}`);
      requireValue(Array.isArray(items), 'Release 分页响应格式错误');
      if (items.length === 0) break;
      for (const item of items) {
        let targetSha = await tagSha(item.tag_name);
        if (item.draft) {
          const draftTarget = SHA.test(item.target_commitish ?? '') ? item.target_commitish
            : (await request(`/commits/${encodeURIComponent(item.target_commitish ?? '')}`))?.sha;
          requireValue(SHA.test(draftTarget ?? '') && (targetSha === null || targetSha === draftTarget), 'draft 目标与已有 tag 冲突');
          targetSha = draftTarget;
        } else {
          requireValue(targetSha !== null, '公开 Release 缺少有效 tag');
        }
        requireValue(SHA.test(targetSha ?? ''), '无法解析 Release 的目标 SHA');
        const assets = item.assets ?? [];
        const manifests = assets.filter(asset => asset.name === 'manifest.txt');
        requireValue(manifests.length <= 1, 'Release 包含多个 manifest');
        const content = manifests[0] ? await request(`/releases/assets/${manifests[0].id}`, {bytes: true}) : null;
        requireValue(!manifests[0] || content !== null, 'Release manifest 无法下载');
        const manifest = content ? parseManifest(content.toString('utf8')) : null;
        all.push({tag: item.tag_name, draft: item.draft, targetSha, manifest, raw: item});
      }
    }
    return all;
  }
  return {request, tagSha, listReleases};
}
function beijingDate(now) {
  const parts = new Intl.DateTimeFormat('en', {timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit'}).formatToParts(now);
  return ['year', 'month', 'day'].map(type => parts.find(part => part.type === type).value).join('');
}
function validateCertificates(releases, certificateSha256) {
  for (const release of releases) {
    if (automated(release.manifest)) requireValue(release.manifest.signing_certificate_sha256 === certificateSha256, '已有自动发布 manifest 与仓库签名证书冲突');
  }
}
export async function preparePublication({channel, output, env = process.env, cwd = process.cwd(), now = new Date(), client}) {
  const identity = context(env);
  requireValue(/^[0-9]+$/.test(env.GITHUB_RUN_NUMBER ?? '') && /^[0-9]+$/.test(env.GITHUB_RUN_ATTEMPT ?? ''), '缺少合法运行序号');
  const api = client ?? createGitHubClient({token: env.GH_TOKEN});
  const releases = await api.listReleases();
  const decision = resolvePublication({channel, sourceVersion: readSourceVersion(await readFile(join(cwd, 'app/build.gradle.kts'), 'utf8')), sourceSha: identity.sourceSha,
    beijingDate: beijingDate(now), runNumber: Number(env.GITHUB_RUN_NUMBER), runAttempt: Number(env.GITHUB_RUN_ATTEMPT), releases});
  validateCertificates(releases, identity.certificateSha256);
  if (decision.publish) {
    const target = await api.tagSha(decision.tag);
    requireValue(target === null || target === identity.sourceSha, '待发布 tag 指向其他 SHA，拒绝移动');
  }
  const plan = {...decision, ...identity};
  await writeFile(output, JSON.stringify(plan, null, 2) + '\n');
  if (env.GITHUB_OUTPUT) {
    const outputs = {publish: plan.publish, reason: plan.reason, tag: plan.tag, track: plan.track, prerelease: plan.prerelease, version_name: plan.versionName, version_code: plan.versionCode, source_sha: plan.sourceSha};
    await appendFile(env.GITHUB_OUTPUT, Object.entries(outputs).map(([key, value]) => `${key}=${value}\n`).join(''));
  }
  return plan;
}

async function sdkTool(name, env) {
  const sdk = env.ANDROID_SDK_ROOT || env.ANDROID_HOME;
  requireValue(sdk, '缺少 Android SDK 路径');
  const directory = join(sdk, 'build-tools');
  const versions = (await readdir(directory)).sort((a, b) => b.localeCompare(a, undefined, {numeric: true}));
  const suffixes = process.platform === 'win32' ? ['.exe', '.bat'] : [''];
  for (const version of versions) {
    for (const suffix of suffixes) {
      const path = join(directory, version, name + suffix);
      try { await access(path); return path; } catch (error) { if (error.code !== 'ENOENT') throw error; }
    }
  }
  fail(`缺少 SDK ${name}`);
}
export function parseSigningCertificate(output) {
  const certificates = [...new Set([...output.matchAll(/^Signer [^\r\n]*certificate SHA-256 digest:\s*([0-9a-fA-F]{64})[ \t]*\r?$/gm)].map(match => match[1].toLowerCase()))];
  requireValue(certificates.length === 1, 'APK 签名证书摘要缺失或不唯一');
  return certificates[0];
}
export async function inspectApk(path, env = process.env) {
  const aapt = await sdkTool('aapt', env);
  const apksigner = await sdkTool('apksigner', env);
  function run(tool, args) {
    try {
      if (process.platform === 'win32' && tool.endsWith('.bat')) {
        return execFileSync('cmd.exe', ['/d', '/s', '/c', `""${tool}" ${args.map(arg => `"${arg}"`).join(' ')}"`], {encoding: 'utf8', env, windowsVerbatimArguments: true, stdio: ['ignore', 'pipe', 'pipe']});
      }
      return execFileSync(tool, args, {encoding: 'utf8', env, stdio: ['ignore', 'pipe', 'pipe']});
    } catch { fail('SDK APK 身份或完整签名验证失败'); }
  }
  const badging = run(aapt, ['dump', 'badging', path]);
  const cert = run(apksigner, ['verify', '--print-certs', path]);
  return {application_id: /^package: name='([^']+)'/m.exec(badging)?.[1], version_code: /^package: .*versionCode='([^']+)'/m.exec(badging)?.[1],
    version_name: /^package: .*versionName='([^']+)'/m.exec(badging)?.[1], signing_certificate_sha256: parseSigningCertificate(cert)};
}
function validatePlan(plan, identity) {
  requireValue(plan.repository === identity.repository && plan.sourceSha === identity.sourceSha && plan.certificateSha256 === identity.certificateSha256, '发布计划与当前仓库、源码或证书不一致');
  requireValue(plan.publish === true && plan.reason === 'publish' && tagTrack(plan.tag) === plan.track && plan.prerelease === (plan.track !== 'stable'), '发布计划状态不合法');
  validateManifest({ref_name: plan.tag, safe_ref_name: plan.tag, commit: plan.sourceSha, application_id: APPLICATION_ID,
    channel: plan.track, version_name: plan.versionName, version_code: plan.versionCode, signing_certificate_sha256: plan.certificateSha256}, plan.tag, plan.sourceSha, {allowLegacyCode: false});
}
export async function verifyArtifactSet(plan, artifacts, {env = process.env, inspect = inspectApk} = {}) {
  const apkName = `OpenOmniBot-${plan.tag}-standard.apk`;
  const names = [apkName, `${apkName}.sha256`, 'manifest.txt'];
  const actualNames = await readdir(artifacts);
  requireValue(actualNames.length === 3 && names.every(name => actualNames.includes(name)), '必须恰好提供 APK、SHA256 和 manifest 三个资产');
  const [apk, sidecar, text] = await Promise.all(names.map(name => readFile(join(artifacts, name))));
  const manifest = parseManifest(text.toString('utf8'));
  validateManifest(manifest, plan.tag, plan.sourceSha, {allowLegacyCode: false});
  requireValue(manifest.version_name === plan.versionName && Number(manifest.version_code) === plan.versionCode && manifest.channel === plan.track && manifest.signing_certificate_sha256 === plan.certificateSha256, '构建 manifest 与发布计划身份不一致');
  const digest = hash(apk);
  requireValue(sidecar.toString('utf8').trim() === `${digest}  ${apkName}`, 'SHA256 文件与实际 APK 不一致');
  requireValue(manifest.asset === `${apkName} sha256=${digest} size=${apk.length}`, 'manifest 资产摘要或大小不一致');
  const identity = await inspect(resolve(artifacts, apkName), env);
  for (const key of ['application_id', 'version_name', 'version_code', 'signing_certificate_sha256']) requireValue(String(identity[key]) === String(manifest[key]), `最终 APK 的 ${key} 与 manifest 不一致`);
  return names.map((name, i) => ({name, bytes: [apk, sidecar, text][i]}));
}
export async function publishPublication({plan, artifacts, env = process.env, client, inspect}) {
  const identity = context(env);
  validatePlan(plan, identity);
  const files = await verifyArtifactSet(plan, artifacts, {env, inspect});
  const api = client ?? createGitHubClient({token: env.GH_TOKEN});
  const releases = await api.listReleases();
  validateCertificates(releases, identity.certificateSha256);
  let release = releases.find(item => item.tag === plan.tag);
  requireValue(releases.filter(item => item.tag === plan.tag).length <= 1, '重复 Release 标签');
  if (release && !release.draft) {
    validateManifest(release.manifest, plan.tag, release.targetSha);
    requireValue(release.manifest.version_name === plan.versionName && Number(release.manifest.version_code) === plan.versionCode && release.manifest.signing_certificate_sha256 === plan.certificateSha256 && release.targetSha === plan.sourceSha, '公开 Release 与计划冲突');
    return {published: false, reason: 'already_published'};
  }
  if (release) requireValue(release.targetSha === plan.sourceSha, 'draft 源码 SHA 冲突');
  let maxCode = 0;
  for (const item of releases) {
    if (automated(item.manifest)) validateManifest(item.manifest, item.tag, item.targetSha);
    if (!item.draft && automated(item.manifest) && item.manifest.version_code !== undefined) maxCode = Math.max(maxCode, Number(item.manifest.version_code));
  }
  requireValue(plan.versionCode > maxCode, '发布计划 CODE 已过时，必须重新 prepare');
  const target = await api.tagSha(plan.tag);
  requireValue(target === null || target === plan.sourceSha, '待写 tag 已指向其他 SHA');
  if (target === null) await api.request('/git/refs', {method: 'POST', body: {ref: `refs/tags/${plan.tag}`, sha: plan.sourceSha}});
  if (!release) {
    const name = plan.track === 'nightly' ? `Nightly ${plan.versionName} (${plan.sourceSha.slice(0, 7)})` : `${plan.track === 'beta' ? 'Beta' : 'Stable'} ${plan.versionName}`;
    const raw = await api.request('/releases', {method: 'POST', body: {tag_name: plan.tag, target_commitish: plan.sourceSha, name, body: `源码：${plan.sourceSha}\n渠道：${plan.track}\n安装 CODE：${plan.versionCode}\n\n下载 APK 后可使用 SHA256 文件核验。`, draft: true, prerelease: plan.prerelease, make_latest: 'false'}});
    release = {raw, targetSha: plan.sourceSha, draft: true};
  }
  const id = release.raw.id;
  requireValue(Number.isSafeInteger(id), 'draft Release id 格式错误');
  const current = await api.request(`/releases/${id}`);
  requireValue(current?.draft === true && current.tag_name === plan.tag, '上传前 draft 状态已改变');
  for (const asset of current.assets ?? []) await api.request(`/releases/assets/${asset.id}`, {method: 'DELETE'});
  for (const file of files) {
    await api.request(`https://uploads.github.com/repos/${REPOSITORY}/releases/${id}/assets?name=${encodeURIComponent(file.name)}`, {method: 'POST', binary: file.bytes, contentType: file.name.endsWith('.apk') ? 'application/vnd.android.package-archive' : 'text/plain'});
  }
  const uploaded = await api.request(`/releases/${id}`);
  requireValue(uploaded?.draft === true && uploaded.tag_name === plan.tag && uploaded.assets?.length === 3, 'draft 资产集合不完整');
  for (const file of files) {
    const matches = uploaded.assets.filter(asset => asset.name === file.name);
    requireValue(matches.length === 1 && matches[0].size === file.bytes.length, '远端资产名称或大小不符');
    const remote = await api.request(`/releases/assets/${matches[0].id}`, {bytes: true});
    requireValue(remote !== null && hash(remote) === hash(file.bytes), '远端资产内容校验失败，保留 draft');
  }
  requireValue(await api.tagSha(plan.tag) === plan.sourceSha, '公开前 tag 身份已改变');
  const published = await api.request(`/releases/${id}`, {method: 'PATCH', body: {draft: false, prerelease: plan.prerelease, make_latest: plan.track === 'stable' ? 'true' : 'false'}});
  requireValue(published?.draft === false && published.tag_name === plan.tag && published.prerelease === plan.prerelease, 'Release 公开状态验证失败');
  return {published: true, url: published.html_url};
}

async function main(args) {
  const command = args.shift();
  const allowed = command === 'prepare' ? ['--channel', '--output'] : command === 'publish' ? ['--plan', '--artifacts'] : [];
  requireValue(allowed.length > 0 && args.length === 4, '用法：prepare --channel <version|nightly> --output <plan.json> 或 publish --plan <plan.json> --artifacts <目录>');
  const options = {};
  for (let i = 0; i < args.length; i += 2) {
    requireValue(allowed.includes(args[i]) && !Object.hasOwn(options, args[i]) && args[i + 1], 'CLI 参数缺失、重复或未知');
    options[args[i]] = args[i + 1];
  }
  requireValue(allowed.every(key => Object.hasOwn(options, key)), 'CLI 必需参数缺失');
  const result = command === 'prepare' ? await preparePublication({channel: options['--channel'], output: options['--output']})
    : await publishPublication({plan: JSON.parse(await readFile(options['--plan'], 'utf8')), artifacts: options['--artifacts']});
  console.log(JSON.stringify(result));
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main(process.argv.slice(2)).catch(error => { console.error(error.message); process.exitCode = 1; });
}
