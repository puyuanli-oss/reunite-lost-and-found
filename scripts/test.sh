#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
./build.sh
mkdir -p target/test-classes
find src/test/java -name '*.java' -print > target/test-sources.txt
compiler_release=(--release 17)
if [[ ${JAVA_RELEASE_FALLBACK:-0} == 1 ]]; then compiler_release=(-source 17 -target 17); fi
java com.sun.tools.javac.Main "${compiler_release[@]}" -encoding UTF-8 -cp 'target/classes:lib/*' -d target/test-classes @target/test-sources.txt
for test in MatchingEngineTest PhotoServiceTest CoreTest; do
  java -Djava.awt.headless=true -cp 'target/classes:target/test-classes:lib/*' "edu.usc.lostfound.$test"
done
