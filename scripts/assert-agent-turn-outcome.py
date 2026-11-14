#!/usr/bin/env python3
"""Read-only, turn-scoped assertions for synthetic provider and tool failures."""
import json, os, re, subprocess, sys, time, uuid
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from agent_test_database import agent_database_snapshot
PACKAGE_NAME = 'cn.com.omnimind.bot'
SNAPSHOT_RECEIVER = f'{PACKAGE_NAME}/.debug.DebugAgentDatabaseSnapshotReceiver'
SNAPSHOT_ACTION = f'{PACKAGE_NAME}.debug.SNAPSHOT_DATABASE'
SCHEMA_VERSION = 'oob.database_snapshot.v1'
SCENARIO_PREFIXES = ('OOB_LIVE_', 'OOB_OUTBOX_', 'OOB_WORKSPACE_', 'OOB_SKILL_', 'OOB_LSPOSED_')

def export_turn_rows(serial: str, marker: str, timeout: int = 30) -> list:
    assert re.fullmatch(r'OOB_[A-Z0-9_]+_\d+', marker), (
        f'Physical device requires bounded synthetic marker with numeric suffix: {marker}'
    )
    nonce = uuid.uuid4().hex
    remote_files = [
        f'cache/oob-checkpoint-{nonce}.lock',
        f'cache/oob-checkpoint-{nonce}.json',
        f'cache/oob-checkpoint-{nonce}.json.tmp',
    ]

    def adb_cmd(*args):
        adb_bin = os.environ.get('ADB', 'adb')
        return [adb_bin, '-s', serial] + list(args)

    try:
        broadcast_cmd = adb_cmd(
            'shell', 'am', 'broadcast',
            '-n', SNAPSHOT_RECEIVER,
            '-a', SNAPSHOT_ACTION,
            '--es', 'nonce', nonce,
            '--es', 'marker', marker,
        )
        broadcast_res = subprocess.run(broadcast_cmd, capture_output=True, text=True, timeout=timeout)
        if broadcast_res.returncode != 0:
            raise RuntimeError(f'Failed to broadcast turn export intent (code {broadcast_res.returncode})')

        deadline = time.monotonic() + timeout
        poll_interval = 0.2
        completed_receipt = None

        while time.monotonic() < deadline:
            cat_cmd = adb_cmd('exec-out', 'run-as', PACKAGE_NAME, 'cat', f'cache/oob-checkpoint-{nonce}.json')
            cat_res = subprocess.run(cat_cmd, capture_output=True, timeout=10)
            if cat_res.returncode == 0 and cat_res.stdout.strip():
                try:
                    receipt = json.loads(cat_res.stdout.decode('utf-8'))
                    if receipt.get('schema_version') != SCHEMA_VERSION or receipt.get('nonce') != nonce:
                        time.sleep(poll_interval)
                        continue
                    status = receipt.get('status')
                    if status == 'pending':
                        time.sleep(poll_interval)
                        continue
                    elif status == 'completed':
                        if receipt.get('success') is True:
                            completed_receipt = receipt
                            break
                        raise RuntimeError('Turn export completed with corrupted receipt state')
                    elif status == 'failed':
                        error = receipt.get('error', 'internal_error')
                        if error in ('user_admission_missing', 'user_admission_duplicated'):
                            raise AssertionError('User admission missing or duplicated')
                        raise RuntimeError(f'Turn export failed on device: {error}')
                except (json.JSONDecodeError, UnicodeDecodeError):
                    pass
            time.sleep(poll_interval)

        if completed_receipt is None:
            raise TimeoutError(f'Turn export timed out waiting for receipt after {timeout}s: nonce={nonce}')

        return completed_receipt.get('rows', [])
    finally:
        rm_cmd = adb_cmd('shell', 'run-as', PACKAGE_NAME, 'rm', '-f') + remote_files
        subprocess.run(rm_cmd, capture_output=True, timeout=10)

