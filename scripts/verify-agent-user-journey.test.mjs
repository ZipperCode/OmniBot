import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync, writeFileSync, readFileSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {spawnSync} from 'node:child_process';

for (const deviceTime of ['1697425200', 'not-a-timestamp']) {
  test(`real-provider clock preflight rejects ${deviceTime} before any UI action`, () => {
    const directory = mkdtempSync(join(tmpdir(), 'oob-clock-preflight-'));
    try {
      const adb = join(directory, 'adb');
      const calls = join(directory, 'calls.jsonl');
      writeFileSync(adb, `#!/usr/bin/env node
const fs = require('node:fs');
fs.appendFileSync(${JSON.stringify(calls)}, JSON.stringify(process.argv.slice(2)) + "\\n");
if (process.argv.slice(2).join(' ') !== '-s emulator-1234 shell date +%s') process.exit(7);
process.stdout.write(${JSON.stringify(deviceTime)});
`, {mode: 0o700});
      const journey = join(directory, 'journey.json');
      writeFileSync(journey, JSON.stringify({name:'clock preflight unit fixture', requireClockSync:true,
        steps:[{action:'send', marker:'OOB_NEVER_SENT'}]}));
      const evidence = join(directory, 'evidence');
      const result = spawnSync(process.execPath, ['scripts/verify-agent-user-journey.mjs',
        'emulator-1234', journey, evidence], {env:{...process.env, ADB:adb}, encoding:'utf8'});
      assert.equal(result.status, 1);
      const report = JSON.parse(readFileSync(join(evidence,'result.json'), 'utf8'));
      assert.equal(report.passed, false);
      assert.equal(report.steps[0].index, 0);
      assert.match(report.steps[0].detail, /Device clock differs/);
      const observations = readFileSync(calls,'utf8').trim().split('\n').map(line => JSON.parse(line));
      assert.deepEqual(observations, [['-s','emulator-1234','shell','date','+%s']]);
    } finally { rmSync(directory, {recursive:true, force:true}); }
  });
}

const escapeXml = value => String(value).replaceAll('&','&amp;').replaceAll('"','&quot;')
  .replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('\n','&#10;');

for (const [name, target, expectedFailure] of [
  ['explicit unsent owner', {agentId:'claude-code-acp',mode:'agent',isNewConversation:true}, null],
  ['another draft owner', {agentId:'xiaowan-acp',mode:'agent',isNewConversation:true}, /Wrong draft conversation owner/],
  ['persisted conversation', {agentId:'claude-code-acp',mode:'agent',isNewConversation:true,conversationId:42}, /created durable history/],
  ['legacy selection without draft ownership', null, /Wrong draft conversation owner/],
]) {
  test(`draft acceptance checks conversation ownership: ${name}`, () => {
    const directory=mkdtempSync(join(tmpdir(),'oob-draft-owner-'));
    try {
      const marker='OOB_LIVE_SWITCH_DRAFT_12345';
      const scenario=JSON.parse(readFileSync('scripts/fixtures/user-scenarios/xiaowan-switch-reply.json','utf8'));
      const expected=`${scenario.prompt.replaceAll('{{MARKER}}',marker)} End your final reply with ${marker}_DONE.`;
      const xml=`<hierarchy><node package="cn.com.omnimind.bot" class="android.widget.EditText" text="${escapeXml(expected)}"/><node package="cn.com.omnimind.bot" text="Claude Code"/></hierarchy>`;
      const preferences=`<map><string name="flutter.last_visible_conversation_target">${escapeXml(JSON.stringify(target))}</string></map>`;
      const legacyPreferences='<map><string name="selected_profile_id">claude-code-acp</string></map>';
      const adb=join(directory,'adb');
      const calls=join(directory,'calls.jsonl');
      writeFileSync(adb,`#!/usr/bin/env node
const fs=require('node:fs');
const args=process.argv.slice(2);
fs.appendFileSync(${JSON.stringify(calls)},JSON.stringify(args)+"\\n");
const command=args.slice(2).join(' ');
if(command==='shell uiautomator dump /data/local/tmp/oob-user-journey.xml') process.stdout.write('UI hierarchy dumped to: /data/local/tmp/oob-user-journey.xml');
else if(command==='shell cat /data/local/tmp/oob-user-journey.xml') process.stdout.write(${JSON.stringify(xml)});
else if(command==='exec-out run-as cn.com.omnimind.bot cat shared_prefs/FlutterSharedPreferences.xml') process.stdout.write(${JSON.stringify(preferences)});
else if(command==='exec-out run-as cn.com.omnimind.bot cat shared_prefs/acp_agent_profiles.xml') process.stdout.write(${JSON.stringify(legacyPreferences)});
else if(command==='exec-out screencap -p') process.stdout.write('fixture-image');
else process.exit(7);
`,{mode:0o700});
      const journey=join(directory,'journey.json');
      writeFileSync(journey,JSON.stringify({name:'draft ownership fixture',steps:[{
        action:'assert-draft',marker,scenario:'xiaowan-switch-reply.json',agentId:'claude-code-acp',agentLabel:'Claude Code',
      }]}));
      const evidence=join(directory,'evidence');
      const result=spawnSync(process.execPath,['scripts/verify-agent-user-journey.mjs','emulator-1234',journey,evidence],
        {env:{...process.env,ADB:adb},encoding:'utf8'});
      const report=JSON.parse(readFileSync(join(evidence,'result.json'),'utf8'));
      assert.equal(result.status,expectedFailure ? 1 : 0,JSON.stringify(report));
      assert.equal(report.passed,!expectedFailure);
      if(expectedFailure) assert.match(report.steps[0].detail,expectedFailure);
      else {
        const draft=JSON.parse(readFileSync(join(evidence,'1-draft.json'),'utf8'));
        assert.equal(draft.draftAgentId,'claude-code-acp');
        assert.equal(draft.visibleAgentLabel,'Claude Code');
      }
      const observations=readFileSync(calls,'utf8').trim().split('\n').map(line=>JSON.parse(line));
      assert(observations.every(args=>!args.includes('shared_prefs/acp_agent_profiles.xml')));
      assert(observations.every(args=>!args.includes('input')),'Draft verification must not mutate UI');
    } finally {rmSync(directory,{recursive:true,force:true});}
  });
}

