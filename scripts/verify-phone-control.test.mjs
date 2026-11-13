import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync, readFileSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {
  parseCliArgs, getAdbEnv, runPreflight, queryFixtureState, sendMessage, tapControl,
  interpretAssertResponse, pollAssert, uniqueControl, modelPickerRows, experienceSwitch,
  runVerification, display0Component, composerReady, composerModelControl,
  isProviderHeader, openModelPicker, chooseModel, readGuiBinding, selectGuiBinding, closePanelToComposer,
  modelPickerSearchBox, clearAndInputText, guiSelector, tapGuiSelectorNode, tapSwitchNode, dismissResultOverlay,
  returnToChat,
} from './verify-phone-control.mjs';
import {uiXmlField as field} from './agent-ui-xml.mjs';

const marker = 'OOB_LIVE_GUI_CONSUMER_TEST';
const context = {marker, caseName: 'interaction', phase: 'default'};
const result = (status = 'passed', extra = {}) => ({status, passed: status === 'passed', marker,
  case: 'interaction', phase: 'default', category: status === 'passed' ? 'ok' : 'waiting_for_sealed_run_log', ...extra});
const response = (status, data) => ({status, stdout: JSON.stringify(data), stderr: ''});
const escape = value => String(value).replaceAll('&', '&amp;').replaceAll('"', '&quot;').replaceAll('\n', '&#10;');
const node = ({label = '', text = '', rect = [20, 20, 200, 70], ...attributes} = {}) => '<node ' + Object.entries({
  package: 'cn.com.omnimind.bot', 'content-desc': label, text, clickable: 'true', enabled: 'true', password: 'false',
  class: 'android.widget.Button', bounds: `[${rect[0]},${rect[1]}][${rect[2]},${rect[3]}]`, ...attributes,
}).map(([key, value]) => `${key}="${escape(value)}"`).join(' ') + '/>';
const chat = [node({label: '小万', rect: [20, 250, 160, 310]}),
  node({text: '', class: 'android.widget.EditText', rect: [10, 400, 400, 500]})];
const foreground = 'Display #0 (activities from top to bottom):\n  mResumedActivity: ActivityRecord{abc cn.com.omnimind.bot/.MainActivity}\n';

function preflightAdb(overrides = {}) {
  const values = {
    'get-state': 'device',
    'shell getprop ro.build.version.sdk': '37',
    'shell pm path cn.com.omnimind.bot': 'package:/test/main.apk',
    'shell pm path cn.com.omnimind.androidgui.test': 'package:/test/fixture.apk',
    'shell settings get secure enabled_accessibility_services': 'cn.com.omnimind.bot/test.Service',
    'shell dumpsys activity activities': foreground,
    'shell dumpsys display': 'Display Devices: 1\n DisplayDeviceInfo{"Built-in": type BUILT_IN}\nVirtualDisplayAdapter',
    'shell uiautomator dump /data/local/tmp/oob-phone-control-ui.xml': 'UI hierarchy dumped to: /data/local/tmp/oob-phone-control-ui.xml',
    'shell cat /data/local/tmp/oob-phone-control-ui.xml': '<hierarchy>' + chat.join('') + '</hierarchy>',
    ...overrides,
  };
  return (_serial, args) => {
    const value = values[args.join(' ')];
    if (value instanceof Error) throw value;
    if (value === undefined) throw new Error('非预期设备命令');
    return value;
  };
}

function outputDirectory(t) {
  const directory = mkdtempSync(join(tmpdir(), 'phone-control-consumer-'));
  t.after(() => rmSync(directory, {recursive: true, force: true}));
  return directory;
}

test('非法CLI和缺参数在访问设备前拒绝', () => {
  for (const args of [['--case', 'unknown'], ['--out'], ['--unknown'], ['--device-env', 'bad=name']]) {
    assert.throws(() => parseCliArgs(args), /CLI_ARGUMENT_INVALID/);
  }
});

test('ADB绝对路径只影响返回的子进程PATH', () => {
  const original = process.env.PATH;
  const scoped = getAdbEnv({ADB: join(tmpdir(), 'platform-tools', 'adb'), PATH: 'sentinel'});
  assert.match(scoped.PATH, /platform-tools/);
  assert.equal(process.env.PATH, original);
});

