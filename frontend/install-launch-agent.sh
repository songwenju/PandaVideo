#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DOMAIN="gui/$(id -u)"
LEGACY_LABEL="com.local.nas-video-downloader"
APP_LABEL="$LEGACY_LABEL.app"
ARIA2_LABEL="$LEGACY_LABEL.aria2"
STAGING_LABEL="$LEGACY_LABEL.staging"
LAUNCH_AGENTS="$HOME/Library/LaunchAgents"
LOG_DIR="$HOME/Library/Logs/PandaVideoDownloader"
RUNTIME_DIR="$HOME/Library/Application Support/PandaVideoDownloader"
APP_PLIST="$LAUNCH_AGENTS/$APP_LABEL.plist"

if [ ! -f "$SCRIPT_DIR/.env" ]; then
  echo "Missing $SCRIPT_DIR/.env"
  echo "Copy .env.example to .env and fill in the required credentials first."
  exit 1
fi

mkdir -p "$LAUNCH_AGENTS" "$LOG_DIR" "$RUNTIME_DIR"

# Retire only this project's old services; downloaded files stay in place.
for retired_label in "$LEGACY_LABEL" "$ARIA2_LABEL" "$STAGING_LABEL"; do
  if launchctl print "$DOMAIN/$retired_label" >/dev/null 2>&1; then
    launchctl bootout "$DOMAIN/$retired_label"
  fi
  launchctl disable "$DOMAIN/$retired_label"
  rm -f "$LAUNCH_AGENTS/$retired_label.plist"
done

/usr/bin/rsync -a --delete \
  --exclude '.git/' \
  --exclude '.local/' \
  --exclude '.service/' \
  --exclude '.cache/' \
  --exclude '.aria2.session' \
  --exclude '.transfer-state.json' \
  --exclude '*.log' \
  "$SCRIPT_DIR/" "$RUNTIME_DIR/"
chmod 600 "$RUNTIME_DIR/.env"
chmod +x "$RUNTIME_DIR/run-app.sh"

write_plist() {
  plist=$1
  label=$2
  program=$3
  stdout_path=$4
  stderr_path=$5
  cat > "$plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>$label</string>
  <key>ProgramArguments</key>
  <array>
    <string>$program</string>
  </array>
  <key>WorkingDirectory</key>
  <string>$RUNTIME_DIR</string>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
  <key>StandardOutPath</key>
  <string>$stdout_path</string>
  <key>StandardErrorPath</key>
  <string>$stderr_path</string>
</dict>
</plist>
EOF
}

write_plist \
  "$APP_PLIST" "$APP_LABEL" "$RUNTIME_DIR/run-app.sh" \
  "$LOG_DIR/app.log" "$LOG_DIR/app-error.log"
launchctl enable "$DOMAIN/$APP_LABEL"
if launchctl print "$DOMAIN/$APP_LABEL" >/dev/null 2>&1; then
  # launchd retains the registered program and working directory after a plist
  # changes. Reload the agent so a renamed runtime directory takes effect.
  launchctl bootout "$DOMAIN/$APP_LABEL"
fi
launchctl bootstrap "$DOMAIN" "$APP_PLIST"
launchctl kickstart -k "$DOMAIN/$APP_LABEL"
attempt=0
while ! lsof -tiTCP:8766 -sTCP:LISTEN >/dev/null 2>&1; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 40 ]; then
    echo "Web app failed to restart on port 8766" >&2
    exit 1
  fi
  sleep 0.25
done
echo "Installed $APP_LABEL; retired download and staging agents"
echo "Open http://127.0.0.1:8766"