test('lsposed preflight rejects missing validation target and enforces zero deployment', () => {
  const directory = mkdtempSync(join(tmpdir(), 'oob-lsposed-preflight-'));
  try {
    const adb = join(directory, 'adb');
    const calls = join(directory, 'calls.jsonl');
    writeFileSync(adb, `#!/usr/bin/env node
const fs = require('node:fs');
const args = process.argv.slice(2);
fs.appendFileSync(${JSON.stringify(calls)}, JSON.stringify(args) + "\\n");
const command = args.slice(2).join(' ');
if (command === 'get-state') {
  process.stdout.write('device');
} else if (command === 'shell pm list packages') {
  process.stdout.write('package:com.android.calculator2\\n');
} else {
  process.exit(7);
}
`, {mode: 0o700});
    const journey = join(directory, 'journey.json');
    writeFileSync(journey, JSON.stringify({
      name: 'lsposed validation target preflight',
      requireLsposedValidationTarget: true,
      steps: [{action: 'precondition', check: 'lsposed-target-prefix', prefix: 'cn.com.omnimind.validation.'}]
    }));
    const evidence = join(directory, 'evidence');
    const result = spawnSync(process.execPath, ['scripts/verify-agent-user-journey.mjs',
      'emulator-1234', journey, evidence], {env: {...process.env, ADB: adb}, encoding: 'utf8'});
    assert.equal(result.status, 1);
    const report = JSON.parse(readFileSync(join(evidence, 'result.json'), 'utf8'));
    assert.equal(report.passed, false);
    assert.match(report.steps[0].detail, /PRECONDITION_FAILED: Dedicated validation target package/);
    const observations = readFileSync(calls, 'utf8').trim().split('\n').map(l => JSON.parse(l));
    assert(observations.every(args => !args.includes('install')));
    assert(observations.every(args => !args.includes('input')));
  } finally { rmSync(directory, {recursive: true, force: true}); }
});

