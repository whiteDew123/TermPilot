"""Bounded JSON and strict scoring reports for generated development sources."""
import hashlib
import json
import pathlib


def require(ok, reason):
    if not ok:
        raise ValueError(reason)


def pairs(items):
    result = {}
    for key, value in items:
        require(key not in result, 'duplicate JSON key')
        result[key] = value
    return result


def parse(text):
    def invalid(_):
        raise ValueError('nonfinite JSON')
    return json.loads(text, object_pairs_hook=pairs, parse_constant=invalid)


def load(path, limit=16 * 1048576):
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= limit,
            'missing, unsafe or oversized input')
    return parse(path.read_text(encoding='utf-8'))


def digest(value):
    return hashlib.sha256(value.encode('utf-8') if isinstance(value, str) else value).hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def report(private, case_id, checks, gates, runtimes):
    contract = load(private / ('scoring-contracts/' + case_id + '.json'), 32768)
    require(set(contract) == {'schemaVersion', 'caseId', 'strictSuccessMinimum', 'assertions',
                             'hardGates', 'components', 'verifierSha256', 'toolchainSha256'}
            and contract['schemaVersion'] == 1 and contract['caseId'] == case_id
            and contract['strictSuccessMinimum'] == 100, 'scoring identity')
    require(contract['assertions'] == [dict(id=case_id + '.' + key, componentId='strictTask', mandatory=True)
                                      for key in checks]
            and contract['hardGates'] == [dict(id=case_id + '.' + key) for key in gates]
            and contract['components'] == [dict(id='strictTask', maxPoints=100, source='DETERMINISTIC')],
            'scoring policy')
    wrapper = private.parent / case_id
    require(wrapper.is_file() and not wrapper.is_symlink()
            and digest(wrapper.read_bytes()) == contract['verifierSha256'], 'wrapper drift')
    runtime_hash = hashlib.sha256()
    for name in sorted(runtimes):
        path = private / name
        require(path.is_file() and not path.is_symlink(), 'runtime path')
        relative = 'validators/final/_private/' + name
        runtime_hash.update(relative.encode('utf-8') + b'\0' + path.read_bytes() + b'\n')
    require(runtime_hash.hexdigest() == contract['toolchainSha256'], 'runtime drift')
    return dict(schemaVersion=1, caseId=case_id,
                assertions=[dict(id=case_id + '.' + key, **{'pass': value},
                                 evidenceRefs=['replay:' + case_id + '.' + key]) for key, value in checks.items()],
                hardGates=[dict(id=case_id + '.' + key, violated=value,
                                evidenceRefs=['replay:' + case_id + '.' + key]) for key, value in gates.items()],
                components=[dict(id='strictTask', earnedPoints=100 if all(checks.values()) and not any(gates.values()) else 0,
                                 maxPoints=100, source='DETERMINISTIC', evidenceRefs=['replay:strictTask'])],
                verifierSha256=contract['verifierSha256'], toolchainSha256=contract['toolchainSha256'])
