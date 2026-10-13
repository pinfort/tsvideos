"""Linux parallel smoke: duplicate claims, independent jobs, queued cancellation and aggregate exit.
Run: python3 scripts/smoke_parallel_processing.py [backend-directory]
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
        inputs = []
        for index in range(6):
            directory = folder / str(index)
            directory.mkdir()
            recording = directory / "[261013-1200][CH][Channel]same.m2ts"
            recording.write_bytes(data)
            inputs.append(recording)
        pid_directory = folder / "pids"
        pid_directory.mkdir()
        probe = folder / "ffprobe"
        probe.write_text("#!/bin/bash\nsleep 120 &\necho $! > " + shlex.quote(str(pid_directory)) + "/$$.pid\nwait\n")
        probe.chmod(0o700)
        env = dict(os.environ, DATABASE_CONNECTION="jdbc:mariadb://127.0.0.1:" + port + "/test",
                   DATABASE_USER_NAME="root", DATABASE_PASSWORD="smoke", FFPROBE_PATH=str(probe),
                   PROCESSOR_SHUTDOWN_GRACE_SECONDS="3", PROCESSOR_PARALLEL_WORKERS="2", PROCESSOR_PARALLEL_QUEUE_CAPACITY="1")
        with (folder / "cli.log").open("w+") as log:
            process = subprocess.Popen([java, "-jar", str(root / "processor/console/build/libs/console.jar"),
                                        "process", str(inputs[0]), str(inputs[0]), *map(str, inputs[1:])], env=env, stdout=log, stderr=log)
            deadline = time.monotonic() + 60
            while len(list(pid_directory.glob("*.pid"))) < 2:
                if process.poll() is not None or time.monotonic() > deadline:
                    log.seek(0)
                    raise AssertionError("CLI did not enter owned probe: " + log.read())
                time.sleep(.05)
            children = [int(path.read_text()) for path in pid_directory.glob("*.pid")]
            time.sleep(.1)
            start = time.monotonic()
            process.send_signal(signal.SIGTERM)
            code = process.wait(timeout=6)
            elapsed = time.monotonic() - start
            assert code in (130, 143, -signal.SIGTERM), code
            assert elapsed < 5, elapsed
            assert sql("SELECT COUNT(*) FROM processing_job") == "2", "queued file was admitted or duplicate claim succeeded"
            assert sql("SELECT COUNT(*) FROM processing_attempt WHERE outcome='INTERRUPTED'") == "2"
            assert sql("SELECT COUNT(*) FROM processing_stage_event WHERE outcome='INTERRUPTED'") == "2"
            assert sql("SELECT COUNT(*) FROM processing_attempt WHERE error_code='INTERRUPTED'") == "2"
            assert sql("SELECT COUNT(*) FROM executed_file") == "0"
            assert all(path.read_bytes() == data for path in inputs)
            for child in children:
                assert not (Path("/proc") / str(child) / "stat").exists(), "owned child PID was not reaped"
            # A new JVM sees the durable state, independent of the terminated invocation.
            history = subprocess.run([java, "-jar", str(root / "processor/console/build/libs/console.jar"),
                                      "history", "--json"], env=env, capture_output=True, text=True, timeout=30)
            assert history.returncode == 0, history.stderr
            import json
            assert all(item["attempt"]["outcome"] == "INTERRUPTED" for item in json.loads(history.stdout)["attempts"])
            bad = folder / "invalid"
            bad.mkdir()
            for name in ("bad-a.m2ts", "bad-b.m2ts"):
                (bad / name).write_bytes(data)
            batch = subprocess.run([java, "-jar", str(root / "processor/console/build/libs/console.jar"), "process", str(bad)],
                                   env=env, capture_output=True, text=True, timeout=30)
            assert batch.returncode == 1, (batch.returncode, batch.stderr)
            assert batch.stdout.count("] FAILED") == 2, batch.stdout
            assert sql("SELECT COUNT(*) FROM processing_attempt WHERE outcome='FAILED'") == "2"
            before = sql("SELECT COUNT(*) FROM processing_attempt")
            probe.write_text("#!/bin/bash\necho 1\n")
            dryrun = subprocess.run([java, "-jar", str(root / "processor/console/build/libs/console.jar"), "process", "--dry-run", *map(str, inputs)],
                                    env=env, capture_output=True, text=True, timeout=30)
            assert dryrun.returncode == 0, dryrun.stderr
            assert dryrun.stdout.count("] DRY_RUN") == 6, dryrun.stdout
            assert sql("SELECT COUNT(*) FROM processing_attempt") == before
            print(f"PASS: workers=2 queue=1, duplicate claim excluded, SIGTERM exit={code} elapsed={elapsed:.3f}s, 2 child PIDs reaped, queued jobs unclaimed, interrupted history durable; aggregate failure exit1; parallel dry-run no writes")
finally:
    if process is not None and process.poll() is None:
        process.kill()
        process.wait()
    docker("stop", container)
