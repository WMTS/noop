package com.noop.intervals

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// Fork addition (Rick): shoe mileage tracker. Each pair's total is the miles it had before NOOP (or
// added by hand for runs done without the app) plus the distance of every NOOP run logged on it. Runs
// are stored as assignments keyed by the workout's start second, so editing a run's distance or deleting
// the run keeps the shoe total right. SharedPreferences + JSON, no Room schema change.

data class Shoe(
    val id: String,
    val name: String,
    /** Distance before NOOP plus any manual adjustments, metres. */
    val baseMeters: Double = 0.0,
    /** Warn when the total passes this, metres (default 400 miles). 0 = no limit. */
    val limitMeters: Double = DEFAULT_LIMIT_M,
    val retired: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("base", baseMeters); put("limit", limitMeters); put("retired", retired)
    }

    companion object {
        const val METERS_PER_MILE = 1609.344
        const val DEFAULT_LIMIT_M = 400 * METERS_PER_MILE

        fun fromJson(o: JSONObject): Shoe? = runCatching {
            Shoe(
                id = o.getString("id"),
                name = o.optString("name", "Shoes"),
                baseMeters = o.optDouble("base", 0.0),
                limitMeters = o.optDouble("limit", DEFAULT_LIMIT_M),
                retired = o.optBoolean("retired", false),
            )
        }.getOrNull()
    }
}

/** One run logged on a shoe. */
data class ShoeRun(val startTs: Long, val shoeId: String, val meters: Double)

/** Pure totals, unit-tested. */
object ShoeMath {
    fun totalMeters(shoe: Shoe, runs: List<ShoeRun>): Double =
        shoe.baseMeters + runs.filter { it.shoeId == shoe.id }.sumOf { it.meters.coerceAtLeast(0.0) }

    fun overLimit(shoe: Shoe, runs: List<ShoeRun>): Boolean =
        shoe.limitMeters > 0 && totalMeters(shoe, runs) >= shoe.limitMeters

    /** Sports that default to the default shoe on the start screen. */
    fun defaultsToShoe(sport: String): Boolean =
        sport.trim().lowercase() in setOf("running", "treadmill run", "trail running", "track running")
}

object ShoeStore {
    private const val NAME = "fork_shoes"
    private const val KEY_SHOES = "shoes"
    private const val KEY_RUNS = "runs"
    private const val KEY_DEFAULT = "defaultId"

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun shoes(ctx: Context): List<Shoe> = runCatching {
        val arr = JSONArray(p(ctx).getString(KEY_SHOES, "[]"))
        (0 until arr.length()).mapNotNull { Shoe.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun activeShoes(ctx: Context): List<Shoe> = shoes(ctx).filter { !it.retired }

    fun find(ctx: Context, id: String?): Shoe? = id?.let { i -> shoes(ctx).firstOrNull { it.id == i } }

    fun runs(ctx: Context): List<ShoeRun> = runCatching {
        val o = JSONObject(p(ctx).getString(KEY_RUNS, "{}"))
        o.keys().asSequence().mapNotNull { k ->
            val r = o.getJSONObject(k)
            ShoeRun(k.toLong(), r.getString("shoe"), r.optDouble("m", 0.0))
        }.toList()
    }.getOrDefault(emptyList())

    fun totalMeters(ctx: Context, shoe: Shoe): Double = ShoeMath.totalMeters(shoe, runs(ctx))

    fun defaultId(ctx: Context): String? =
        p(ctx).getString(KEY_DEFAULT, null)?.takeIf { id -> activeShoes(ctx).any { it.id == id } }
            ?: activeShoes(ctx).firstOrNull()?.id

    fun setDefault(ctx: Context, id: String) = p(ctx).edit().putString(KEY_DEFAULT, id).apply()

    fun upsert(ctx: Context, shoe: Shoe) {
        val list = shoes(ctx)
        val next = if (list.any { it.id == shoe.id }) list.map { if (it.id == shoe.id) shoe else it } else list + shoe
        writeShoes(ctx, next)
    }

    /** Delete a pair and forget which runs were on it (the runs themselves stay in NOOP). */
    fun delete(ctx: Context, id: String) {
        writeShoes(ctx, shoes(ctx).filterNot { it.id == id })
        writeRuns(ctx, runs(ctx).filterNot { it.shoeId == id })
    }

    fun newId(): String = "shoe." + System.currentTimeMillis().toString(36)

    // --- Runs on shoes ---

    fun shoeForRun(ctx: Context, startTs: Long): ShoeRun? = runs(ctx).firstOrNull { it.startTs == startTs }

    fun assign(ctx: Context, startTs: Long, shoeId: String, meters: Double) {
        writeRuns(ctx, runs(ctx).filterNot { it.startTs == startTs } + ShoeRun(startTs, shoeId, meters))
    }

    fun unassign(ctx: Context, startTs: Long) {
        writeRuns(ctx, runs(ctx).filterNot { it.startTs == startTs })
    }

    /** A run was edited: carry its shoe over to the (possibly moved) start and its new distance. */
    fun onRunEdited(ctx: Context, oldStartTs: Long, newStartTs: Long, meters: Double?) {
        val existing = shoeForRun(ctx, oldStartTs) ?: return
        val rest = runs(ctx).filterNot { it.startTs == oldStartTs || it.startTs == newStartTs }
        writeRuns(ctx, rest + ShoeRun(newStartTs, existing.shoeId, meters ?: 0.0))
    }

    private fun writeShoes(ctx: Context, list: List<Shoe>) {
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        p(ctx).edit().putString(KEY_SHOES, arr.toString()).apply()
    }

    private fun writeRuns(ctx: Context, list: List<ShoeRun>) {
        val o = JSONObject()
        list.forEach { r -> o.put(r.startTs.toString(), JSONObject().apply { put("shoe", r.shoeId); put("m", r.meters) }) }
        p(ctx).edit().putString(KEY_RUNS, o.toString()).apply()
    }
}
