#!/bin/sh
set -eu
APP_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export APP_DIR
PYTHON_BIN=${PYTHON_BIN:-}
if [ -z "$PYTHON_BIN" ]; then
  for candidate in /usr/local/bin/python3.9 /var/packages/Python3.9/target/usr/local/bin/python3.9 /var/packages/Python3.9/target/usr/bin/python3.9 /usr/local/bin/python3 /var/packages/Python3/target/usr/local/bin/python3 /usr/bin/python3; do
    if [ -x "$candidate" ] && "$candidate" -c 'import sys; sys.exit(0 if sys.version_info >= (3,9) else 1)'; then
      PYTHON_BIN=$candidate
      break
    fi
  done
fi
if [ -z "$PYTHON_BIN" ]; then
  echo "请先在套件中心安装 Python 3，或设置 PYTHON_BIN。"
  exit 1
fi
exec "$PYTHON_BIN" "$APP_DIR/service.py" "${1:-status}"
