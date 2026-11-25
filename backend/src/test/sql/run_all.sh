#!/bin/sh
# Runs every SQL suite against $LEENAX_TEST_DSN (a throwaway database with the migrations applied).
# Needs Python 3.11+ and psycopg 3 (pip install "psycopg[binary]").
set -e
cd "$(dirname "$0")"
for f in run.py v8.py v9.py v10.py e2.py v11.py e3.py v12.py e4.py v13.py e5.py e6.py e7.py e8.py e9.py e10.py e11.py; do
  echo "== $f"
  python3 "$f"
done
