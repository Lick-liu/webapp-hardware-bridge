"""Build native macOS installers and verify the mounted app using bundled Java."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import plistlib
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
    assert not BUILD.exists(), 'Use a fresh build directory; do not overwrite prior evidence'
    EVIDENCE.mkdir(parents=True)
    run('gradle', '--no-daemon', '--console=plain', '-I', 'scripts/macos/package.gradle',
        'stageMacInstaller', 'test', '--tests', '*PrintServiceDiscoveryServiceTest',
        '--tests', '*PrinterMappingServiceTest', '--tests', '*VirtualPdfPrinterSupportTest')
    tests = [ET.parse(p).getroot() for p in (ROOT / 'build/test-results/test').glob('TEST-*.xml')]
    assert sum(int(t.attrib['tests']) for t in tests) == 13
    assert all(int(t.attrib[k]) == 0 for t in tests for k in ('failures', 'errors', 'skipped'))
    jdk = Path(os.environ['JAVA_HOME']) / 'bin'
    staged = BUILD / 'input'
    jars = list(staged.glob('*.jar'))
    assert len(jars) == 49
    classes = BUILD / 'launcher-classes'
    classes.mkdir()
    run(jdk / 'javac', '--release', '17', '-encoding', 'UTF-8', '-cp', str(staged / '*'),
        '-d', classes, 'scripts/macos/MacLauncher.java')
    run(jdk / 'jar', '--create', '--file', staged / 'mac-launcher.jar', '-C', classes, '.')
    shutil.copyfile(ROOT / 'LICENSE', staged / 'LICENSE')
    run(jdk / 'jlink', '--output', BUILD / 'runtime', '--add-modules',
        'java.se,jdk.unsupported,jdk.crypto.ec,jdk.charsets,jdk.zipfs',
        '--strip-debug', '--no-header-files', '--no-man-pages')
    run(jdk / 'jpackage', '--type', 'app-image', '--name', APP_NAME, '--app-version', '1.2.0',
        '--input', staged, '--main-jar', 'mac-launcher.jar', '--main-class', 'cn.teahouses.printhelper.MacLauncher',
        '--runtime-image', BUILD / 'runtime', '--dest', BUILD / 'app',
        '--mac-package-identifier', 'cn.teahouses.printhelper', '--vendor', '茶坊村')
    app = BUILD / 'app' / (APP_NAME + '.app')
    launcher = app / 'Contents/MacOS' / APP_NAME
    assert arch in run('lipo', '-archs', launcher)
    assert arch in run('lipo', '-archs', app / 'Contents/runtime/Contents/Home/bin/java')
    smoke = run(launcher, '--smoke-test', timeout=90)
    assert 'MAC_APP_SMOKE_PASS' in smoke
    (EVIDENCE / 'app-startup.txt').write_text(smoke)
    installers = BUILD / 'installers'
    for kind in ('dmg', 'pkg'):
        run(jdk / 'jpackage', '--type', kind, '--name', APP_NAME, '--app-image', app,
            '--app-version', '1.2.0', '--dest', installers, '--vendor', '茶坊村')
    dmg = next(installers.glob('*.dmg'))
    pkg = next(installers.glob('*.pkg'))
    label = 'apple-silicon' if arch == 'arm64' else 'intel'
    dmg = dmg.rename(installers / f'tea-print-helper-macos-{label}.dmg')
    pkg = pkg.rename(installers / f'tea-print-helper-macos-{label}.pkg')
    (EVIDENCE / 'dmg-verify.txt').write_text(run('hdiutil', 'verify', dmg))
    mount = BUILD / 'mounted installer'
    mount.mkdir()
    run('hdiutil', 'attach', '-readonly', '-nobrowse', '-mountpoint', mount, dmg)
    try:
        mounted_app = mount / (APP_NAME + '.app')
        assert mounted_app.is_dir()
        installed = BUILD / 'Installed Applications' / (APP_NAME + '.app')
        installed.parent.mkdir()
        run('ditto', mounted_app, installed)
        smoke_installed = run(installed / 'Contents/MacOS' / APP_NAME, '--smoke-test', timeout=90)
        assert 'MAC_APP_SMOKE_PASS' in smoke_installed
        (EVIDENCE / 'installed-app-startup.txt').write_text(smoke_installed)
        with (installed / 'Contents/Info.plist').open('rb') as f:
            info = plistlib.load(f)
        assert info['CFBundleIdentifier'] == 'cn.teahouses.printhelper'
    finally:
        run('hdiutil', 'detach', mount)
    expanded = BUILD / 'pkg-expanded'
    run('pkgutil', '--expand', pkg, expanded)
    assert (expanded / 'Distribution').exists()
    (EVIDENCE / 'pkg-distribution.xml').write_bytes((expanded / 'Distribution').read_bytes())
    source = run('git', 'rev-parse', 'HEAD').strip()
    manifest = {'sourceCommit': source, 'architecture': arch, 'unitTests': 13,
                'nativeAppSmoke': 'PASS', 'mountedDmgInstalledAppSmoke': 'PASS',
                'developerIdSigned': False, 'notarized': False, 'physicalPrintTested': False,
                'artifacts': {p.name: {'bytes': p.stat().st_size, 'sha256': sha(p)} for p in (dmg, pkg)}}
    (installers / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    (installers / 'SHA256SUMS.txt').write_text(''.join(f'{sha(p)}  {p.name}\n' for p in (dmg, pkg)))
    (installers / 'README.txt').write_text(
        '茶坊村打印助手 macOS 安装包\n\n'
        '推荐：打开 DMG，将 Tea Print Helper 拖到 Applications，再从“应用程序”双击启动。\n'
        '也可选择 PKG，按安装向导操作。两种方式任选一种。无需另装 Java，无需打开终端。\n'
        '启动后自动打开配置页 http://127.0.0.1:12212/；顶部状态栏图标菜单提供 Web UI 和 Exit。\n'
        '配置和日志：~/Library/Application Support/TeaPrintHelper。旧便携包数据不自动迁移。\n'
        '尚未进行 Apple Developer ID 签名/公证。系统若阻止运行，请核验来源后按单项安全提示处理；勿关闭系统保护。\n'
        'Mac 构建、安装包结构和本地服务启动已验证；实体打印机出纸尚未验证，请先打印测试单。\n', encoding='utf-8')
    print(json.dumps(manifest, indent=2), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--architecture', required=True, choices=['arm64', 'x86_64'])
    main(parser.parse_args().architecture)