test('provider preflight rejects unconfigured model provider on UI', () => {
  const directory = mkdtempSync(join(tmpdir(), 'oob-provider-preflight-'));
  try {
    const adb = join(directory, 'adb');
    const calls = join(directory, 'calls.jsonl');
    writeFileSync(adb, `#!/usr/bin/env node
const fs = require('node:fs');
const args = process.argv.slice(2);
fs.appendFileSync(${JSON.stringify(calls)}, JSON.stringify(args) + "\\n");
const command = args.slice(2).join(' ');
if (command === 'get-state') {
  process.stdout.write('device');
} else if (command === 'shell uiautomator dump /data/local/tmp/oob-user-journey.xml') {
  process.stdout.write('UI hierarchy dumped to: /data/local/tmp/oob-user-journey.xml');
} else if (command === 'shell cat /data/local/tmp/oob-user-journey.xml') {
  process.stdout.write('<hierarchy><node package="cn.com.omnimind.bot" content-desc="模型与参数" /></hierarchy>');
} else {
  process.exit(7);
}
`, {mode: 0o700});
    const journey = join(directory, 'journey.json');
    writeFileSync(journey, JSON.stringify({
      name: 'provider preflight test',
      requireProvider: true,
      steps: [{action: 'send', marker: 'OOB_UNSENT'}]
    }));
    const evidence = join(directory, 'evidence');
    const result = spawnSync(process.execPath, ['scripts/verify-agent-user-journey.mjs',
      'emulator-1234', journey, evidence], {env: {...process.env, ADB: adb}, encoding: 'utf8'});
    assert.equal(result.status, 1);
    const report = JSON.parse(readFileSync(join(evidence, 'result.json'), 'utf8'));
    assert.equal(report.passed, false);
    assert.match(report.steps[0].detail, /PRECONDITION_FAILED: ChatPage UI does not display a configured active model/);
  } finally { rmSync(directory, {recursive: true, force: true}); }
});

for (const [name, model, expected] of [
  ['已选择紧凑模型', 'deepseek-v4-pro', true],
  ['未选择模型', 'Model', false],
  ['历史文本不能代替当前选择', '', false],
]) {
  test(`模型前置检查：${name}`, () => {
    const directory = mkdtempSync(join(tmpdir(), 'oob-compact-model-'));
    try {
      const node = (description, bounds, clickable = true) =>
        `<node package="cn.com.omnimind.bot" content-desc="${description}" bounds="${bounds}" clickable="${clickable}" enabled="true"/>`;
      const xml = `<hierarchy>${node('附件与权限', '[20,200][60,244]')}${node('发送', '[300,200][344,244]')}` +
        node('deepseek-v4-pro', '[70,20][290,60]', false) +
        (model ? node(model, '[140,200][290,244]') : '') + '</hierarchy>';
      const adb = join(directory, 'adb');
      const calls = join(directory, 'calls.jsonl');
      writeFileSync(adb, `#!/usr/bin/env node
const fs = require('node:fs');
const args = process.argv.slice(2);
fs.appendFileSync(${JSON.stringify(calls)}, JSON.stringify(args) + "\\n");
const command = args.slice(2).join(' ');
if (command === 'get-state') process.stdout.write('device');
else if (command === 'shell uiautomator dump /data/local/tmp/oob-user-journey.xml') process.stdout.write('UI hierarchy dumped to: /data/local/tmp/oob-user-journey.xml');
else if (command === 'shell cat /data/local/tmp/oob-user-journey.xml') process.stdout.write(${JSON.stringify(xml)});
else if (command === 'exec-out screencap -p') process.stdout.write('fixture-image');
else process.exit(7);
`, {mode: 0o700});
      const journey = join(directory, 'journey.json');
      writeFileSync(journey, JSON.stringify({name, requireProvider: true,
        steps: [{action: 'precondition', check: 'provider-configured'}]}));
      const evidence = join(directory, 'evidence');
      const result = spawnSync(process.execPath, ['scripts/verify-agent-user-journey.mjs',
        'emulator-1234', journey, evidence], {env: {...process.env, ADB: adb}, encoding: 'utf8'});
      assert.equal(result.status, expected ? 0 : 1);
      const report = JSON.parse(readFileSync(join(evidence, 'result.json'), 'utf8'));
      assert.equal(report.passed, expected);
      const observations = readFileSync(calls, 'utf8').trim().split('\n').map(line => JSON.parse(line));
      assert(observations.every(args => !args.includes('input') && !args.includes('install')));
    } finally { rmSync(directory, {recursive: true, force: true}); }
  });
}

