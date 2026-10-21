package br.com.exames.datalocal

import androidx.room.RoomDatabase

internal inline fun <T : RoomDatabase, R> T.useDatabase(block: (T) -> R): R =
    try { block(this) } finally { close() }
