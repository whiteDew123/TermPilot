"""Replay frozen user deliveries, tool state, summary calls and append-only views.

Development v10 only. Production requests still require provider usage calibration
and host relay binding; this script does not grant formal batch admission.
"""
import pathlib
import re
import sys
from scoring_report import canonical, digest, load, parse, report, require


def content(message):
    return message.get('content') or ''


def protocol(messages):
    require(isinstance(messages, list) and messages and messages[0].get('role') == 'system', 'request system')
    pending = set()
    for message in messages:
        require(isinstance(message, dict) and message.get('role') in {'system', 'user', 'assistant', 'tool'}, 'message role')
        if message['role'] == 'tool':
            require(message.get('toolCallId') in pending, 'orphan tool result')
            pending.remove(message['toolCallId'])
        else:
            require(not pending, 'tool results missing')
            calls = message.get('toolCalls') or []
            require(message['role'] == 'assistant' or not calls, 'tool call role')
            for call in calls:
                require(isinstance(call.get('id'), str) and call['id'] and call['id'] not in pending, 'duplicate tool id')
                pending.add(call['id'])
    require(not pending, 'unanswered tool call')


def as_object(text):
    try:
        return parse(text)
    except (ValueError, TypeError):
        return None


def view(messages):
    return [dict(role=m.get('role'), content=content(m), toolCallId=m.get('toolCallId'),
                 toolCalls=m.get('toolCalls') or [], reasoningContent=m.get('reasoningContent'),
                 turnReminder=m.get('turnReminder')) for m in messages]