test('unreachable device fails device precondition before checking provider or actions', () => {
  const directory = mkdtempSync(join(tmpdir(), 'oob-unreachable-device-'));
  try {
    const adb = join(directory, 'adb');
    writeFileSync(adb, `#!/usr/bin/env node
process.stderr.write("error: device 'emulator-9999' not found\\n");
process.exit(1);
`, {mode: 0o700});
    const journey = join(directory, 'journey.json');
    writeFileSync(journey, JSON.stringify({
      name: 'unreachable device preflight',
      requireProvider: true,
      steps: [{action: 'send', marker: 'OOB_UNSENT'}]
    }));
    const evidence = join(directory, 'evidence');
    const result = spawnSync(process.execPath, ['scripts/verify-agent-user-journey.mjs',
      'emulator-9999', journey, evidence], {env: {...process.env, ADB: adb}, encoding: 'utf8'});
    assert.equal(result.status, 1);
    const report = JSON.parse(readFileSync(join(evidence, 'result.json'), 'utf8'));
    assert.equal(report.passed, false);
    assert.match(report.steps[0].detail, /PRECONDITION_FAILED: Device 'emulator-9999' is not reachable via adb/);
  } finally { rmSync(directory, {recursive: true, force: true}); }
});

test('workspace modify-file enforces workspace path boundary', () => {
  const directory = mkdtempSync(join(tmpdir(), 'oob-workspace-file-'));
  try {
    const adb = join(directory, 'adb');
    writeFileSync(adb, `#!/usr/bin/env node
process.exit(0);
`, {mode: 0o700});
    const journey = join(directory, 'journey.json');
    writeFileSync(journey, JSON.stringify({
      name: 'workspace path traversal guard',
      steps: [{action: 'modify-file', path: 'outside/path.txt', content: 'MALICIOUS'}]
    }));
    const evidence = join(directory, 'evidence');
    const result = spawnSync(process.execPath, ['scripts/verify-agent-user-journey.mjs',
      'emulator-1234', journey, evidence], {env: {...process.env, ADB: adb}, encoding: 'utf8'});
    assert.equal(result.status, 1);
    const report = JSON.parse(readFileSync(join(evidence, 'result.json'), 'utf8'));
    assert.equal(report.passed, false);
    assert.match(report.steps[0].detail, /Path must be within workspace/);
  } finally { rmSync(directory, {recursive: true, force: true}); }
});

test('queue action enters text into composer, taps 排队发送, and clears composer', () => {
  const directory = mkdtempSync(join(tmpdir(), 'oob-queue-test-'));
  try {
    const adb = join(directory, 'adb');
    const calls = join(directory, 'calls.jsonl');
    const expectedText = 'Reply OOB_QUEUE_TEST';
    writeFileSync(adb, `#!/usr/bin/env node
const fs = require('node:fs');
const args = process.argv.slice(2);
fs.appendFileSync(${JSON.stringify(calls)}, JSON.stringify(args) + "\\n");
const command = args.slice(2).join(' ');
if (command === 'shell uiautomator dump /data/local/tmp/oob-user-journey.xml') {
  process.stdout.write('UI hierarchy dumped to: /data/local/tmp/oob-user-journey.xml');
} else if (command === 'shell cat /data/local/tmp/oob-user-journey.xml') {
  const lines = fs.readFileSync(${JSON.stringify(calls)}, 'utf8').trim().split('\\n').filter(Boolean).map(l => JSON.parse(l));
  const typed = lines.filter(c => c[3] === 'input' && c[4] === 'text')
    .map(c => c[5].slice(1, -1).replaceAll('%s', ' ')).join('');
  const hasTappedQueue = lines.some(c => c.includes('tap') && c.includes('955'));
  const text = hasTappedQueue ? '' : typed;
  const buttonDesc = !typed ? '停止' : '排队发送';
  const xml = '<hierarchy><node package="cn.com.omnimind.bot" class="android.widget.EditText" text="' + text + '" bounds="[100,500][900,600]"/>' +
    '<node package="cn.com.omnimind.bot" content-desc="' + buttonDesc + '" clickable="true" enabled="true" bounds="[910,500][1000,600]"/></hierarchy>';
  process.stdout.write(xml);
} else if (command === 'exec-out screencap -p') {
  process.stdout.write('image-data');
} else {
  process.exit(0);
}
`, {mode: 0o700});
    const journey = join(directory, 'journey.json');
    writeFileSync(journey, JSON.stringify({
      name: 'queue action unit test',
      steps: [{action: 'queue', marker: 'OOB_QUEUE_TEST', text: expectedText}]
    }));
    const evidence = join(directory, 'evidence');
    const result = spawnSync(process.execPath, ['scripts/verify-agent-user-journey.mjs',
      'emulator-1234', journey, evidence], {env: {...process.env, ADB: adb}, encoding: 'utf8'});
    const report = JSON.parse(readFileSync(join(evidence, 'result.json'), 'utf8'));
    assert.equal(result.status, 0, JSON.stringify({stderr: result.stderr, report}));
    assert.equal(report.passed, true);
    assert.equal(report.steps[0].action, 'queue');
    assert.equal(report.steps[0].passed, true);
    const queueRecord = JSON.parse(readFileSync(join(evidence, '1-queue.json'), 'utf8'));
    assert.equal(queueRecord.queued, true);
    assert.equal(queueRecord.text, `Reply OOB_QUEUE_TEST_${report.runId}`);
  } finally { rmSync(directory, {recursive: true, force: true}); }
});

