#!/usr/bin/env node
import {execFileSync, spawnSync} from 'node:child_process';
import {mkdirSync, readFileSync, unlinkSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {dirname, isAbsolute, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {uiXmlField as field} from './agent-ui-xml.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const SCENARIOS = join(ROOT, 'scripts/fixtures/user-scenarios');
const CASES = ['interaction', 'collection', 'experience', 'cancel', 'model'];
const APP = 'cn.com.omnimind.bot';
const sleep = ms => new Promise(done => setTimeout(done, ms));
const blocked = category => ({status: 'blocked', category});

export function parseCliArgs(argv = process.argv.slice(2)) {
  const options = {deviceEnv: 'ANDROID_SERIAL', caseName: 'all', outDir: '', expectedRuntime: '', modelA: '', modelB: ''};
  for (let i = 0; i < argv.length; i++) {
    if (['--help', '-h'].includes(argv[i])) return {help: true};
    const key = {
      '--device-env': 'deviceEnv',
      '--case': 'caseName',
      '--out': 'outDir',
      '--expected-runtime': 'expectedRuntime',
      '--model-a': 'modelA',
      '--model-b': 'modelB',
    }[argv[i]];
    if (!key || !argv[i + 1] || argv[i + 1].startsWith('--')) throw new Error('CLI_ARGUMENT_INVALID');
    options[key] = argv[++i];
  }
  if (!['all', ...CASES].includes(options.caseName) || !/^[A-Za-z_][A-Za-z0-9_]*$/.test(options.deviceEnv)) {
    throw new Error('CLI_ARGUMENT_INVALID');
  }
  options.outDir = resolve(options.outDir || join(tmpdir(), `phone-control-verification-${Date.now()}`));
  if (options.expectedRuntime) options.expectedRuntime = resolve(options.expectedRuntime);
  return options;
}

export function getAdbEnv(extraEnv = {}) {
  const env = {...process.env, ...extraEnv};
  const adb = env.ADB;
  if (adb && /[/\\]/.test(adb)) {
    const directory = dirname(resolve(adb));
    const separator = process.platform === 'win32' ? ';' : ':';
    if (!(env.PATH || '').split(separator).includes(directory)) env.PATH = directory + separator + (env.PATH || '');
  }
  return env;
}

export function execAdb(serial, args) {
  return execFileSync(process.env.ADB || 'adb', ['-s', serial, ...args], {
    env: getAdbEnv(), encoding: 'utf8', timeout: 30000, maxBuffer: 32 * 1024 * 1024,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
}

const nodeLabel = node => field(node, 'content-desc') || field(node, 'text');
const appNode = node => field(node, 'package') === APP && field(node, 'password') !== 'true';
const actionable = node => appNode(node) && field(node, 'clickable') === 'true' && field(node, 'enabled') === 'true';
const bounds = node => [...field(node, 'bounds').matchAll(/-?\d+/g)].map(([value]) => Number(value));
const validBounds = b => b.length === 4 && b[2] > b[0] && b[3] > b[1] && b[0] >= 0 && b[1] >= 0;
const inside = (a, b) => validBounds(a) && validBounds(b) && a[0] >= b[0] && a[1] >= b[1] && a[2] <= b[2] && a[3] <= b[3];
const XIAOWAN_LABELS = ['小万', 'Xiaowan', '小万 Agent', 'OmniBot Agent'];
const firstLine = label => label.split('\n')[0];
const firstSegment = label => label.split(/[,，\n]/)[0].trim();
const hasLabel = (nodes, label) => nodes.some(node => appNode(node) && (
  nodeLabel(node) === label ||
  nodeLabel(node).split('\n').includes(label) ||
  firstSegment(nodeLabel(node)) === label
));

export function getUiDumpNodes(serial, adb = execAdb) {
  const path = '/data/local/tmp/oob-phone-control-ui.xml';
  const result = adb(serial, ['shell', 'uiautomator', 'dump', path]);
  if (!result.includes('dumped to:')) throw new Error('UI_DUMP_UNAVAILABLE');
  return [...adb(serial, ['shell', 'cat', path]).matchAll(/<node\b[^>]*>/g)].map(([node]) => node);
}

export function display0Component(output) {
  const section = output.split(/(?=Display\s*#?\d+\b)/).find(value => /^Display\s*#?0\b/.test(value));
  if (!section) return null;
  const components = [...section.matchAll(/(?:mResumedActivity|topResumedActivity|resumedActivity|mFocusedActivity|mFocusedApp)[^\n]*?\b([A-Za-z_][\w.]*)\/[\w.$]+/g)].map(match => match[1]);
  return components.length && components.every(value => value === components[0]) ? components[0] : null;
}

export function composerModelControl(nodes) {
  const editors = nodes.filter(node => appNode(node) && field(node, 'class') === 'android.widget.EditText');
  if (editors.length !== 1) return null;
  const editor = bounds(editors[0]);
  const sends = nodes.filter(node => appNode(node) && ['发送', 'Send'].includes(firstLine(nodeLabel(node))));
  const buttons = nodes.filter(node => actionable(node) && nodeLabel(node) && field(node, 'class') !== 'android.widget.EditText')
    .filter(node => {
      const box = bounds(node);
      return validBounds(box) && box[1] >= editor[3] && box[3] <= editor[3] + 240 &&
        box[0] >= (editor[0] + editor[2]) / 2 && !['发送', 'Send', '附件与权限', 'Attachments & permissions'].includes(firstLine(nodeLabel(node))) &&
        (!sends.length || sends.some(send => { const b = bounds(send); return box[1] < b[3] && box[3] > b[1] && box[2] <= b[0]; }));
    });
  return buttons.length === 1 ? buttons[0] : null;
}

export function composerReady(nodes) {
  const editors = nodes.filter(node => appNode(node) && field(node, 'class') === 'android.widget.EditText');
  if (editors.length !== 1 || field(editors[0], 'text').trim()) return false;
  const editor = bounds(editors[0]);
  const currentIdentity = nodes.some(node => appNode(node) && XIAOWAN_LABELS.includes(firstLine(nodeLabel(node))) &&
    (actionable(node) && bounds(node)[3] <= editor[1] && bounds(node)[1] >= editor[1] - 300 || field(node, 'class') === 'android.widget.ImageView'));
  return currentIdentity && !['Stop', '停止', '停止生成'].some(label => hasLabel(nodes, label));
}

export function runPreflight(serial, {adb = execAdb} = {}) {
  if (!serial.trim()) return {ok: false, category: 'DEVICE_ENV_UNSET'};
  const emulator = /^emulator-\d+$/.test(serial);
  if (!emulator && process.env.OOB_ALLOW_PHYSICAL_DEVICE !== '1') return {ok: false, category: 'PHYSICAL_DEVICE_NOT_ALLOWED'};
  if (!/^[A-Za-z0-9._:-]+$/.test(serial)) return {ok: false, category: 'INVALID_DEVICE_SERIAL'};
  let sdk;
  try {
    if (adb(serial, ['get-state']).trim() !== 'device') return {ok: false, category: 'DEVICE_OFFLINE'};
    sdk = Number(adb(serial, ['shell', 'getprop', 'ro.build.version.sdk']).trim());
  } catch { return {ok: false, category: 'DEVICE_UNREACHABLE'}; }
  if (!Number.isInteger(sdk) || sdk < 34) return {ok: false, category: 'API_LEVEL_UNSUPPORTED'};
  for (const [pkg, category] of [[APP, 'MAIN_APP_NOT_INSTALLED'], ['cn.com.omnimind.androidgui.test', 'FIXTURE_APP_NOT_INSTALLED']]) {
    try {
      if (!adb(serial, ['shell', 'pm', 'path', pkg]).trim().startsWith('package:')) return {ok: false, category};
    } catch { return {ok: false, category}; }
  }
  try {
    const services = adb(serial, ['shell', 'settings', 'get', 'secure', 'enabled_accessibility_services']);
    if (!services.split(':').some(value => value.trim().startsWith(APP + '/'))) return {ok: false, category: 'ACCESSIBILITY_NOT_ENABLED'};
    const activities = adb(serial, ['shell', 'dumpsys', 'activity', 'activities']);
    if (display0Component(activities) !== APP) return {ok: false, category: 'CHAT_FOREGROUND_UNPROVEN'};
    const primary = activities.split(/Display\s*#?0\b/)[1]?.split(/Display\s*#?[1-9]\d*\b/)[0];
    if (!primary || primary.includes('cn.com.omnimind.androidgui.test/')) return {ok: false, category: 'FIXTURE_ON_PRIMARY_DISPLAY'};
    const displays = adb(serial, ['shell', 'dumpsys', 'display']);
    if (!/DisplayDeviceInfo|Display Devices/.test(displays)) return {ok: false, category: 'DISPLAY_STATE_UNOBSERVABLE'};
    const displayRecords = displays.split(/\r?\n/).filter(line => line.includes('DisplayDeviceInfo{'));
    if (!displayRecords.length) return {ok: false, category: 'DISPLAY_STATE_UNOBSERVABLE'};
    const virtualDisplays = displayRecords.filter(line => /\btype\s*(?:VIRTUAL|[=:]?\s*5\b)/i.test(line));
    // 仅允许明确归属其他普通应用的屏共存；本应用与未知或特权所有者仍阻塞。
    if (virtualDisplays.some(line => {
      const owner = line.match(/\bowner\s+([A-Za-z_][\w.]*)\s*\(uid\s+(\d+)\)/);
      return !owner || Number(owner[2]) < 10000 || owner[1] === APP || owner[1] === 'cn.com.omnimind.androidgui.test';
    })) return {ok: false, category: 'EXISTING_VD_OCCUPIED'};
    if (!composerReady(getUiDumpNodes(serial, adb))) return {ok: false, category: 'EMPTY_XIAOWAN_CHAT_UNPROVEN'};
  } catch { return {ok: false, category: 'PREFLIGHT_STATE_UNOBSERVABLE'}; }
  return {ok: true, details: {deviceKind: emulator ? 'EMULATOR' : 'DEVICE', sdkLevel: sdk}};
}

export function queryFixtureState(serial, adb = execAdb) {
  try {
    const output = adb(serial, ['shell', 'content', 'query', '--uri', 'content://cn.com.omnimind.androidgui.test.evidence/state']);
    const rows = [...output.matchAll(/\bjson=(\{[^\r\n]*\})/g)];
    if (rows.length !== 1) return null;
    const state = JSON.parse(rows[0][1]);
    return state.schema_version === 'omnibot.gui-fixture.v1' ? state : null;
  } catch { return null; }
}

export function sendMessage(serial, marker, scenarioPath, {spawn = spawnSync, timeout = 600000} = {}) {
  if (process.env.OOB_PREPARE_DRAFT_ONLY === '1') return {ok: false, category: 'DRAFT_ONLY_MODE_ACTIVE'};
  const env = getAdbEnv({OOB_USER_SCENARIO_FILE: scenarioPath});
  // 旧 sender 的可选截图输出含设备和草稿，只保留本 runner 的受限结构化证据。
  delete env.OOB_SEND_EVIDENCE_DIR;
  try {
    const proc = spawn(process.execPath, [join(ROOT, 'scripts/send-agent-test-message.mjs'), serial, marker], {
      env, timeout, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'],
    });
    if (proc.status !== 0) return {ok: false, category: 'SEND_FAILED'};
    const messages = proc.stdout.trim().split(/\r?\n/).map(line => JSON.parse(line));
    const accepted = messages.at(-1);
    return accepted?.marker === marker && accepted.sendDispatched === true ? {ok: true} : {ok: false, category: 'SEND_RESPONSE_INVALID'};
  } catch { return {ok: false, category: 'SEND_PROCESS_ERROR'}; }
}

export function tapControl(serial, label, {spawn = spawnSync} = {}) {
  try {
    const proc = spawn(process.execPath, [join(ROOT, 'scripts/tap-agent-device-control.mjs'), serial, label], {
      env: getAdbEnv(), timeout: 30000, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'],
    });
    if (proc.status !== 0) return {ok: false, category: 'TAP_CONTROL_FAILED'};
    const value = JSON.parse(proc.stdout);
    if (value.tapped !== true || value.control !== label || value.gesture !== 'tap') return {ok: false, category: 'TAP_RESPONSE_INVALID'};
    return {ok: true, data: {control: label, tapped: true, gesture: 'tap'}};
  } catch { return {ok: false, category: 'TAP_RESPONSE_INVALID'}; }
}

export function interpretAssertResponse(proc, {marker, caseName, phase = null}) {
  const invalid = {ok: false, status: 'failed', category: 'ASSERT_RESPONSE_INVALID'};
  let data;
  try { data = JSON.parse(proc.stdout.trim()); } catch { return invalid; }
  const status = {0: 'passed', 1: 'failed', 2: 'pending', 3: 'blocked'}[proc.status];
  if (!status || data.status !== status || data.passed !== (status === 'passed') ||
      data.marker !== marker || data.case !== caseName || (phase && data.phase !== phase) ||
      typeof data.category !== 'string' || !/^[A-Za-z0-9_]+$/.test(data.category)) return invalid;
  return {ok: status === 'passed', status, category: data.category, data};
}

export async function pollAssert({serial, marker, caseName, phase = null, sourceMarker = null,
  expectedExperienceId = null, expectedModel = null, expectedFormFirst = null, expectedFormSecond = null,
  outDir = null, expectedRuntime = null, timeoutMs = 3600000, pollIntervalMs = 3000, spawn = spawnSync}) {
  const args = [join(ROOT, 'scripts/assert-phone-control.py'), serial, marker, caseName];
  for (const [name, value] of Object.entries({phase, 'source-marker': sourceMarker, 'expected-experience-id': expectedExperienceId,
    'expected-model': expectedModel, 'expected-form-first': expectedFormFirst, 'expected-form-second': expectedFormSecond,
    'expected-runtime': expectedRuntime, out: outDir})) {
    if (value) args.push('--' + name, value);
  }
  const deadline = Date.now() + timeoutMs;
  do {
    let proc;
    try {
      proc = spawn(process.platform === 'win32' ? 'python' : 'python3', args, {
        env: getAdbEnv(), encoding: 'utf8', timeout: 30000, stdio: ['ignore', 'pipe', 'pipe'],
      });
    } catch { return {status: 'failed', category: 'ASSERT_PROCESS_ERROR'}; }
    const result = interpretAssertResponse(proc, {marker, caseName, phase});
    if (result.status !== 'pending') return result;
    await sleep(pollIntervalMs);
  } while (Date.now() < deadline);
  return {status: 'blocked', category: 'ASSERT_TIMEOUT'};
}

export function uniqueControl(nodes, labels, {prefix = false} = {}) {
  const found = nodes.filter(node => actionable(node) && labels.some(label => {
    const value = nodeLabel(node);
    const seg = value.split(/[,，\n]/)[0].trim();
    return value === label || firstLine(value) === label || seg === label || (prefix && value.startsWith(label));
  }));
  return found.length === 1 ? found[0] : null;
}

export function tapGuiSelectorNode(serial, node, adb = execAdb) {
  const b = bounds(node);
  if (!actionable(node) || !validBounds(b)) throw new Error('UI_CONTROL_UNPROVEN');
  const width = b[2] - b[0];
  const height = b[3] - b[1];
  const x = width > 400 ? Math.round(b[0] + width * 0.76) : Math.round((b[0] + b[2]) / 2);
  const y = width > 400 ? Math.round(b[1] + height * 0.38) : Math.round((b[1] + b[3]) / 2);
  adb(serial, ['shell', 'input', 'tap', String(x), String(y)]);
}
export function tapSwitchNode(serial, node, adb = execAdb) {
  const b = bounds(node);
  if (!actionable(node) || !validBounds(b)) throw new Error('UI_CONTROL_UNPROVEN');
  const width = b[2] - b[0];
  const height = b[3] - b[1];
  // 针对整行复合 Switch（label+switch 同节点），点击右侧开关图形区域（避开左侧文本）
  const x = width > height * 2 ? Math.round(b[2] - height * 0.6) : Math.round((b[0] + b[2]) / 2);
  const y = Math.round((b[1] + b[3]) / 2);
  adb(serial, ['shell', 'input', 'tap', String(x), String(y)]);
}

export async function dismissResultOverlay(serial, {adb = execAdb} = {}) {
  try {
    const nodes = getUiDumpNodes(serial, adb);
    const stayNode = uniqueControl(nodes, ['留在当前', 'Stay']);
    if (stayNode) {
      tapNode(serial, stayNode, adb);
      await sleep(500);
      const freshNodes = getUiDumpNodes(serial, adb);
      if (uniqueControl(freshNodes, ['留在当前', 'Stay'])) {
        throw new Error('OVERLAY_DISMISS_UNPROVEN');
      }
      return true;
    }
  } catch (err) {
    if (err.message === 'OVERLAY_DISMISS_UNPROVEN') throw err;
  }
  return false;
}


function tapNode(serial, node, adb = execAdb) {
  const b = bounds(node);
  if (!actionable(node) || !validBounds(b)) throw new Error('UI_CONTROL_UNPROVEN');
  adb(serial, ['shell', 'input', 'tap', String(Math.round((b[0] + b[2]) / 2)), String(Math.round((b[1] + b[3]) / 2))]);
}

async function click(serial, labels, options = {}) {
  const adb = options.adb || execAdb;
  const node = uniqueControl(getUiDumpNodes(serial, adb), labels, options);
  if (!node) throw new Error('UI_CONTROL_UNPROVEN');
  tapNode(serial, node, adb);
  await sleep(500);
}

function scroll(serial, nodes, direction = 'down', adb = execAdb) {
  const containers = nodes.filter(node => appNode(node) && field(node, 'scrollable') === 'true' && validBounds(bounds(node)));
  if (containers.length !== 1) throw new Error('UI_SCROLL_REGION_UNPROVEN');
  const [left, top, right, bottom] = bounds(containers[0]);
  const x = Math.round((left + right) / 2);
  const from = Math.round(top + (bottom - top) * (direction === 'down' ? 0.8 : 0.2));
  const to = Math.round(top + (bottom - top) * (direction === 'down' ? 0.2 : 0.8));
  adb(serial, ['shell', 'input', 'swipe', String(x), String(from), String(x), String(to), '300']);
}

async function reveal(serial, labels, {pages = 6, direction = 'down', adb = execAdb} = {}) {
  for (let page = 0; page <= pages; page++) {
    const nodes = getUiDumpNodes(serial, adb);
    if (labels.some(label => hasLabel(nodes, label))) return nodes;
    if (page === pages) break;
    scroll(serial, nodes, direction, adb);
    await sleep(300);
  }
  throw new Error('UI_IDENTITY_NOT_VISIBLE');
}

async function back(serial, adb = execAdb) {
  adb(serial, ['shell', 'input', 'keyevent', '4']);
  await sleep(500);
}

export async function returnToChat(serial, {adb = execAdb, expectedTitle = null} = {}) {
  for (let count = 0; count < 6; count++) {
    const nodes = getUiDumpNodes(serial, adb);
    if (composerReady(nodes)) {
      if (!expectedTitle || chatTitle(nodes) === expectedTitle) return;
    }
    await back(serial, adb);
    await sleep(400);
  }
  const finalNodes = getUiDumpNodes(serial, adb);
  if (!composerReady(finalNodes)) {
    throw new Error('CHAT_RETURN_UNPROVEN');
  }
  if (expectedTitle && chatTitle(finalNodes) !== expectedTitle) {
    throw new Error('CONVERSATION_RETURN_IDENTITY_MISSING');
  }
}

export async function openExecutionCenter(serial, {adb = execAdb} = {}) {
  await dismissResultOverlay(serial, {adb});
  let nodes = getUiDumpNodes(serial, adb);
  if (hasLabel(nodes, '执行中心') || hasLabel(nodes, 'Execution Center')) return;

  if (uniqueControl(nodes, ['返回会话列表', 'Back to conversations'])) {
    await click(serial, ['返回会话列表', 'Back to conversations'], {adb});
    nodes = getUiDumpNodes(serial, adb);
  }

  if (hasLabel(nodes, '执行中心') || hasLabel(nodes, 'Execution Center')) return;

  // 首页抽屉菜单：显示菜单 -> 插件市场 -> OmniFlow -> 已保存操作与执行记录
  const menuBtn = uniqueControl(nodes, ['显示菜单', 'Show menu', '菜单', 'Menu']);
  if (menuBtn) {
    tapNode(serial, menuBtn, adb);
    await sleep(500);
    nodes = getUiDumpNodes(serial, adb);
  }

  const pluginMarket = uniqueControl(nodes, ['插件市场', 'Plugin Marketplace', 'Plugins']);
  if (pluginMarket) {
    tapNode(serial, pluginMarket, adb);
    await sleep(500);
    nodes = getUiDumpNodes(serial, adb);
  }

  const omniflowItem = nodes.find(n => actionable(n) && (nodeLabel(n) === 'OmniFlow' || nodeLabel(n).startsWith('OmniFlow\n') || nodeLabel(n).startsWith('OmniFlow ')));
  if (omniflowItem) {
    tapNode(serial, omniflowItem, adb);
    await sleep(500);
    nodes = getUiDumpNodes(serial, adb);
  }

  const recordEntry = uniqueControl(nodes, ['已保存操作与执行记录', 'Saved operations and run logs']);
  if (recordEntry) {
    tapNode(serial, recordEntry, adb);
    await sleep(500);
    nodes = getUiDumpNodes(serial, adb);
  }

  if (!hasLabel(nodes, '执行中心') && !hasLabel(nodes, 'Execution Center')) {
    throw new Error('EXECUTION_CENTER_UNPROVEN');
  }
}

export function experienceSwitch(nodes) {
  // 1. 优先匹配自身即为 Switch/checkable 的行节点（label 包含“启用该应用经验”）
  const selfSwitches = nodes.filter(node => actionable(node) && (field(node, 'class') === 'android.widget.Switch' || field(node, 'checkable') === 'true'))
    .filter(node => {
      const lbl = nodeLabel(node);
      return ['启用该应用经验', 'Enable experience'].some(prefix => lbl === prefix || lbl.startsWith(prefix + '\n') || lbl.startsWith(prefix + ',') || lbl.startsWith(prefix + ' '));
    });
  if (selfSwitches.length === 1 && ['true', 'false'].includes(field(selfSwitches[0], 'checked'))) {
    return selfSwitches[0];
  }

  // 2. 兼容 label 与 Switch 分离为两个兄弟节点的旧布局
  const labels = nodes.filter(node => appNode(node) && ['启用该应用经验', 'Enable experience'].includes(nodeLabel(node)));
  if (labels.length === 1) {
    const anchor = bounds(labels[0]);
    const choices = nodes.filter(node => actionable(node) && (field(node, 'class') === 'android.widget.Switch' || field(node, 'checkable') === 'true'))
      .filter(node => { const b = bounds(node); return validBounds(b) && b[0] >= anchor[2] && b[1] < anchor[3] && b[3] > anchor[1]; });
    if (choices.length === 1 && ['true', 'false'].includes(field(choices[0], 'checked'))) return choices[0];
  }
  return null;
}

async function setExperienceEnabled(serial, id, enabled, {adb = execAdb} = {}) {
  await openExecutionCenter(serial, {adb});
  await click(serial, ['应用经验', 'Experiences'], {adb});
  let matched = false;
  for (let page = 0; page < 20 && !matched; page++) {
    let nodes = getUiDumpNodes(serial, adb);
    const candidates = nodes.filter(node => actionable(node) && nodeLabel(node).split('\n').includes('GUI验收'));
    for (let index = 0; index < candidates.length; index++) {
      // 相同应用/动作可有多个版本；先打开只读详情确证规则 ID，再允许改开关。
      const fresh = getUiDumpNodes(serial, adb).filter(node => actionable(node) && nodeLabel(node).split('\n').includes('GUI验收'));
      if (fresh.length !== candidates.length) throw new Error('EXPERIENCE_LIST_CHANGED');
      tapNode(serial, fresh[index], adb);
      await sleep(500);
      try {
        await reveal(serial, [id], {adb});
        matched = true;
        break;
      } catch {
        await click(serial, ['关闭', 'Close'], {adb});
        nodes = getUiDumpNodes(serial, adb);
      }
    }
    if (!matched) { scroll(serial, nodes, 'down', adb); await sleep(300); }
  }
  if (!matched) throw new Error('EXPERIENCE_IDENTITY_UNPROVEN');
  let toggle;
  for (let count = 0; count < 8; count++) {
    const nodes = getUiDumpNodes(serial, adb);
    toggle = experienceSwitch(nodes);
    if (toggle) break;
    scroll(serial, nodes, 'up', adb);
    await sleep(300);
  }
  if (!toggle) throw new Error('EXPERIENCE_SWITCH_UNPROVEN');
  if ((field(toggle, 'checked') === 'true') !== enabled) {
    tapSwitchNode(serial, toggle, adb);
    await sleep(800);
    const updated = experienceSwitch(getUiDumpNodes(serial, adb));
    if (!updated || (field(updated, 'checked') === 'true') !== enabled) throw new Error('EXPERIENCE_WRITE_UNCONFIRMED');
  }
  await click(serial, ['关闭', 'Close'], {adb});
  // 执行中心关闭后逐层返回，回到首页会话列表
  for (let c = 0; c < 6; c++) {
    const current = getUiDumpNodes(serial, adb);
    if (uniqueControl(current, ['切换 Agent', 'Switch Agent']) || composerReady(current)) break;
    await back(serial, adb);
    await sleep(400);
  }
}

async function runScenario(serial, outDir, caseName, phase, marker, file, expectations = {}) {
  await dismissResultOverlay(serial);
  if (!composerReady(getUiDumpNodes(serial))) return blocked('EMPTY_XIAOWAN_CHAT_UNPROVEN');
  const scenarioPath = isAbsolute(file) ? file : join(SCENARIOS, file);
  const sent = sendMessage(serial, marker, scenarioPath);
  if (!sent.ok) return {status: 'failed', category: sent.category};
  const pollResult = await pollAssert({serial, outDir: typeof outDir === 'object' ? outDir.outDir : outDir,
    expectedRuntime: typeof outDir === 'object' ? outDir.expectedRuntime : null, caseName, phase, marker, ...expectations});
  await dismissResultOverlay(serial);
  return pollResult;
}

export async function executeInteractionCase(serial, outDir) {
  return runScenario(serial, outDir, 'interaction', 'default', `OOB_LIVE_GUI_INTERACTION_${Date.now()}`, 'phone-control-interaction.json');
}

export async function executeCollectionCase(serial, outDir) {
  const marker = `OOB_LIVE_GUI_COLLECTION_${Date.now()}`;
  const full = await runScenario(serial, outDir, 'collection', 'full', marker + '_FULL', 'phone-control-collection.json');
  if (full.status !== 'passed') return full;
  const truncated = await runScenario(serial, outDir, 'collection', 'truncated', marker + '_TRUNC', 'phone-control-collection-trunc.json');
  return {...truncated, phases: {full, truncated}};
}

export async function executeExperienceCase(serial, outDir) {
  const base = `OOB_LIVE_GUI_EXPERIENCE_${Date.now()}`;
  const phases = {};
  let experienceId;
  let functionId;
  for (const [phase, suffix] of [['round1', '01'], ['round2', '02'], ['round3', '03']]) {
    await newXiaowanConversation(serial);
    const result = await runScenario(serial, outDir, 'experience', phase, base + '_' + phase.toUpperCase(), `phone-control-experience-${phase}.json`, {
      expectedExperienceId: experienceId, expectedFormFirst: 'SYNTHETIC_FIRST_' + suffix, expectedFormSecond: 'SYNTHETIC_SECOND_' + suffix,
    });
    phases[phase] = result;
    if (result.status !== 'passed') return {...result, phases};
    if (phase === 'round1') {
      experienceId = result.data?.details?.experience_id;
      if (!/^[a-f0-9]{24}$/.test(experienceId || '')) return {...blocked('EXPERIENCE_IDENTITY_UNPROVEN'), phases};
      functionId = result.data?.details?.function_id || result.data?.details?.registered_function_id;
      if (!functionId || !/^[A-Za-z0-9._-]+$/.test(functionId)) return {...blocked('SOURCE_FUNCTION_ID_UNPROVEN'), phases};
    }
  }
  try { await setExperienceEnabled(serial, experienceId, false); }
  catch (err) {
    const category = err.message === 'CONVERSATION_RETURN_IDENTITY_MISSING' ? 'CONVERSATION_RETURN_IDENTITY_MISSING' : 'UI_EXPERIENCE_DISABLE_UNPROVEN';
    return {...blocked(category), phases};
  }
  await newXiaowanConversation(serial);
  const disabled = await runScenario(serial, outDir, 'experience', 'disabled', base + '_DISABLED', 'phone-control-experience-disabled.json', {
    expectedExperienceId: experienceId, expectedFormFirst: 'SYNTHETIC_FIRST_04', expectedFormSecond: 'SYNTHETIC_SECOND_04',
  });
  phases.disabled = disabled;
  if (disabled.status !== 'passed') return {...disabled, phases};
  const replayTemplatePath = join(SCENARIOS, 'phone-control-experience-replay.json');
  const targetOutDir = typeof outDir === 'object' ? outDir.outDir : outDir;
  const tempReplayFile = join(targetOutDir, `phone-control-experience-replay-${Date.now()}.json`);
  let replay;
  try {
    const templateContent = readFileSync(replayTemplatePath, 'utf8');
    const substituted = templateContent.replaceAll('{{FUNCTION_ID}}', functionId);
    writeFileSync(tempReplayFile, substituted, 'utf8');
    await newXiaowanConversation(serial);
    replay = await runScenario(serial, outDir, 'experience', 'replay', base + '_REPLAY', tempReplayFile, {
      sourceMarker: base + '_ROUND1', expectedFormFirst: 'SYNTHETIC_FIRST_REPLAY', expectedFormSecond: 'SYNTHETIC_SECOND_REPLAY',
    });
  } finally {
    try { unlinkSync(tempReplayFile); } catch {}
  }
  phases.replay = replay;
  if (replay.status !== 'passed') return {...replay, phases};
  try { await setExperienceEnabled(serial, experienceId, true); }
  catch (err) {
    const category = err.message === 'CONVERSATION_RETURN_IDENTITY_MISSING' ? 'CONVERSATION_RETURN_IDENTITY_MISSING' : 'UI_EXPERIENCE_ENABLE_UNPROVEN';
    return {...blocked(category), phases};
  }
  for (const [phase, suffix] of [['rollback1', 'RB1'], ['rollback2', 'RB2']]) {
    await newXiaowanConversation(serial);
    const result = await runScenario(serial, outDir, 'experience', phase, base + '_' + suffix, `phone-control-experience-${phase}.json`, {
      expectedExperienceId: experienceId, expectedFormFirst: 'SYNTHETIC_FIRST_' + suffix, expectedFormSecond: 'SYNTHETIC_SECOND_' + suffix,
    });
    phases[phase] = result;
    if (result.status !== 'passed') return {...result, phases};
  }
  return {status: 'passed', category: 'ok', phases};
}

export function queryWaitActionAdmitted(serial, marker, {spawn = spawnSync} = {}) {
  const pyCode = `
import importlib.util, sys
from agent_test_database import agent_database_snapshot

spec = importlib.util.spec_from_file_location('phone_assert', 'assert-phone-control.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
try:
    with agent_database_snapshot(sys.argv[1]) as db:
        admitted = module.wait_action_admitted(db, sys.argv[2])
except Exception:
    admitted = False
print('ADMITTED' if admitted else 'NOT_ADMITTED')
`;
  try {
    const res = spawn(process.platform === 'win32' ? 'python' : 'python3', ['-c', pyCode, serial, marker], {
      cwd: join(ROOT, 'scripts'),
      env: getAdbEnv(),
      encoding: 'utf8',
      timeout: 15000,
    });
    return res.stdout?.trim() === 'ADMITTED';
  } catch {
    return false;
  }
}

export async function executeCancelCase(serial, outDir) {
  const marker = `OOB_LIVE_GUI_CANCEL_${Date.now()}`;
  await dismissResultOverlay(serial);
  const sent = sendMessage(serial, marker, join(SCENARIOS, 'phone-control-cancel.json'));
  if (!sent.ok) return {status: 'failed', category: sent.category};
  let loading = false;
  let waitAdmitted = false;
  const deadline = Date.now() + 600000;
  do {
    const state = queryFixtureState(serial);
    if (state?.marker === marker && state.display_id > 0 && state.scenario === 'loading' && state.persistent_loading === true && state.counts?.load === 1 && state.counts?.confirm === 0) {
      loading = true;
      if (queryWaitActionAdmitted(serial, marker)) {
        waitAdmitted = true;
        break;
      }
    }
    await sleep(500);
  } while (Date.now() < deadline);
  if (!loading || !waitAdmitted) return blocked('CANCEL_WAIT_ADMISSION_UNPROVEN');
  const stopNode = uniqueControl(getUiDumpNodes(serial), ['Stop', '停止', '停止生成']);
  if (!stopNode) return blocked('STOP_CONTROL_UNPROVEN');
  const stopped = tapControl(serial, firstLine(nodeLabel(stopNode)));
  if (!stopped.ok) return {status: 'failed', category: stopped.category};
  const cancelled = await pollAssert({serial, marker, caseName: 'cancel', phase: 'cancel',
    outDir: typeof outDir === 'object' ? outDir.outDir : outDir,
    expectedRuntime: typeof outDir === 'object' ? outDir.expectedRuntime : null});
  if (cancelled.status !== 'passed') return cancelled;
  await dismissResultOverlay(serial);
  const recovery = await runScenario(serial, outDir, 'cancel', 'recovery', marker + '_RECOVERY', 'phone-control-cancel-recovery.json', {sourceMarker: marker});
  return {...recovery, phases: {cancelled, recovery}};
}

export function isProviderHeader(label) {
  if (!label) return false;
  const lines = label.split('\n').map(l => l.trim()).filter(Boolean);
  return lines.length >= 2 && lines.some(line => /^(\d+|—)$/.test(line));
}

export function modelPickerSearchBox(nodes, footerNode) {
  if (!footerNode) return null;
  const frame = bounds(footerNode);
  if (!validBounds(frame)) return null;
  const candidates = nodes.filter(n => appNode(n) && field(n, 'class') === 'android.widget.EditText').filter(n => {
    const b = bounds(n);
    return validBounds(b) && b[3] <= frame[1] && b[0] >= frame[0] - 30 && b[2] <= frame[2] + 30 &&
      !['描述你的任务', 'Describe your task'].some(h => (field(n, 'hint') || '').includes(h));
  });
  const hinted = candidates.filter(n => ['搜索模型 ID', 'Search model ID', '搜索模型或服务商', 'Search models or providers'].some(h => (nodeLabel(n) || '').includes(h) || (field(n, 'hint') || '').includes(h)));
  if (hinted.length === 1) return hinted[0];
  return candidates.length === 1 ? candidates[0] : null;
}

export async function clearAndInputText(serial, searchBox, text, adb = execAdb) {
  tapNode(serial, searchBox, adb);
  await sleep(300);
  const clearKeys = ['123', ...Array(40).fill('67')];
  adb(serial, ['shell', 'input', 'keyevent', ...clearKeys]);
  await sleep(300);
  const sanitized = (text || '').replace(/[^A-Za-z0-9._ -]/g, '').replace(/ /g, '%s');
  if (sanitized) {
    adb(serial, ['shell', 'input', 'text', sanitized]);
    await sleep(500);
  }
}

// 仅从带选中语义的菜单区域选取实际可点击模型，不把背景消息或服务商标题当模型。
export function modelPickerRows(nodes) {
  const footer = nodes.filter(node => actionable(node) && ['配置模型连接', 'Configure model connections'].includes(firstLine(nodeLabel(node))));
  if (footer.length !== 1) return [];
  const searchBox = modelPickerSearchBox(nodes, footer[0]);
  if (!searchBox) return [];
  const frame = bounds(footer[0]);
  const top = bounds(searchBox)[3];
  return nodes.filter(node => actionable(node) && nodeLabel(node) && field(node, 'class') !== 'android.widget.EditText' &&
    !isProviderHeader(nodeLabel(node)) &&
    !/\n(?:\d+|—)$/.test(nodeLabel(node)))
    .filter(node => { const b = bounds(node); return validBounds(b) && b[1] >= top && b[3] <= frame[1] && b[0] >= frame[0] && b[2] <= frame[2]; });
}

export async function closePanelToComposer(serial, {adb = execAdb} = {}) {
  for (let count = 0; count < 5; count++) {
    const nodes = getUiDumpNodes(serial, adb);
    if (composerModelControl(nodes) && composerReady(nodes)) return true;
    await back(serial, adb);
    await sleep(400);
  }
  const finalNodes = getUiDumpNodes(serial, adb);
  return Boolean(composerModelControl(finalNodes) && composerReady(finalNodes));
}

export async function openModelPicker(serial, {adb = execAdb} = {}) {
  let nodes = getUiDumpNodes(serial, adb);
  let footerNode = nodes.find(node => actionable(node) && ['配置模型连接', 'Configure model connections'].includes(firstLine(nodeLabel(node))));
  let searchBox = modelPickerSearchBox(nodes, footerNode);
  const inPopup = Boolean(footerNode && searchBox);

  let composerShortName = null;
  if (!inPopup) {
    const inPanel = uniqueControl(nodes, ['模型', 'Model']);
    if (inPanel) {
      tapNode(serial, inPanel, adb);
      await sleep(500);
    } else {
      const trigger = composerModelControl(nodes);
      if (!trigger) throw new Error('COMPOSER_MODEL_CONTROL_UNPROVEN');
      composerShortName = nodeLabel(trigger).trim();
      tapNode(serial, trigger, adb);
      await sleep(500);
      nodes = getUiDumpNodes(serial, adb);
      const modelItem = uniqueControl(nodes, ['模型', 'Model']);
      if (modelItem) {
        tapNode(serial, modelItem, adb);
        await sleep(500);
      }
    }
    nodes = getUiDumpNodes(serial, adb);
    footerNode = nodes.find(node => actionable(node) && ['配置模型连接', 'Configure model connections'].includes(firstLine(nodeLabel(node))));
    searchBox = modelPickerSearchBox(nodes, footerNode);
  }

  if (!footerNode || !searchBox) throw new Error('MODEL_CHOICES_UNPROVEN');

  let rows = modelPickerRows(nodes);
  // 若 popup 内暂无实际 model rows（例如 Provider 全部折叠），优先展开 Provider header
  if (!rows.length) {
    const headers = nodes.filter(n => actionable(n) && isProviderHeader(nodeLabel(n)));
    for (const h of headers) {
      tapNode(serial, h, adb);
      await sleep(400);
    }
    nodes = getUiDumpNodes(serial, adb);
    rows = modelPickerRows(nodes);
  }

  const seenChoices = new Set();
  for (const r of rows) seenChoices.add(nodeLabel(r));

  let selectedNode = rows.find(node => field(node, 'selected') === 'true');
  let selected = selectedNode ? nodeLabel(selectedNode) : null;

  // 首屏未包含 selected，通过有界 scroll 收集并定位，遇到折叠 Provider 尝试展开
  let currentNodes = nodes;
  for (let page = 0; page < 6 && (!selected || seenChoices.size < 2); page++) {
    try {
      scroll(serial, currentNodes, 'down', adb);
      await sleep(300);
    } catch { break; }
    currentNodes = getUiDumpNodes(serial, adb);
    let scrolledRows = modelPickerRows(currentNodes);
    if (!scrolledRows.length) {
      const headers = currentNodes.filter(n => actionable(n) && isProviderHeader(nodeLabel(n)));
      for (const h of headers) {
        tapNode(serial, h, adb);
        await sleep(400);
      }
      currentNodes = getUiDumpNodes(serial, adb);
      scrolledRows = modelPickerRows(currentNodes);
    }
    if (!scrolledRows.length) break;
    for (const r of scrolledRows) {
      const l = nodeLabel(r);
      seenChoices.add(l);
      if (!selected && field(r, 'selected') === 'true') {
        selected = l;
      }
    }
  }

  // 若依然未标定 selected，且在 composer 读到了 shortName，使用 search 定位并展开折叠的 Provider
  if (!selected && composerShortName) {
    const currentFooter = currentNodes.find(n => actionable(n) && ['配置模型连接', 'Configure model connections'].includes(firstLine(nodeLabel(n))));
    const searchInput = modelPickerSearchBox(currentNodes, currentFooter);
    if (searchInput) {
      await clearAndInputText(serial, searchInput, composerShortName, adb);
      let searchNodes = getUiDumpNodes(serial, adb);
      let searchRows = modelPickerRows(searchNodes);

      // 若搜索后未直接出现 actual row，展开匹配的 Provider header
      if (!searchRows.length) {
        const matchingHeaders = searchNodes.filter(n => actionable(n) && isProviderHeader(nodeLabel(n)));
        for (const h of matchingHeaders) {
          tapNode(serial, h, adb);
          await sleep(400);
        }
        searchNodes = getUiDumpNodes(serial, adb);
        searchRows = modelPickerRows(searchNodes);
      }

      for (const r of searchRows) seenChoices.add(nodeLabel(r));
      const match = searchRows.find(r => field(r, 'selected') === 'true');
      if (match) selected = nodeLabel(match);

      // 定位后清空搜索框以恢复全局模型列表供 choices 收集
      const clearFooter = searchNodes.find(n => actionable(n) && ['配置模型连接', 'Configure model connections'].includes(firstLine(nodeLabel(n))));
      const clearInput = modelPickerSearchBox(searchNodes, clearFooter);
      if (clearInput) {
        await clearAndInputText(serial, clearInput, '', adb);
        currentNodes = getUiDumpNodes(serial, adb);
        const restoredRows = modelPickerRows(currentNodes);
        for (const r of restoredRows) seenChoices.add(nodeLabel(r));
      }
    }
  }

  // 必须确证 selected 为真实已选 model，绝不猜词
  if (!selected) throw new Error('MODEL_CHOICES_UNPROVEN');

  return {rows, selected, choices: [...seenChoices]};
}

export async function chooseModel(serial, label, {adb = execAdb} = {}) {
  const picker = await openModelPicker(serial, {adb});
  if (picker.selected === label) {
    await closePanelToComposer(serial, {adb});
    return;
  }

  let nodes = getUiDumpNodes(serial, adb);
  let rows = modelPickerRows(nodes);
  let target = rows.find(r => nodeLabel(r) === label);

  if (!target) {
    const footerNode = nodes.find(node => actionable(node) && ['配置模型连接', 'Configure model connections'].includes(firstLine(nodeLabel(node))));
    const searchInput = modelPickerSearchBox(nodes, footerNode);
    if (searchInput) {
      await clearAndInputText(serial, searchInput, label.split('\n')[0], adb);
      nodes = getUiDumpNodes(serial, adb);
      rows = modelPickerRows(nodes);
      if (!rows.length) {
        const headers = nodes.filter(n => actionable(n) && isProviderHeader(nodeLabel(n)));
        for (const h of headers) {
          tapNode(serial, h, adb);
          await sleep(400);
        }
        nodes = getUiDumpNodes(serial, adb);
        rows = modelPickerRows(nodes);
      }
      target = rows.find(r => nodeLabel(r) === label);
    }
  }

  if (!target) {
    for (let page = 0; page < 6; page++) {
      try {
        scroll(serial, nodes, 'down', adb);
        await sleep(300);
      } catch { break; }
      nodes = getUiDumpNodes(serial, adb);
      rows = modelPickerRows(nodes);
      target = rows.find(r => nodeLabel(r) === label);
      if (target) break;
    }
  }

  if (!target) throw new Error('MODEL_SELECTION_UNCONFIRMED');

  tapNode(serial, target, adb);
  await sleep(600);

  await closePanelToComposer(serial, {adb});

  const confirmed = await openModelPicker(serial, {adb});
  if (confirmed.selected !== label) {
    throw new Error('MODEL_SELECTION_UNCONFIRMED');
  }
  await closePanelToComposer(serial, {adb});
}

function chatTitle(nodes) {
  const menu = uniqueControl(nodes, ['返回会话列表', 'Back to conversations']);
  if (!menu) throw new Error('CONVERSATION_RETURN_IDENTITY_MISSING');
  const b = bounds(menu);
  const titles = nodes.filter(node => appNode(node) && !actionable(node) && nodeLabel(node) &&
    !XIAOWAN_LABELS.includes(nodeLabel(node)))
    .filter(node => { const n = bounds(node); return validBounds(n) && n[0] >= b[2] && n[1] < b[3] && n[3] > b[1]; });
  const labels = [...new Set(titles.map(nodeLabel))];
  // Flutter 可能将模式图标与标题合并到同一 ImageView 语义节点。
  const mergedTitles = nodes.filter(node => appNode(node) && !actionable(node) && field(node, 'class') === 'android.widget.ImageView')
    .map(node => nodeLabel(node).split('\n').map(part => part.trim()).filter(Boolean))
    .filter(parts => parts.length === 2 && XIAOWAN_LABELS.includes(parts[0]))
    .map(parts => parts[1]);
  labels.push(...mergedTitles.filter(label => !labels.includes(label)));
  if (labels.length !== 1) throw new Error('CONVERSATION_RETURN_IDENTITY_MISSING');
  return labels[0];
}

async function enterSceneSettings(serial, {adb = execAdb} = {}) {
  const title = chatTitle(getUiDumpNodes(serial, adb));
  await click(serial, ['返回会话列表', 'Back to conversations'], {adb});
  await click(serial, ['设置', 'Settings'], {adb});
  const sceneLabels = ['场景模型配置', '场景模型配置, 按场景配置基础与高级模型绑定', 'Scene Model Config'];
  await reveal(serial, sceneLabels, {adb});
  await click(serial, sceneLabels, {adb});
  return title;
}

async function leaveSceneSettings(serial, title, {adb = execAdb} = {}) {
  await back(serial, adb);
  let nodes = getUiDumpNodes(serial, adb);
  if (!composerReady(nodes)) {
    if (uniqueControl(nodes, ['返回', 'Back'])) await click(serial, ['返回', 'Back'], {adb});
    else await click(serial, ['首页', 'Home'], {adb});
    nodes = getUiDumpNodes(serial, adb);
    if (!composerReady(nodes)) await click(serial, [title], {adb});
  }
  if (!composerReady(getUiDumpNodes(serial, adb))) throw new Error('CONVERSATION_RETURN_UNPROVEN');
}

export async function guiSelector(serial, {adb = execAdb} = {}) {
  const nodes = await reveal(serial, ['GUI 操作'], {adb});
  // 1. 优先匹配整行卡片（actionable 且首行或首段为 "GUI 操作"）
  const fullRows = nodes.filter(node => actionable(node) && nodeLabel(node).includes('\n') &&
    firstSegment(nodeLabel(node)) === 'GUI 操作');
  if (fullRows.length === 1) return fullRows[0];

  // 2. 兼容测试环境中的独立 label + 右侧 choice 布局
  const labels = nodes.filter(node => appNode(node) && nodeLabel(node).split('\n').includes('GUI 操作'));
  if (labels.length === 1) {
    const b = bounds(labels[0]);
    const choices = nodes.filter(actionable).filter(node => {
      const n = bounds(node);
      return validBounds(n) && n[0] >= b[2] && n[1] < b[3] && n[3] > b[1] && nodeLabel(node);
    });
    if (choices.length === 1) return choices[0];
  }
  throw new Error('GUI_SELECTOR_UNPROVEN');
}

export async function readGuiBinding(serial, {adb = execAdb} = {}) {
  const node = await guiSelector(serial, {adb});
  const label = nodeLabel(node);
  const lines = label.split('\n').map(l => l.trim()).filter(Boolean);
  const inherited = lines.some(l => /^(继承：|默认：|未配置)/.test(l));

  tapGuiSelectorNode(serial, node, adb);
  await sleep(500);
  let nodes = getUiDumpNodes(serial, adb);
  const searches = nodes.filter(option => appNode(option) && field(option, 'class') === 'android.widget.EditText' &&
    ['搜索模型或服务商', 'Search models or providers'].some(h => nodeLabel(option).includes(h) || field(option, 'hint').includes(h)));
  if (searches.length !== 1) throw new Error('GUI_BINDING_POPUP_UNPROVEN');
  const popup = bounds(searches[0]);

  const extractChoices = list => list.filter(actionable).filter(option => {
    const b = bounds(option);
    return validBounds(b) && b[1] >= popup[3] && b[0] >= popup[0] - 24 && b[2] <= popup[2] + 24 &&
      nodeLabel(option) && firstLine(nodeLabel(option)) !== '跟随主 Agent';
  });

  const choicesMap = new Map();
  for (const c of extractChoices(nodes)) choicesMap.set(nodeLabel(c), c);

  let selected = [...choicesMap.values()].find(option => field(option, 'selected') === 'true');

  if (!inherited && !selected) {
    let currentNodes = nodes;
    for (let page = 0; page < 4 && !selected; page++) {
      try {
        scroll(serial, currentNodes, 'down', adb);
        await sleep(300);
      } catch { break; }
      currentNodes = getUiDumpNodes(serial, adb);
      for (const c of extractChoices(currentNodes)) {
        choicesMap.set(nodeLabel(c), c);
        if (field(c, 'selected') === 'true') selected = c;
      }
    }
  }

  const original = inherited ? null : selected ? nodeLabel(selected) : undefined;
  if (original === undefined && !inherited) throw new Error('GUI_BINDING_IDENTITY_UNPROVEN');
  if (choicesMap.size === 0) throw new Error('GUI_BINDING_IDENTITY_UNPROVEN');
  await back(serial, adb);
  return {original, choices: [...choicesMap.keys()]};
}

export async function selectGuiBinding(serial, label, {adb = execAdb} = {}) {
  const selector = await guiSelector(serial, {adb});
  const currentLabel = nodeLabel(selector);
  const lines = currentLabel.split('\n').map(l => l.trim()).filter(Boolean);
  const isInherited = lines.some(l => /^(继承：|默认：|未配置)/.test(l));
  if (label === null && isInherited) return;

  tapGuiSelectorNode(serial, selector, adb);
  await sleep(500);

  if (label === null) {
    await click(serial, ['跟随主 Agent', 'Follow primary agent'], {adb});
  } else {
    let nodes = getUiDumpNodes(serial, adb);
    let target = uniqueControl(nodes, [label, firstLine(label)]);
    if (!target) {
      const search = nodes.find(n => appNode(n) && field(n, 'class') === 'android.widget.EditText' &&
        ['搜索模型或服务商', 'Search models or providers'].some(h => nodeLabel(n).includes(h) || field(n, 'hint').includes(h)));
      if (search) {
        tapNode(serial, search, adb);
        await sleep(300);
        const query = firstLine(label).replace(/[^A-Za-z0-9._-]/g, '');
        if (query) {
          adb(serial, ['shell', 'input', 'text', query]);
          await sleep(500);
          nodes = getUiDumpNodes(serial, adb);
          target = uniqueControl(nodes, [label, firstLine(label)]);
        }
      }
    }
    if (!target) {
      for (let page = 0; page < 4 && !target; page++) {
        try {
          scroll(serial, nodes, 'down', adb);
          await sleep(300);
        } catch { break; }
        nodes = getUiDumpNodes(serial, adb);
        target = uniqueControl(nodes, [label, firstLine(label)]);
      }
    }
    if (!target) throw new Error('GUI_BINDING_IDENTITY_UNPROVEN');
    tapNode(serial, target, adb);
    await sleep(600);
  }

  const read = await readGuiBinding(serial, {adb});
  if (label === null) {
    if (read.original !== null) throw new Error('GUI_BINDING_WRITE_UNCONFIRMED');
  } else {
    if (read.original !== label && firstLine(read.original || '') !== firstLine(label)) {
      throw new Error('GUI_BINDING_WRITE_UNCONFIRMED');
    }
  }
}

export async function newXiaowanConversation(serial, {adb = execAdb} = {}) {
  let nodes = getUiDumpNodes(serial, adb);
  if (uniqueControl(nodes, ['返回会话列表', 'Back to conversations'])) {
    await click(serial, ['返回会话列表', 'Back to conversations'], {adb});
    nodes = getUiDumpNodes(serial, adb);
  }
  const selectedAgent = uniqueControl(nodes, XIAOWAN_LABELS);
  if (!selectedAgent) {
    throw new Error('XIAOWAN_HOME_IDENTITY_UNPROVEN');
  }
  await click(serial, ['新对话', 'New conversation'], {adb});
  const arrived = getUiDumpNodes(serial, adb);
  if (!composerReady(arrived) || (!hasLabel(arrived, '新对话') && !hasLabel(arrived, 'New conversation'))) {
    throw new Error('NEW_XIAOWAN_CONVERSATION_UNPROVEN');
  }
}

export async function executeModelCase(serial, outDir) {
  const base = `OOB_LIVE_GUI_MODEL_${Date.now()}`;
  const phases = {};
  let originalModel, originalBinding, originalConversation, title, guiChanged = false, modelChanged = false;
  let result;
  try {
    originalConversation = chatTitle(getUiDumpNodes(serial));
    const picker = await openModelPicker(serial);
    originalModel = picker.selected;
    // 严禁硬编码特定产品名称；依据显式 runner 配置的两模型参数
    const explicitModelA = (typeof outDir === 'object' ? outDir.modelA : null) || process.env.OOB_MODEL_CASE_A;
    const explicitModelB = (typeof outDir === 'object' ? outDir.modelB : null) || process.env.OOB_MODEL_CASE_B;

    let modelA, modelB;
    if (explicitModelA && explicitModelB) {
      modelA = picker.choices.find(c => c === explicitModelA || firstLine(c) === explicitModelA);
      modelB = picker.choices.find(c => c === explicitModelB || firstLine(c) === explicitModelB);
      if (!modelA || !modelB || modelA === modelB) throw new Error('MODEL_CHOICES_UNPROVEN');
    } else {
      // 若未提供显式已证视觉能力模型配置，列表选项文字无法自证视觉能力，绝不盲目默认特定模型，明确返回阻塞交由实机调度
      throw new Error('MODEL_CHOICES_UNPROVEN');
    }
    await closePanelToComposer(serial);

    title = await enterSceneSettings(serial);
    const binding = await readGuiBinding(serial);
    originalBinding = binding.original;
    await selectGuiBinding(serial, null);
    guiChanged = true;
    await leaveSceneSettings(serial, title);
    title = null;

    for (const [phase, label] of [['default', modelA], ['second', modelB]]) {
      if (phase === 'default' && picker.selected !== modelA) {
        await chooseModel(serial, modelA);
        modelChanged = true;
      } else if (phase === 'second') {
        await chooseModel(serial, label);
        modelChanged = true;
      }
      await newXiaowanConversation(serial);
      const evidence = await runScenario(serial, outDir, 'model', phase, base + '_' + phase.toUpperCase(), 'phone-control-model-default.json');
      phases[phase] = evidence;
      // 配置字段不可读不等于动作失败；继续执行矩阵，但不将整体标为通过。
      if (evidence.status !== 'passed' && evidence.category !== 'model_configuration_evidence_insufficient') {
        result = {...evidence, phases};
        break;
      }
    }
    if (!result) {
      const firstResolved = phases.default.data?.details?.resolved_model;
      const secondResolved = phases.second.data?.details?.resolved_model;
      if (!firstResolved || !secondResolved) result = {...blocked('MODEL_ROUTE_COMPARISON_UNPROVEN'), phases};
      else if (firstResolved === secondResolved) result = {status: 'failed', category: 'MODEL_SWITCH_NOT_EFFECTIVE', phases};
    }
    if (!result) {
      title = await enterSceneSettings(serial);
      const dedicated = binding.choices[0];
      await selectGuiBinding(serial, dedicated);
      await leaveSceneSettings(serial, title);
      title = null;
      await newXiaowanConversation(serial);
      phases.binding = await runScenario(serial, outDir, 'model', 'binding', base + '_BINDING', 'phone-control-model-binding.json', {expectedModel: firstLine(dedicated)});
      result = {...phases.binding, phases};
      if (phases.binding.status === 'passed' && Object.values(phases).every(phase => phase.status === 'passed')) result = {status: 'passed', category: 'ok', phases};
    }
  } catch (err) {
    const category = err.message === 'MODEL_CHOICES_UNPROVEN' ? 'MODEL_CHOICES_UNPROVEN' : 'MODEL_UI_IDENTITY_UNPROVEN';
    result = {...blocked(category), phases};
  } finally {
    let restored = true;
    try {
      if (guiChanged) {
        if (!title) title = await enterSceneSettings(serial);
        await selectGuiBinding(serial, originalBinding);
        await leaveSceneSettings(serial, title);
        title = null;
      } else if (title) {
        await leaveSceneSettings(serial, title);
        title = null;
      }
      if (modelChanged && originalModel) {
        await chooseModel(serial, originalModel);
      }
      await closePanelToComposer(serial);
      if (originalConversation && composerReady(getUiDumpNodes(serial))) {
        try {
          await click(serial, ['返回会话列表', 'Back to conversations']);
          const convNodes = getUiDumpNodes(serial);
          const target = uniqueControl(convNodes, [originalConversation]);
          if (!target) throw new Error('CONVERSATION_RETURN_IDENTITY_MISSING');
          await click(serial, [originalConversation]);
        } catch {
          restored = false;
        }
      }
      if (!composerReady(getUiDumpNodes(serial))) restored = false;
    } catch {
      restored = false;
    }
    if (!restored) result = {...blocked('MODEL_SETTINGS_RESTORE_UNPROVEN'), phases};
  }
  return result;
}

export async function runVerification({deviceEnv, outDir, caseName, expectedRuntime = '', modelA = '', modelB = ''}, {preflight = runPreflight, execute = null} = {}) {
  mkdirSync(outDir, {recursive: true, mode: 0o700});
  const serial = process.env[deviceEnv] || '';
  const report = {deviceKind: serial.startsWith('emulator-') ? 'EMULATOR' : 'DEVICE', caseResults: {}, allPassed: false, overallStatus: 'blocked'};
  report.preflight = preflight(serial);
  const selected = caseName === 'all' ? CASES : [caseName];
  const runners = {interaction: executeInteractionCase, collection: executeCollectionCase, experience: executeExperienceCase, cancel: executeCancelCase, model: executeModelCase};
  let settled = report.preflight.ok;
  for (const name of selected) {
    if (!settled) {
      report.caseResults[name] = blocked(report.preflight.ok ? 'PREVIOUS_CASE_NOT_SETTLED' : report.preflight.category);
      continue;
    }
    try { report.caseResults[name] = await (execute || runners[name])(serial, {outDir, expectedRuntime, modelA, modelB}, name); }
    catch { report.caseResults[name] = {status: 'failed', category: 'CASE_EXECUTION_ERROR'}; }
    settled = report.caseResults[name].status === 'passed';
  }
  const results = Object.values(report.caseResults);
  report.allPassed = results.length === selected.length && results.every(result => result.status === 'passed');
  report.overallStatus = report.allPassed ? 'passed' : results.some(result => result.status === 'failed') ? 'failed' : 'blocked';
  writeFileSync(join(outDir, 'phone-control-verification-report.json'), JSON.stringify(report, null, 2), {mode: 0o600});
  return report;
}

export async function main() {
  const options = parseCliArgs();
  if (options.help) {
    console.log('用法: node scripts/verify-phone-control.mjs --device-env ANDROID_SERIAL --out <临时目录> --case all --expected-runtime <本次归档身份JSON>\n用例: interaction | collection | experience | cancel | model\n退出码: 0=通过，1=失败，3=前置或证据阻塞。物理设备还需 OOB_ALLOW_PHYSICAL_DEVICE=1。');
    return;
  }
  const report = await runVerification(options);
  console.log(JSON.stringify({deviceKind: report.deviceKind, overallStatus: report.overallStatus,
    cases: Object.fromEntries(Object.entries(report.caseResults).map(([name, result]) => [name, {status: result.status, category: result.category}]))}));
  process.exitCode = report.allPassed ? 0 : report.overallStatus === 'blocked' ? 3 : 1;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  main().catch(() => { console.error(JSON.stringify({status: 'failed', category: 'RUNNER_ERROR'})); process.exitCode = 1; });
}
