"""Windows-friendly setup for this source-built Fire OS 7 port.

Set TV_HOST first. Run with the Python venv containing tools/adb/requirements.txt.
Commands: install, launch, redirect-on, redirect-off. Never disables Amazon Home.
"""
import json
import os
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / 'tools' / 'adb'))
import tv

PACKAGE = 'com.ivantrj.glasslauncher.os7'
ACTIVITY = PACKAGE + '/com.ivantrj.glasslauncher.HomeActivity'
SERVICE = PACKAGE + '/com.ivantrj.glasslauncher.HomeButtonService'
STATE = ROOT / 'local-state' / 'before-glass.json'
APK = ROOT / 'launcher/app/build/outputs/apk/debug/app-debug.apk'


def shell(dev, command):
    return (dev.shell(command, read_timeout_s=30) or '').strip()


def backup(dev):
    if STATE.exists():
        state = json.loads(STATE.read_text(encoding='utf-8'))
        if state['host'] != os.environ['TV_HOST']:
            raise RuntimeError('This backup belongs to a different device; keep it and use a separate project copy.')
        return state
    commands = {
        'services': 'settings get secure enabled_accessibility_services',
        'accessibility': 'settings get secure accessibility_enabled',
        'home': 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME',
        'packages': 'pm list packages',
        'disabled': 'pm list packages -d',
        'getprop': 'getprop',
        'system': 'settings list system',
        'secure': 'settings list secure',
        'global': 'settings list global',
    }
    state = {'host': os.environ['TV_HOST']}
    for name, command in commands.items():
        state[name] = shell(dev, command)
    if not state['packages'].startswith('package:') or 'com.amazon.tv.launcher/' not in state['home']:
        raise RuntimeError('Could not verify stock Home and backup; no changes made.')
    STATE.parent.mkdir(parents=True, exist_ok=True)
    STATE.write_text(json.dumps(state, indent=2), encoding='utf-8')
    return state


def services(dev):
    value = shell(dev, 'settings get secure enabled_accessibility_services')
    return [item for item in value.split(':') if item and item != 'null']


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in ('install', 'launch', 'redirect-on', 'redirect-off'):
        raise SystemExit(__doc__)
    command = sys.argv[1]
    dev = tv.connect(timeout=30)
    try:
        if command in ('install', 'redirect-on'):
            sdk = shell(dev, 'getprop ro.build.version.sdk')
            if sdk != '28':
                raise RuntimeError('This device-specific port was prepared for SDK 28 only; stopped.')
            backup(dev)
        if command == 'install':
            if not APK.is_file():
                raise RuntimeError('Build from source first; APK missing.')
            subprocess.run([sys.executable, str(ROOT / 'tools/adb/b64push.py'), str(APK),
                            '/data/local/tmp/glass-os7.apk'], check=True)
            result = shell(dev, 'pm install -r /data/local/tmp/glass-os7.apk')
            print(result)
            if result.strip() != 'Success':
                raise RuntimeError('Install refused; stopped without changing Home or enabling the redirect.')
            shell(dev, 'rm -f /data/local/tmp/glass-os7.apk')
            print(shell(dev, 'am start -n ' + ACTIVITY))
        elif command == 'launch':
            print(shell(dev, 'am start -n ' + ACTIVITY))
        elif command == 'redirect-on':
            if 'package:' + PACKAGE not in shell(dev, 'pm list packages -e ' + PACKAGE).splitlines():
                raise RuntimeError('Launcher must be installed and enabled first.')
            current = services(dev)
            if SERVICE not in current:
                current.append(SERVICE)
            # Only names returned by Android plus our fixed service enter this quoted value.
            value = ':'.join(current)
            if any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_./:$' for c in value):
                raise RuntimeError('Unexpected accessibility component format; stopped.')
            print(shell(dev, "settings put secure enabled_accessibility_services '" + value + "'"))
            print(shell(dev, 'settings put secure accessibility_enabled 1'))
            if SERVICE not in services(dev):
                raise RuntimeError('Fire OS refused the redirect setting; stopped.')
            for attempt in range(20):
                binding = shell(dev, 'dumpsys activity services ' + PACKAGE)
                if 'hasBound=true' in binding and 'received=true' in binding:
                    break
                time.sleep(0.5)
            else:
                raise RuntimeError('Service configured but not bound; run redirect-off and inspect Accessibility.')
            # Binding can precede the service connection callback; let setup settle.
            time.sleep(2)
            print(shell(dev, 'am start -n ' + ACTIVITY))
        else:
            state = json.loads(STATE.read_text(encoding='utf-8'))
            if state['host'] != os.environ['TV_HOST']:
                raise RuntimeError('Backup belongs to a different device; stopped.')
            remaining = [s for s in services(dev) if s != SERVICE]
            if remaining:
                value = ':'.join(remaining)
                if any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_./:$' for c in value):
                    raise RuntimeError('Unexpected accessibility component format; stopped.')
                shell(dev, "settings put secure enabled_accessibility_services '" + value + "'")
            elif state['services'] == 'null':
                shell(dev, 'settings delete secure enabled_accessibility_services')
            else:
                shell(dev, "settings put secure enabled_accessibility_services ''")
            if not remaining:
                if state['accessibility'] == 'null':
                    shell(dev, 'settings delete secure accessibility_enabled')
                elif state['accessibility'] in ('0', '1'):
                    shell(dev, 'settings put secure accessibility_enabled ' + state['accessibility'])
            if SERVICE in services(dev):
                raise RuntimeError('Redirect is still enabled; check Accessibility settings.')
            print(shell(dev, 'input keyevent KEYCODE_HOME'))
            print('Home redirect removed. Amazon launcher was kept enabled throughout.')
    finally:
        dev.close()


if __name__ == '__main__':
    main()
