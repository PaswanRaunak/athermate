#!/usr/bin/env python3
"""Exercise the actual migration SQL against a populated SQLite v1 schema.
Room's on-device open/identity verification still requires an Android device.
"""
import pathlib
import re
import sqlite3

root = pathlib.Path(__file__).resolve().parents[1]
schema = root / 'android/app/src/test/resources/local/schema-v1.sql'
source = root / 'android/app/src/main/java/io/ather/pro/data/local/AtherDatabase.kt'
connection = sqlite3.connect(':memory:')
connection.executescript(schema.read_text())
connection.execute("INSERT INTO telemetry_history VALUES (1000, 0, 70.5, 'Ride')")
connection.execute("INSERT INTO trip_baseline VALUES (1, 2200.5, 70.5, 1000)")
connection.execute("INSERT INTO meta VALUES ('migrated', 'true')")
connection.execute("INSERT INTO trips VALUES ('saved-ride',1000,2000,12,8,250,20.8,2,2188.5,2200.5,NULL,0)")
tables = ('telemetry_history', 'trip_baseline', 'meta', 'trips')
rows = {table: connection.execute(f'SELECT * FROM {table}').fetchall() for table in tables}
statements = re.findall(r'db\.execSQL\("([^\"]+)"\)', source.read_text())
assert statements, 'No migration found'
for sql in statements:
    connection.execute(sql)
for table in tables:
    assert connection.execute(f'SELECT * FROM {table}').fetchall() == rows[table], table
connection.execute('INSERT INTO ride_history VALUES (2000, 35, NULL, NULL)')
assert connection.execute('SELECT speedKmh, odometerKm, rangeKm FROM ride_history').fetchone() == (35, None, None)
print('PASS: v1 trips, battery history and baseline survive; sparse v2 ride readings remain nullable')