test('无设备与未授权物理设备均不执行ADB', t => {
  const original = process.env.OOB_ALLOW_PHYSICAL_DEVICE;
  delete process.env.OOB_ALLOW_PHYSICAL_DEVICE;
  t.after(() => { if (original === undefined) delete process.env.OOB_ALLOW_PHYSICAL_DEVICE; else process.env.OOB_ALLOW_PHYSICAL_DEVICE = original; });
  const adb = () => { assert.fail('不得访问设备'); };
  assert.equal(runPreflight('', {adb}).category, 'DEVICE_ENV_UNSET');
  assert.equal(runPreflight('DEVICE_TEST', {adb}).category, 'PHYSICAL_DEVICE_NOT_ALLOWED');
});

test('目录中的小万显示名可准入，其他Agent同名历史文字不能替代当前composer', () => {
  const empty = node({text: '', class: 'android.widget.EditText', rect: [10, 400, 400, 500]});
  assert.equal(composerReady([node({label: '小万', rect: [20, 250, 160, 310]}), empty]), true);
  assert.equal(composerReady([node({label: 'Xiaowan', rect: [20, 250, 160, 310]}), empty]), true);
  assert.equal(composerReady([node({label: '其他 Agent', rect: [20, 250, 160, 310]}), empty]), false);
  assert.equal(composerReady([node({label: '小万', rect: [20, 20, 100, 70]}), empty]), false);
});

test('实际composer模型短名无tooltip时仍按当前输入区定位，不点历史正文', () => {
  const editor = node({class: 'android.widget.EditText', rect: [20, 400, 980, 500]});
  const model = node({label: 'model-a', rect: [600, 520, 850, 600]});
  const send = node({label: '发送', rect: [870, 520, 970, 600], enabled: 'false'});
  const history = node({label: 'model-a', rect: [20, 100, 800, 200]});
  assert.equal(composerModelControl([editor, model, send, history]), model);
  assert.equal(composerModelControl([editor, model, model, send]), null);
});

test('只有物理屏且存在虚拟屏适配器不误判为活跃虚拟屏', () => {
  const value = runPreflight('emulator-5588', {adb: preflightAdb()});
  assert.equal(value.ok, true);
  assert.equal(value.details.sdkLevel, 37);
});

test('名为idle的真实虚拟屏仍阻塞，探测失败也不放行', () => {
  const occupied = preflightAdb({'shell dumpsys display': 'DisplayDeviceInfo{"idle": type VIRTUAL}'});
  assert.equal(runPreflight('emulator-5588', {adb: occupied}).category, 'EXISTING_VD_OCCUPIED');
  const unreadable = preflightAdb({'shell dumpsys display': new Error('仅合成错误')});
  assert.equal(runPreflight('emulator-5588', {adb: unreadable}).category, 'PREFLIGHT_STATE_UNOBSERVABLE');
});

test('明确归属其他普通应用的虚拟屏可共存，本应用、未知或特权所有者仍阻塞', () => {
  const display = owner => preflightAdb({'shell dumpsys display': `DisplayDeviceInfo{"合成屏": type VIRTUAL, ${owner}}`});
  assert.equal(runPreflight('emulator-5588', {adb: display('owner test.external.app (uid 10123)')}).ok, true);
  for (const owner of ['owner cn.com.omnimind.bot (uid 10123)', 'owner cn.com.omnimind.androidgui.test (uid 10124)',
    'owner test.external.app (uid 1000)', 'owner test.external.app', '无所有权证据']) {
    assert.equal(runPreflight('emulator-5588', {adb: display(owner)}).category, 'EXISTING_VD_OCCUPIED');
  }
});

test('主应用后台堆栈或其他显示屏不能证明主屏前台', () => {
  const background = 'Display #0:\n Hist #1 cn.com.omnimind.bot/.MainActivity\n mResumedActivity: ActivityRecord{abc test.launcher/.Home}\nDisplay #2:\n mResumedActivity: ActivityRecord{abc cn.com.omnimind.bot/.MainActivity}';
  assert.equal(display0Component(background), 'test.launcher');
  assert.equal(runPreflight('emulator-5588', {adb: preflightAdb({'shell dumpsys activity activities': background})}).category, 'CHAT_FOREGROUND_UNPROVEN');
});

test('无障碍读取失败、UI空树、草稿、运行中和错误Agent均不放行', () => {
  const xmlKey = 'shell cat /data/local/tmp/oob-phone-control-ui.xml';
  for (const nodes of [[], [chat[0], node({class: 'android.widget.EditText', text: 'SYNTHETIC_DRAFT'})],
    [...chat, node({label: '停止'})], [node({label: '其他 Agent'}), chat[1]]]) {
    assert.equal(runPreflight('emulator-5588', {adb: preflightAdb({[xmlKey]: nodes.join('')})}).ok, false);
  }
  assert.equal(runPreflight('emulator-5588', {adb: preflightAdb({'shell settings get secure enabled_accessibility_services': new Error('unreadable')})}).ok, false);
});

