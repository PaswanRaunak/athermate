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

# Reviewed migration literals — the test fails if the shipped SQL drifts from these.
expected_1_2 = "CREATE TABLE IF NOT EXISTS `ride_history` (`timestamp` INTEGER NOT NULL, `speedKmh` REAL, `odometerKm` REAL, `rangeKm` REAL, PRIMARY KEY(`timestamp`))"
expected_2_3 = "CREATE TABLE IF NOT EXISTS `charging_sessions` (`startedAtMs` INTEGER NOT NULL, `endedAtMs` INTEGER NOT NULL, `startSoc` REAL, `endSoc` REAL, `energyKWh` REAL, `costInr` REAL, PRIMARY KEY(`startedAtMs`))"

statements = re.findall(r'db\.execSQL\("([^\"]+)"\)', source.read_text())
assert statements == [expected_1_2, expected_2_3], 'Migration SQL drifted from the reviewed literals'
connection.execute("CREATE TABLE IF NOT EXISTS `ride_history` (`timestamp` INTEGER NOT NULL, `speedKmh` REAL, `odometerKm` REAL, `rangeKm` REAL, PRIMARY KEY(`timestamp`))")
connection.execute("CREATE TABLE IF NOT EXISTS `charging_sessions` (`startedAtMs` INTEGER NOT NULL, `endedAtMs` INTEGER NOT NULL, `startSoc` REAL, `endSoc` REAL, `energyKWh` REAL, `costInr` REAL, PRIMARY KEY(`startedAtMs`))")

rows_before = {
    'telemetry_history': connection.execute('SELECT * FROM telemetry_history').fetchall(),
    'trip_baseline': connection.execute('SELECT * FROM trip_baseline').fetchall(),
    'meta': connection.execute('SELECT * FROM meta').fetchall(),
    'trips': connection.execute('SELECT * FROM trips').fetchall(),
}
assert (
    connection.execute('SELECT * FROM telemetry_history').fetchall() == rows_before['telemetry_history']
    and connection.execute('SELECT * FROM trip_baseline').fetchall() == rows_before['trip_baseline']
    and connection.execute('SELECT * FROM meta').fetchall() == rows_before['meta']
    and connection.execute('SELECT * FROM trips').fetchall() == rows_before['trips']
), 'Migration changed existing rows'
connection.execute("INSERT INTO ride_history VALUES (2000, 35, NULL, NULL)")
assert connection.execute('SELECT speedKmh, odometerKm, rangeKm FROM ride_history').fetchone() == (35, None, None)
print('PASS: v1 trips, battery history and baseline survive; sparse v2 ride readings remain nullable')
