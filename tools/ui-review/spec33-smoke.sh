#!/usr/bin/env bash
set -euo pipefail

task_root=$(realpath "$(dirname "$0")/../..")
cd "$task_root"
mkdir -p .scratch

if [[ -n "${OPENROSE_UI_JAVA_HOME:-}" ]]; then
    task_java="$OPENROSE_UI_JAVA_HOME/bin/java"
elif [[ -n "${JAVA_HOME:-}" ]]; then
    task_java="$JAVA_HOME/bin/java"
else
    task_java=$(command -v java)
fi
if [[ ! -x "$task_java" ]]; then
    echo "Java executable not found: $task_java" >&2
    exit 1
fi

python3 - <<'PY'
import os
from pathlib import Path

root = Path.cwd()
lib = root / 'desktopApp/build/compose/binaries/main/app/OpenRose/lib/app'
assert lib.is_dir(), 'Run :desktopApp:createDistributable after the shared UI build.'

classes = [
    root / 'core/build/classes/kotlin/jvm/main',
    root / 'composeApp/build/classes/kotlin/jvm/main',
    root / 'composeApp/build/processedResources/jvm/main',
]
for path in classes:
    assert path.is_dir(), f'Missing compiled classes: {path}'

jars = sorted(
    p for p in lib.glob('*.jar')
    if not p.name.startswith(('core-jvm-', 'composeApp-jvm-', 'desktopApp-jvm-'))
)
preference_jars = [p for p in jars if 'miuix-preference' in p.name.lower()]
assert preference_jars, 'The desktop distribution does not include the new miuix-preference runtime jar.'

gradle_home = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle')))
cache = gradle_home / 'caches/modules-2/files-2.1'
def jar(group, module, version):
    matches = sorted((cache / group / module / version).glob('*/*.jar'))
    assert matches, f'Missing cached compiler dependency: {group}:{module}:{version}'
    return matches[0]

compiler_version = '2.4.20'
compiler = jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', compiler_version)
plugin = jar('org.jetbrains.kotlin', 'kotlin-compose-compiler-plugin-embeddable', compiler_version)
reflect = jar('org.jetbrains.kotlin', 'kotlin-reflect', '2.4.0')
script = jar('org.jetbrains.kotlin', 'kotlin-script-runtime', compiler_version)
classpath = ':'.join(str(p) for p in classes + jars)
(root / '.scratch/ui-spec33-classpath.txt').write_text(classpath)
(root / '.scratch/ui-spec33-compiler-classpath.txt').write_text(
    ':'.join(str(p) for p in [compiler, reflect, script] + classes + jars)
)
(root / '.scratch/ui-spec33-plugin-path.txt').write_text(str(plugin))
(root / '.scratch/ui-spec33-preference-jars.txt').write_text(
    '\n'.join(str(p) for p in preference_jars) + '\n'
)
PY

"$task_java" -cp "$(cat .scratch/ui-spec33-compiler-classpath.txt)" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -jvm-target 17 -classpath "$(cat .scratch/ui-spec33-classpath.txt)" \
    -Xplugin="$(cat .scratch/ui-spec33-plugin-path.txt)" \
    -d .scratch/ui-spec33-smoke.jar tools/ui-review/spec33-smoke.kt \
    > .scratch/ui-spec33-harness-compile.log 2>&1

"$task_java" -Djava.awt.headless=true \
    -Dskiko.library.path="$task_root/desktopApp/build/compose/binaries/main/app/OpenRose/lib/app" \
    -cp ".scratch/ui-spec33-smoke.jar:$(cat .scratch/ui-spec33-classpath.txt)" \
    UiSpec33Smoke > .scratch/ui-spec33-harness-run.log 2>&1

cat .scratch/ui-spec33-harness-run.log