test('真实Provider单json列解析，错误列和多行证据拒绝', () => {
  const data = {schema_version: 'omnibot.gui-fixture.v1', marker, display_id: 2, counts: {confirm: 0}};
  assert.deepEqual(queryFixtureState('EMULATOR', () => 'Row: 0 json=' + JSON.stringify(data)), data);
  assert.equal(queryFixtureState('EMULATOR', () => 'Row: 0 state=' + JSON.stringify(data)), null);
  assert.equal(queryFixtureState('EMULATOR', () => 'Row: 0 json=' + JSON.stringify(data) + '\nRow: 1 json=' + JSON.stringify(data)), null);
});

test('发送只调用一次，缺准入回执不假成功也不泄漏原始错误', () => {
  let calls = 0;
  const rejected = sendMessage('DEVICE_TEST', marker, 'synthetic.json', {spawn: () => {
    calls++;
    return {status: 1, stdout: 'SYNTHETIC_PRIVATE_DRAFT', stderr: 'SYNTHETIC_PRIVATE_DRAFT'};
  }});
  assert.equal(calls, 1);
  assert.deepEqual(rejected, {ok: false, category: 'SEND_FAILED'});
  assert.equal(sendMessage('DEVICE_TEST', marker, 'synthetic.json', {spawn: () => response(0, {marker, draftPrepared: true})}).ok, false);
});

test('发送接受真实回执，且不提升物理授权、不让旧sender外写草稿截图', t => {
  const previous = process.env.OOB_ALLOW_PHYSICAL_DEVICE;
  delete process.env.OOB_ALLOW_PHYSICAL_DEVICE;
  t.after(() => { if (previous === undefined) delete process.env.OOB_ALLOW_PHYSICAL_DEVICE; else process.env.OOB_ALLOW_PHYSICAL_DEVICE = previous; });
  const accepted = sendMessage('DEVICE_TEST', marker, 'synthetic.json', {spawn: (_binary, _args, options) => {
    assert.equal(options.env.OOB_ALLOW_PHYSICAL_DEVICE, undefined);
    assert.equal(options.env.OOB_SEND_EVIDENCE_DIR, undefined);
    return response(0, {serial: 'DEVICE_TEST', marker, sendDispatched: true});
  }});
  assert.deepEqual(accepted, {ok: true});
});

test('真实tap消费者只导出请求控件白名单，错身份或非JSON不能通过', () => {
  const value = tapControl('DEVICE_TEST', 'Stop', {spawn: () => response(0,
    {control: 'Stop', tapped: true, gesture: 'tap', serial: 'DEVICE_TEST', coordinates: [1, 2]})});
  assert.deepEqual(value, {ok: true, data: {control: 'Stop', tapped: true, gesture: 'tap'}});
  assert.equal(tapControl('DEVICE_TEST', 'Stop', {spawn: () => response(0, {control: 'Send', tapped: true, gesture: 'tap'})}).ok, false);
  assert.equal(tapControl('DEVICE_TEST', 'Stop', {spawn: () => ({status: 0, stdout: 'not-json'})}).ok, false);
});

test('断言退出码和结构、marker、phase必须一致，无假passed回退', () => {
  assert.equal(interpretAssertResponse(response(0, result()), context).status, 'passed');
  for (const proc of [{status: 0, stdout: ''}, response(0, result('passed', {marker: 'OOB_LIVE_GUI_OLD'})),
    response(0, result('passed', {phase: 'other'})), response(0, result('blocked')), response(3, result())]) {
    assert.equal(interpretAssertResponse(proc, context).category, 'ASSERT_RESPONSE_INVALID');
  }
});

test('轮询只重读pending，明确失败后不再次调用', async () => {
  let calls = 0;
  const value = await pollAssert({...context, serial: 'EMULATOR', pollIntervalMs: 0, spawn: () => {
    calls++;
    return calls === 1 ? response(2, result('pending')) : response(1, result('failed', {category: 'wait_condition_not_met'}));
  }});
  assert.equal(calls, 2);
  assert.equal(value.status, 'failed');
  assert.equal(value.category, 'wait_condition_not_met');
});

