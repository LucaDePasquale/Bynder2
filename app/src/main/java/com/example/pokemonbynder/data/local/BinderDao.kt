package com.example.pokemonbynder.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.pokemonbynder.data.model.Binder
import kotlinx.coroutines.flow.Flow

@Dao
interface BinderDao {
    @Query("SELECT * FROM binders ORDER BY createdAt, id")
    fun getAll(): Flow<List<Binder>>

    @Query("SELECT COUNT(*) FROM binders")
    suspend fun count(): Int

    @Insert
    suspend fun insert(binder: Binder): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(binder: Binder)

    @Query("UPDATE binders SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM binders WHERE id = :id")
    suspend fun delete(id: Long)
}
