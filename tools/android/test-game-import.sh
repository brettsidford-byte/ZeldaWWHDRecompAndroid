#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
test_output=$(mktemp -d)
trap 'rm -rf "$test_output"' EXIT
javac -d "$test_output" android/app/src/main/java/org/wwhdrecomp/app/GameFolderImport.java tools/android/tests/GameFolderImportTest.java
java -cp "$test_output" org.wwhdrecomp.app.GameFolderImportTest
