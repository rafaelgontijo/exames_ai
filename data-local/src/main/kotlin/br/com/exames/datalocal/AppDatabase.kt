package br.com.exames.datalocal

import androidx.room.Database
import androidx.room.RoomDatabase

/** Legacy schema retained for migration fixtures; production opens through ProtectedDatabaseFactory. */
@Database(entities = [DocumentEntity::class, ObservationEntity::class, ReferenceRangeEntity::class,
    AssessmentEntity::class, ManualCorrectionEntity::class, PendingDeletionEntity::class],
    version = 1, exportSchema = true)
@androidx.room.TypeConverters(TypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun observationDao(): ObservationDao
    abstract fun documentDao(): DocumentDao
}
