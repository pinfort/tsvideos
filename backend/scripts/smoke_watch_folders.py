"""Linux watch smoke: live polling, bounded queue, SIGTERM, restart dedupe and read-only capacity.
Run: python3 scripts/smoke_watch_folders.py [backend-directory]
Requires Docker and JAVA_HOME (Java 24); builds are deliberately separate.
"""
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys
import tempfile
import time
import uuid

root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[1]
java = str(Path(os.environ["JAVA_HOME"]) / "bin/java")
container = "tsvideos-shutdown-" + uuid.uuid4().hex[:12]

def docker(*args, **kwargs):
    return subprocess.run(["docker", *args], check=True, capture_output=True, text=True, **kwargs).stdout.strip()

def sql(query):
    return docker("exec", container, "mariadb", "-uroot", "-psmoke", "-N", "test", "-e", query)

process = None
try:
    docker("run", "--rm", "-d", "--name", container, "-e", "MARIADB_ROOT_PASSWORD=smoke",
           "-e", "MARIADB_DATABASE=test", "-p", "127.0.0.1::3306", "mariadb:11.4")
    for _ in range(120):
        try:
            sql("SELECT 1")
            break
        except subprocess.CalledProcessError:
            time.sleep(.25)
    else:
        raise AssertionError("MariaDB did not become ready")
    docker("exec", "-i", container, "mariadb", "-uroot", "-psmoke", "test",
           input=(root / "core/src/test/resources/ddl/01_create_database.sql").read_text())
    port = docker("port", container, "3306/tcp").split(":")[-1]
    with tempfile.TemporaryDirectory(prefix="tsvideos-shutdown-") as directory:
        folder = Path(directory)
        packet = bytearray(188)
        packet[:4] = bytes([0x47, 0x01, 0, 0x10])
        data = bytearray()
        for i in range(200):
            packet[3] = 0x10 | (i & 15)
            packet[4] = i & 255
            data.extend(packet)
        recordings = folder / "recordings"
        recordings.mkdir()
        inputs = [recordings / f"[261013-1200][CH][Channel]{i}.m2ts" for i in range(4)]
        for path in inputs:
            path.write_bytes(data)
            marker = Path(str(path) + ".ready")
            marker.write_text("done")
            os.utime(marker, ns=(path.stat().st_mtime_ns + 1000000000,) * 2)
        pidfile = folder / "child.pid"
        probe = folder / "ffprobe"
        probe.write_text("#!/bin/bash\nsleep 120 &\necho $! > " + shlex.quote(str(pidfile)) + "\nwait\n")
        probe.chmod(0o700)
        env = dict(os.environ, DATABASE_CONNECTION="jdbc:mariadb://127.0.0.1:" + port + "/test",
                   DATABASE_USER_NAME="root", DATABASE_PASSWORD="smoke", FFPROBE_PATH=str(probe),
                   PROCESSOR_SHUTDOWN_GRACE_SECONDS="3", PROCESSOR_PARALLEL_WORKERS="1", PROCESSOR_PARALLEL_QUEUE_CAPACITY="1")
        jar = str(root / "processor/console/build/libs/console.jar")
        arguments = [java, "-jar", jar, "watch", str(folder / "missing"), str(recordings), "--completion", "marker", "--poll-seconds", "1"]
        with (folder / "watch.log").open("w+") as log:
            process = subprocess.Popen(arguments, env=env, stdout=log, stderr=log)
            deadline = time.monotonic() + 60
            while not pidfile.exists():
                if process.poll() is not None or time.monotonic() > deadline:
                    log.seek(0)
                    raise AssertionError("watch did not start: " + log.read())
                time.sleep(.05)
            child = int(pidfile.read_text())
            time.sleep(.15)
            start = time.monotonic()
            process.send_signal(signal.SIGTERM)
            code = process.wait(timeout=6)
            elapsed = time.monotonic() - start
            assert code in (130, 143, -signal.SIGTERM) and elapsed < 5, (code, elapsed)
            assert not (Path("/proc") / str(child)).exists(), "child PID not reaped"
            assert sql("SELECT COUNT(*) FROM processing_job") == "1", "queued work was claimed"
            assert sql("SELECT outcome FROM processing_attempt") == "INTERRUPTED"
            assert sql("SELECT outcome FROM processing_stage_event") == "INTERRUPTED"
            log.seek(0)
            assert "WATCH_DIRECTORY_UNAVAILABLE" in log.read()
        owned_path = Path(sql("SELECT input_path FROM processing_job"))
        for path in inputs:
            assert path.read_bytes() == data and Path(str(path) + ".ready").read_text() == "done"
            if path != owned_path:
                Path(str(path) + ".ready").unlink()  # test fixture now exposes only the already claimed version
        with (folder / "restart.log").open("w+") as log:
            process = subprocess.Popen(arguments, env=env, stdout=log, stderr=log)
            deadline = time.monotonic() + 45
            while True:
                log.flush()
                content = (folder / "restart.log").read_text()
                if "WATCH_EXISTING_JOB" in content:
                    break
                assert process.poll() is None and time.monotonic() < deadline, content
                time.sleep(.1)
            time.sleep(2.2)
            process.send_signal(signal.SIGTERM)
            assert process.wait(timeout=6) in (130, 143, -signal.SIGTERM)
            assert (folder / "restart.log").read_text().count("WATCH_EXISTING_JOB") == 1
            assert sql("SELECT COUNT(*) FROM processing_attempt") == "1"
        before_files = {str(path): (path.read_bytes(), path.stat().st_mtime_ns) for path in recordings.iterdir()}
        before_db = sql("SELECT COUNT(*) FROM processing_attempt")
        dry = subprocess.run([java, "-jar", jar, "watch", str(recordings), "--completion", "marker", "--dry-run", "--max-observations", "2"],
                             env=env, capture_output=True, text=True, timeout=30)
        assert dry.returncode == 4 and "WATCH_CAPACITY_EXCEEDED" in dry.stdout, (dry.returncode, dry.stdout, dry.stderr)
        assert sql("SELECT COUNT(*) FROM processing_attempt") == before_db
        assert before_files == {str(path): (path.read_bytes(), path.stat().st_mtime_ns) for path in recordings.iterdir()}
        print(f"PASS: watch existing marker input, independent missing directory, queue1, SIGTERM={code}/{elapsed:.3f}s, child PID gone, queued work unclaimed, restart dedupe, dry-run capacity exit4 with no DB/file writes")

finally:
    if process is not None and process.poll() is None:
        process.kill()
        process.wait()
    docker("stop", container)