test('UI仅点击唯一可用控件，前缀不默认匹配且不选背景和其他包', () => {
  const stop = node({label: '停止'});
  assert.equal(uniqueControl([stop], ['停止']), stop);
  assert.equal(uniqueControl([stop, stop], ['停止']), null);
  assert.equal(uniqueControl([node({label: '停止后继续'})], ['停止']), null);
  assert.equal(uniqueControl([node({label: '停止', package: 'test.other'})], ['停止']), null);
  assert.equal(uniqueControl([node({label: '停止', enabled: 'false'})], ['停止']), null);
});

test('模型候选限定真实菜单区域，不把消息、服务商标题或配置入口当模型', () => {
  const model = node({label: 'model-a', selected: 'true', rect: [20, 150, 290, 200]});
  const nodes = [node({label: '搜索模型或服务商', class: 'android.widget.EditText', rect: [20, 80, 290, 130]}),
    node({label: '模型服务商\n34', selected: 'true', rect: [20, 132, 290, 149]}), model,
    node({label: 'model-b', rect: [20, 210, 290, 260]}),
    node({label: '配置模型连接', rect: [20, 270, 290, 320]}), node({label: '背景消息', rect: [10, 500, 400, 700]})];
  assert.deepEqual(modelPickerRows(nodes), [model, nodes[3]]);
  assert.deepEqual(modelPickerRows(nodes.filter(n => n !== nodes[0])), []);
});

test('经验开关按真实label同一行定位，不点击裸文字或其他条目开关', () => {
  const label = node({label: '启用该应用经验', clickable: 'false', rect: [20, 100, 250, 160]});
  const toggle = node({label: '', class: 'android.widget.Switch', checkable: 'true', checked: 'true', rect: [270, 105, 330, 155]});
  const unrelated = node({label: '', class: 'android.widget.Switch', checkable: 'true', checked: 'false', rect: [270, 200, 330, 250]});
  assert.equal(experienceSwitch([label, toggle, unrelated]), toggle);
  assert.equal(experienceSwitch([label, toggle, toggle]), null);
  assert.equal(experienceSwitch([label, unrelated]), null);
});

test('未指定设备时五用例全blocked，报告不保存设备原值', async t => {
  const outDir = outputDirectory(t);
  const variable = 'OOB_PHONE_CONSUMER_UNSET';
  const before = process.env[variable];
  delete process.env[variable];
  t.after(() => { if (before !== undefined) process.env[variable] = before; });
  const report = await runVerification({deviceEnv: variable, outDir, caseName: 'all'});
  assert.equal(report.allPassed, false);
  assert.deepEqual(Object.keys(report.caseResults), ['interaction', 'collection', 'experience', 'cancel', 'model']);
  assert.ok(Object.values(report.caseResults).every(item => item.status === 'blocked' && item.category === 'DEVICE_ENV_UNSET'));
  assert.equal(JSON.parse(readFileSync(join(outDir, 'phone-control-verification-report.json'), 'utf8')).overallStatus, 'blocked');
});

test('用例未收尾不继续发送下一任务，也不把未执行用例标通过', async t => {
  const outDir = outputDirectory(t);
  let calls = 0;
  const report = await runVerification({deviceEnv: 'OOB_PHONE_CONSUMER_UNSET', outDir, caseName: 'all'}, {
    preflight: () => ({ok: true}), execute: async () => { calls++; return {status: 'failed', category: 'ASSERT_TIMEOUT'}; },
  });
  assert.equal(calls, 1);
  assert.equal(report.allPassed, false);
  assert.equal(report.caseResults.collection.category, 'PREVIOUS_CASE_NOT_SETTLED');
  assert.equal(report.caseResults.model.status, 'blocked');
});

test('provider header selected 时不误当作模型候选或已选模型', async () => {
  assert.equal(isProviderHeader('DeepSeek 真机验收\n34'), true);
  assert.equal(isProviderHeader('deepseek-v4-pro'), false);
  const search = node({label: '搜索模型 ID', hint: '搜索模型 ID', class: 'android.widget.EditText', rect: [20, 80, 290, 130]});
  const header = node({label: 'DeepSeek 真机验收\n34', selected: 'true', rect: [20, 132, 290, 149]});
  const modelA = node({label: 'deepseek-v4-pro', selected: 'false', rect: [20, 150, 290, 200]});
  const modelB = node({label: 'deepseek-chat', selected: 'true', rect: [20, 210, 290, 260]});
  const footer = node({label: '配置模型连接', rect: [20, 270, 290, 320]});
  const nodes = [search, header, modelA, modelB, footer];

  const rows = modelPickerRows(nodes);
  assert.deepEqual(rows, [modelA, modelB]);

  const xml = '<hierarchy>' + nodes.join('') + '</hierarchy>';
  const adb = (_serial, args) => {
    if (args.join(' ').includes('dump')) return 'dumped to: /data/local/tmp/oob-phone-control-ui.xml';
    if (args.join(' ').includes('cat')) return xml;
    return '';
  };
  const picker = await openModelPicker('EMULATOR', {adb});
  assert.equal(picker.selected, 'deepseek-chat');
  assert.ok(!picker.choices.includes('DeepSeek 真机验收\n34'));
});

