"""Package accepted XP58 Java code with a native macOS application and runtime."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import plistlib
import shutil
import stat
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / 'build/accepted-xp58-installer'
ARCHIVE = ROOT / 'deliveries/TeaPrintHelper-XP58-local-20261021.zip'
ARCHIVE_SHA = 'ae3fa981a2ed370ffeae8c2701b75536f4c7fa77fcdbdfab7eced41004452e58'
APP_NAME = 'Tea Print Helper.app'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(*args, timeout=240):
    result = subprocess.run(list(map(str, args)), cwd=ROOT, capture_output=True,
                            text=True, timeout=timeout)
    print(result.stdout, end='')
    print(result.stderr, end='')
    result.check_returncode()
    return result.stdout + result.stderr


def inventory(app):
    result = {}
    for directory, dirs, files in os.walk(app, followlinks=False):
        for name in sorted(dirs + files):
            path = Path(directory) / name
            key = path.relative_to(app).as_posix()
            if path.is_symlink():
                target = path.resolve()
                if not target.is_relative_to(app.resolve()):
                    raise ValueError('Bundle symlink escapes app: ' + key)
                result[key] = {'link': os.readlink(path)}
            elif path.is_file():
                result[key] = {'sha256': sha(path), 'executable': stat.S_IMODE(path.stat().st_mode) & 0o111}
    return result


def inspect_app(app, arch):
    run('/usr/bin/codesign', '--verify', '--deep', '--strict', app)
    for executable in [app / 'Contents/MacOS/Tea Print Helper',
                       app / 'Contents/runtime/Contents/Home/bin/java']:
        assert arch in run('/usr/bin/lipo', '-archs', executable).split()
    config = (app / 'Contents/app/Tea Print Helper.cfg').read_text()
    assert 'java-options=-Dtea.print.macPos58RasterQueue=Xprinter_USB_Printer_Port' in config
    assert 'java-options=-Dtea.print.macPos58ManualTear=true' in config
    with (app / 'Contents/Info.plist').open('rb') as stream:
        info = plistlib.load(stream)
    assert info['CFBundleIdentifier'] == 'cn.teahouses.printhelper'
    assert info['CFBundleShortVersionString'] == '1.3.2'


def smoke(app, evidence):
    output = run(app / 'Contents/MacOS/Tea Print Helper', '--smoke-test', timeout=180)
    evidence.write_text(output)
    assert 'MAC_APP_SMOKE_PASS' in output
    assert 'MAC_WEB_PROTOCOL_PASS' in output and 'restart=true' in output


def intel_app(accepted_app, build):
    """Keep every JAR byte; replace only the architecture-dependent app shell/JVM."""
    jdk = Path(os.environ['JAVA_HOME']) / 'bin'
    staged = build / 'intel-input'
    staged.mkdir()
    jars = sorted((accepted_app / 'Contents/app').glob('*.jar'))
    assert {'webapp-hardware-bridge.jar', 'mac-launcher.jar'} <= {p.name for p in jars}
    expected = {p.name: sha(p) for p in jars}
    for jar in jars:
        shutil.copyfile(jar, staged / jar.name)
    shutil.copyfile(ROOT / 'LICENSE', staged / 'LICENSE')
    runtime = build / 'intel-runtime'
    run(jdk / 'jlink', '--output', runtime, '--add-modules',
        'java.se,jdk.unsupported,jdk.crypto.ec,jdk.charsets,jdk.zipfs',
        '--strip-debug', '--no-header-files', '--no-man-pages')
    run(jdk / 'jpackage', '--type', 'app-image', '--name', 'Tea Print Helper',
        '--app-version', '1.3.2', '--input', staged, '--main-jar', 'mac-launcher.jar',
        '--main-class', 'cn.teahouses.printhelper.MacLauncher',
        '--runtime-image', runtime, '--dest', build / 'intel-app',
        '--mac-package-identifier', 'cn.teahouses.printhelper', '--vendor', '茶坊村',
        '--java-options', '-Dtea.print.macPos58RasterQueue=Xprinter_USB_Printer_Port',
        '--java-options', '-Dtea.print.macPos58ManualTear=true')
    app = build / 'intel-app' / APP_NAME
    assert {p.name: sha(p) for p in (app / 'Contents/app').glob('*.jar')} == expected
    return app, expected


def compare_classes(accepted_app, classes):
    count = 0
    for name in ['webapp-hardware-bridge.jar', 'mac-launcher.jar']:
        with zipfile.ZipFile(accepted_app / 'Contents/app' / name) as jar:
            for entry in jar.namelist():
                if entry.endswith('.class'):
                    assert (classes / entry).read_bytes() == jar.read(entry), entry
                    count += 1
    assert count == 70
    return count


def main(arch):
    assert platform.system() == 'Darwin' and platform.machine() == arch
    build = BUILD if arch == 'arm64' else ROOT / 'build/accepted-xp58-installer-intel'
    assert sha(ARCHIVE) == ARCHIVE_SHA
    with zipfile.ZipFile(ARCHIVE) as archive:
        assert archive.testzip() is None
    build.mkdir(parents=True, exist_ok=False)
    evidence = build / 'evidence'
    evidence.mkdir()
    extracted = build / 'accepted'
    run('/usr/bin/ditto', '-x', '-k', ARCHIVE, extracted)
    delivery = extracted / 'TeaPrintHelper-XP58-local-20261021'
    accepted_app = delivery / 'app' / APP_NAME
    inspect_app(accepted_app, 'arm64')
    app = accepted_app
    jar_hashes = {p.name: sha(p) for p in (accepted_app / 'Contents/app').glob('*.jar')}
    if arch == 'x86_64':
        app, jar_hashes = intel_app(accepted_app, build)
    inspect_app(app, arch)
    accepted = inventory(app)
    native_java = app / 'Contents/runtime/Contents/Home/bin/java'
    run('python3', ROOT / 'scripts/macos/test-local.py', '--delivery', delivery,
        '--output', evidence / 'unit-tests', '--java', native_java, timeout=600)
    test_log = (evidence / 'unit-tests/tests.log').read_text()
    assert 'OK (74 tests)' in test_log
    class_count = compare_classes(accepted_app, evidence / 'unit-tests/classes')
    smoke(app, evidence / 'native-app-smoke.txt')
    assert inventory(app) == accepted, 'Tests must not change accepted application'

    payload = build / 'payload'
    destination = payload / 'Applications' / APP_NAME
    destination.parent.mkdir(parents=True)
    run('/usr/bin/ditto', app, destination)
    components = build / 'components.plist'
    run('/usr/bin/pkgbuild', '--analyze', '--root', payload, components)
    with components.open('rb') as stream:
        properties = plistlib.load(stream)
    assert len(properties) == 1 and properties[0]['RootRelativeBundlePath'] == 'Applications/' + APP_NAME
    properties[0]['BundleIsRelocatable'] = False
    properties[0]['BundleOverwriteAction'] = 'upgrade'
    with components.open('wb') as stream:
        plistlib.dump(properties, stream)
    output = build / 'installers'
    output.mkdir()
    label = 'apple-silicon' if arch == 'arm64' else 'intel'
    pkg = output / f'tea-print-helper-1.3.2-xp58-manual-tear-macos-{label}.pkg'
    run('/usr/bin/pkgbuild', '--root', payload, '--component-plist', components,
        '--identifier', 'cn.teahouses.printhelper.xp58', '--version', '1.3.2',
        '--install-location', '/', '--ownership', 'recommended', pkg)
    assert pkg.read_bytes()[:4] == b'xar!'
    expanded = build / 'expanded'
    run('/usr/sbin/pkgutil', '--expand-full', pkg, expanded)
    apps = [p for p in expanded.rglob(APP_NAME) if p.is_dir()]
    assert len(apps) == 1
    installed = apps[0]
    assert inventory(installed) == accepted, 'Actual PKG payload differs from accepted application'
    inspect_app(installed, arch)
    smoke(installed, evidence / 'pkg-payload-smoke.txt')
    assert inventory(installed) == accepted, 'Smoke must not change actual PKG application'
    checksum = sha(pkg)
    (output / 'SHA256SUMS').write_text(checksum + '  ' + pkg.name + '\n')
    manifest = {
        'sourceCommit': run('git', 'rev-parse', 'HEAD').strip(),
        'originalHelperCommit': '514136ee2e1cf47c4abc3f4967d83612a632d6ca',
        'harnessArchiveCommit': '95165d4dad1c572b8d8129681cfc7d480a94eab1',
        'acceptedArchiveSha256': ARCHIVE_SHA,
        'version': '1.3.2-local-xp58-manual-tear', 'architecture': arch,
        'package': pkg.name, 'bytes': pkg.stat().st_size, 'sha256': checksum,
        'verifiedApplicationEntries': len(accepted), 'payloadByteIdentical': True,
        'unitTests': 74, 'nativeAppSmoke': 'PASS', 'pkgPayloadSmoke': 'PASS',
        'acceptedAppSmoke': 'PASS' if arch == 'arm64' else 'not_applicable',
        'acceptedJarHashes': jar_hashes, 'classBytesEqualToAccepted': class_count,
        'fullAppEqualToAcceptedArm64': arch == 'arm64',
        'installLocation': '/Applications/Tea Print Helper.app',
        'relocatable': False, 'installerScripts': False, 'userDataIncluded': False,
        'developerIdSigned': False, 'notarized': False,
        'physicalPrintingRetested': False, 'osInstallerExecuted': False,
    }
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    (evidence / 'native-app-inventory.json').write_text(json.dumps(accepted, indent=2) + '\n')
    print('ACCEPTED_XP58_PKG_PASS ' + json.dumps(manifest))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--architecture', choices=['arm64', 'x86_64'], default='arm64')
    main(parser.parse_args().architecture)
