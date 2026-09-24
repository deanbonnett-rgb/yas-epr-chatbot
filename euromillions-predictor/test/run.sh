#!/usr/bin/env bash
# Compiles the Android-free logic classes and runs LogicTest on the desktop JVM.
set -euo pipefail
cd "$(dirname "$0")/.."
out=build/test-classes
rm -rf "$out" && mkdir -p "$out"
javac -d "$out" src/com/emp/predictor/{Game,Draw,DrawParser,DrawSchedule,Stats,Predictor}.java test/LogicTest.java
java -cp "$out" LogicTest