test('selected 模型在当前可视范围之外时不误判假阻塞，滚动后正确定位', async () => {
  const search = node({label: '搜索模型 ID', hint: '搜索模型 ID', class: 'android.widget.EditText', rect: [20, 80, 290, 130]});
  const footer = node({label: '配置模型连接', rect: [20, 270, 290, 320]});
  const container = node({class: 'android.widget.ScrollView', scrollable: 'true', rect: [20, 130, 290, 270]});
  const page1Model = node({label: 'model-page1', selected: 'false', rect: [20, 140, 290, 190]});
  const page2Model = node({label: 'model-page2', selected: 'true', rect: [20, 140, 290, 190]});

  let page = 1;
  const adb = (_serial, args) => {
    const cmd = args.join(' ');
    if (cmd.includes('dump')) return 'dumped to: /data/local/tmp/oob-phone-control-ui.xml';
    if (cmd.includes('cat')) {
      const current = page === 1 ? [search, container, page1Model, footer] : [search, container, page2Model, footer];
      return '<hierarchy>' + current.join('') + '</hierarchy>';
    }
    if (cmd.includes('swipe')) {
      page = 2;
      return '';
    }
    return '';
  };
  const picker = await openModelPicker('EMULATOR', {adb});
  assert.equal(picker.selected, 'model-page2');
  assert.deepEqual(picker.choices.sort(), ['model-page1', 'model-page2'].sort());
});

test('同名短名跨 Provider 存在时，依据 selected 属性精确辨识真实选中项', async () => {
  const search = node({label: '搜索模型 ID', hint: '搜索模型 ID', class: 'android.widget.EditText', rect: [20, 80, 290, 130]});
  const footer = node({label: '配置模型连接', rect: [20, 350, 290, 400]});
  const prov1 = node({label: 'Provider One\n1', rect: [20, 135, 290, 160]});
  const m1 = node({label: 'shared-name', selected: 'false', rect: [20, 165, 290, 210]});
  const prov2 = node({label: 'Provider Two\n1', rect: [20, 220, 290, 245]});
  const m2 = node({label: 'shared-name', selected: 'true', rect: [20, 250, 290, 295]});
  const nodes = [search, prov1, m1, prov2, m2, footer];

  const xml = '<hierarchy>' + nodes.join('') + '</hierarchy>';
  const adb = (_serial, args) => {
    if (args.join(' ').includes('dump')) return 'dumped to: /data/local/tmp/oob-phone-control-ui.xml';
    if (args.join(' ').includes('cat')) return xml;
    return '';
  };
  const picker = await openModelPicker('EMULATOR', {adb});
  assert.equal(picker.selected, 'shared-name');
  assert.equal(picker.rows.filter(r => field(r, 'selected') === 'true')[0], m2);
});

test('仅单一可用模型时清晰返回 choices 且不满足双模型时不误选，找不到目标不修改', async () => {
  const search = node({label: '搜索模型 ID', hint: '搜索模型 ID', class: 'android.widget.EditText', rect: [20, 80, 290, 130]});
  const footer = node({label: '配置模型连接', rect: [20, 270, 290, 320]});
  const onlyModel = node({label: 'only-model', selected: 'true', rect: [20, 150, 290, 200]});
  const xml = '<hierarchy>' + [search, onlyModel, footer].join('') + '</hierarchy>';
  const commands = [];
  const adb = (_serial, args) => {
    commands.push(args.join(' '));
    if (args.join(' ').includes('dump')) return 'dumped to: /data/local/tmp/oob-phone-control-ui.xml';
    if (args.join(' ').includes('cat')) return xml;
    return '';
  };
  const picker = await openModelPicker('EMULATOR', {adb});
  assert.equal(picker.selected, 'only-model');
  assert.deepEqual(picker.choices, ['only-model']);

  await assert.rejects(
    () => chooseModel('EMULATOR', 'non-existent-model', {adb}),
    /MODEL_SELECTION_UNCONFIRMED/
  );
  assert.ok(!commands.some(cmd => cmd.includes('tap 0 0')));
});

