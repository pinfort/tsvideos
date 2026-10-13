#!/usr/bin/env python3
"""Packaged callback smoke: stopped NAS recovers during bounded retry; receipts converge.
The fixture deliberately has no original archive receipt, so callback completion is refused
and the original recording remains. No encoder submission is performed.
"""
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import uuid
root = Path(__file__).resolve().parents[1]
prefix = 'tsvideos-retry65-' + uuid.uuid4().hex[:8]
db, nas = prefix + '-db', prefix + '-nas'
process = None
def run(args, **kw):
    return subprocess.run(args, text=True, capture_output=True, check=True, **kw)
def sql(query):
    return run(['docker', 'exec', '-i', db, 'mariadb', '-uroot', '-pretry-db', '-N', 'test'], input=query).stdout.strip()
def port(container, internal):
    return run(['docker', 'port', container, str(internal)]).stdout.strip().split(':')[-1]
def quoted(value):
    return "'" + str(value).replace("'", "''") + "'"
try:
    run(['docker', 'run', '-d', '--name', db, '-e', 'MARIADB_ROOT_PASSWORD=retry-db', '-e', 'MARIADB_DATABASE=test', '-p', '127.0.0.1::3306', 'mariadb:11.8'])
    run(['docker', 'run', '-d', '--name', nas, '-e', 'AVAHI_DISABLE=1', '-e', 'WSDD2_DISABLE=1', '-e', 'NETBIOS_DISABLE=1', '-e', 'ACCOUNT_smoke=retry-nas', '-e', 'UID_smoke=1000', '-e', 'SAMBA_VOLUME_CONFIG_fixture=[fixture]; path=/shares/fixture; valid users = smoke; guest ok = no; read only = no; browseable = yes', '-p', '127.0.0.1::445', 'ghcr.io/servercontainers/samba'])
    run(['docker', 'exec', nas, 'mkdir', '-p', '/shares/fixture'])
    run(['docker', 'exec', nas, 'chown', '1000:1000', '/shares/fixture'])
    for _ in range(60):
        try:
            sql('SELECT 1;')
            with socket.create_connection(('127.0.0.1', int(port(nas, 445))), timeout=1): pass
            break
        except (subprocess.CalledProcessError, OSError): time.sleep(1)
    else: raise RuntimeError('Fixtures not ready')
    sql((root / 'core/src/test/resources/ddl/01_create_database.sql').read_text())
    nas_port = port(nas, 445)
    with tempfile.TemporaryDirectory(prefix=prefix) as directory:
        work = Path(directory) / 'Series'
        split = work / 'tssplitter' / 'split.m2ts'
        succeeded = split.parent / 'succeeded' / split.name
        output = split.parent / 'encoded' / 'output.mp4'
        succeeded.parent.mkdir(parents=True); output.parent.mkdir()
        raw = work / 'original.m2ts'; raw.write_bytes(b'original-recording')
        split.write_bytes(b'split'); succeeded.write_bytes(b'split'); output.write_bytes(b'encoded-video')
        modified = raw.stat().st_mtime_ns // 1000000
        identity = hashlib.sha256(f'{raw}\0{raw.stat().st_size}\0{modified}'.encode()).hexdigest()
        sql(f"INSERT INTO executed_file(id,file,drops,size,recorded_at,channel,title,channelName,duration,status) VALUES(100,{quoted(raw)},0,18,'2026-10-14 00:00:00','TEST','title','Channel',60,'SPLITTED'); INSERT INTO program(id,name,executed_file_id,status) VALUES(100,'retry smoke',100,'REGISTERED'); INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) VALUES(100,100,{quoted(split)},5,60,'REGISTERED'); INSERT INTO processing_job(id,identity_key,input_path,input_size,modified_millis,state,executed_file_id) VALUES(100,'{identity}',{quoted(raw)},{raw.stat().st_size},{modified},'SUBMITTED',100);")
        env = dict(os.environ, DATABASE_CONNECTION=f'jdbc:mariadb://127.0.0.1:{port(db, 3306)}/test', DATABASE_USER_NAME='root', DATABASE_PASSWORD='retry-db', SLACK_WEBHOOK_URL='', SPRING_APPLICATION_JSON=json.dumps({'processor-retry': {'max-attempts': 10, 'initial-delay-millis': 2000, 'max-delay-millis': 2000}}))
        for kind in ['VIDEO', 'ORIGINAL']:
            env.update({f'{kind}_STORE_NAS_URL': f'smb://127.0.0.1:{nas_port}/fixture', f'{kind}_STORE_NAS_USERNAME': 'smoke', f'{kind}_STORE_NAS_PASSWORD': 'retry-nas', f'{kind}_STORE_NAS_BASE_DIR': ''})
        command = ['java', '-jar', str(root / 'processor/console/build/libs/console.jar'), 'after-encode', '--item-id', '10', '--in-path', str(succeeded), '--files', str(output), '--success', '1']
        # Read-only callback dry-run does not create history, manifests or upload data.
        subprocess.run(command + ['--dry-run'], env=env, capture_output=True, check=True)
        assert sql('SELECT COUNT(*) FROM upload_manifest;') == '0'
        assert output.exists()
        run(['docker', 'stop', nas])
        log = Path(directory) / 'callback.log'
        with log.open('w') as stream:
            process = subprocess.Popen(command, env=env, stdout=stream, stderr=subprocess.STDOUT)
            for _ in range(240):
                text = log.read_text(errors='replace')
                if 'UPLOAD retry attempt' in text:
                    run(['docker', 'start', nas]); break
                if process.poll() is not None: raise AssertionError(text)
                time.sleep(.25)
            else: raise AssertionError('No bounded retry observed')
            assert process.wait(timeout=90) == 0
        text = log.read_text(errors='replace')
        retry_lines = [line for line in text.splitlines() if 'ProcessingRetry' in line]
        assert retry_lines and all('retry-nas' not in line and 'retry-db' not in line for line in retry_lines)
        assert sql('SELECT state FROM upload_manifest;') == 'REGISTERED'
        assert sql('SELECT COUNT(*) FROM created_file;') == '1'
        assert sql('SELECT COUNT(*) FROM upload_manifest WHERE claim_token IS NOT NULL;') == '0'
        assert not output.exists()
        assert raw.read_bytes() == b'original-recording'
        assert succeeded.exists()
        subprocess.run(command, env=env, capture_output=True, check=True, timeout=60)
        assert sql('SELECT COUNT(*) FROM created_file;') == '1'
        assert sql('SELECT COUNT(*) FROM upload_manifest;') == '1'
        assert raw.exists()
        remote = run(['docker', 'exec', nas, 'find', '/shares/fixture', '-type', 'f']).stdout.strip().splitlines()
        assert len(remote) == 1 and remote[0].endswith('output.mp4')
        print(json.dumps({'result': 'PASS', 'actualTransientRetryObserved': True, 'receiptState': 'REGISTERED', 'createdRows': 1, 'repeatedCallbackConverged': True, 'originalPreserved': True, 'retryLines': len(retry_lines)}))
finally:
    if process is not None and process.poll() is None:
        process.terminate(); process.wait(timeout=15)
    subprocess.run(['docker', 'rm', '-f', db, nas], capture_output=True)
