"""Shared settings for the SQL suites: where the database is and where the Java sources are.

These suites create and delete users, projects and posts. Run them only against a throwaway
database with the migrations applied (CI makes one); LEENAX_TEST_DSN must be set on purpose."""
import os, sys

DSN = os.environ.get('LEENAX_TEST_DSN')
if not DSN:
    sys.exit('Set LEENAX_TEST_DSN to a throwaway PostgreSQL database with the migrations applied, '
             'e.g. "host=localhost port=5432 user=leenax password=… dbname=leenax_test". The suites delete data.')
HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.normpath(os.path.join(HERE, '..', '..', 'main', 'java', 'com', 'leenax', 'studio')) + '/'
FIXTURES = os.path.join(HERE, 'fixtures')