test('GUI 场景 binding 正确识别继承与专用状态，未配置或继承时识别为 null', async () => {
  const inheritedSelector = node({label: '继承：DeepSeek / deepseek-chat', rect: [200, 20, 350, 70]});
  const search = node({label: '搜索模型或服务商', hint: '搜索模型或服务商', class: 'android.widget.EditText', rect: [20, 80, 290, 130]});
  const optionA = node({label: 'deepseek-chat\nDeepSeek', selected: 'false', rect: [20, 140, 290, 190]});
  const optionB = node({label: 'gpt-4o\nOpenAI', selected: 'false', rect: [20, 200, 290, 250]});
  const sceneLabel = node({label: 'GUI 操作', rect: [20, 20, 150, 70]});

  let inPopup = false;
  const adb = (_serial, args) => {
    const cmd = args.join(' ');
    if (cmd.includes('dump')) return 'dumped to: /data/local/tmp/oob-phone-control-ui.xml';
    if (cmd.includes('cat')) {
      const current = inPopup ? [search, optionA, optionB] : [sceneLabel, inheritedSelector];
      return '<hierarchy>' + current.join('') + '</hierarchy>';
    }
    if (cmd.includes('tap')) {
      inPopup = true;
      return '';
    }
    if (cmd.includes('keyevent 4')) {
      inPopup = false;
      return '';
    }
    return '';
  };
  const binding = await readGuiBinding('EMULATOR', {adb});
  assert.equal(binding.original, null);
  assert.deepEqual(binding.choices, ['deepseek-chat\nDeepSeek', 'gpt-4o\nOpenAI']);
});

test('搜索框输入后hint消失仍由弹窗结构定位且排除背景composer', () => {
  const footer = node({label: '配置模型连接', rect: [366, 850, 807, 910]});
  // 真实真机输入文本后 hint 变空
  const popupSearch = node({label: 'deepseek-v4-pro', text: 'deepseek-v4-pro', hint: '', class: 'android.widget.EditText', rect: [366, 471, 807, 526]});
  // 背景 composer 的 EditText
  const backgroundComposer = node({label: '', text: '', hint: '描述你的任务…', class: 'android.widget.EditText', rect: [105, 1188, 975, 1259]});
  const modelRow = node({label: 'deepseek-v4-pro', selected: 'true', rect: [366, 540, 807, 600]});

  const nodes = [backgroundComposer, popupSearch, modelRow, footer];
  const foundSearch = modelPickerSearchBox(nodes, footer);
  assert.equal(foundSearch, popupSearch);

  const rows = modelPickerRows(nodes);
  assert.deepEqual(rows, [modelRow]);

});

test('模型显示名含空格时搜索仍能选择并回读同一模型', async () => {
  const targetLabel = 'Model Beta 5';
  let selected = 'Model Alpha';
  let query = '';
  let popup = true;
  const adb = (_serial, args) => {
    if (args.includes('dump')) return 'dumped to: /data/local/tmp/oob-phone-control-ui.xml';
    if (args.includes('cat')) {
      if (!popup) return '<hierarchy>' + [
        ...chat,
        node({label: selected, rect: [220, 510, 300, 550]}),
      ].join('') + '</hierarchy>';
      const labels = query
        ? [targetLabel, 'Model Alpha'].filter(label => label.toLowerCase().includes(query.toLowerCase()))
        : [selected, 'Other Model'];
      return '<hierarchy>' + [
        node({text: query, hint: '搜索模型 ID', class: 'android.widget.EditText', rect: [20, 80, 290, 130]}),
        ...labels.map((label, index) => node({label, selected: String(label === selected),
          rect: [20, 150 + index * 60, 290, 200 + index * 60]})),
        node({label: '配置模型连接', rect: [20, 350, 290, 400]}),
      ].join('') + '</hierarchy>';
    }
    if (args[2] === 'keyevent') {
      if (args[3] === '4') popup = false;
      else query = '';
    } else if (args[2] === 'text') {
      query = args[3].replaceAll('%s', ' ');
    } else if (args[2] === 'tap') {
      if (!popup) popup = true;
      else if (query === targetLabel && Number(args[4]) >= 150 && Number(args[4]) <= 200) selected = targetLabel;
    }
    return '';
  };
  await chooseModel('EMULATOR', targetLabel, {adb});
  assert.equal(selected, targetLabel);
});

