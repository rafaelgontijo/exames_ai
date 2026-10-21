package br.com.exames.datalocal

import androidx.room.TypeConverter
import br.com.exames.domain.model.Ambiguity
import java.math.BigDecimal
import java.time.Instant

class TypeConverters {
    @TypeConverter fun decimalToString(value: BigDecimal?): String? = value?.toPlainString()
    @TypeConverter fun stringToDecimal(value: String?): BigDecimal? = value?.toBigDecimal()
    @TypeConverter fun instantToLong(value: Instant?): Long? = value?.toEpochMilli()
    @TypeConverter fun longToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)
    @TypeConverter fun ambiguitiesToString(value: Set<Ambiguity>): String = value.sortedBy { it.name }.joinToString(",") { it.name }
    @TypeConverter fun stringToAmbiguities(value: String): Set<Ambiguity> =
        if (value.isEmpty()) emptySet() else value.split(',').map(Ambiguity::valueOf).toSet()
}
