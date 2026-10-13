"""Linux packaged batch reporting smoke; requires Docker and JAVA_HOME (Java 24)."""
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

root = Path(__file__).resolve().parents[1]
java = str(Path(os.environ['JAVA_HOME']) / 'bin/java')
container = 'tsvideos-batch-' + uuid.uuid4().hex[:12]
requests = []
class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        requests.append(json.loads(self.rfile.read(int(self.headers['Content-Length']))))
        self.send_response(503)
        self.end_headers()
    def log_message(self, *args): pass
server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
def docker(*args, **kwargs):
    return subprocess.run(['docker', *args], check=True, capture_output=True, text=True, **kwargs).stdout.strip()
def sql(query):
    return docker('exec', container, 'mariadb', '-uroot', '-psmoke', '-N', 'test', '-e', query)
process = None
try:
    docker('run', '--rm', '-d', '--name', container, '-e', 'MARIADB_ROOT_PASSWORD=smoke', '-e', 'MARIADB_DATABASE=test', '-p', '127.0.0.1::3306', 'mariadb:11.4')
    for _ in range(120):
        try:
            sql('SELECT 1')
            break
        except subprocess.CalledProcessError: time.sleep(.25)
    docker('exec', '-i', container, 'mariadb', '-uroot', '-psmoke', 'test', input=(root / 'core/src/test/resources/ddl/01_create_database.sql').read_text())
    port = docker('port', container, '3306/tcp').split(':')[-1]
    with tempfile.TemporaryDirectory(prefix='tsvideos-batch-') as temp:
        folder = Path(temp)
        empty = folder / 'empty'; empty.mkdir()
        probe = folder / 'ffprobe'; probe.write_text('#!/bin/bash\necho 1\n'); probe.chmod(0o700)
        packet = bytearray(188); packet[:4] = bytes([0x47, 0x01, 0, 0x10])
        data = bytearray()
        for i in range(200):
            packet[3] = 0x10 | (i & 15); packet[4] = i & 255; data.extend(packet)
        recording = folder / '[261013-1200][CH][Channel]日本語.m2ts'; recording.write_bytes(data)
        bad = folder / 'invalid.m2ts'; bad.write_bytes(data)
        env = dict(os.environ, DATABASE_CONNECTION='jdbc:mariadb://127.0.0.1:' + port + '/test', DATABASE_USER_NAME='root', DATABASE_PASSWORD='smoke', FFPROBE_PATH=str(probe), PROCESSOR_SHUTDOWN_GRACE_SECONDS='3', PROCESSOR_PARALLEL_WORKERS='1', PROCESSOR_PARALLEL_QUEUE_CAPACITY='1', SLACK_WEBHOOK_URL=f'http://127.0.0.1:{server.server_port}/hook')
        command = [java, '-jar', str(root / 'processor/console/build/libs/console.jar'), 'process', '--json']
        def run(*args, code=0):
            result = subprocess.run([*command, *map(str,args)], env=env, capture_output=True, text=True, encoding='utf-8', timeout=40)
            assert result.returncode == code, (result.returncode, result.stderr)
            report = json.loads(result.stdout)
            assert report['schemaVersion'] == 1
            assert report['batch']['exitCode'] == code
            return report['batch'], result.stderr
        report, _ = run('--slack', 'summary', empty)
        assert report['recordings'] == [] and len(requests) == 1
        assert 'failed=0' in requests[0]['text']
        before = sql('SELECT COUNT(*) FROM processing_attempt')
        report, stderr = run('--dry-run', '--slack', 'summary', recording)
        assert report['planned'] == 1 and report['recordings'][0]['stageTimings'] == []
        assert '日本語' in report['recordings'][0]['path'] and 'DRY_RUN' in stderr
        assert len(requests) == 1 and sql('SELECT COUNT(*) FROM processing_attempt') == before
        report, _ = run('--dry-run', recording, folder / 'missing', code=4)
        assert [r['outcome'] for r in report['recordings']] == ['DRY_RUN', 'FAILED']
        assert len(requests) == 1
        report, _ = run('--slack', 'none', bad, code=1)
        assert report['failed'] == 1 and len(requests) == 1
        timings = report['recordings'][0]['stageTimings']
        assert timings and timings[0]['stage'] == 'INSPECT' and timings[0]['elapsedMillis'] >= 0, timings
        assert sql("SELECT COUNT(*) FROM processing_stage_event WHERE stage='INSPECT' AND outcome='FAILED'") == '1'
        locked = folder / 'inaccessible'; locked.mkdir(); locked.chmod(0)
        try:
            report, _ = run('--slack', 'none', locked, code=1)
            assert report['recordings'][0]['reasonCode'] == 'DIRECTORY_INACCESSIBLE'
        finally: locked.chmod(0o700)
        pid = folder / 'child.pid'
        probe.write_text('#!/bin/bash\nsleep 120 &\necho $! > ' + str(pid) + '\nwait\n')
        with (folder / 'stdout').open('w+') as out, (folder / 'stderr').open('w+') as err:
            process = subprocess.Popen([*command, '--slack', 'none', str(recording), str(recording), str(recording)], env=env, stdout=out, stderr=err)
            deadline = time.monotonic() + 40
            while not pid.exists():
                assert process.poll() is None and time.monotonic() < deadline
                time.sleep(.05)
            child = int(pid.read_text()); start = time.monotonic(); process.send_signal(signal.SIGTERM)
            code = process.wait(timeout=6); elapsed = time.monotonic() - start
            assert code in (130,143,-signal.SIGTERM) and elapsed < 5, (code, elapsed)
            out.seek(0); output = out.read()
            if output:
                report = json.loads(output)['batch']
                assert report['interrupted'] and report['exitCode'] == 130
                assert report['interruptedRecordings'] == 1
                assert report['notStarted'] >= 1
            assert not Path('/proc', str(child)).exists()
            assert sql("SELECT COUNT(*) FROM processing_attempt WHERE outcome='INTERRUPTED'") == '1'
            print(f'PASS JSON UTF8/empty/dry-run/partial exit4/inaccessible/actual DB stage timing/Slack 503 isolation/none; SIGTERM={code} {elapsed:.3f}s partialJSON={bool(output)} child reaped')
finally:
    if process is not None and process.poll() is None:
        process.kill(); process.wait()
    server.shutdown(); server.server_close()
    docker('stop', container)