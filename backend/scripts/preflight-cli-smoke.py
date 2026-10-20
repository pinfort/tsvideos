#!/usr/bin/env python3
"""Linux packaged CLI smoke; creates/removes dedicated DB, NAS and input fixtures."""
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import uuid
root = Path(__file__).resolve().parents[1]
prefix = 'tsvideos-preflight66-' + uuid.uuid4().hex[:8]
db, nas = prefix + '-db', prefix + '-nas'
def run(args, **kw):
    return subprocess.run(args, text=True, capture_output=True, check=True, **kw)
def sql(query):
    return run(['docker', 'exec', '-i', db, 'mariadb', '-uroot', '-psmoke-db-secret', '-N', 'test'], input=query).stdout.strip()
def port(container, internal):
    return run(['docker', 'port', container, str(internal)]).stdout.strip().split(':')[-1]
try:
    run(['docker', 'run', '-d', '--name', db, '-e', 'MARIADB_ROOT_PASSWORD=smoke-db-secret', '-e', 'MARIADB_DATABASE=test', '-p', '127.0.0.1::3306', 'mariadb:11.8'])
    run(['docker', 'run', '-d', '--name', nas, '-e', 'AVAHI_DISABLE=1', '-e', 'WSDD2_DISABLE=1', '-e', 'NETBIOS_DISABLE=1', '-e', 'ACCOUNT_smoke=smoke-nas-secret', '-e', 'UID_smoke=1000', '-e', 'SAMBA_VOLUME_CONFIG_fixture=[fixture]; path=/shares/fixture; valid users = smoke; guest ok = no; read only = no; browseable = yes', '-p', '127.0.0.1::445', 'ghcr.io/servercontainers/samba'])
    run(['docker', 'exec', nas, 'mkdir', '-p', '/shares/fixture'])
    run(['docker', 'exec', nas, 'chown', '1000:1000', '/shares/fixture'])
    for _ in range(60):
        try:
            sql('SELECT 1;')
            with socket.create_connection(('127.0.0.1', int(port(nas, 445))), timeout=1): pass
            break
        except (subprocess.CalledProcessError, OSError): time.sleep(1)
    else: raise RuntimeError('Fixtures did not start')
    sql('\n'.join(p.read_text() for p in sorted((root / 'database-schema/src/main/resources/db/migrations').glob('*.sql'))))
    with tempfile.TemporaryDirectory(prefix=prefix) as directory, socket.socket() as listener:
        listener.bind(('127.0.0.1', 0)); listener.listen(32)
        work = Path(directory)
        source = work / '[261013-1200][TEST][確認]番組.m2ts'
        packets = bytearray()
        for i in range(16):
            packet = bytearray(188); packet[:5] = bytes([0x47, 1, 0, 0x10 | i, i]); packets.extend(packet)
        source.write_bytes(packets)
        tool = work / 'tool.sh'
        tool.write_text('#!/bin/sh\nprintf called >> "$(dirname "$0")/calls"\nprintf "60\\n"\n'); tool.chmod(0o700)
        env = dict(os.environ, DATABASE_CONNECTION=f'jdbc:mariadb://127.0.0.1:{port(db, 3306)}/test', DATABASE_USER_NAME='root', DATABASE_PASSWORD='smoke-db-secret', TS_SPLITTER_PATH=str(tool), AMATSUKAZE_ADD_TASK_PATH=str(tool), FFPROBE_PATH=str(tool), AMATSUKAZE_HOST='127.0.0.1', AMATSUKAZE_PORT=str(listener.getsockname()[1]), SLACK_WEBHOOK_URL='')
        for kind in ['VIDEO', 'ORIGINAL']:
            env.update({f'{kind}_STORE_NAS_URL': f'smb://127.0.0.1:{port(nas, 445)}/fixture', f'{kind}_STORE_NAS_USERNAME': 'smoke', f'{kind}_STORE_NAS_PASSWORD': 'smoke-nas-secret', f'{kind}_STORE_NAS_BASE_DIR': ''})
        jar = root / 'processor/console/build/libs/console.jar'
        def cli(args, expected=0, changes=None):
            result = subprocess.run(['java', '-jar', str(jar), *args], env=dict(env, **(changes or {})), text=True, encoding='utf-8', capture_output=True, timeout=90)
            assert result.returncode == expected, (args, result.returncode, result.stdout, result.stderr)
            assert 'smoke-db-secret' not in result.stdout + result.stderr
            assert 'smoke-nas-secret' not in result.stdout + result.stderr
            return result
        counts = 'SELECT (SELECT COUNT(*) FROM program),(SELECT COUNT(*) FROM executed_file),(SELECT COUNT(*) FROM splitted_file),(SELECT COUNT(*) FROM created_file);'
        before = sql(counts)
        report = json.loads(cli(['preflight', '--json', '--dry-run', str(source)]).stdout)
        checks = {item['id']: item for item in report['checks']}
        assert checks['database.connectivity']['status'] == 'PASS'
        assert checks['nas.video-store-nas.read']['status'] == 'PASS'
        assert checks['encoder.connectivity']['status'] == 'PASS'
        assert checks['tool.ts-splitter-path.execution']['status'] == 'UNCHECKED'
        assert '番組' in checks['input.0.0']['reason']
        assert not (work / 'calls').exists()
        write = json.loads(cli(['preflight', '--json', '--check-write', str(source)]).stdout)
        assert sum(c['status'] == 'PASS' and c['id'].endswith('.cleanup') for c in write['checks']) == 2
        assert run(['docker', 'exec', nas, 'find', '/shares/fixture', '-type', 'f']).stdout.strip() == ''
        cli(['preflight', '--dry-run', '--check-write', str(source)], 2)
        cli(['process', '--dry-run', str(source)])
        calls = (work / 'calls').read_bytes()
        cli(['process', str(source)], 1, {'FFPROBE_PATH': str(work / 'missing-secret-tool')})
        assert (work / 'calls').read_bytes() == calls
        invalid = json.loads(cli(['preflight', '--json', str(source)], 1, {'DATABASE_CONNECTION': 'jdbc:smoke-db-secret', 'AMATSUKAZE_PORT': 'invalid'}).stdout)
        assert sum(c['status'] == 'FAIL' for c in invalid['checks']) >= 2
        assert sql(counts) == before
        assert source.read_bytes() == packets
        assert not (work / 'tssplitter').exists()
        print(json.dumps({'result': 'PASS', 'readonlyDatabaseUnchanged': True, 'nasReadAndExclusiveWriteCleanup': True, 'processGateBeforeInspection': True, 'invalidConfigurationAggregated': True, 'japaneseJson': True, 'originalPreserved': True}))
finally:
    subprocess.run(['docker', 'rm', '-f', db, nas], capture_output=True)
