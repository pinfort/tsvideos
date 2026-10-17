"""Linux integration smoke: packaged CLI + disposable MariaDB + actual SIGTERM.
Run: python3 scripts/smoke_graceful_shutdown.py [backend-directory]
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
        first = folder / "[261013-1200][CH][Channel]a.m2ts"
        second = folder / "[261013-1200][CH][Channel]b.m2ts"
        first.write_bytes(data)
        second.write_bytes(data)
        pidfile = folder / "child.pid"
        probe = folder / "ffprobe"
        probe.write_text("#!/bin/bash\nsleep 120 &\necho $! > " + shlex.quote(str(pidfile)) + "\nwait\n")
        probe.chmod(0o700)
        env = dict(os.environ, DATABASE_CONNECTION="jdbc:mariadb://127.0.0.1:" + port + "/test",
                   DATABASE_USER_NAME="root", DATABASE_PASSWORD="smoke", FFPROBE_PATH=str(probe),
                   PROCESSOR_SHUTDOWN_GRACE_SECONDS="3")
        with (folder / "cli.log").open("w+") as log:
            process = subprocess.Popen([java, "-jar", str(root / "processor/console/build/libs/console.jar"),
                                        "process", str(folder)], env=env, stdout=log, stderr=log)
            deadline = time.monotonic() + 60
            while not pidfile.exists():
                if process.poll() is not None or time.monotonic() > deadline:
                    log.seek(0)
                    raise AssertionError("CLI did not enter owned probe: " + log.read())
                time.sleep(.05)
            child = int(pidfile.read_text())
            time.sleep(.1)
            start = time.monotonic()
            process.send_signal(signal.SIGTERM)
            code = process.wait(timeout=6)
            elapsed = time.monotonic() - start
            assert code in (130, 143, -signal.SIGTERM), code
            assert elapsed < 5, elapsed
            assert sql("SELECT COUNT(*) FROM processing_job") == "1", "subsequent file was admitted"
            assert sql("SELECT outcome FROM processing_attempt") == "INTERRUPTED"
            assert sql("SELECT outcome FROM processing_stage_event") == "INTERRUPTED"
            assert sql("SELECT error_code FROM processing_attempt") == "INTERRUPTED"
            assert sql("SELECT COUNT(*) FROM executed_file") == "0"
            assert first.read_bytes() == data and second.read_bytes() == data
            stat = Path("/proc") / str(child) / "stat"
            assert not stat.exists(), "owned child PID was not reaped"
            # A new JVM sees the durable state, independent of the terminated invocation.
            history = subprocess.run([java, "-jar", str(root / "processor/console/build/libs/console.jar"),
                                      "history", "--json"], env=env, capture_output=True, text=True, timeout=30)
            assert history.returncode == 0, history.stderr
            import json
            assert json.loads(history.stdout)["attempts"][0]["attempt"]["outcome"] == "INTERRUPTED"
            print(f"PASS: SIGTERM exit={code}, elapsed={elapsed:.3f}s, child stopped, originals retained, next file unclaimed, restarted history INTERRUPTED")
finally:
    if process is not None and process.poll() is None:
        process.kill()
        process.wait()
    docker("stop", container)