test('queue action rejects non-empty composer to preserve draft', () => {
  const directory = mkdtempSync(join(tmpdir(), 'oob-queue-draft-'));
  try {
    const adb = join(directory, 'adb');
    writeFileSync(adb, `#!/usr/bin/env node
const args = process.argv.slice(2);
const command = args.slice(2).join(' ');
if (command === 'shell uiautomator dump /data/local/tmp/oob-user-journey.xml') {
  process.stdout.write('UI hierarchy dumped to: /data/local/tmp/oob-user-journey.xml');
} else if (command === 'shell cat /data/local/tmp/oob-user-journey.xml') {
  const xml = '<hierarchy><node package="cn.com.omnimind.bot" class="android.widget.EditText" text="EXISTING_DRAFT" bounds="[100,500][900,600]"/>' +
    '<node package="cn.com.omnimind.bot" content-desc="停止" clickable="true" enabled="true" bounds="[910,500][1000,600]"/></hierarchy>';
  process.stdout.write(xml);
} else {
  process.exit(0);
}
`, {mode: 0o700});
    const journey = join(directory, 'journey.json');
    writeFileSync(journey, JSON.stringify({
      name: 'queue draft guard test',
      steps: [{action: 'queue', marker: 'OOB_QUEUE_TEST', text: 'Reply OOB'}]
    }));
    const evidence = join(directory, 'evidence');
    const result = spawnSync(process.execPath, ['scripts/verify-agent-user-journey.mjs',
      'emulator-1234', journey, evidence], {env: {...process.env, ADB: adb}, encoding: 'utf8'});
    assert.equal(result.status, 1);
    const report = JSON.parse(readFileSync(join(evidence, 'result.json'), 'utf8'));
    assert.equal(report.passed, false);
    assert.match(report.steps[0].detail, /Queue requires an empty composer; preserve existing draft/);
  } finally { rmSync(directory, {recursive: true, force: true}); }
});

test('queue action rejects idle task when no running task exists', () => {
  const directory = mkdtempSync(join(tmpdir(), 'oob-queue-idle-'));
  try {
    const adb = join(directory, 'adb');
    writeFileSync(adb, `#!/usr/bin/env node
const args = process.argv.slice(2);
const command = args.slice(2).join(' ');
if (command === 'shell uiautomator dump /data/local/tmp/oob-user-journey.xml') {
  process.stdout.write('UI hierarchy dumped to: /data/local/tmp/oob-user-journey.xml');
} else if (command === 'shell cat /data/local/tmp/oob-user-journey.xml') {
  const xml = '<hierarchy><node package="cn.com.omnimind.bot" class="android.widget.EditText" text="" bounds="[100,500][900,600]"/>' +
    '<node package="cn.com.omnimind.bot" content-desc="发送" clickable="true" enabled="true" bounds="[910,500][1000,600]"/></hierarchy>';
  process.stdout.write(xml);
} else {
  process.exit(0);
}
`, {mode: 0o700});
    const journey = join(directory, 'journey.json');
    writeFileSync(journey, JSON.stringify({
      name: 'queue idle guard test',
      steps: [{action: 'queue', marker: 'OOB_QUEUE_TEST', text: 'Reply OOB'}]
    }));
    const evidence = join(directory, 'evidence');
    const result = spawnSync(process.execPath, ['scripts/verify-agent-user-journey.mjs',
      'emulator-1234', journey, evidence], {env: {...process.env, ADB: adb}, encoding: 'utf8'});
    assert.equal(result.status, 1);
    const report = JSON.parse(readFileSync(join(evidence, 'result.json'), 'utf8'));
    assert.equal(report.passed, false);
    assert.match(report.steps[0].detail, /Queue requires an active running task/);
  } finally { rmSync(directory, {recursive: true, force: true}); }
});

