#!/usr/bin/env node
import {uiXmlField as field} from './agent-ui-xml.mjs';
import {assertMcpFixturePhase} from './mcp-fixture-observation.mjs';
// Execute a maintained UI journey on an isolated Android emulator.
// Every action uses current accessibility bounds. No ACP calls, DB writes,
// synthetic replies, retrying sends, or coordinate fallbacks count as UI acceptance.
import {execFileSync} from 'node:child_process';
import {readFileSync, mkdirSync, writeFileSync} from 'node:fs';
import {resolve, dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';

const [serial, journeyPath, outputPath] = process.argv.slice(2);
assert((/^emulator-\d+$/.test(serial || '') ||
  (process.env.OOB_ALLOW_PHYSICAL_DEVICE === '1' && /^[A-Za-z0-9._:-]+$/.test(serial || ''))) &&
  journeyPath && outputPath,
  'Usage: verify-agent-user-journey.mjs SERIAL journey.json evidence-directory; physical devices require OOB_ALLOW_PHYSICAL_DEVICE=1');
const scripts = dirname(fileURLToPath(import.meta.url));
const journey = JSON.parse(readFileSync(journeyPath, 'utf8'));
assert(Array.isArray(journey.steps) && journey.steps.length, 'Journey requires steps');
// Old successful history must never satisfy a new run's reply assertion.
const runId = Date.now().toString();
const checkpoints = new Map();
const markers = new Map(journey.steps
  .filter(s => ['send', 'prepare-draft', 'queue'].includes(s.action) && typeof s.marker === 'string')
  .map(s => [s.marker, `${s.marker}_${runId}`]));
const markerNames = [...markers.keys()].sort((a, b) => b.length - a.length);
const markerPattern = markerNames.length
  ? new RegExp(markerNames.map(value => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|'), 'g')
  : null;
for (const step of journey.steps) {
  for (const [key, value] of Object.entries(step)) {
    if (typeof value === 'string') {
      const expanded = value.replaceAll('{{RUN_ID}}', runId);
      step[key] = markerPattern
        ? expanded.replace(markerPattern, original => markers.get(original))
        : expanded;
    }
  }
}
const out = resolve(outputPath);
mkdirSync(out, {recursive: true, mode: 0o700});
const shellQuote = value => "'" + value.replaceAll("'", "'\\''") + "'";
const adb = (...args) => {
  const binary = process.env.ADB || 'adb';
  if (process.platform === 'win32' && binary !== 'adb' && !binary.toLowerCase().endsWith('.exe')) {
    return execFileSync(process.execPath, [binary, '-s', serial, ...args],
      {timeout: 30000, maxBuffer: 32 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe']});
  }
  return execFileSync(binary, ['-s', serial, ...args],
    {timeout: 30000, maxBuffer: 32 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe']});
};

function snapshot() {
  assert.match(adb('shell', 'uiautomator', 'dump', '/data/local/tmp/oob-user-journey.xml').toString(),
    /dumped to:/, 'Fresh accessibility snapshot unavailable');
  return [...adb('shell', 'cat', '/data/local/tmp/oob-user-journey.xml').toString()
    .matchAll(/<node\b[^>]*>/g)].map(([n]) => n)
    .filter(n => field(n, 'package') === 'cn.com.omnimind.bot');
}
const nodeLabels = n => [field(n, 'content-desc'), field(n, 'text'), field(n, 'hint')].filter(Boolean);
const label = n => field(n, 'content-desc') || field(n, 'text') || field(n, 'hint') || '';
function configuredModelIndicator(nodes) {
  const explicit = nodes.find(n => {
    const value = label(n).trim();
    return /^(?:模型与参数：|Model & settings:)\s*\S/.test(value);
  });
  if (explicit) return explicit;

  // 紧凑输入区只暴露模型名；仅接受附件与发送按钮之间同一行的唯一控件。
  const bounds = n => [...field(n, 'bounds').matchAll(/\d+/g)].map(([v]) => Number(v));
  const attachments = nodes.filter(n => ['附件与权限', 'Attachments & permissions'].includes(label(n)));
  const sends = nodes.filter(n => ['发送', 'Send', '排队发送', 'Queue send', '停止', 'Stop'].includes(label(n)));
  if (attachments.length !== 1 || sends.length !== 1) return null;
  const left = bounds(attachments[0]);
  const right = bounds(sends[0]);
  if (left.length !== 4 || right.length !== 4 || left[2] >= right[0]) return null;
  const candidates = nodes.filter(n => {
    const value = label(n).trim();
    if (field(n, 'clickable') !== 'true' || field(n, 'enabled') !== 'true' ||
        !/^[A-Za-z0-9][A-Za-z0-9._:/+()-]{0,255}$/.test(value) ||
        /^(?:model|select|default|auto)$/i.test(value)) return false;
    const area = bounds(n);
    return area.length === 4 && area[0] >= left[2] && area[2] <= right[0] &&
      area[1] < Math.min(left[3], right[3]) && area[3] > Math.max(left[1], right[1]);
  });
  return candidates.length === 1 ? candidates[0] : null;
}
const report = {name: journey.name, serial, runId,
  kind: serial.startsWith('emulator-') ? 'emulator-user-interface' : 'physical-device-user-interface',
  passed: false, steps: []};
let index = 0;
let mcpBaseline;
try {
  if (journey.requireDevice || journey.requireProvider || journey.requireLsposedValidationTarget) {
    let deviceState = '';
    try {
      deviceState = adb('get-state').toString().trim();
    } catch (err) {
      assert.fail(`PRECONDITION_FAILED: Device '${serial}' is not reachable via adb: ${err.message}`);
    }
    assert.equal(deviceState, 'device', `PRECONDITION_FAILED: Device '${serial}' state is '${deviceState}', expected 'device'`);
  }

  if (journey.requireClockSync) {
    const deviceSeconds = Number(adb('shell', 'date', '+%s').toString().trim());
    assert(Number.isFinite(deviceSeconds) && Math.abs(deviceSeconds - Date.now() / 1000) < 300,
      'Device clock differs by more than five minutes; correct test environment before real TLS requests');
  }
  if (journey.requireLsposedValidationTarget) {
    const targetPrefix = journey.requireLsposedValidationTargetPrefix || 'cn.com.omnimind.validation.';
    const modulePrefix = journey.requireLsposedValidationModulePrefix || 'cn.com.omnimind.validation.module';
    const lines = adb('shell', 'pm', 'list', 'packages').toString().split('\n')
      .map(l => l.trim().replace(/^package:/, '').trim())
      .filter(Boolean);
    const hasTarget = lines.some(pkg => pkg.startsWith(targetPrefix) && !pkg.startsWith(modulePrefix));
    assert(hasTarget,
      `PRECONDITION_FAILED: Dedicated validation target package starting with '${targetPrefix}' is not installed on device; zero deployment enforced`);
    const deployedModule = lines.find(pkg => pkg === modulePrefix || pkg.startsWith(modulePrefix + '.'));
    assert(!deployedModule,
      `PRECONDITION_FAILED: Unauthorized module '${deployedModule}' is already installed; zero deployment boundary violated`);
  }
  if (journey.requireProvider) {
    const nodes = snapshot();
    const modelIndicator = configuredModelIndicator(nodes);
    assert(modelIndicator != null,
      `PRECONDITION_FAILED: ChatPage UI does not display a configured active model on device '${serial}'; select a model in UI before running journey`);
  }
  for (const step of journey.steps) {
    index++;
    const started = Date.now();
    if (step.action === 'mcp-fixture') {
      const deadline=Date.now()+20000;
      while(true) {
        const response=await fetch('http://127.0.0.1:29091/observations', {signal:AbortSignal.timeout(3000)});
        assert(response.ok,'MCP fixture observations unavailable');
        const observation=await response.json();
        if(step.phase==='baseline') {mcpBaseline=observation.events.length;break;}
        assert(Number.isInteger(mcpBaseline),'MCP baseline must precede send');
        try {
          const verified=assertMcpFixturePhase(observation,mcpBaseline,step.phase);
          writeFileSync(resolve(out,`${index}-mcp-fixture.json`),JSON.stringify(verified,null,2));
          break;
        } catch(error) {if(Date.now()>=deadline) throw error;}
        await new Promise(resolve=>setTimeout(resolve,250));
      }
    } else if (step.action === 'tap' || step.action === 'long-press') {
      let tapped = false;
      if (step.matchIndex != null) {
        const matches = snapshot().filter(n =>
          field(n, 'clickable') === 'true' && field(n, 'enabled') === 'true' &&
          nodeLabels(n).some(value => value === step.label))
          .sort((a, b) => Number(field(a, 'bounds').match(/\d+/g)?.[1]) - Number(field(b, 'bounds').match(/\d+/g)?.[1]));
        assert.equal(matches.length, step.expectedMatches, 'Fixture control count changed; no tap performed');
        assert(Number.isInteger(step.matchIndex) && step.matchIndex >= 0 && step.matchIndex < matches.length);
        const bounds = [...field(matches[step.matchIndex], 'bounds').matchAll(/\d+/g)].map(([v]) => Number(v));
        adb('shell', 'input', 'tap', String(Math.round((bounds[0] + bounds[2]) / 2)),
          String(Math.round((bounds[1] + bounds[3]) / 2)));
        tapped = true;
      } else {
      try {
        execFileSync(process.execPath, [resolve(scripts, 'tap-agent-device-control.mjs'), serial, step.label, step.parentLabel || '', ...(step.action === 'long-press' ? ['long-press'] : [])],
          {timeout: 40000, stdio: ['ignore', 'pipe', 'pipe']});
        tapped = true;
      } catch (tapErr) {
        const nodes = snapshot();
        const clickable = nodes.filter(n => field(n, 'package') === 'cn.com.omnimind.bot' &&
          field(n, 'enabled') === 'true' && (field(n, 'clickable') === 'true' || field(n, 'long-clickable') === 'true'));
        let matches = clickable.filter(n => {
          const labels = nodeLabels(n);
          return labels.some(l => l === step.label || l.split('\n')[0] === step.label || l.split('\n').includes(step.label));
        });
        if (matches.length === 0) {
          matches = clickable.filter(n => nodeLabels(n).some(l => l.includes(step.label)));
        }
        if (step.parentLabel) {
          const parent = nodes.find(n => label(n).split('\n')[0] === step.parentLabel);
          if (parent) {
            const pb = [...field(parent, 'bounds').matchAll(/\d+/g)].map(([v]) => Number(v));
            matches = matches.filter(n => {
              const b = [...field(n, 'bounds').matchAll(/\d+/g)].map(([v]) => Number(v));
              return b[0] >= pb[0] && b[1] >= pb[1] && b[2] <= pb[2] && b[3] <= pb[3];
            });
          }
        }
        assert.equal(matches.length, 1, `Expected one matching clickable control for '${step.label}' (found ${matches.length}); original error: ${tapErr.message}`);
        const b = [...field(matches[0], 'bounds').matchAll(/\d+/g)].map(([v]) => Number(v));
        const x = String(Math.round((b[0] + b[2]) / 2));
        const y = String(Math.round((b[1] + b[3]) / 2));
        if (step.action === 'long-press') {
          adb('shell', 'input', 'swipe', x, y, x, y, '900');
        } else {
          adb('shell', 'input', 'tap', x, y);
        }
        tapped = true;
      }
      }
      assert(tapped, `Failed to tap control: ${step.label}`);
    } else if (step.action === 'dismiss-settings') {
      const nodes=snapshot();
      assert(nodes.some(n=>label(n)==='Next turn') && nodes.some(n=>label(n)==='Model & settings'), 'Expected Agent settings sheet');
      adb('shell','input','keyevent','4');
      const returned=snapshot();
      assert(returned.some(n=>field(n,'class')==='android.widget.EditText') && !returned.some(n=>label(n)==='Next turn'), 'System back did not close settings while preserving the conversation');
    } else if (step.action === 'dismiss-permissions') {
      const nodes = snapshot();
      assert(nodes.some(n => label(n) === 'Read only') && nodes.some(n => label(n) === 'Workspace write'), 'Expected permission popup');
      adb('shell', 'input', 'keyevent', '4');
      const returned = snapshot();
      assert(returned.some(n => field(n, 'class') === 'android.widget.EditText') && !returned.some(n => label(n) === 'Read only'), 'System back did not close permissions while preserving the conversation');
    } else if (step.action === 'clear-command-draft') {
      const inputs = snapshot().filter(n => field(n,'class') === 'android.widget.EditText');
      assert.equal(inputs.length,1);
      assert.equal(field(inputs[0],'text'), '/', 'Only the command-menu slash may be cleared');
      assert.equal(field(inputs[0],'focused'),'true');
      adb('shell','input','keyevent','67');
      assert(snapshot().some(n => field(n,'class') === 'android.widget.EditText' && field(n,'text') === ''));
    } else if (step.action === 'assert-draft') {
      const scenario=JSON.parse(readFileSync(resolve(scripts,'fixtures','user-scenarios',step.scenario),'utf8'));
      const expected=`${scenario.prompt.replaceAll('{{MARKER}}',step.marker)} End your final reply with ${step.marker}_DONE.`;
      const nodes=snapshot();
      const inputs=nodes.filter(n=>field(n,'class')==='android.widget.EditText');
      assert.equal(inputs.length,1,'Expected unique draft input');
      assert.equal(field(inputs[0],'text'),expected,'Switch changed the draft');
      assert.equal(typeof step.agentLabel,'string','Draft owner requires a visible Agent label');
      assert(nodes.some(n=>label(n).split('\n').includes(step.agentLabel)),'Expected draft Agent label is not visible');
      const target=JSON.parse(execFileSync('python3',['-c',
        "import sys,json,xml.etree.ElementTree as E; r=E.fromstring(sys.stdin.read()); " +
        "raw=next((n.text for n in r if n.get('name')=='flutter.last_visible_conversation_target'),'null'); " +
        "target=json.loads(raw) or {}; print(json.dumps({k:target.get(k) for k in ('agentId','mode','isNewConversation','conversationId')}))"],
        {input:adb('exec-out','run-as','cn.com.omnimind.bot','cat','shared_prefs/FlutterSharedPreferences.xml'),encoding:'utf8'}));
      assert.equal(target.agentId,step.agentId,'Wrong draft conversation owner');
      assert.equal(target.isNewConversation,true,'Expected an unsent conversation draft');
      assert(target.conversationId == null || target.conversationId === 0,'Draft selection created durable history before send');
      writeFileSync(resolve(out,`${index}-draft.json`),JSON.stringify({passed:true,marker:step.marker,draftAgentId:target.agentId,mode:target.mode,visibleAgentLabel:step.agentLabel,characters:expected.length}));
    } else if (step.action === 'search') {
      assert(/^[A-Za-z0-9._-]+$/.test(step.text), 'Search only accepts a non-private model ID');
      const inputs = snapshot().filter(n => field(n, 'class') === 'android.widget.EditText' &&
        (field(n, 'hint') === step.hint ||
          (field(n, 'focused') === 'true' && field(n, 'text') === step.text)) &&
        field(n, 'enabled') === 'true');
      assert.equal(inputs.length, 1, 'Expected one model search input');
      assert(['', step.text].includes(field(inputs[0], 'text')), 'Preserve existing search input');
      const b = [...field(inputs[0], 'bounds').matchAll(/\d+/g)].map(([n]) => Number(n));
      assert.equal(b.length, 4);
      adb('shell', 'input', 'tap', String(Math.round((b[0]+b[2])/2)), String(Math.round((b[1]+b[3])/2)));
      assert(snapshot().some(n => field(n, 'class') === 'android.widget.EditText' && field(n, 'focused') === 'true'),
        'Model search did not gain focus');
      if (!field(inputs[0], 'text')) adb('shell', 'input', 'text', step.text);
      assert(snapshot().some(n => field(n, 'class') === 'android.widget.EditText' &&
        field(n, 'focused') === 'true' && field(n, 'text') === step.text),
        'Search text was not entered correctly');
    } else if (step.action === 'open-xiaowan-session-list') {
      // The current Android tab strip lacks individual accessibility labels.
      // Derive its bounds from a fresh, unique horizontal scrollable surface;
      // this fixture selects Xiaowan's left tab, never stale screen coordinates.
      const findStrips = () => snapshot().filter(n => field(n,'scrollable') === 'true' && !label(n)).filter(n => {
          const b = [...field(n,'bounds').matchAll(/\d+/g)].map(([v]) => +v);
          return b.length === 4 && b[1] < 500 && (b[2]-b[0]) > 3*(b[3]-b[1]);
        });
      let strips = findStrips();
      const interactive = strips.filter(n => field(n,'clickable') === 'true');
      if (interactive.length) strips = interactive;
      assert.equal(strips.length,1,'Expected unique Xiaowan tab strip');
      if (field(strips[0],'clickable') !== 'true') {
        // Tool activity selects the tools layer. An upward gesture restores
        // the mode layer using the existing app-bar gesture.
        const region = [...field(strips[0],'bounds').matchAll(/\d+/g)].map(([v]) => +v);
        const x = String(Math.round((region[0]+region[2])/2));
        adb('shell','input','swipe',x,String(Math.round((region[1]+region[3])/2)),x,String(Math.max(0,region[1]-100)),'400');
        strips = findStrips().filter(n => field(n,'clickable') === 'true');
        assert.equal(strips.length,1);
        assert.equal(field(strips[0],'clickable'),'true','Mode layer did not become interactive');
      }
      const b = [...field(strips[0],'bounds').matchAll(/\d+/g)].map(([v]) => +v);
      adb('shell','input','tap',String(Math.round(b[0]+(b[2]-b[0])/4)),String(Math.round((b[1]+b[3])/2)));
    } else if (step.action === 'session-counters') {
      const deadline = Date.now() + (step.timeoutMs || 30000);
      while (true) {
        try {
          const verified = JSON.parse(execFileSync('python3', [resolve(scripts,'assert-agent-session-list.py'),serial,
            '--running',String(step.running),'--loaded',String(step.loaded),'--archived','0'],
            {encoding:'utf8',timeout:30000,stdio:['ignore','pipe','pipe']}));
          writeFileSync(resolve(out,`${index}-session-counters.json`),JSON.stringify(verified,null,2));
          break;
        } catch(error) { if(Date.now() >= deadline) throw error; }
        await new Promise(r => setTimeout(r,750));
      }
    } else if (['init','init-attachment-cancel','init-double-tap-cancel'].includes(step.action)) {
      const before = execFileSync('python3', ['-c',
        "from agent_test_database import agent_database_snapshot; import sys; " +
        "ctx=agent_database_snapshot(sys.argv[1]); db=ctx.__enter__(); " +
        "print(db.execute('SELECT coalesce(max(id),0) FROM agent_conversation_entries').fetchone()[0]); ctx.__exit__(None,None,None)", serial],
        {cwd:scripts,encoding:'utf8',timeout:30000}).trim();
      assert(!snapshot().some(n => /^(Stop|停止|停止生成)(\n|$)/.test(label(n))), 'Init requires idle composer');
      for (const control of (step.menuAlreadyOpen ? ['init'] : ['命令','init'])) {
        const readyDeadline=Date.now()+30000;
        while (!snapshot().some(n => label(n).split('\n')[0]===control &&
          field(n,'clickable')==='true' && field(n,'enabled')==='true')) {
          assert(Date.now()<readyDeadline, `Init control not ready: ${control}`);
          await new Promise(r=>setTimeout(r,500));
        }
        const gesture=control==='init' && step.action==='init-double-tap-cancel' ? ['','double-tap'] : [];
        const tapResult=execFileSync(process.execPath,[resolve(scripts,'tap-agent-device-control.mjs'),serial,control,...gesture],
          {timeout:40000,stdio:['ignore','pipe','pipe']});
        if (gesture.length) writeFileSync(resolve(out,`${index}-double-tap.json`),tapResult);
      }
      const deadline=Date.now()+(step.timeoutMs || 600000);
      const attachmentArgs=step.action==='init-attachment-cancel'
        ? ['--attachment','oob-attachment-admission.txt'] : [];
      const cancelInit=step.action!=='init';
      if (cancelInit) {
        while(true) {
          try {
            const active=execFileSync('python3',[resolve(scripts,'assert-agent-init.py'),serial,before,
              '--expected','active',...attachmentArgs],{encoding:'utf8',timeout:30000});
            writeFileSync(resolve(out,`${index}-init-active.json`),active);
            break;
          } catch(error) {if(error.status!==2 || Date.now()>=deadline) throw error;}
          await new Promise(r=>setTimeout(r,1000));
        }
        execFileSync(process.execPath,[resolve(scripts,'tap-agent-device-control.mjs'),serial,'Stop'],
          {timeout:40000,stdio:['ignore','pipe','pipe']});
      }
      while(true) {
        try {
          const result=execFileSync('python3',[resolve(scripts,'assert-agent-init.py'),serial,before,
            '--expected',cancelInit ? 'cancelled' : 'end_turn',...attachmentArgs],
            {encoding:'utf8',timeout:30000});
          writeFileSync(resolve(out,`${index}-init.json`),result);
          break;
        } catch(error) {
          if(error.status!==2 || Date.now()>=deadline) throw error;
        }
        await new Promise(r=>setTimeout(r,5000));
      }
      if (!cancelInit) {
        adb('shell','run-as','cn.com.omnimind.bot','test','-s','workspace/AGENTS.md');
        writeFileSync(resolve(out,`${index}-file.sha256`),
          adb('shell','run-as','cn.com.omnimind.bot','sha256sum','workspace/AGENTS.md'));
      }
    } else if (step.action === 'unsupported-command') {
      assert(['/pause', '/resume'].includes(step.command), 'Only unsupported lifecycle commands');
      const admissions = () => execFileSync('python3', ['-c',
        "from agent_test_database import agent_database_snapshot; import sys; " +
        "ctx=agent_database_snapshot(sys.argv[1]); db=ctx.__enter__(); " +
        "print(db.execute(\"SELECT count(*) FROM agent_conversation_entries WHERE entryType='user_message'\").fetchone()[0]); ctx.__exit__(None,None,None)", serial],
        {cwd: scripts, encoding: 'utf8', timeout: 30000}).trim();
      assert(!snapshot().some(n => /^(Stop|停止|停止生成)(\n|$)/.test(label(n))), 'Command check requires an idle turn');
      const before = admissions();
      execFileSync(process.execPath, [resolve(scripts, 'send-agent-test-message.mjs'), serial, step.command],
        {timeout: 90000, stdio: ['ignore', 'pipe', 'pipe']});
      assert(!snapshot().some(n => /^(Stop|停止|停止生成)(\n|$)/.test(label(n))), 'Unsupported command started a turn');
      assert.equal(admissions(), before, 'Unsupported command admitted a user prompt');
    } else if (step.action === 'compact') {
      execFileSync(process.execPath, [resolve(scripts, 'send-agent-test-message.mjs'), serial, '/compact'],
        {timeout: 90000, stdio: ['ignore', 'pipe', 'pipe']});
    } else if (step.action === 'turn-outcome') {
      // A visible final text chunk can precede PromptResponse and its durable
      // commit. Observe that completion; never resend the logical user turn.
      const deadline = Date.now() + 60000;
      let verified;
      while (!verified) {
        try {
          verified = JSON.parse(execFileSync('python3', [resolve(scripts, 'assert-agent-turn-outcome.py'), serial, step.marker, step.expected, ...(step.summary ? [step.summary] : [])],
            {encoding: 'utf8', timeout: 60000, stdio: ['ignore', 'pipe', 'pipe']}));
        } catch (error) {
          if (Date.now() >= deadline || !/Missing canonical completion|Missing canonical cancellation|Turn still loading|Missing current pending permission|Missing or mixed turn identity/.test(String(error.stderr))) throw error;
        }
      }
      assert(verified.passed, 'Canonical turn did not complete');
      if (step.agentId) {
        verified.owner = JSON.parse(execFileSync('python3', [resolve(scripts, 'assert-agent-prompt-owner.py'), serial, step.marker, step.agentId],
          {encoding: 'utf8', timeout: 60000, stdio: ['ignore', 'pipe', 'pipe']}));
      }
      writeFileSync(resolve(out, `${index}-turn-outcome.json`), JSON.stringify(verified, null, 2));
    } else if (step.action === 'workspace-file-absent') {
      assert(/^OOB_FAILURE_STREAMTOOL_\d+$/.test(step.marker), 'Only synthetic failed-tool output may be checked');
      adb('shell','run-as','cn.com.omnimind.bot','test','-d','workspace');
      adb('shell','run-as','cn.com.omnimind.bot','test','!','-e',`workspace/${step.marker}-must-not-exist.txt`);
    } else if (step.action === 'session-config') {
      const verified=JSON.parse(execFileSync('python3',[resolve(scripts,'assert-agent-session-config.py'),serial,step.marker,step.effort],{encoding:'utf8',timeout:60000}));
      assert(verified.passed);
      writeFileSync(resolve(out,`${index}-session-config.json`),JSON.stringify(verified));
    } else if (step.action === 'terminal-child') {
      assert(['started','stopped'].includes(step.state));
      const record = resolve(out, `${step.marker}-child.json`);
      const verified = JSON.parse(execFileSync('python3', [resolve(scripts, 'assert-terminal-child-state.py'), serial, step.marker, step.state, record], {encoding:'utf8',timeout:90000}));
      writeFileSync(resolve(out, `${index}-terminal-child.json`), JSON.stringify(verified));
    } else if (step.action === 'live-task') {
      const verified = JSON.parse(execFileSync('python3', [resolve(scripts, 'assert-xiaowan-live-task.py'), serial, step.marker, step.phase], {encoding: 'utf8', timeout: 60000}));
      writeFileSync(resolve(out, `${index}-live-task.json`), JSON.stringify(verified, null, 2));
    } else if (step.action === 'checkpoint') {
      const checkpoint = JSON.parse(execFileSync('python3', [resolve(scripts, 'assert-agent-context-checkpoint.py'), serial, step.marker], {encoding: 'utf8', timeout: 30000}));
      if (checkpoints.has(step.marker)) assert.deepEqual(checkpoint, checkpoints.get(step.marker), 'Checkpoint changed across restart');
      checkpoints.set(step.marker, checkpoint);
      writeFileSync(resolve(out, `${index}-checkpoint.json`), JSON.stringify(checkpoint, null, 2));
    } else if (step.action === 'send' || step.action === 'prepare-draft') {
      let observations;
      try {
        observations = execFileSync(process.execPath, [resolve(scripts, 'send-agent-test-message.mjs'), serial, step.marker],
        {timeout: step.timeoutMs || 90000, stdio: ['ignore', 'pipe', 'pipe'],
          env: {...process.env, OOB_PREPARE_DRAFT_ONLY:step.action==='prepare-draft'?'1':'0', OOB_USER_SCENARIO_RUN_ID: runId, ...(step.scenario ? {OOB_USER_SCENARIO_FILE: resolve(scripts, 'fixtures', 'user-scenarios', step.scenario)} : {})}});
      } catch (error) {
        observations = error.stdout;
        throw error;
      } finally {
        // The sender emits only synthetic markers and semantic bounds, never user content.
        if (observations) writeFileSync(resolve(out, `${index}-send.jsonl`), observations, {mode: 0o600});
      }
    } else if (step.action === 'expect' || step.action === 'reply') {
      const deadline = Date.now() + (step.timeoutMs || 120000);
      let found = false;
      let snapshotFailures = 0;
      do {
        let nodes;
        try {
          nodes = snapshot();
        } catch {
          // UIAutomator can be unavailable during window transitions. Repeat
          // observation only; never repeat a tap or send or reuse stale XML.
          snapshotFailures++;
          await new Promise(r => setTimeout(r, 750));
          continue;
        }
        const matching = nodes.filter(n => {
          const labels = nodeLabels(n);
          if (step.containsText) return labels.some(l => l.includes(step.containsText));
          return labels.some(l => l.split('\n').includes(step.text) && !l.includes(`Reply ${step.text}`));
        });
        const running = nodes.some(n => /^(Stop|停止|停止生成)(\n|$)/.test(label(n)));
        found = matching.length > 0 && (step.action !== 'reply' || !running);
        if (found) break;
        await new Promise(r => setTimeout(r, 750));
      } while (Date.now() < deadline);
      assert(found, `Expected visible result did not appear before deadline (${snapshotFailures} unavailable snapshots)`);
    } else if (step.action === 'assertAbsent') {
      const target = step.label || step.text;
      const matching = snapshot().filter(n => {
        const labels = nodeLabels(n);
        return labels.some(l => l === target || l.split('\n')[0] === target || l.includes(target)) &&
          field(n, 'enabled') === 'true' && field(n, 'clickable') === 'true';
      });
      assert.equal(matching.length, 0, `Stale interactive control remains: ${target}`);
    } else if (step.action === 'background') {
      assert(!snapshot().some(n => /^(Stop|停止|停止生成)(\n|$)/.test(label(n))), 'Parent turn must be idle');
      if (step.marker) {
        assert(/^OOB_LIVE_SCHEDULE_[0-9]+$/.test(step.marker));
        let exists = false;
        try { adb('shell','run-as','cn.com.omnimind.bot','test','-e',`workspace/oob-live-scheduled/${step.marker}.txt`); exists = true; }
        catch (error) { assert.equal(error.status, 1, 'Could not verify artifact absence'); }
        assert(!exists, 'Artifact appeared before background acceptance began');
      }
      adb('shell', 'input', 'keyevent', '3');
    } else if (step.action === 'scheduled-file') {
      assert(/^OOB_LIVE_SCHEDULE_[0-9]+$/.test(step.marker));
      const path = `workspace/oob-live-scheduled/${step.marker}.txt`;
      const deadline = Date.now() + (step.timeoutMs || 180000);
      let content;
      do {
        try { content = adb('shell','run-as','cn.com.omnimind.bot','cat',path).toString(); }
        catch { /* Observe only; never trigger or resubmit the scheduled task. */ }
        if (content?.trim() === `${step.marker}_FIRED`) break;
        await new Promise(r => setTimeout(r, 1500));
      } while (Date.now() < deadline);
      assert.equal(content?.trim(), `${step.marker}_FIRED`, 'Scheduled artifact not produced before deadline');
      writeFileSync(resolve(out, `${index}-scheduled-file.json`), JSON.stringify({marker:step.marker,content:content.trim(),passed:true}));
    } else if (step.action === 'foreground') {
      adb('shell', 'am', 'start', '-n', 'cn.com.omnimind.bot/.activity.LauncherActivity');
    } else if (step.action === 'restart') {
      assert(!snapshot().some(n => /^(Stop|停止|停止生成)(\n|$)/.test(label(n))),
        'Cannot restart during an active turn');
      adb('shell', 'am', 'force-stop', 'cn.com.omnimind.bot');
      adb('shell', 'am', 'start', '-n', 'cn.com.omnimind.bot/.activity.LauncherActivity');
    } else if (step.action === 'queue') {
      let queueText = step.text;
      if (!queueText && step.scenario) {
        const scenarioFile = resolve(scripts, 'fixtures', 'user-scenarios', step.scenario);
        const sc = JSON.parse(readFileSync(scenarioFile, 'utf8'));
        queueText = `${sc.prompt.replaceAll('{{MARKER}}', step.marker)} End your final reply with ${step.marker}_DONE.`;
      } else if (!queueText && step.marker) {
        queueText = `Reply ${step.marker}`;
      }
      assert(queueText, 'Queue action requires text, scenario, or marker');
      assert(/^[\x20-\x7E]*$/.test(queueText), 'Queue action requires ASCII text for batch input');
      const deadline = Date.now() + (step.timeoutMs || 30000);
      let inputs;
      do {
        inputs = snapshot().filter(n => field(n, 'class') === 'android.widget.EditText');
        if (inputs.length === 1) break;
        await new Promise(r => setTimeout(r, 250));
      } while (Date.now() < deadline);
      assert.equal(inputs.length, 1, 'Expected one composer input for queue action');
      const comp = inputs[0];
      assert.equal(field(comp, 'text'), '', 'Queue requires an empty composer; preserve existing draft');
      const runningBefore = snapshot().some(n => /^(Stop|停止|停止生成)(\n|$)/.test(label(n)));
      assert(runningBefore, 'Queue requires an active running task');
      const b = [...field(comp, 'bounds').matchAll(/\d+/g)].map(([v]) => Number(v));
      adb('shell', 'input', 'tap', String(Math.round((b[0]+b[2])/2)), String(Math.round((b[1]+b[3])/2)));
      await new Promise(r => setTimeout(r, 200));
      adb('shell', 'input', 'text', shellQuote(queueText.replaceAll(' ', '%s')));
      let ready = null;
      let readyInput = null;
      const inputDeadline = Date.now() + 10000;
      do {
        ready = snapshot();
        readyInput = ready.find(n => field(n, 'class') === 'android.widget.EditText');
        if (readyInput && field(readyInput, 'text') === queueText) break;
        await new Promise(r => setTimeout(r, 200));
      } while (Date.now() < inputDeadline);
      assert.equal(field(readyInput, 'text'), queueText, 'Queue text was not entered into composer correctly');
      const queueButtons = ready.filter(n => ['排队发送', 'Queue', 'Queue send'].includes(label(n)) &&
        field(n, 'clickable') === 'true' && field(n, 'enabled') === 'true');
      assert.equal(queueButtons.length, 1, 'Expected one enabled Queue ("排队发送") button; task may not be running');
      const sendButtons = ready.filter(n => ['发送', 'Send'].includes(label(n)) &&
        field(n, 'clickable') === 'true' && field(n, 'enabled') === 'true');
      assert.equal(sendButtons.length, 0, 'Forbidden: Ordinary Send button present while queuing');
      const qb = [...field(queueButtons[0], 'bounds').matchAll(/\d+/g)].map(([v]) => Number(v));
      adb('shell', 'input', 'tap', String(Math.round((qb[0]+qb[2])/2)), String(Math.round((qb[1]+qb[3])/2)));
      let cleared = false;
      const clearDeadline = Date.now() + 15000;
      do {
        try {
          const snap = snapshot();
          const cur = snap.find(n => field(n, 'class') === 'android.widget.EditText');
          if (field(cur, 'text') === '') { cleared = true; break; }
        } catch (_) {}
        await new Promise(r => setTimeout(r, 400));
      } while (Date.now() < clearDeadline);
      assert(cleared, 'Queue was tapped but composer did not clear');
      writeFileSync(resolve(out, `${index}-queue.json`), JSON.stringify({marker: step.marker, text: queueText, queued: true, passed: true}));
    } else if (step.action === 'set-input' || step.action === 'edit-input') {
      const newText = step.text || (step.marker ? `Reply ${step.marker}` : '');
      assert(typeof newText === 'string', 'Input action requires text');
      assert(/^[\x20-\x7E]*$/.test(newText), 'set-input requires ASCII text for batch input');
      const nodes = snapshot();
      let target = null;
      if (step.hint) {
        target = nodes.find(n => field(n, 'class') === 'android.widget.EditText' &&
          [field(n, 'hint'), field(n, 'content-desc'), field(n, 'text')].some(v => v && (v === step.hint || v.split('\n')[0] === step.hint || v.includes(step.hint))));
      } else if (step.label) {
        target = nodes.find(n => field(n, 'class') === 'android.widget.EditText' &&
          [field(n, 'content-desc'), field(n, 'text'), field(n, 'hint')].some(v => v && (v === step.label || v.split('\n')[0] === step.label || v.split('\n').includes(step.label) || v.includes(step.label))));
      } else {
        const inputs = nodes.filter(n => field(n, 'class') === 'android.widget.EditText');
        target = inputs.find(n => field(n, 'focused') === 'true') || (inputs.length === 1 ? inputs[0] : null);
      }
      assert(target, `Target input field not found (hint: ${step.hint || ''}, label: ${step.label || ''})`);
      let focusBounds = [...field(target, 'bounds').matchAll(/\d+/g)].map(([v]) => Number(v));
      if (!step.hint && !step.label && field(target, 'focused') !== 'true') {
        const left = nodes.filter(n => ['附件与权限', 'Attachments & permissions'].includes(label(n)));
        const right = nodes.filter(n => ['发送', 'Send', '排队发送', 'Queue send', '停止', 'Stop'].includes(label(n)));
        if (left.length === 1 && right.length === 1) {
          const lb = [...field(left[0], 'bounds').matchAll(/\d+/g)].map(([v]) => Number(v));
          const rb = [...field(right[0], 'bounds').matchAll(/\d+/g)].map(([v]) => Number(v));
          if (lb[2] < rb[0] && Math.max(lb[1], rb[1]) < Math.min(lb[3], rb[3])) {
            focusBounds = [lb[2], Math.max(lb[1], rb[1]), rb[0], Math.min(lb[3], rb[3])];
          }
        }
      }
      adb('shell', 'input', 'tap', String(Math.round((focusBounds[0]+focusBounds[2])/2)), String(Math.round((focusBounds[1]+focusBounds[3])/2)));
      let focusedTarget = null;
      const focusDeadline = Date.now() + 10000;
      do {
        const focused = snapshot().filter(n =>
          field(n, 'class') === 'android.widget.EditText' && field(n, 'focused') === 'true' &&
          (!step.hint && !step.label || nodeLabels(n).some(value =>
            value === (step.hint || step.label) || value.split('\n').includes(step.hint || step.label) ||
            value.includes(step.hint || step.label))));
        if (focused.length === 1) { focusedTarget = focused[0]; break; }
        await new Promise(r => setTimeout(r, 150));
      } while (Date.now() < focusDeadline);
      assert(focusedTarget, 'Target editor did not gain focus; input retained');
      // 获得唯一聚焦目标后，在后续观测仅跟踪该唯一聚焦输入框，需保持 resource-id 若非空、label 仍在时一致
      const targetId = field(focusedTarget, 'resource-id') || (target ? field(target, 'resource-id') : '') || '';
      const targetLabel = step.hint || step.label || '';
      const findTrackedEditor = (snap) => {
        const focused = snap.filter(n =>
          field(n, 'class') === 'android.widget.EditText' && field(n, 'focused') === 'true');
        // 必须为唯一聚焦输入框；无焦点或存在多个焦点歧义时均拒绝
        if (focused.length !== 1) return null;
        const cur = focused[0];
        const curId = field(cur, 'resource-id') || '';
        // 若存在 resource-id，则必须保持一致
        if ((targetId || curId) && curId !== targetId) return null;
        // 若当前仍保留 label/hint/content-desc，则必须与期望一致；若输入后占位 hint 消失变空，则允许通过
        if (targetLabel) {
          const remainingLabels = [field(cur, 'hint'), field(cur, 'content-desc')].filter(Boolean);
          if (remainingLabels.length > 0 && !remainingLabels.some(v =>
            v === targetLabel || v.split('\n')[0] === targetLabel || v.split('\n').includes(targetLabel) || v.includes(targetLabel))) {
            return null;
          }
        }
        return cur;
      };
      const oldText = field(focusedTarget, 'text') || '';
      if (oldText) {
        let cleared = false;
        try {
          adb('shell', 'input', 'keycombination', '113', '29');
          adb('shell', 'input', 'keyevent', '67');
          const check = findTrackedEditor(snapshot());
          if (check && field(check, 'text') === '') cleared = true;
        } catch (_) {}
        if (!cleared) {
          const focusedEditor = () => findTrackedEditor(snapshot());
          const current = focusedEditor();
          assert(current, 'Target editor lost focus; replacement not entered');
          const bounds = [...field(current, 'bounds').matchAll(/\d+/g)].map(([v]) => Number(v));
          const x = String(Math.round((bounds[0] + bounds[2]) / 2));
          const y = String(Math.round((bounds[1] + bounds[3]) / 2));
          adb('shell', 'input', 'swipe', x, y, x, y, '900');
          const selectAll = snapshot().filter(n =>
            nodeLabels(n).some(value => ['全选', 'Select all'].includes(value)) &&
            field(n, 'clickable') === 'true' && field(n, 'enabled') === 'true');
          assert.equal(selectAll.length, 1, 'Select all unavailable; replacement not entered');
          const selectedBounds = [...field(selectAll[0], 'bounds').matchAll(/\d+/g)].map(([v]) => Number(v));
          adb('shell', 'input', 'tap', String(Math.round((selectedBounds[0] + selectedBounds[2]) / 2)),
            String(Math.round((selectedBounds[1] + selectedBounds[3]) / 2)));
          adb('shell', 'input', 'keyevent', '67');
          const emptyEditor = focusedEditor();
          assert(emptyEditor && field(emptyEditor, 'text') === '',
            'Target editor did not clear; replacement not entered');
        }
      }
      adb('shell', 'input', 'text', shellQuote(newText.replaceAll(' ', '%s')));
      let updatedTarget = null;
      const verifyDeadline = Date.now() + (step.timeoutMs || 10000);
      do {
        const snap = snapshot();
        const candidate = findTrackedEditor(snap);
        if (candidate && field(candidate, 'text') === newText) {
          updatedTarget = candidate;
          break;
        }
        await new Promise(r => setTimeout(r, 200));
      } while (Date.now() < verifyDeadline);
      assert(updatedTarget, 'Target input field missing after typing');
      assert.equal(field(updatedTarget, 'text'), newText, `Field text was not set correctly (expected: "${newText}", actual: "${field(updatedTarget, 'text')}")`);
      writeFileSync(resolve(out, `${index}-set-input.json`), JSON.stringify({text: newText, passed: true}));
    } else if (step.action === 'modify-file') {
      assert(step.path && typeof step.content === 'string');
      assert(/^workspace\/[A-Za-z0-9._/-]+$/.test(step.path), 'Path must be within workspace');
      adb('shell', 'run-as', 'cn.com.omnimind.bot', 'sh', '-c',
        shellQuote(`printf '%s' ${shellQuote(step.content)} > ${shellQuote(step.path)}`));
      writeFileSync(resolve(out, `${index}-modify-file.json`), JSON.stringify({path: step.path, content: step.content, passed: true}));
    } else if (step.action === 'assert-file') {
      assert(step.path);
      assert(/^workspace\/[A-Za-z0-9._/-]+$/.test(step.path), 'Path must be within workspace');
      if (step.exists === false) {
        assert(step.path.includes('OOB_'), 'Absence checks require a synthetic workspace file');
        adb('shell', 'run-as', 'cn.com.omnimind.bot', 'test', '!', '-e', step.path);
        writeFileSync(resolve(out, `${index}-assert-file.json`), JSON.stringify({path: step.path, exists: false, passed: true}));
      } else {
      const actual = adb('shell', 'run-as', 'cn.com.omnimind.bot', 'cat', step.path).toString().trim();
      if (typeof step.content === 'string') {
        assert.equal(actual, step.content.trim(), `Workspace file content mismatch for ${step.path}`);
      } else if (step.contains) {
        assert(actual.includes(step.contains), `Workspace file ${step.path} does not contain ${step.contains}`);
      }
      writeFileSync(resolve(out, `${index}-assert-file.json`), JSON.stringify({path: step.path, content: actual, passed: true}));
      }
    } else if (step.action === 'back') {
      adb('shell', 'input', 'keyevent', '4');
      await new Promise(r => setTimeout(r, 600));
      writeFileSync(resolve(out, `${index}-back.json`), JSON.stringify({passed: true}));
    } else if (step.action === 'precondition') {
      if (step.check === 'lsposed-target-prefix') {
        const prefix = step.prefix || 'cn.com.omnimind.validation.';
        const modulePrefix = step.modulePrefix || 'cn.com.omnimind.validation.module';
        const lines = adb('shell', 'pm', 'list', 'packages').toString().split('\n')
          .map(l => l.trim().replace(/^package:/, '').trim())
          .filter(Boolean);
        const hasTarget = lines.some(pkg => pkg.startsWith(prefix) && !pkg.startsWith(modulePrefix));
        assert(hasTarget,
          `PRECONDITION_FAILED: Dedicated validation target package starting with '${prefix}' is not installed on device; zero deployment enforced`);
      } else if (step.check === 'zero-deployment') {
        const prefix = step.prefix || 'cn.com.omnimind.validation.module';
        const lines = adb('shell', 'pm', 'list', 'packages').toString().split('\n')
          .map(l => l.trim().replace(/^package:/, '').trim())
          .filter(Boolean);
        const deployedModule = lines.find(pkg => pkg === prefix || pkg.startsWith(prefix + '.'));
        assert(!deployedModule,
          `VIOLATION: Unexpected module package '${deployedModule}' was deployed without explicit authorization`);
      } else if (step.check === 'provider-configured') {
        const nodes = snapshot();
        const modelIndicator = configuredModelIndicator(nodes);
        assert(modelIndicator != null,
          `PRECONDITION_FAILED: ChatPage UI does not display a configured active model on device '${serial}'; select a model in UI before running journey`);
      }
      writeFileSync(resolve(out, `${index}-precondition.json`), JSON.stringify({check: step.check, passed: true}));
    } else {
      throw new Error('Unknown journey action');
    }
    writeFileSync(resolve(out, `${index}.png`), adb('exec-out', 'screencap', '-p'), {mode: 0o600});
    report.steps.push({index, action: step.action, marker: step.marker,
      passed: true, elapsedMs: Date.now() - started});
    console.log(JSON.stringify(report.steps.at(-1)));
  }
  report.passed = true;
} catch (error) {
  report.steps.push({index, passed: false, errorType: error.name,
    code: error.code, status: error.status, signal: error.signal,
    detail: (error.stderr?.toString() || error.message)?.slice(0, 2000),
    assertionSite: error.stack?.split('\n').find(line => line.includes('verify-agent-user-journey.mjs:'))?.trim()});
  process.exitCode = 1;
} finally {
  writeFileSync(resolve(out, 'result.json'), JSON.stringify(report, null, 2), {mode: 0o600});
  console.log(JSON.stringify({name: journey.name, passed: report.passed, evidenceDirectory: out}));
}
