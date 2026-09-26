#!/usr/bin/env python3
"""Installer regression checks; never touches a real service or model."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('install-daemon')

class InstallTest(unittest.TestCase):
    def run_install(self, mode, existing=False, bad=False):
        temp = tempfile.TemporaryDirectory(prefix='voxtype-installer-')
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        bin = root / 'bin'; bin.mkdir()
        data = root / 'data' / 'voxtype-mobile'
        config = root / 'config'
        (config / 'voxtype').mkdir(parents=True)
        if existing or bad:
            (config / 'voxtype/config.toml').write_text('engine = "whisper"\n' if bad else 'engine="parakeet"\n[parakeet]\nmodel="parakeet-tdt-0.6b-v3"\n')
        if existing:
            data.mkdir(parents=True)
            (data / 'voxtype').write_text('old binary')
            (data / 'token').write_text('preserved-token')
            dropin = config / 'systemd/user/voxtype.service.d/90-mobile.conf'
            dropin.parent.mkdir(parents=True); dropin.write_text('old unit')
        executable = bin / 'daemon'
        executable.write_text('#!/bin/sh\nprintf "download\\n" >> "$LOG"\n')
        executable.chmod(0o755)
        systemctl = bin / 'systemctl'
        systemctl.write_text('''#!/bin/bash
printf '%s\\n' "$*" >> "$LOG"
case "$*" in
  *show-environment*) [[ "$MODE" != manual ]];;
  *LoadState*) if [[ "$EXISTING" == yes ]]; then echo loaded; else echo not-found; fi;;
  *ExecStart*) echo "argv[]=$XDG_DATA_HOME/voxtype-mobile/voxtype daemon ;";;
  *is-active*) [[ "$MODE" != fail && "$EXISTING" == yes ]] || [[ -f "$HOME/restarted" ]];;
  *restart*) touch "$HOME/restarted";;
esac
''')
        systemctl.chmod(0o755)
        python = bin / 'python3'
        python.write_text('''#!/bin/bash
if [[ "${2:-}" == */token ]]; then cat >/dev/null; [[ "$MODE" != fail ]]; else exec /usr/bin/python3 "$@"; fi
''')
        python.chmod(0o755)
        pgrep = bin / 'pgrep'; pgrep.write_text('#!/bin/sh\nexit 1\n'); pgrep.chmod(0o755)
        env = dict(os.environ, HOME=str(root), XDG_DATA_HOME=str(root/'data'), XDG_CONFIG_HOME=str(config), PATH=str(bin)+':/usr/bin:/bin', LOG=str(root/'log'), MODE=mode, EXISTING='yes' if existing else 'no')
        result = subprocess.run(['bash',str(SCRIPT),str(executable)], env=env, capture_output=True, text=True)
        return root, result

    def test_fresh_systemd_installs_enables_and_only_downloads_model(self):
        root, result = self.run_install('fresh')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('WantedBy=default.target', (root/'config/systemd/user/voxtype.service').read_text())
        log = (root/'log').read_text()
        self.assertIn('download',log); self.assertIn('enable voxtype.service',log)
        self.assertEqual((root/'data/voxtype-mobile/token').stat().st_mode & 0o777,0o600)

    def test_upgrade_preserves_token_and_uses_dropin(self):
        root,result = self.run_install('upgrade',existing=True)
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual((root/'data/voxtype-mobile/token').read_text(),'preserved-token')
        self.assertIn('VOXTYPE_REMOTE_TOKEN_FILE',(root/'config/systemd/user/voxtype.service.d/90-mobile.conf').read_text())
        self.assertNotIn('download',(root/'log').read_text())

    def test_failed_readiness_restores_executable_and_service(self):
        root,result = self.run_install('fail',existing=True)
        self.assertNotEqual(result.returncode,0)
        self.assertEqual((root/'data/voxtype-mobile/voxtype').read_text(),'old binary')
        self.assertEqual((root/'config/systemd/user/voxtype.service.d/90-mobile.conf').read_text(),'old unit')

    def test_no_systemd_only_installs_files(self):
        root,result = self.run_install('manual')
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertIn('No systemd',result.stdout)
        self.assertNotIn('restart',(root/'log').read_text())
        self.assertFalse((root/'config/systemd').exists())

    def test_incompatible_configuration_does_not_replace_binary(self):
        root,result = self.run_install('fresh',existing=True,bad=True)
        self.assertNotEqual(result.returncode,0)
        self.assertEqual((root/'data/voxtype-mobile/voxtype').read_text(),'old binary')

if __name__ == '__main__': unittest.main()