for (const keyboardSelectAllWorks of [true, false]) {
  test(`set-input replaces the complete focused field when keyboard select-all ${keyboardSelectAllWorks ? 'works' : 'needs the visible menu'}`, () => {
    const directory = mkdtempSync(join(tmpdir(), 'oob-set-input-test-'));
    try {
      const adb = join(directory, 'adb');
      const stateFile = join(directory, 'state.json');
      const targetText = 'oob-candidate-helper-skill-12345';
      writeFileSync(adb, `#!/usr/bin/env node
const fs = require('node:fs');
const args = process.argv.slice(2);
const file = ${JSON.stringify(stateFile)};
const state = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : {text:'old-skill-name', focused:false, selected:false, menu:false, textCalls:0, observationsAfterTap:0};
const command = args.slice(2).join(' ');
if (command === 'shell uiautomator dump /data/local/tmp/oob-user-journey.xml') {
  process.stdout.write('UI hierarchy dumped to: /data/local/tmp/oob-user-journey.xml');
} else if (command === 'shell cat /data/local/tmp/oob-user-journey.xml') {
  if (state.observationsAfterTap > 0) { state.observationsAfterTap++; state.focused = state.observationsAfterTap >= 3; }
  process.stdout.write('<hierarchy><node package="cn.com.omnimind.bot" class="android.widget.EditText" text="' + state.text + '" focused="' + state.focused + '" hint="技能标识 (kebab-case)" bounds="[100,300][900,400]"/>' +
    (state.menu ? '<node package="cn.com.omnimind.bot" text="全选" clickable="true" enabled="true" bounds="[0,50][200,100]"/>' : '') + '</hierarchy>');
} else if (args[3] === 'input' && args[4] === 'tap') {
  if (Number(args[6]) >= 150 && state.observationsAfterTap === 0) state.observationsAfterTap = 1;
  if (state.menu && Number(args[6]) < 150) {state.selected = true; state.menu = false;}
} else if (args[3] === 'input' && args[4] === 'keycombination') {
  state.selected = ${keyboardSelectAllWorks};
} else if (args[3] === 'input' && args[4] === 'swipe') {
  state.menu = true;
} else if (args[3] === 'input' && args[4] === 'keyevent' && args[5] === '67') {
  state.text = state.selected ? '' : state.text.slice(0, -1);
  state.selected = false;
} else if (args[3] === 'input' && args[4] === 'text') {
  state.textCalls++;
  state.text += args.at(-1).slice(1, -1).replaceAll('%s', ' ');
} else if (command === 'exec-out screencap -p') {
  process.stdout.write('image-data');
}
fs.writeFileSync(file, JSON.stringify(state));
`, {mode: 0o700});
      const journey = join(directory, 'journey.json');
      writeFileSync(journey, JSON.stringify({
        name: '替换已存在的完整字段',
        steps: [{action: 'set-input', label: '技能标识 (kebab-case)', text: targetText}]
      }));
      const evidence = join(directory, 'evidence');
      const result = spawnSync(process.execPath, ['scripts/verify-agent-user-journey.mjs',
        'emulator-1234', journey, evidence], {env: {...process.env, ADB: adb}, encoding: 'utf8'});
      const report = JSON.parse(readFileSync(join(evidence, 'result.json'), 'utf8'));
      assert.equal(result.status, 0, JSON.stringify({stderr: result.stderr, report}));
      const state = JSON.parse(readFileSync(stateFile, 'utf8'));
      assert.equal(state.text, targetText);
      assert.equal(state.textCalls, 1);
    } finally { rmSync(directory, {recursive: true, force: true}); }
  });
}

