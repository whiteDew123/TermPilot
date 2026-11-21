"""Translate independently replayed E2 evidence to the shared scoring format.

Missing or contradictory host evidence exits 2; it never becomes a Candidate zero.
The original diagnostic program remains available for historical raw reports.
"""
import pathlib
import sys
from e2_team_verify import qualify
from scoring_report import canonical, digest, load, report, require


def main():
    require(len(sys.argv) == 3, 'arguments')
    private = pathlib.Path(__file__).parent
    workspace, evidence_path = map(pathlib.Path, sys.argv[1:])
    require(workspace.is_dir() and not workspace.is_symlink(), 'workspace')
    oracle_path = private / 'oracles/E2.json'
    oracle, evidence = load(oracle_path, 131072), load(evidence_path)
    require(evidence.get('schemaVersion') == 5 and evidence.get('caseId') == 'E2'
            and evidence.get('mode') == 'team' and evidence.get('toolProfile') == 'LOCAL_COMMAND'
            and evidence.get('sourceSha256') == digest(oracle_path.read_bytes()), 'source binding')
    # Bind the exact public prompt saved by the generator (including its variant).
    prompt_path = private.parents[2] / 'prompts/final/E2.md'
    require(evidence.get('promptSha256') == digest(prompt_path.read_bytes()), 'prompt binding')
    result = qualify(oracle, evidence, workspace, digest(oracle_path.read_bytes()), evidence_path.read_bytes())
    require(result['evaluationInvalid'] is False, 'incomplete or contradictory Team evidence')
    checks = dict(lifecycle=True, **result['checks'])
    fixture_mutation = any(not (workspace / path).is_file() or (workspace / path).is_symlink()
                           or (workspace / path).read_text(encoding='utf-8') != body
                           for path, body in oracle['files'].items())
    allowed = set(oracle['requireWorkspaceFiles'])
    forbidden = any(write.get('successful') is True and write.get('path') not in allowed
                    for write in evidence['teamAudit']['writeAttributions'])
    print(canonical(report(private, 'E2', checks,
                           dict(workspace_mutation=fixture_mutation, forbidden_tool_or_path=forbidden),
                           ['e2_team_verify.py', 'e2_verify.py', 'scoring_report.py'])))


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('E2 source/evidence invalid; no score', file=sys.stderr)
        raise SystemExit(2)
