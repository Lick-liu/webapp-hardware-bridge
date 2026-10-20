"""Build native macOS installers and verify the mounted app using bundled Java."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import plistlib
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / 'build/macos'
EVIDENCE = BUILD / 'evidence'
APP_NAME = 'Tea Print Helper'


def run(*args, timeout=600):
    print('+', ' '.join(map(str, args)), flush=True)
    result = subprocess.run(list(map(str, args)), cwd=ROOT, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    print(result.stdout, flush=True)
    if result.returncode:
        raise RuntimeError(f'Command exited {result.returncode}: {args[0]}')
    return result.stdout


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def main(arch):
    assert platform.system() == 'Darwin' and platform.machine() == arch
    run('git', 'merge-base', '--is-ancestor', '7e3a108', 'HEAD')
    version = re.search(r"(?m)^version '([^']+)'$", (ROOT / 'build.gradle').read_text()).group(1)
    assert not BUILD.exists(), 'Use a fresh build directory; do not overwrite prior evidence'
    EVIDENCE.mkdir(parents=True)
    required_suites = ['PrintServiceDiscoveryServiceTest', 'PrinterMappingServiceTest',
                       'VirtualPdfPrinterSupportTest', 'LocalPrintDeviceContextServiceTest',
                       'PrintJobReplayGuardTest', 'PrinterWebSocketServiceIdempotencyTest',
                       'TrustedOriginPolicyTest', 'HelperSecurityContractTest', 'ReleaseVersionContractTest']
    run('gradle', '--no-daemon', '--console=plain', '-I', 'scripts/macos/package.gradle',
        'stageMacInstaller', 'test', *[arg for suite in required_suites for arg in ('--tests', '*' + suite)])
    tests = [ET.parse(p).getroot() for p in (ROOT / 'build/test-results/test').glob('TEST-*.xml')]
    assert set(required_suites) <= {t.attrib['name'].split('.')[-1] for t in tests}
    test_count = sum(int(t.attrib['tests']) for t in tests)
    assert test_count > 13
    assert all(int(t.attrib[k]) == 0 for t in tests for k in ('failures', 'errors', 'skipped'))
    jdk = Path(os.environ['JAVA_HOME']) / 'bin'
    staged = BUILD / 'input'
    jars = list(staged.glob('*.jar'))
    assert len(jars) == 49
    classes = BUILD / 'launcher-classes'
    classes.mkdir()
    run(jdk / 'javac', '--release', '17', '-encoding', 'UTF-8', '-cp', str(staged / '*'),
        '-d', classes, 'scripts/macos/MacLauncher.java', 'scripts/macos/MacProtocolProbe.java')
    run(jdk / 'jar', '--create', '--file', staged / 'mac-launcher.jar', '-C', classes, '.')
    shutil.copyfile(ROOT / 'LICENSE', staged / 'LICENSE')
    run(jdk / 'jlink', '--output', BUILD / 'runtime', '--add-modules',
        'java.se,jdk.unsupported,jdk.crypto.ec,jdk.charsets,jdk.zipfs',
        '--strip-debug', '--no-header-files', '--no-man-pages')
    run(jdk / 'jpackage', '--type', 'app-image', '--name', APP_NAME, '--app-version', version,
        '--input', staged, '--main-jar', 'mac-launcher.jar', '--main-class', 'cn.teahouses.printhelper.MacLauncher',
        '--runtime-image', BUILD / 'runtime', '--dest', BUILD / 'app',
        '--mac-package-identifier', 'cn.teahouses.printhelper', '--vendor', '茶坊村')
    app = BUILD / 'app' / (APP_NAME + '.app')
    launcher = app / 'Contents/MacOS' / APP_NAME
    assert arch in run('lipo', '-archs', launcher)
    assert arch in run('lipo', '-archs', app / 'Contents/runtime/Contents/Home/bin/java')
    smoke = run(launcher, '--smoke-test', timeout=150)
    assert 'MAC_APP_SMOKE_PASS' in smoke and 'restart=true' in smoke and 'MAC_WEB_PROTOCOL_PASS' in smoke
    (EVIDENCE / 'app-startup.txt').write_text(smoke)
    installers = BUILD / 'installers'
    run(jdk / 'jpackage', '--type', 'pkg', '--name', APP_NAME, '--app-image', app,
        '--app-version', version, '--dest', installers, '--vendor', '茶坊村')
    pkg = next(installers.glob('*.pkg'))
    label = 'apple-silicon' if arch == 'arm64' else 'intel'
    pkg = pkg.rename(installers / f'tea-print-helper-{version}-macos-{label}.pkg')
    expanded = BUILD / 'pkg-expanded'
    run('pkgutil', '--expand-full', pkg, expanded)
    assert (expanded / 'Distribution').exists()
    (EVIDENCE / 'pkg-distribution.xml').write_bytes((expanded / 'Distribution').read_bytes())
    payload_apps = list(expanded.rglob(APP_NAME + '.app'))
    assert len(payload_apps) == 1, 'Expected one app in the actual PKG payload'
    installed = BUILD / 'Installed Applications' / (APP_NAME + '.app')
    installed.parent.mkdir()
    run('ditto', payload_apps[0], installed)
    smoke_installed = run(installed / 'Contents/MacOS' / APP_NAME, '--smoke-test', timeout=150)
    assert 'MAC_APP_SMOKE_PASS' in smoke_installed and 'restart=true' in smoke_installed
    (EVIDENCE / 'pkg-payload-startup.txt').write_text(smoke_installed)
    with (installed / 'Contents/Info.plist').open('rb') as f:
        info = plistlib.load(f)
    assert info['CFBundleIdentifier'] == 'cn.teahouses.printhelper'
    assert info['CFBundleShortVersionString'] == version
    source = run('git', 'rev-parse', 'HEAD').strip()
    manifest = {'sourceCommit': source, 'architecture': arch, 'version': version, 'unitTests': test_count,
                'requiredProtocolBaseline': '7e3a108', 'nativeAppSmoke': 'PASS', 'pkgPayloadAppSmoke': 'PASS',
                'identityActivationCorsRestartWebSocketSmoke': 'PASS',
                'developerIdSigned': False, 'notarized': False, 'physicalPrintTested': False,
                'artifacts': {pkg.name: {'bytes': pkg.stat().st_size, 'sha256': sha(pkg)}}}
    (installers / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    (installers / 'SHA256SUMS.txt').write_text(f'{sha(pkg)}  {pkg.name}\n')
    (installers / 'README.txt').write_text(
        '茶坊村打印助手 macOS 安装包\n\n'
        '双击 PKG，按安装向导操作。无需另装 Java，无需打开终端。\n'
        '启动后自动打开配置页 http://127.0.0.1:12212/；顶部状态栏图标菜单提供 Web UI 和 Exit。\n'
        '配置和日志：~/Library/Application Support/TeaPrintHelper。旧便携包数据不自动迁移。\n'
        '尚未进行 Apple Developer ID 签名/公证。系统若阻止运行，请核验来源后按单项安全提示处理；勿关闭系统保护。\n'
        'Mac 构建、安装包结构和本地服务启动已验证；实体打印机出纸尚未验证，请先打印测试单。\n', encoding='utf-8')
    print(json.dumps(manifest, indent=2), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--architecture', required=True, choices=['arm64', 'x86_64'])
    main(parser.parse_args().architecture)
