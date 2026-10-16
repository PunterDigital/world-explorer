package co.shaypunter.worldexplorer.data

import androidx.room.migration.Migration

/**
 * Every schema change bumps the [AppDatabase] version and adds a migration
 * here, plus a case in MigrationTest. Never use fallbackToDestructiveMigration:
 * it would wipe the user's explored area.
 */
val ALL_MIGRATIONS: Array<Migration> = arrayOf()