test('set-input verifies uniquely focused editor when placeholder hint disappears after typing', () => {
  const directory = mkdtempSync(join(tmpdir(), 'oob-set-input-hint-clear-'));
  try {
    const adb = join(directory, 'adb');
    const stateFile = join(directory, 'state.json');
    const targetText = 'oob-candidate-helper-skill-12345';
    writeFileSync(adb, `#!/usr/bin/env node
const fs = require('node:fs');
const args = process.argv.slice(2);
const file = ${JSON.stringify(stateFile)};
const state = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : {text:'', hint:'搜索技能名称或描述', focused:false, textCalls:0, observationsAfterTap:0};
const command = args.slice(2).join(' ');
if (command === 'shell uiautomator dump /data/local/tmp/oob-user-journey.xml') {
  process.stdout.write('UI hierarchy dumped to: /data/local/tmp/oob-user-journey.xml');
} else if (command === 'shell cat /data/local/tmp/oob-user-journey.xml') {
  if (state.observationsAfterTap > 0) { state.observationsAfterTap++; state.focused = state.observationsAfterTap >= 3; }
  process.stdout.write('<hierarchy><node package="cn.com.omnimind.bot" class="android.widget.EditText" text="' + state.text + '" focused="' + state.focused + '" hint="' + state.hint + '" bounds="[100,100][900,200]"/></hierarchy>');
} else if (args[3] === 'input' && args[4] === 'tap') {
  if (state.observationsAfterTap === 0) state.observationsAfterTap = 1;
} else if (args[3] === 'input' && args[4] === 'text') {
  state.textCalls++;
  state.text = args.at(-1).slice(1, -1).replaceAll('%s', ' ');
  // 真机输入内容后占位 hint 变为空字符串
  state.hint = '';
} else if (command === 'exec-out screencap -p') {
  process.stdout.write('image-data');
}
fs.writeFileSync(file, JSON.stringify(state));
`, {mode: 0o700});
    const journey = join(directory, 'journey.json');
    writeFileSync(journey, JSON.stringify({
      name: '占位 hint 输入后消失仍精确校验',
      steps: [{action: 'set-input', hint: '搜索技能名称或描述', text: targetText}]
    }));
    const evidence = join(directory, 'evidence');
    const result = spawnSync(process.execPath, ['scripts/verify-agent-user-journey.mjs',
      'emulator-1234', journey, evidence], {env: {...process.env, ADB: adb}, encoding: 'utf8'});
    const report = JSON.parse(readFileSync(join(evidence, 'result.json'), 'utf8'));
    assert.equal(result.status, 0, JSON.stringify({stderr: result.stderr, report}));
    assert.equal(report.passed, true);
    const state = JSON.parse(readFileSync(stateFile, 'utf8'));
    assert.equal(state.text, targetText);
    assert.equal(state.hint, '');
    assert.equal(state.textCalls, 1);
  } finally { rmSync(directory, {recursive: true, force: true}); }
});

test('set-input rejects and preserves failure when focus switches to another editor', () => {
  const directory = mkdtempSync(join(tmpdir(), 'oob-set-input-focus-switch-'));
  try {
    const adb = join(directory, 'adb');
    const stateFile = join(directory, 'state.json');
    const targetText = 'oob-candidate-helper-skill-12345';
    writeFileSync(adb, `#!/usr/bin/env node
const fs = require('node:fs');
const args = process.argv.slice(2);
const file = ${JSON.stringify(stateFile)};
const state = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : {
  editor1Text: '',
  editor1Hint: '搜索技能名称或描述',
  editor1Focused: false,
  editor2Text: ${JSON.stringify(targetText)},
  editor2Hint: '其他输入框',
  editor2Focused: false,
  textCalls: 0,
  observationsAfterTap: 0
};
const command = args.slice(2).join(' ');
if (command === 'shell uiautomator dump /data/local/tmp/oob-user-journey.xml') {
  process.stdout.write('UI hierarchy dumped to: /data/local/tmp/oob-user-journey.xml');
} else if (command === 'shell cat /data/local/tmp/oob-user-journey.xml') {
  if (state.observationsAfterTap > 0 && state.observationsAfterTap < 3) {
    state.observationsAfterTap++;
    if (state.observationsAfterTap >= 3) state.editor1Focused = true;
  }
  process.stdout.write('<hierarchy>' +
    '<node package="cn.com.omnimind.bot" class="android.widget.EditText" text="' + state.editor1Text + '" focused="' + state.editor1Focused + '" hint="' + state.editor1Hint + '" bounds="[100,100][900,200]"/>' +
    '<node package="cn.com.omnimind.bot" class="android.widget.EditText" text="' + state.editor2Text + '" focused="' + state.editor2Focused + '" hint="' + state.editor2Hint + '" bounds="[100,300][900,400]"/>' +
    '</hierarchy>');
} else if (args[3] === 'input' && args[4] === 'tap') {
  if (state.observationsAfterTap === 0) state.observationsAfterTap = 1;
} else if (args[3] === 'input' && args[4] === 'text') {
  state.textCalls++;
  state.editor1Text = args.at(-1).slice(1, -1).replaceAll('%s', ' ');
  state.editor1Hint = '';
  // 输入后焦点意外转移到另一个输入框
  state.editor1Focused = false;
  state.editor2Focused = true;
} else if (command === 'exec-out screencap -p') {
  process.stdout.write('image-data');
}
fs.writeFileSync(file, JSON.stringify(state));
`, {mode: 0o700});
    const journey = join(directory, 'journey.json');
    writeFileSync(journey, JSON.stringify({
      name: '焦点转移到另一输入框必须失败',
      steps: [{action: 'set-input', hint: '搜索技能名称或描述', text: targetText, timeoutMs: 600}]
    }));
    const evidence = join(directory, 'evidence');
    const result = spawnSync(process.execPath, ['scripts/verify-agent-user-journey.mjs',
      'emulator-1234', journey, evidence], {env: {...process.env, ADB: adb}, encoding: 'utf8'});
    const report = JSON.parse(readFileSync(join(evidence, 'result.json'), 'utf8'));
    assert.notEqual(result.status, 0);
    assert.equal(report.passed, false);
    assert.match(report.steps[0].detail, /Target input field missing after typing/);
    const state = JSON.parse(readFileSync(stateFile, 'utf8'));
    // 焦点切换必须失败保留，绝不重复发送输入
    assert.equal(state.textCalls, 1);
  } finally { rmSync(directory, {recursive: true, force: true}); }
});


