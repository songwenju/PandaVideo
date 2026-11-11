#!/usr/bin/env python3
"""Native DSM launcher; no pip dependencies."""
import os
import signal
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
STATE = ROOT / ".service"
PID = STATE / "server.pid"


def running_pid():
    try:
        pid = int(PID.read_text().strip())
        os.kill(pid, 0)
        # Linux /proc prevents signalling a process that reused a stale PID.
        cmdline = Path("/proc/{}/cmdline".format(pid))
        if cmdline.exists() and str(ROOT / "app.py").encode() not in cmdline.read_bytes():
            return None
        return pid
    except (OSError, ValueError):
        return None


def main():
    if sys.version_info < (3, 9):
        sys.exit("需要 Python 3.9 或更高版本；当前版本：" + sys.version.split()[0])
    action = sys.argv[1] if len(sys.argv) > 1 else "status"
    STATE.mkdir(exist_ok=True)
    pid = running_pid()
    if action == "status":
        print("运行中，PID {}".format(pid) if pid else "未运行")
        return
    if action == "stop":
        if pid:
            os.kill(pid, signal.SIGTERM)
            print("已发送停止信号")
        else:
            print("未运行")
        return
    if action != "start":
        sys.exit("用法：service.sh start|stop|status")
    if pid:
        print("已运行，PID {}".format(pid))
        return
    envfile = ROOT / ".env"
    if not envfile.exists():
        envfile.write_bytes((ROOT / ".env.nas.example").read_bytes())
        envfile.chmod(0o600)
    # Environment inherited by app.py overrides its dotenv defaults.
    env = dict(os.environ)
    env.setdefault("APP_HOST", "0.0.0.0")
    with (STATE / "server.log").open("ab") as log:
        process = subprocess.Popen(
            [sys.executable, "-u", str(ROOT / "app.py")],
            cwd=str(ROOT), env=env, stdin=subprocess.DEVNULL,
            stdout=log, stderr=log, start_new_session=True,
        )
    PID.write_text(str(process.pid))
    print("已启动进程 {}；请验证网页并查看 .service/server.log".format(process.pid))


if __name__ == "__main__":
    main()