test('目标Provider折叠时自动展开以暴露真实model行，必须选定真实model', async () => {
  const search = node({label: '搜索模型 ID', hint: '搜索模型 ID', class: 'android.widget.EditText', rect: [366, 471, 807, 526]});
  const collapsedHeader = node({label: 'DeepSeek 真机验收\n34', selected: 'true', rect: [366, 530, 807, 580]});
  const actualModel = node({label: 'deepseek-v4-pro', selected: 'true', rect: [366, 590, 807, 650]});
  const otherModel = node({label: 'deepseek-chat', selected: 'false', rect: [366, 660, 807, 720]});
  const footer = node({label: '配置模型连接', rect: [366, 850, 807, 910]});

  let expanded = false;
  const adb = (_serial, args) => {
    const cmd = args.join(' ');
    if (cmd.includes('dump')) return 'dumped to: /data/local/tmp/oob-phone-control-ui.xml';
    if (cmd.includes('cat')) {
      const current = expanded
        ? [search, collapsedHeader, actualModel, otherModel, footer]
        : [search, collapsedHeader, footer];
      return '<hierarchy>' + current.join('') + '</hierarchy>';
    }
    if (cmd.includes('tap') && !expanded) {
      expanded = true;
      return '';
    }
    return '';
  };

  const picker = await openModelPicker('EMULATOR', {adb});
  assert.equal(picker.selected, 'deepseek-v4-pro');
  assert.ok(picker.choices.includes('deepseek-v4-pro'));
  assert.ok(picker.choices.includes('deepseek-chat'));
  assert.ok(!picker.choices.includes('DeepSeek 真机验收\n34'));
});

test('设置页逗号合并项支持精确首段识别，GUI卡片整行无child按钮时点击右侧pill区域', async () => {
  // 1. 设置项逗号合并测试
  const settingsRow = node({label: '场景模型配置, 按场景配置基础与高级模型绑定', rect: [58, 567, 1022, 714]});
  assert.equal(uniqueControl([settingsRow], ['场景模型配置']), settingsRow);

  // 2. GUI 行大卡片节点测试
  const guiCard = node({
    label: 'GUI 操作\nProvider / deepseek-v4-pro\n当前模型不支持此场景，请重新选择模型或确认能力标记',
    rect: [100, 1066, 980, 1268],
  });
  const xml = '<hierarchy>' + [guiCard].join('') + '</hierarchy>';
  const adb = (_serial, args) => {
    if (args.join(' ').includes('dump')) return 'dumped to: /data/local/tmp/oob-phone-control-ui.xml';
    if (args.join(' ').includes('cat')) return xml;
    return '';
  };
  const selector = await guiSelector('EMULATOR', {adb});
  assert.equal(selector, guiCard);

  // 验证点击位置落入右侧 pill 区域（x 约 769，y 约 1143），避开中心 540
  const tapCmds = [];
  tapGuiSelectorNode('EMULATOR', guiCard, (_serial, args) => tapCmds.push(args.join(' ')));
  assert.equal(tapCmds.length, 1);
  const [tx, ty] = tapCmds[0].split(' ').slice(3).map(Number);
  assert.ok(tx >= 760 && tx <= 775, `tx: ${tx} 应在右侧 pill 区域`);
  assert.ok(ty >= 1130 && ty <= 1150, `ty: ${ty} 应在 pill 垂直区域`);
});

test('GUI 专用绑定在 popup 无法确证 selected 时严格抛错阻止误改', async () => {
  const guiCard = node({label: 'GUI 操作\nProvider / deepseek-v4-pro\n不支持', rect: [100, 1066, 980, 1268]});
  const search = node({label: '搜索模型或服务商', hint: '搜索模型或服务商', class: 'android.widget.EditText', rect: [20, 80, 290, 130]});
  const followAgent = node({label: '跟随主 Agent', selected: 'false', rect: [20, 140, 290, 190]});
  const otherOpt = node({label: 'eligible-model\nProvider', selected: 'false', rect: [20, 200, 290, 250]});

  let inPopup = false;
  const adb = (_serial, args) => {
    const cmd = args.join(' ');
    if (cmd.includes('dump')) return 'dumped to: /data/local/tmp/oob-phone-control-ui.xml';
    if (cmd.includes('cat')) {
      const current = inPopup ? [search, followAgent, otherOpt] : [guiCard];
      return '<hierarchy>' + current.join('') + '</hierarchy>';
    }
    if (cmd.includes('tap')) { inPopup = true; return ''; }
    if (cmd.includes('keyevent 4')) { inPopup = false; return ''; }
    return '';
  };

  await assert.rejects(
    () => readGuiBinding('EMULATOR', {adb}),
    /GUI_BINDING_IDENTITY_UNPROVEN/
  );
});