def verify(db_or_rows, marker, expected, summary=None):
    assert re.fullmatch(r'OOB_[A-Z0-9_]+', marker), 'Synthetic marker required'
    is_scenario = any(marker.startswith(p) for p in SCENARIO_PREFIXES)
    if hasattr(db_or_rows, 'execute'):
        candidates = db_or_rows.execute("SELECT id,conversationId,payloadJson FROM agent_conversation_entries WHERE entryType='user_message' AND instr(payloadJson,?)>0", (marker,)).fetchall()
        def matches_user(payload):
            text = payload.get('content',{}).get('text','')
            return text == 'Reply '+marker or (is_scenario and
                text.endswith(f'End your final reply with {marker}_DONE.'))
        selected = [r for r in candidates if matches_user(json.loads(r[2]))]
        assert len(selected) == 1, 'User admission missing or duplicated'
        start, conversation, _ = selected[0]
        end = db_or_rows.execute("SELECT min(id) FROM agent_conversation_entries WHERE conversationId=? AND id>? AND entryType='user_message'", (conversation,start)).fetchone()[0]
        rows = [(t,json.loads(p)) for t,p in db_or_rows.execute('SELECT entryType,payloadJson FROM agent_conversation_entries WHERE conversationId=? AND id>? AND id<? ORDER BY id', (conversation,start,end or 2**63-1))]
    else:
        rows = []
        for r in db_or_rows:
            if isinstance(r, (list, tuple)):
                t, p = r[0], r[1]
            elif isinstance(r, dict):
                t = r.get('entryType')
                p = r.get('payloadJson') or r.get('payload', {})
            else:
                continue
            p_dict = json.loads(p) if isinstance(p, str) else p
            rows.append((t, p_dict))
    identities = {(p.get('streamMeta',{}).get('sessionId'),p.get('streamMeta',{}).get('turnId')) for _,p in rows}
    assert len(identities)==1 and all(next(iter(identities))), 'Missing or mixed turn identity'
    if expected=='permission-pending':
        cards=[p.get('content',{}).get('cardData',{}) for t,p in rows if t=='ui_card']
        pending=[c for c in cards if c.get('type')=='agent_request' and c.get('status')=='pending' and c.get('requestKind')=='approval' and c.get('requestId')]
        assert len(pending)==1, 'Missing current pending permission'
        assert not any(p.get('streamMeta',{}).get('stopReason') in ('end_turn','cancelled','error') for t,p in rows if t=='assistant_message'), 'Permission belongs to a finished turn'
        return {'marker':marker,'expected':expected,'passed':True,'entries':len(rows)}
    assert all(not p.get('isLoading',False) for _,p in rows), 'Turn still loading'
    failures=[p for t,p in rows if t=='tool_event' and p.get('toolName')=='agent.status' and p.get('status')=='error']
    assistants=[p for t,p in rows if t=='assistant_message']
    if expected=='cancelled':
        assert not failures, 'User cancellation incorrectly projected as failure'
        assert not any(t=='ui_card' and p.get('content',{}).get('cardData',{}).get('type')=='agent_request' and p.get('content',{}).get('cardData',{}).get('status')=='pending' for t,p in rows), 'Cancelled turn retains actionable request'
        assert any(p.get('streamMeta',{}).get('stopReason')=='cancelled' for _,p in rows), 'Missing canonical cancellation'
        assert not any(p.get('streamMeta',{}).get('stopReason') in ('end_turn','error') for _,p in rows), 'Mixed terminal outcomes'
    elif expected=='error':
        assert len(failures)==1, 'Expected one owning turn failure'
        if summary is not None: assert failures[0].get('summary')==summary, 'Wrong user-facing error category'
        assert failures[0]['streamMeta'].get('stopReason')=='error'
        assert not any(p.get('streamMeta',{}).get('stopReason')=='end_turn' for p in assistants), 'Failure projected as completion'
        if '_PARTIAL_' in marker or re.search(r'^OOB_FAILURE_STREAM(?:ERROR|RATE|LIMIT|AUTH|SERVICE|REJECT|MODEL)_', marker):
            assert any(marker+'_PARTIAL' in p.get('content',{}).get('text','') for p in assistants), 'Partial output lost'
        if '_STREAMTOOL_' in marker:
            assert all(p.get('status')=='pending' and p.get('success') is False for t,p in rows if t=='tool_event' and p.get('toolName')!='agent.status'), 'Failed provider response contains an executed tool result'
    else:
        assert expected in ('recovered','done')
        assert not failures, 'Recovered turn incorrectly remains failed'
        if marker.startswith('OOB_LIVE_TERMINAL_RECOVERY_'):
            tools=[p for t,p in rows if t=='tool_event' and p.get('toolName')!='agent.status']
            assert len(tools)==1 and tools[0].get('toolName') in ('terminal_execute','bash') and tools[0].get('success') is True, 'Next turn did not complete exactly one terminal command'
            raw=tools[0].get('rawResultJson') or '{}'
            result=json.loads(raw) if isinstance(raw,str) else raw
            assert result.get('result',{}).get('stdout','').strip()==marker+'_DONE', 'Terminal output missing or incorrect'
            reasons={p.get('streamMeta',{}).get('stopReason') for _,p in rows}-{None,''}
            assert reasons=={'end_turn'}, 'Missing canonical completion for terminal execution'
            return {'marker':marker,'expected':expected,'passed':True,'entries':len(rows)}
        suffix='_RECOVERED' if expected=='recovered' else '_DONE'
        replies=[p for p in assistants if p.get('content',{}).get('text')==marker+suffix or
            (is_scenario and p.get('content',{}).get('text','').rstrip().rstrip('*`').rstrip().endswith(marker+suffix))]
        assert len(replies)==1 and replies[0].get('streamMeta',{}).get('stopReason')=='end_turn', 'Missing canonical completion'
        if marker.startswith('OOB_LIVE_CODEX_EXIT_'):
            tools=[p for t,p in rows if t=='tool_event' and p.get('toolName')!='agent.status']
            assert len(tools)==1, 'Codex exit probe requires exactly one terminal call'
            raw=tools[0].get('rawResultJson') or '{}'
            result=json.loads(raw) if isinstance(raw,str) else raw
            assert result.get('type')=='commandExecution', 'Codex exit probe did not use command execution'
            output=result.get('rawOutput',{})
            assert output.get('exit_code')==7, 'Codex terminal did not execute the expected exit 7 command'
            text=output.get('formatted_output','')
            assert marker+'_STDOUT' in text and marker+'_STDERR' in text, 'Codex terminal output missing'
            assert tools[0].get('success') is False, 'Nonzero exit incorrectly marked successful'
        if marker.startswith('OOB_LIVE_CODEX_DETAIL_'):
            tools=[p for t,p in rows if t=='tool_event' and p.get('toolName')!='agent.status']
            assert len(tools)==1, 'Command detail probe requires exactly one tool'
            tool=tools[0]
            raw=tool.get('rawResultJson') or '{}'
            result=json.loads(raw) if isinstance(raw,str) else raw
            assert result.get('type')=='commandExecution', 'Expected actual command tool'
            code=result.get('rawOutput',{}).get('exit_code')
            assert type(code) is int and code!=0, 'Expected recorded nonzero exit'
            assert tool.get('status')=='error' and tool.get('success') is False, 'Failed command state lost'
            assert tool.get('summary')==f'Command exited with code {code}', 'Actual exit detail missing from history'
        if marker.startswith('OOB_LIVE_MCP_DENIED_'):
            tools=[p for t,p in rows if t=='tool_event' and p.get('toolName')!='agent.status']
            assert len(tools)==1 and tools[0].get('success') is False, 'MCP denial missing or replayed'
            assert 'lifecycle_denied' in tools[0].get('toolName','') and 'HTTP 401' in tools[0].get('summary',''), 'Wrong MCP denial category'
        if expected=='recovered':
            tools=[p for t,p in rows if t=='tool_event' and p.get('toolName')!='agent.status']
            assert len(tools)==1 and tools[0].get('success') is False, 'Failed tool missing or replayed'
            tool=tools[0]; diagnostic=tool.get('summary','')
            if '_UNKNOWN_' in marker:
                assert tool.get('toolName')=='oob_nonexistent_tool' and 'Unknown capability' in diagnostic, 'Wrong unknown-tool failure'
            elif '_ARGS_' in marker:
                assert tool.get('toolName')=='file_read' and '{bad json' in diagnostic, 'Wrong malformed-arguments failure'
            elif '_MISSING_' in marker:
                assert tool.get('toolName')=='file_read' and marker+'-missing.txt' in diagnostic and re.search(r'does not exist|not found|不存在',diagnostic,re.I), 'Permission or another failure is not missing-file coverage'

    return {'marker':marker,'expected':expected,'passed':True,'entries':len(rows)}

if __name__=='__main__':
    args = sys.argv[1:]
    physical_flag = False
    if '--physical' in args:
        physical_flag = True
        args = [a for a in args if a != '--physical']
    assert len(args) >= 3, 'Usage: assert-agent-turn-outcome.py [--physical] SERIAL MARKER EXPECTED [SUMMARY]'
    serial, marker, expected, *summary = args
    summary_arg = summary[0] if summary else None

    is_emulator = bool(re.fullmatch(r'emulator-\d+', serial))
    if is_emulator and not physical_flag:
        with agent_database_snapshot(serial) as db:
            print(json.dumps(verify(db, marker, expected, summary_arg)))
    else:
        is_opted_in = physical_flag or (os.environ.get('OOB_ALLOW_PHYSICAL_DEVICE') == '1')
        assert is_opted_in, (
            f'Physical device {serial} rejected: requires explicit OOB_ALLOW_PHYSICAL_DEVICE=1 opt-in or --physical flag'
        )
        assert re.fullmatch(r'[A-Za-z0-9._:-]+', serial), f'Invalid device serial format: {serial}'
        rows = export_turn_rows(serial, marker)
        print(json.dumps(verify(rows, marker, expected, summary_arg)))
