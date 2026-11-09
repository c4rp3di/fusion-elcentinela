package com.vigia.centinela.datos

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/** Un dominio de la lista de amenazas, en minúsculas y sin esquema ni ruta. La clave primaria evita duplicados. */
@Entity(tableName = "dominios")
data class DominioEntity(
    @PrimaryKey val dominio: String
)

@Dao
interface DominioDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertar(lista: List<DominioEntity>)

    @Query("DELETE FROM dominios")
    suspend fun borrarTodo()

    @Query("SELECT COUNT(*) FROM dominios")
    suspend fun contar(): Int

    /** Devuelve el primer candidato que está en la lista, o null si ninguno lo está. */
    @Query("SELECT dominio FROM dominios WHERE dominio IN (:candidatos) LIMIT 1")
    suspend fun buscar(candidatos: List<String>): String?
}

@Database(entities = [DominioEntity::class], version = 1, exportSchema = false)
abstract class BaseDatos : RoomDatabase() {
    abstract fun dominios(): DominioDao

    companion object {
        @Volatile
        private var instancia: BaseDatos? = null

        fun obtener(contexto: Context): BaseDatos {
            val actual = instancia
            if (actual != null) return actual
            return synchronized(this) {
                instancia ?: Room.databaseBuilder(contexto.applicationContext, BaseDatos::class.java, "centinela.db")
                    .build()
                    .also { instancia = it }
            }
        }
    }
}
