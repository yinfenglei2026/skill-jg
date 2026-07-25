#!/bin/sh
set -eu

base_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
wrapper_jar="$base_dir/.mvn/wrapper/maven-wrapper.jar"
wrapper_url="https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar"

if [ ! -r "$wrapper_jar" ]; then
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$wrapper_url" -o "$wrapper_jar"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$wrapper_url" -O "$wrapper_jar"
  else
    echo "Maven Wrapper requires curl or wget to download its bootstrap JAR." >&2
    exit 1
  fi
fi

exec java -classpath "$wrapper_jar" org.apache.maven.wrapper.MavenWrapperMain "$@"
