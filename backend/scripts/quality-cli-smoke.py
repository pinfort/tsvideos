#!/usr/bin/env python3
"""Isolated packaged CLI/MariaDB smoke. Run from backend after processor:console:bootJar."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import uuid

root = Path(__file__).resolve().parents[1]
container = 'tsvideos-quality71-' + uuid.uuid4().hex[:10]

def run(args, **kwargs):
    return subprocess.run(args, text=True, capture_output=True, check=True, **kwargs)

def sql(query):
    return run(['docker', 'exec', '-i', container, 'mariadb', '-uroot', '-pquality-smoke', '-N', 'test'], input=query).stdout.strip()

try:
    run(['docker', 'run', '-d', '--name', container, '-e', 'MARIADB_ROOT_PASSWORD=quality-smoke', '-e', 'MARIADB_DATABASE=test', '-p', '127.0.0.1::3306', 'mariadb:11.8'])
    for _ in range(60):
        try:
            sql('SELECT 1;')
            break
        except subprocess.CalledProcessError:
            time.sleep(1)
    else:
        raise RuntimeError('Dedicated MariaDB failed to start')
    # Same schema source as core:generateTestDdl: every migration in filename order.
    sql('\n'.join(p.read_text() for p in sorted((root / 'core/src/main/resources/db/migrations').glob('*.sql'))))
    port = run(['docker', 'port', container, '3306']).stdout.strip().split(':')[-1]
    with tempfile.TemporaryDirectory(prefix='quality71-') as directory:
        work = Path(directory)
        source = work / '[261013-1200][TEST][確認]番組.m2ts'
        packets = bytearray()
        for i in range(16):
            packet = bytearray(188)
            packet[:5] = bytes([0x47, 1, 0, 0x10 | (i & 15), i])
            packets.extend(packet)
        source.write_bytes(packets)
        stat = source.stat()
        probe = work / 'probe.sh'
        probe.write_text('#!/bin/sh\ncase "$*" in *stream=codec_type*) printf \'{"streams":[{"codec_type":"audio"}]}\\n\';; *) printf \'1\\n\';; esac\n')
        probe.chmod(0o700)
        env = dict(os.environ, DATABASE_CONNECTION=f'jdbc:mariadb://127.0.0.1:{port}/test', DATABASE_USER_NAME='root', DATABASE_PASSWORD='quality-smoke', FFPROBE_PATH=str(probe), PROCESSORQUALITY_MINDURATIONSECONDS='60', PROCESSORQUALITY_DURATIONACTION='QUARANTINE', PROCESSORQUALITY_REQUIREDSTREAMS='VIDEO,AUDIO', PROCESSORQUALITY_STREAMSACTION='QUARANTINE')
        jar = root / 'processor/console/build/libs/console.jar'
        def cli(args, expected=0, stdin=None):
            result = subprocess.run(['java', '-jar', str(jar), *args], input=stdin, env=env, text=True, encoding='utf-8', capture_output=True, timeout=60)
            assert result.returncode == expected, (args, result.returncode, result.stdout, result.stderr)
            assert 'quality-smoke' not in result.stdout + result.stderr
            return result
        before = sql('SELECT (SELECT COUNT(*) FROM program),(SELECT COUNT(*) FROM executed_file),(SELECT COUNT(*) FROM processing_job),(SELECT COUNT(*) FROM processing_attempt),(SELECT COUNT(*) FROM processing_quality_report);')
        cli(['process', '--dry-run', str(source)], 4)
        assert sql('SELECT (SELECT COUNT(*) FROM program),(SELECT COUNT(*) FROM executed_file),(SELECT COUNT(*) FROM processing_job),(SELECT COUNT(*) FROM processing_attempt),(SELECT COUNT(*) FROM processing_quality_report);') == before
        cli(['process', str(source)], 4)
        job = sql('SELECT MAX(id) FROM processing_job;')
        assert sql(f"SELECT state FROM processing_job WHERE id={job};") == 'QUARANTINED'
        assert sql(f"SELECT CONCAT(outcome,':',error_code) FROM processing_attempt WHERE job_id={job};") == 'FAILED:QUALITY_QUARANTINED'
        report = json.loads(cli(['quality', 'report', '--job-id', job, '--json']).stdout)
        evidence = report['reports'][0]
        assert evidence['quarantineLocation'] == str(source)
        assert evidence['findings'][0]['observed'] == '1.0'
        assert evidence['findings'][1]['observed'] == 'AUDIO'
        assert evidence['action'] == 'QUARANTINE'
        cli(['process', str(source)], 4)
        assert sql(f'SELECT COUNT(*) FROM processing_attempt WHERE job_id={job};') == '1'
        cli(['quality', 'release', '--job-id', job, '--dry-run'])
        cli(['quality', 'release', '--job-id', job], stdin='n\n')
        assert sql(f'SELECT state FROM processing_job WHERE id={job};') == 'QUARANTINED'
        source.write_bytes(packets + b'changed')
        cli(['quality', 'release', '--job-id', job, '--yes'], 4)
        source.write_bytes(packets)
        os.utime(source, ns=(stat.st_atime_ns, stat.st_mtime_ns))
        cli(['quality', 'release', '--job-id', job, '--yes'])
        assert sql(f'SELECT state FROM processing_job WHERE id={job};') == 'RETRYABLE'
        assert json.loads(cli(['quality', 'report', '--job-id', job, '--json']).stdout) == report
        cli(['quality', 'report', '--job-id', '9223372036854775807', '--json'], 3)
        cli(['quality', 'report', '--job-id', '0', '--json'], 2)
        assert source.read_bytes() == packets
        assert sorted(p.name for p in work.iterdir()) == sorted([source.name, probe.name])
        print(json.dumps({'result': 'PASS', 'jobId': int(job), 'dryRunExit': 4, 'quarantineExit': 4, 'changedSourceReleaseExit': 4, 'release': 'RETRYABLE', 'originalPreserved': True, 'reportsSurviveRestart': True}, ensure_ascii=False))
finally:
    subprocess.run(['docker', 'rm', '-f', container], capture_output=True)