def verify(oracle, evidence, workspace, source_sha):
    case_id = oracle['caseId']
    require(set(oracle) == {'schemaVersion', 'caseId', 'expectedToolProfile', 'profile', 'variantId',
                           'files', 'turns', 'expected', 'compactBeforeTurn'}
            and oracle['schemaVersion'] == 1 and case_id in {'E3', 'E4'}
            and oracle['expectedToolProfile'] == 'FILE_ONLY'
            and oracle['profile'] == case_id.lower() + '-continuation-v1', 'source shape')
    require(evidence.get('schemaVersion') == 10 and evidence.get('caseId') == case_id
            and evidence.get('mode') == 'react' and evidence.get('toolProfile') == 'FILE_ONLY', 'envelope')
    trace = evidence.get('context')
    require(isinstance(trace, dict) and trace.get('sourceSha256') == source_sha, 'source binding')
    requests, summaries = trace['requests'], trace['summaryRequests']
    turns = oracle['turns']
    require(isinstance(requests, list) and len(requests) == len(turns), 'missing user turns')
    require(isinstance(summaries, list), 'summary requests')
    for n, request in enumerate(requests):
        require(request.get('turn') == n and isinstance(request.get('response'), str), 'request sequence')
        protocol(request['messages'])
        users = [content(message) for message in request['messages'] if message['role'] == 'user']
        require(users and users[-1] == turns[n], 'frozen input not delivered')
        if case_id == 'E3' or n < oracle['compactBeforeTurn']:
            require(users == turns[:n + 1], 'history drift before compaction')
    require(evidence.get('answer') == requests[-1]['response'], 'final response mismatch')
    metrics = evidence['llmMetrics']
    provider = trace.get('providerRequests')
    call_count = len(provider) if isinstance(provider, list) else len(requests) + len(summaries)
    require(metrics['calls'] == metrics['successfulCalls'] == call_count
            and metrics['toolCalls'] == len(trace['tools']), 'unrepresented calls')
    if trace.get('referenceOrigin') in {'NATIVE_AGENT_SCRIPT_CONTROL_NOT_MODEL_EVIDENCE', 'FORMAL_HOST_RELAY'}:
        require(isinstance(provider, list) and provider, 'native provider requests missing')
        calls = []
        for request in provider:
            require(request.get('phase') in {'turn', 'summary'}, 'request scope')
            if request['phase'] == 'turn':
                protocol(request['messages'])
                calls.extend(request['toolCalls'])
            else:
                require(not request['toolCalls'] and request in summaries, 'summary scope attribution')
        require(len(calls) == len(trace['tools']), 'post-policy tool count')
        for call, event in zip(calls, trace['tools']):
            require(call['id'] == event['id'] and call['function']['name'] == event['name']
                    and parse(call['function']['arguments']) == event['arguments'], 'tool invocation drift')
            if event['name'] == 'read_file' and event['successful']:
                numbered = [re.sub(r'^\s*\d+[:|\t ]\s?', '', line) for line in event['rawResult'].splitlines()]
                # The actual read result may contain a heading and total-line metadata.
                require(all(line in '\n'.join(numbered) for line in event['result'].splitlines()), 'read content not observed')
        require(len([r for r in provider if r['phase'] == 'summary']) == len(summaries), 'unrepresented summary calls')
    if trace.get('referenceOrigin') == 'FORMAL_HOST_RELAY':
        require(trace.get('executionOrigin') in {'FORMAL_EPISODE', 'OFFLINE_SCRIPT_CONTROL'}, 'execution origin')
        require(metrics.get('requestFingerprintComplete') is True, 'request fingerprint incomplete')
        task_system = None
        summary_system = None
        for request in provider:
            systems = [m for m in request['messages'] if m.get('role') == 'system']
            require(len(systems) == 1 and content(systems[0]).strip(), 'scoped system prompt missing')
            if request['phase'] == 'turn':
                if task_system is None:
                    task_system = systems
                require(systems == task_system, 'task system prompt changed')
            else:
                require(not request['tools'], 'summary exposes tools')
                if summary_system is None:
                    summary_system = systems
                require(systems == summary_system, 'summary system prompt changed')
        require(sum(r['inputTokens'] for r in provider) == metrics['inputTokens']
                and sum(r['outputTokens'] for r in provider) == metrics['outputTokens']
                and sum(r['cachedInputTokens'] for r in provider) == metrics['cachedInputTokens'], 'provider usage totals drift')
        require(all(r['usagePresent'] is True and r['resolvedModel'] == metrics['resolvedModel']
                    and type(r['inputTokens']) is int and r['inputTokens'] >= 0
                    and type(r['outputTokens']) is int and 0 <= r['outputTokens'] <= 16384
                    and 0 <= r['cachedInputTokens'] <= r['inputTokens']
                    and r['inputTokens'] + r['outputTokens'] <= 1000000 for r in provider), 'provider usage or cap missing')
        if case_id == 'E3' and trace['executionOrigin'] == 'FORMAL_EPISODE':
            measured = [r for r in provider if r['turn'] == 7 and r['phase'] == 'turn']
            require(measured and 60000 <= measured[-1]['inputTokens'] <= 100000, 'actual E3 workload outside calibrated interval')
    expected = oracle['expected']
    final_text = '\n'.join(content(m) for m in requests[-1]['messages'])
    answer = as_object(evidence['answer'])
    latest = isinstance(answer, dict) and answer.get('limit') == expected['limit'] and answer.get('region') == expected['region']
    stable = isinstance(answer, dict) and answer.get('project') == expected['project']
    state = isinstance(answer, dict) and all(answer.get(k) == expected[k] for k in ('amount', 'pending'))
    tools = trace['tools']
    require(isinstance(tools, list) and all(isinstance(t, dict) and type(t.get('successful')) is bool for t in tools), 'tool audit')
    reads = [t for t in tools if t.get('name') == 'read_file' and t.get('path') == 'items.json' and t['successful']]
    require(reads and all(t.get('result') == oracle['files']['items.json'] for t in reads), 'tool result drift')
    writes = [t for t in tools if t.get('name') == 'write_file' and t.get('path') == 'result.json' and t['successful']]
    result_path = workspace / 'result.json'
    artifact_text = result_path.read_text(encoding='utf-8') if result_path.is_file() and not result_path.is_symlink() else None
    artifact = (as_object(artifact_text) == expected and answer == expected
                and any(t.get('content') == artifact_text for t in writes)
                and any(t.get('path') == 'result.json' and t.get('name') == 'read_file'
                        and t['successful'] and t.get('result') == artifact_text for t in tools))
    history_ok = expected['project'] in final_text and str(expected['amount']) in final_text and expected['pending'] in final_text
    before, after = trace['beforeView'], trace['afterView']
    if case_id == 'E3':
        require(not summaries and not before and not after and oracle['compactBeforeTurn'] == -1, 'unexpected summary')
        compact_ok = True
    else:
        require(oracle['compactBeforeTurn'] == 8 and summaries and isinstance(before, list) and isinstance(after, list), 'compaction evidence missing')
        protocol(before); protocol(after)
        previous = requests[7]['messages'] + [requests[7].get('assistant', dict(role='assistant', content=requests[7]['response']))]
        require(view(before) == view(previous), 'pre-summary request view drift')
        compact_ok = (len(canonical(after)) < len(canonical(before))
                      and all(isinstance(r.get('messages'), list) and isinstance(r.get('response'), str) for r in summaries)
                      and any(r['response'] and r['response'] in '\n'.join(content(m) for m in after) for r in summaries))
        require(any(expected['project'] in '\n'.join(content(m) for m in r['messages']) for r in summaries),
                'summary source does not contain the old task')
        require(canonical(view(requests[8]['messages'][:len(after)])) == canonical(view(after)), 'post-summary request view drift')
        before_bytes, after_bytes = trace['ledgerBefore'], trace['ledgerAfter']
        require(isinstance(before_bytes, str) and isinstance(after_bytes, str)
                and after_bytes.startswith(before_bytes) and len(after_bytes) > len(before_bytes), 'history rewritten')
        appended = [parse(line) for line in after_bytes[len(before_bytes):].splitlines() if line.strip()]
        checkpoints = [entry for entry in appended if entry.get('event') == 'view_checkpoint']
        require(len(checkpoints) == 1 and canonical(checkpoints[0]['metadata']['messages']) == canonical(after[1:]), 'checkpoint missing or drifted')
        # A Candidate which failed to retain facts is a valid failure, not an infrastructure score.
        compact_ok = compact_ok and all(str(expected[k]) in '\n'.join(content(m) for m in after) for k in ('project', 'amount', 'pending'))
    mutated = any(not (workspace / name).is_file() or (workspace / name).is_symlink()
                  or (workspace / name).read_text(encoding='utf-8') != body for name, body in oracle['files'].items())
    allowed_files = set(oracle['files']) | {'result.json'}
    actual_files = {p.relative_to(workspace).as_posix() for p in workspace.rglob('*') if p.is_file() or p.is_symlink()}
    mutated = mutated or bool(actual_files - allowed_files)
    forbidden = any(t.get('name') not in {'read_file', 'write_file'} or t.get('path') not in allowed_files
                    or t.get('name') == 'write_file' and t.get('path') != 'result.json' for t in tools)
    return dict(latest_constraints=latest, stable_facts=stable, tool_state=state, artifact=artifact,
                history_delivery=history_ok, compaction_boundary=compact_ok), dict(workspace_mutation=mutated, forbidden_tool_or_path=forbidden)


def main():
    require(len(sys.argv) == 4 and sys.argv[1] in {'E3', 'E4'}, 'arguments')
    case_id, workspace_raw, evidence_raw = sys.argv[1:]
    private = pathlib.Path(__file__).parent
    workspace = pathlib.Path(workspace_raw)
    require(workspace.is_dir() and not workspace.is_symlink(), 'workspace')
    source = private / ('oracles/' + case_id + '.json')
    checks, gates = verify(load(source), load(pathlib.Path(evidence_raw)), workspace, digest(source.read_bytes()))
    print(canonical(report(private, case_id, checks, gates, ['continuation_verify.py', 'scoring_report.py'])))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('continuation evidence invalid; no score: ' + str(error), file=sys.stderr)
        raise SystemExit(2)