test('lsposed-evidence.zh.json blocks on missing validation target and records precondition failure', () => {
  const directory = mkdtempSync(join(tmpdir(), 'oob-lsposed-fixture-test-'));
  try {
    const adb = join(directory, 'adb');
    writeFileSync(adb, `#!/usr/bin/env node
const args = process.argv.slice(2);
const command = args.slice(2).join(' ');
if (command === 'get-state') {
  process.stdout.write('device');
} else if (command === 'shell date +%s') {
  process.stdout.write(String(Math.floor(Date.now() / 1000)));
} else if (command === 'shell pm list packages') {
  process.stdout.write('package:com.android.calculator2\\n');
} else {
  process.exit(7);
}
`, {mode: 0o700});
    const evidence = join(directory, 'evidence');
    const result = spawnSync(process.execPath, ['scripts/verify-agent-user-journey.mjs',
      'emulator-1234', 'scripts/fixtures/agent-user-journeys/lsposed-evidence.zh.json', evidence],
      {env: {...process.env, ADB: adb}, encoding: 'utf8'});
    assert.equal(result.status, 1);
    const report = JSON.parse(readFileSync(join(evidence, 'result.json'), 'utf8'));
    assert.equal(report.passed, false);
    assert.match(report.steps[0].detail, /PRECONDITION_FAILED: Dedicated validation target package/);
  } finally { rmSync(directory, {recursive: true, force: true}); }
});

test('outbox-and-result.zh.json blocks when model provider is not configured', () => {
  const directory = mkdtempSync(join(tmpdir(), 'oob-outbox-fixture-test-'));
  try {
    const adb = join(directory, 'adb');
    writeFileSync(adb, `#!/usr/bin/env node
const args = process.argv.slice(2);
const command = args.slice(2).join(' ');
if (command === 'get-state') {
  process.stdout.write('device');
} else if (command === 'shell date +%s') {
  process.stdout.write(String(Math.floor(Date.now() / 1000)));
} else if (command === 'shell uiautomator dump /data/local/tmp/oob-user-journey.xml') {
  process.stdout.write('UI hierarchy dumped to: /data/local/tmp/oob-user-journey.xml');
} else if (command === 'shell cat /data/local/tmp/oob-user-journey.xml') {
  process.stdout.write('<hierarchy><node package="cn.com.omnimind.bot" content-desc="模型与参数" /></hierarchy>');
} else {
  process.exit(7);
}
`, {mode: 0o700});
    const evidence = join(directory, 'evidence');
    const result = spawnSync(process.execPath, ['scripts/verify-agent-user-journey.mjs',
      'emulator-1234', 'scripts/fixtures/agent-user-journeys/outbox-and-result.zh.json', evidence],
      {env: {...process.env, ADB: adb}, encoding: 'utf8'});
    assert.equal(result.status, 1);
    const report = JSON.parse(readFileSync(join(evidence, 'result.json'), 'utf8'));
    assert.equal(report.passed, false);
    assert.match(report.steps[0].detail, /PRECONDITION_FAILED: ChatPage UI does not display a configured active model/);
  } finally { rmSync(directory, {recursive: true, force: true}); }
});
