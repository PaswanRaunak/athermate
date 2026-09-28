-- Actual table definitions from the supplied 1.0.0 APK.
CREATE TABLE IF NOT EXISTS `trips` (`id` TEXT NOT NULL, `startTimeMs` INTEGER NOT NULL, `endTimeMs` INTEGER NOT NULL, `distanceKm` REAL NOT NULL, `socConsumed` REAL NOT NULL, `energyConsumedWh` REAL NOT NULL, `efficiencyWhPerKm` REAL NOT NULL, `electricityCostInr` REAL NOT NULL, `startOdoKm` REAL NOT NULL, `endOdoKm` REAL NOT NULL, `estimatedPackCapacityWh` REAL, `isOfficialRide` INTEGER NOT NULL, PRIMARY KEY(`id`));
CREATE TABLE IF NOT EXISTS `telemetry_history` (`timestamp` INTEGER NOT NULL, `speedKmh` REAL NOT NULL, `batterySoc` REAL NOT NULL, `mode` TEXT NOT NULL, PRIMARY KEY(`timestamp`));
CREATE TABLE IF NOT EXISTS `trip_baseline` (`id` INTEGER NOT NULL, `odometerKm` REAL NOT NULL, `batterySoc` REAL NOT NULL, `timestampMs` INTEGER NOT NULL, PRIMARY KEY(`id`));
CREATE TABLE IF NOT EXISTS `meta` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`));