test('经验开关支持包含label和switch的单行复合节点并精准点击右侧图形区域', () => {
  const row = node({
    label: '启用该应用经验\n在相同界面作为操作参考',
    class: 'android.widget.Switch',
    checkable: 'true',
    checked: 'true',
    rect: [42, 918, 1038, 1107],
  });
  assert.equal(experienceSwitch([row]), row);

  const tapCmds = [];
  tapSwitchNode('EMULATOR', row, (_serial, args) => tapCmds.push(args.join(' ')));
  assert.equal(tapCmds.length, 1);
  const [tx, ty] = tapCmds[0].split(' ').slice(3).map(Number);
  assert.ok(tx >= 900 && tx <= 960, `tx: ${tx} 应在右侧开关区域`);
  assert.ok(ty >= 1000 && ty <= 1025, `ty: ${ty} 应在垂直中心区域`);
});

test('dismissResultOverlay 存在留在当前时点击并确认消失，未消失或无控件严格受控', async () => {
  const stayNode = node({label: '留在当前', rect: [100, 1800, 500, 1900]});
  const composer = node({class: 'android.widget.EditText', text: '', rect: [20, 1900, 980, 2000]});

  let clicked = false;
  const adb = (_serial, args) => {
    const cmd = args.join(' ');
    if (cmd.includes('dump')) return 'dumped to: /data/local/tmp/oob-phone-control-ui.xml';
    if (cmd.includes('cat')) {
      return '<hierarchy>' + (clicked ? [composer] : [composer, stayNode]).join('') + '</hierarchy>';
    }
    if (cmd.includes('tap')) {
      clicked = true;
      return '';
    }
    return '';
  };

  const dismissed = await dismissResultOverlay('EMULATOR', {adb});
  assert.equal(dismissed, true);
  assert.equal(clicked, true);

  // 无浮层时返回 false
  const noOverlay = await dismissResultOverlay('EMULATOR', {adb});
  assert.equal(noOverlay, false);
});

test('returnToChat 无法确证原会话身份时严格抛错 CONVERSATION_RETURN_IDENTITY_MISSING', async () => {
  const composer = node({class: 'android.widget.EditText', text: '', rect: [20, 500, 400, 600]});
  const xiaowanPill = node({label: '小万', rect: [20, 250, 160, 310]});
  const wrongTitle = node({label: '其他不相关会话', rect: [150, 20, 500, 70]});
  const chatBack = node({label: '返回会话列表', rect: [20, 20, 100, 70]});

  const adb = (_serial, args) => {
    const cmd = args.join(' ');
    if (cmd.includes('dump')) return 'dumped to: /data/local/tmp/oob-phone-control-ui.xml';
    if (cmd.includes('cat')) {
      return '<hierarchy>' + [chatBack, xiaowanPill, wrongTitle, composer].join('') + '</hierarchy>';
    }
    return '';
  };

  await assert.rejects(
    () => returnToChat('EMULATOR', {adb, expectedTitle: '目标测试会话'}),
    /CONVERSATION_RETURN_IDENTITY_MISSING/
  );
});

test('returnToChat 识别真实合并的小万标题语义，不使用历史正文冒充', async () => {
  const xml = '<hierarchy>' + [
    node({label: '返回会话列表', rect: [37, 121, 163, 247]}),
    node({label: '小万\n新对话', class: 'android.widget.ImageView', clickable: 'false', rect: [0, 0, 1080, 2400]}),
    node({label: '小万', rect: [63, 998, 295, 1124]}),
    node({class: 'android.widget.EditText', rect: [105, 1188, 975, 1259]}),
  ].join('') + '</hierarchy>';
  const commands = [];
  const adb = (_serial, args) => {
    commands.push(args.join(' '));
    return args.includes('cat') ? xml : 'UI hierarchy dumped to: /data/local/tmp/oob-phone-control-ui.xml';
  };
  await returnToChat('EMULATOR', {adb, expectedTitle: '新对话'});
  assert.equal(commands.some(command => command.includes('input keyevent')), false);
});

test('parseCliArgs 支持 --model-a 与 --model-b 参数解析', () => {
  const parsed = parseCliArgs(['--case', 'model', '--model-a', 'Model Alpha', '--model-b', 'Model Beta']);
  assert.equal(parsed.modelA, 'Model Alpha');
  assert.equal(parsed.modelB, 'Model Beta');
});


