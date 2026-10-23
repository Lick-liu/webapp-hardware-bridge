"""Compile/test current source offline against a verified local delivery.

Does not launch the helper, install an app, or submit any print jobs.
This supplements, rather than verifies, the standard modular Gradle build.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--delivery', required=True, type=Path,
                        help='Extracted TeaPrintHelper-XP58-local-20261021 directory')
    parser.add_argument('--output', required=True, type=Path, help='New evidence directory')
    parser.add_argument('--java', type=Path,
                        help='Native host Java for testing the same portable JARs on another architecture')
    args = parser.parse_args()
    delivery = args.delivery.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    for line in (delivery / 'SHA256SUMS').read_text().splitlines():
        digest, relative = line.split('  ', 1)
        path = (delivery / relative).resolve()
        if not path.is_relative_to(delivery):
            raise ValueError('Checksum path escapes delivery')
        if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise ValueError('Delivery checksum mismatch: ' + relative)
    app = delivery / 'app/Tea Print Helper.app/Contents'
    java = args.java.resolve() if args.java else app / 'runtime/Contents/Home/bin/java'
    tools = delivery / 'build-tools'
    libraries = sorted(p for p in (app / 'app').glob('*.jar')
                       if p.name not in ('webapp-hardware-bridge.jar', 'mac-launcher.jar'))
    cp = os.pathsep.join(map(str, [*libraries, *sorted(tools.glob('*.jar'))]))
    compiler = [java, '-javaagent:' + str(tools / 'lombok-1.18.34.jar') + '=ECJ',
                '-jar', tools / 'ecj-3.41.0.jar', '-17', '-encoding', 'UTF-8']

    def run(command, name, cwd):
        result = subprocess.run(list(map(str, command)), cwd=cwd,
                                capture_output=True, text=True, timeout=180)
        (output / (name + '.log')).write_text(result.stdout + result.stderr)
        if result.returncode:
            raise RuntimeError(name + ' failed; see ' + str(output))

    with tempfile.TemporaryDirectory(prefix='tea-helper-tests-') as temporary:
        workspace = Path(temporary)
        # Contract tests need these files; all mutable test data stays isolated.
        for name in ('build.gradle', 'CHANGELOG.md', 'install.nsi'):
            shutil.copyfile(ROOT / name, workspace / name)
        shutil.copytree(ROOT / 'src', workspace / 'src')
        classes, tests = output / 'classes', output / 'test-classes'
        classes.mkdir()
        tests.mkdir()
        sources = sorted(p for p in (ROOT / 'src/main/java').rglob('*.java')
                         if p.name != 'module-info.java')
        sources += sorted((ROOT / 'scripts/macos').glob('*.java'))
        test_sources = sorted((ROOT / 'src/test/java').rglob('*Test.java'))
        launcher_tests = sorted((ROOT / 'scripts/macos/tests').glob('*Test.java'))
        run([*compiler, '-cp', cp, '-d', classes, *sources], 'compile', workspace)
        test_cp = str(classes) + os.pathsep + cp
        run([*compiler, '-cp', test_cp, '-d', tests, *test_sources, *launcher_tests],
            'test-compile', workspace)
        names = ['.'.join(p.relative_to(ROOT / 'src/test/java').with_suffix('').parts)
                 for p in test_sources]
        names += ['cn.teahouses.printhelper.' + p.stem for p in launcher_tests]
        run([java, '-Djava.awt.headless=true', '-cp', str(tests) + os.pathsep + test_cp,
             'org.junit.runner.JUnitCore', *names], 'tests', workspace)
    (output / 'source-sha256.json').write_text(json.dumps({
        str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
        for p in [*sources, *test_sources, *launcher_tests]
    }, indent=2) + '\n')
    print((output / 'tests.log').read_text())


if __name__ == '__main__':
    main()
