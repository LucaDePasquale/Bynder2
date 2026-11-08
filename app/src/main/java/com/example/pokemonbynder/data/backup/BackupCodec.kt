package com.example.pokemonbynder.data.backup

import com.example.pokemonbynder.data.model.Binder
import com.example.pokemonbynder.data.model.CardCategory
import com.example.pokemonbynder.data.model.CardCondition
import com.example.pokemonbynder.data.model.CardPrice
import com.example.pokemonbynder.data.model.DEFAULT_BINDER_ID
import com.example.pokemonbynder.data.model.PokemonCard
import com.example.pokemonbynder.data.model.WishlistCard
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.google.gson.annotations.SerializedName

/** Il file di backup non è valido o viene da una versione più recente dell'app. */
class BackupException(message: String) : Exception(message)

/** Tutto ciò che finisce nel backup: binder, carte, wishlist e ultimi prezzi noti. */
data class BackupData(
    val binders: List<Binder>,
    val cards: List<PokemonCard>,
    val wishlist: List<WishlistCard>,
    val prices: List<CardPrice>
)

/**
 * Converte la collezione in un file JSON leggibile e viceversa. Non dipende da Android.
 *
 * Il formato è indipendente dal database: i nomi dei campi sono fissati con @SerializedName e le
 * tipologie/condizioni si salvano per nome, così il file resta valido anche se l'app cambia.
 * Un valore sconosciuto o mancante non blocca l'importazione: si usano valori di riserva e le
 * righe senza dati essenziali (id o nome) vengono ignorate.
 */
object BackupCodec {
    const val APP = "PokemonBynder"
    const val VERSION = 1

    private const val MAX_QUANTITY = 999

    private class BinderDto(
        @SerializedName("id") val id: Long?,
        @SerializedName("name") val name: String?,
        @SerializedName("createdAt") val createdAt: Long?
    )

    private class CardDto(
        @SerializedName("id") val id: String?,
        @SerializedName("name") val name: String?,
        @SerializedName("supertype") val supertype: String?,
        @SerializedName("subtypes") val subtypes: List<String>?,
        @SerializedName("number") val number: String?,
        @SerializedName("artist") val artist: String?,
        @SerializedName("rarity") val rarity: String?,
        @SerializedName("imageUrl") val imageUrl: String?,
        @SerializedName("set") val set: String?,
        @SerializedName("category") val category: String?,
        @SerializedName("binderId") val binderId: Long?,
        @SerializedName("quantity") val quantity: Int?,
        @SerializedName("condition") val condition: String?
    )

    private class WishDto(
        @SerializedName("id") val id: String?,
        @SerializedName("name") val name: String?,
        @SerializedName("supertype") val supertype: String?,
        @SerializedName("number") val number: String?,
        @SerializedName("artist") val artist: String?,
        @SerializedName("rarity") val rarity: String?,
        @SerializedName("imageUrl") val imageUrl: String?,
        @SerializedName("set") val set: String?,
        @SerializedName("addedAt") val addedAt: Long?
    )

    private class PriceDto(
        @SerializedName("catalogKey") val catalogKey: String?,
        @SerializedName("priceEur") val priceEur: Double?,
        @SerializedName("updatedAt") val updatedAt: Long?
    )

    private class FileDto(
        @SerializedName("app") val app: String?,
        @SerializedName("version") val version: Int?,
        @SerializedName("exportedAt") val exportedAt: Long?,
        @SerializedName("binders") val binders: List<BinderDto>?,
        @SerializedName("cards") val cards: List<CardDto>?,
        @SerializedName("wishlist") val wishlist: List<WishDto>?,
        @SerializedName("prices") val prices: List<PriceDto>?
    )

    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun encode(data: BackupData, exportedAt: Long): String {
        val file = FileDto(
            app = APP,
            version = VERSION,
            exportedAt = exportedAt,
            binders = data.binders.map { BinderDto(it.id, it.name, it.createdAt) },
            cards = data.cards.map {
                CardDto(
                    it.id, it.name, it.supertype, it.subtypes, it.number, it.artist, it.rarity,
                    it.imageUrl, it.set, it.category.name, it.binderId, it.quantity, it.condition.name
                )
            },
            wishlist = data.wishlist.map {
                WishDto(it.id, it.name, it.supertype, it.number, it.artist, it.rarity, it.imageUrl, it.set, it.addedAt)
            },
            prices = data.prices.map { PriceDto(it.catalogKey, it.priceEur, it.updatedAt) }
        )
        return gson.toJson(file)
    }

    /** Legge un backup; lancia [BackupException] se il file non è un backup di questa app. */
    fun decode(text: String): BackupData {
        val file = try {
            gson.fromJson(text, FileDto::class.java)
        } catch (e: JsonParseException) {
            throw BackupException("Il file non è un backup valido.")
        } ?: throw BackupException("Il file è vuoto.")

        if (file.app != APP) throw BackupException("Il file non è un backup di questa app.")
        val version = file.version ?: 0
        if (version < 1) throw BackupException("Il file non è un backup valido.")
        if (version > VERSION) {
            throw BackupException("Il backup è stato creato da una versione più recente dell'app: aggiorna l'app e riprova.")
        }

        val binders = file.binders.orEmpty().mapNotNull {
            val id = it.id ?: return@mapNotNull null
            val name = it.name?.trim().orEmpty()
            if (name.isEmpty()) null else Binder(id = id, name = name, createdAt = it.createdAt ?: 0L)
        }
        val cards = file.cards.orEmpty().mapNotNull {
            val id = it.id?.takeIf { value -> value.isNotBlank() } ?: return@mapNotNull null
            val name = it.name?.takeIf { value -> value.isNotBlank() } ?: return@mapNotNull null
            PokemonCard(
                id = id,
                name = name,
                supertype = it.supertype.orEmpty(),
                subtypes = it.subtypes.orEmpty(),
                number = it.number.orEmpty(),
                artist = it.artist,
                rarity = it.rarity,
                imageUrl = it.imageUrl.orEmpty(),
                set = it.set.orEmpty(),
                category = CardCategory.entries.firstOrNull { c -> c.name == it.category } ?: CardCategory.OTHER,
                binderId = it.binderId ?: DEFAULT_BINDER_ID,
                quantity = (it.quantity ?: 1).coerceIn(1, MAX_QUANTITY),
                condition = CardCondition.entries.firstOrNull { c -> c.name == it.condition } ?: CardCondition.NEAR_MINT
            )
        }
        val wishlist = file.wishlist.orEmpty().mapNotNull {
            val id = it.id?.takeIf { value -> value.isNotBlank() } ?: return@mapNotNull null
            val name = it.name?.takeIf { value -> value.isNotBlank() } ?: return@mapNotNull null
            WishlistCard(
                id = id,
                name = name,
                supertype = it.supertype.orEmpty(),
                number = it.number.orEmpty(),
                artist = it.artist,
                rarity = it.rarity,
                imageUrl = it.imageUrl.orEmpty(),
                set = it.set.orEmpty(),
                addedAt = it.addedAt ?: 0L
            )
        }
        val prices = file.prices.orEmpty().mapNotNull {
            val key = it.catalogKey?.takeIf { value -> value.isNotBlank() } ?: return@mapNotNull null
            val price = it.priceEur?.takeIf { value -> value.isFinite() && value > 0.0 } ?: return@mapNotNull null
            CardPrice(key, price, it.updatedAt ?: 0L)
        }
        return BackupData(binders, cards, wishlist, prices)
    }
}
