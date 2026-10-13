#!/usr/bin/env python3
"""Packaged profile-rule QA against an isolated MariaDB. Run after bootJar."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import uuid

root = Path(__file__).resolve().parents[1]
container = 'tsvideos-profile73-' + uuid.uuid4().hex[:10]
def run(args, **kw):
    return subprocess.run(args, text=True, capture_output=True, check=True, **kw)
def sql(query):
    return run(['docker', 'exec', '-i', container, 'mariadb', '-uroot', '-pprofile-smoke', '-N', 'test'], input=query).stdout.strip()
try:
    run(['docker', 'run', '-d', '--name', container, '-e', 'MARIADB_ROOT_PASSWORD=profile-smoke', '-e', 'MARIADB_DATABASE=test', '-p', '127.0.0.1::3306', 'mariadb:11.8'])
    for _ in range(60):
        try:
            sql('SELECT 1;')
            break
        except subprocess.CalledProcessError:
            time.sleep(1)
    else:
        raise RuntimeError('Dedicated DB did not start')
    sql((root / 'core/src/test/resources/ddl/01_create_database.sql').read_text())
    port = run(['docker', 'port', container, '3306']).stdout.strip().split(':')[-1]
    with tempfile.TemporaryDirectory(prefix='profile73-') as directory:
        work = Path(directory)
        source = work / '[261013-1200][TEST][確認]番組.m2ts'
        packets = bytearray()
        for i in range(16):
            packet = bytearray(188)
            packet[:5] = bytes([0x47, 1, 0, 0x10 | (i & 15), i])
            packets.extend(packet)
        source.write_bytes(packets)
        probe = work / 'probe.sh'
        probe.write_text('#!/bin/sh\nprintf called >> "$(dirname "$0")/probe.calls"\nprintf "60\\n"\n')
        probe.chmod(0o700)
        env = dict(os.environ, DATABASE_CONNECTION=f'jdbc:mariadb://127.0.0.1:{port}/test', DATABASE_USER_NAME='root', DATABASE_PASSWORD='profile-smoke', FFPROBE_PATH=str(probe), SLACK_WEBHOOK_URL='')
        policy = {'fallback-profile': '標準 fallback', 'rules': [
            {'id': 'japanese-first', 'profile': '日本語 高画質', 'channel': 'TEST', 'title-pattern': '番組', 'min-duration-seconds': 60, 'max-duration-seconds': 60},
            {'id': 'later', 'profile': 'later-profile', 'channel': 'TEST'}]}
        jar = root / 'processor/console/build/libs/console.jar'
        def cli(args, config=policy, expect=0):
            current = dict(env, SPRING_APPLICATION_JSON=json.dumps({'processor-profiles': config}, ensure_ascii=False))
            result = subprocess.run(['java', '-jar', str(jar), *args], env=current, text=True, encoding='utf-8', capture_output=True, timeout=60)
            assert (result.returncode == expect if expect is not None else result.returncode != 0), (args, result.returncode, result.stdout, result.stderr)
            return result
        counts = 'SELECT (SELECT COUNT(*) FROM program),(SELECT COUNT(*) FROM executed_file),(SELECT COUNT(*) FROM splitted_file),(SELECT COUNT(*) FROM created_file),(SELECT COUNT(*) FROM processing_job),(SELECT COUNT(*) FROM processing_attempt),(SELECT COUNT(*) FROM processing_profile_selection);'
        before = sql(counts)
        output = cli(['process', '--dry-run', str(source)])
        assert 'profile=日本語 高画質, rule=japanese-first, reason=MATCHED_RULE' in output.stdout + output.stderr
        assert sql(counts) == before
        fallback = dict(policy, rules=[{'id': 'other', 'profile': '別', 'channel': 'OTHER'}])
        output = cli(['process', '--dry-run', str(source)], fallback)
        assert 'profile=標準 fallback, rule=fallback, reason=FALLBACK' in output.stdout + output.stderr
        assert sql(counts) == before
        calls = (work / 'probe.calls').read_bytes()
        for invalid in [dict(policy, rules=[{'id': 'bad', 'profile': 'x', 'title-pattern': '['}]), {'rules': policy['rules']}]:
            cli(['process', str(source)], invalid, expect=None)
            assert sql(counts) == before
            assert (work / 'probe.calls').read_bytes() == calls
        # Existing split blocks processing after durable selection, before any external submission.
        split_dir = work / 'tssplitter'
        split_dir.mkdir()
        sentinel = split_dir / (source.stem + '_existing.m2ts')
        sentinel.write_bytes(b'owned-fixture-do-not-delete')
        cli(['process', str(source)])  # Base #67 runner logs failure but exits zero.
        job = sql('SELECT MAX(id) FROM processing_job;')
        assert sql(f"SELECT outcome FROM processing_attempt WHERE job_id={job};") == 'FAILED'
        assert sql('SELECT COUNT(*) FROM processing_profile_selection;') == '1'
        changed = dict(policy, rules=[{'id': 'changed', 'profile': '変更後', 'channel': 'TEST'}])
        report = json.loads(cli(['history', '--job-id', job, '--json'], changed).stdout)
        selection = report['attempts'][0]['encodingProfile']
        assert selection == {'profile': '日本語 高画質', 'ruleId': 'japanese-first', 'reason': 'MATCHED_RULE'}
        text = cli(['history', '--job-id', job], changed).stdout
        assert 'Profile 日本語 高画質; rule japanese-first; MATCHED_RULE' in text
        assert source.read_bytes() == packets
        assert sentinel.read_bytes() == b'owned-fixture-do-not-delete'
        print(json.dumps({'result': 'PASS', 'dryRunDatabaseUnchanged': True, 'invalidConfigurationBeforeProbe': True, 'firstMatchingRule': selection, 'historySurvivesConfigurationChange': True, 'safeFailureOutcome': 'FAILED', 'originalAndSentinelPreserved': True}, ensure_ascii=False))
finally:
    subprocess.run(['docker', 'rm', '-f', container], capture_output=True)
