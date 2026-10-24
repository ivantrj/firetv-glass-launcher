"""Exercise setup/undo against a fake device; never connects to a TV."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[1]
PKG = 'com.ivantrj.glasslauncher.os7'
SVC = PKG + '/com.ivantrj.glasslauncher.HomeButtonService'
OTHER = 'com.example.reader/.ReaderService'


class Device:
    def __init__(self, sdk='28', services='null', enabled=True):
        self.sdk, self.services, self.enabled = sdk, services, enabled
        self.accessibility = '0' if services == 'null' else '1'
        self.commands = []
        self.closed = False

    def shell(self, command, **kwargs):
        self.commands.append(command)
        if command == 'getprop ro.build.version.sdk':
            return self.sdk
        if command == 'settings get secure enabled_accessibility_services':
            return self.services
        if command == 'settings get secure accessibility_enabled':
            return self.accessibility
        if command.startswith('settings put secure enabled_accessibility_services '):
            self.services = command.split(' ', 4)[4].strip("'")
        elif command == 'settings delete secure enabled_accessibility_services':
            self.services = 'null'
        elif command.startswith('settings put secure accessibility_enabled '):
            self.accessibility = command.rsplit(' ', 1)[1]
        elif command == 'pm list packages':
            return 'package:com.amazon.tv.launcher\npackage:' + PKG
        elif command.startswith('pm list packages -e '):
            return 'package:' + PKG if self.enabled else ''
        elif command.startswith('cmd package resolve-activity '):
            return 'com.amazon.tv.launcher/.ui.HomeActivity_vNext'
        elif command.startswith('dumpsys activity services '):
            return 'received=true hasBound=true'
        return ''

    def close(self):
        self.closed = True

    def mutations(self):
        return [c for c in self.commands if c.startswith(('settings put ', 'settings delete ', 'am ', 'input ', 'pm install '))]


class ControlTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.environment = patch.dict(os.environ, {'TV_HOST': '192.168.1.50'})
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.device = Device()
        fake_tv = types.SimpleNamespace(connect=Mock(return_value=self.device))
        spec = importlib.util.spec_from_file_location('glass_control_under_test', ROOT / 'control-os7.py')
        self.control = importlib.util.module_from_spec(spec)
        with patch.dict('sys.modules', {'tv': fake_tv}):
            spec.loader.exec_module(self.control)
        self.control.STATE = Path(self.tmp.name) / 'before.json'

    def run_command(self, command):
        with patch('sys.argv', ['control-os7.py', command]), patch.object(self.control.time, 'sleep'):
            self.control.main()

    def saved(self, host='192.168.1.50'):
        self.control.STATE.write_text(json.dumps({
            'host': host, 'services': 'null', 'accessibility': '0'
        }), encoding='utf-8')

    def test_wrong_sdk_stops_before_backup_or_mutation(self):
        self.device.sdk = '30'
        with self.assertRaisesRegex(RuntimeError, 'SDK 28'):
            self.run_command('redirect-on')
        self.assertFalse(self.control.STATE.exists())
        self.assertEqual([], self.device.mutations())
        self.assertTrue(self.device.closed)

    def test_missing_launcher_cannot_enable_redirect(self):
        self.device.enabled = False
        with self.assertRaisesRegex(RuntimeError, 'installed and enabled'):
            self.run_command('redirect-on')
        self.assertEqual([], self.device.mutations())

    def test_enable_preserves_existing_accessibility_service(self):
        self.device.services = OTHER
        self.device.accessibility = '1'
        self.run_command('redirect-on')
        self.assertEqual([OTHER, SVC], self.device.services.split(':'))
        self.assertEqual('1', self.device.accessibility)
        self.assertTrue(self.control.STATE.exists())

    def test_undo_preserves_service_added_after_setup(self):
        self.saved()
        self.device.services = SVC + ':' + OTHER
        self.device.accessibility = '1'
        self.run_command('redirect-off')
        self.assertEqual(OTHER, self.device.services)
        self.assertEqual('1', self.device.accessibility)
        self.assertIn('input keyevent KEYCODE_HOME', self.device.commands)

    def test_undo_restores_null_and_disabled_accessibility(self):
        self.saved()
        self.device.services = SVC
        self.device.accessibility = '1'
        self.run_command('redirect-off')
        self.assertEqual('null', self.device.services)
        self.assertEqual('0', self.device.accessibility)

    def test_different_device_backup_cannot_restore(self):
        self.saved(host='other-device')
        with self.assertRaisesRegex(RuntimeError, 'different device'):
            self.run_command('redirect-off')
        self.assertEqual([], self.device.mutations())

    def test_initial_backup_is_not_overwritten(self):
        original = self.control.backup(self.device)
        self.device.services = SVC
        self.device.accessibility = '1'
        self.control.backup(self.device)
        self.assertEqual(original, json.loads(self.control.STATE.read_text(encoding='utf-8')))


if __name__ == '__main__':
    unittest.main()
