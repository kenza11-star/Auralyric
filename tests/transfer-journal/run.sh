#!/bin/sh
# Compiles the REAL Journal class from TransferRestore.kt and runs the rollback scenarios against it.
# Needs kotlinc 2.x and python3. Run from the project root: sh tests/transfer-journal/run.sh
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../../app/src/main/kotlin/com/vantra/music/importer/transfer/TransferRestore.kt"
OUT="$(mktemp -d)"
python3 - "$SRC" "$OUT/Journal.kt" <<'PY'
import sys
src = open(sys.argv[1]).read()
body = src[src.index("internal class Journal("):]
open(sys.argv[2], "w").write("package probe\n\nimport java.io.File\n\n" + body)
PY
kotlinc "$OUT/Journal.kt" "$HERE/JournalScenarios.kt" -include-runtime -d "$OUT/probe.jar" 2>/dev/null
java -jar "$OUT/probe.jar"
