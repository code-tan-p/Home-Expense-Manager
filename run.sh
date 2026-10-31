#!/bin/sh
# Starts Home Expense Manager. Open http://127.0.0.1:8080/ in your browser. Press Ctrl+C to stop.
# The first time, create your account on the sign-up page.
cd "$(dirname "$0")" || exit 1
if ! command -v java >/dev/null 2>&1; then
  echo "Java 21 or newer is needed. Install it from https://adoptium.net and run this again."
  exit 1
fi
if [ ! -f target/home-expense-manager.jar ]; then
  echo "First start: building the program. This takes a minute..."
  ./mvnw -q -DskipTests package || { echo "The build failed."; exit 1; }
fi
echo "Starting at http://127.0.0.1:8080/ ..."
exec java -jar target/home-expense-manager.jar
