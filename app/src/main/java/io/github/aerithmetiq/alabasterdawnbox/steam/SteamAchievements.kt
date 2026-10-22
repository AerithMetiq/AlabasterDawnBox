package io.github.aerithmetiq.alabasterdawnbox.steam

import `in`.dragonbra.javasteam.base.ClientMsgProtobuf
import `in`.dragonbra.javasteam.base.IPacketMsg
import `in`.dragonbra.javasteam.enums.EMsg
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverUserstats.CMsgClientGetUserStats
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverUserstats.CMsgClientStoreUserStats2
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverUserstats.CMsgClientStoreUserStatsResponse
import `in`.dragonbra.javasteam.steam.handlers.ClientMsgHandler
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.SteamUserStats
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.callback.UserStatsCallback
import `in`.dragonbra.javasteam.types.AsyncJobSingle
import android.util.Log
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * One game's Steam achievements on a signed-in session: [read] the user's state (read-only), and
 * [unlock] achievements by name, which **writes to Steam** and is only used while the user has
 * achievements turned on (see `AchievementService`).
 *
 * JavaSteam can read user stats but not store them, so [unlock] sends Steam's own
 * `ClientStoreUserStats2` message and waits for its `ClientStoreUserStatsResponse`. Every call blocks.
 */
class SteamAchievements(private val session: SteamSession, private val appId: Int) {

    /** The user's achievements as Steam has them, with what is needed to store a change. */
    class State(
        val crc: Int,
        val values: Map<Int, Int>,
        val bits: Map<String, AchievementBit>,
        /** Display names from the schema, by API name, for reports. */
        val titles: Map<String, String>,
        /** What Steam sent, for the report when no achievement is found: schema bytes, stats, blocks. */
        val received: String,
    ) {
        fun unlocked(name: String) = Achievements.unlocked(name, bits, values)
    }

    /* JavaSteam's SteamUserStats turns Steam's answer into a UserStatsCallback; the request is sent
     * from here (see [read]). */
    init {
        session.client.getHandler(SteamUserStats::class.java) ?: error("JavaSteam has no SteamUserStats handler")
    }

    private val replies = StoreReplies().also { session.client.addHandler(it) }

    fun read(): State {
        val me = session.client.steamID ?: throw IOException("not logged on")
        /* JavaSteam's getUserStats leaves `schema_local_version` at 0, which tells Steam this client
         * already has the schema, and Steam then sends none: no achievement names, nothing to unlock
         * (seen on the Nova, 2026-10-21: "0 of 0"). -1 asks for it, as Steam's own client does on a
         * first request. */
        val request = ClientMsgProtobuf<CMsgClientGetUserStats.Builder>(
            CMsgClientGetUserStats::class.java, EMsg.ClientGetUserStats,
        )
        request.sourceJobID = session.client.getNextJobID()
        request.body.setGameId(appId.toLong())
            .setSteamIdForUser(me.convertToUInt64())
            .setCrcStats(0)
            .setSchemaLocalVersion(-1)
        /* JavaSteam gives a job 10 s by default; Steam took longer than that to send the schema on the
         * Nova (the job was cancelled while still connected). */
        val job = AsyncJobSingle<UserStatsCallback>(session.client, request.sourceJobID).apply { timeout = TIMEOUT_SEC * 1000 }
        session.client.send(request)
        val got = try {
            job.toFuture().get(TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.CancellationException) {
            throw IOException("Steam closed the connection before sending the achievements (${closeReason()})", e)
        }
        /* A game with no stats on Steam is answered Fail (Alabaster Dawn, 2026-10-21): no achievements,
         * so whatever was asked for is not this game's, and is dropped rather than retried forever. */
        if (got.result == EResult.Fail) {
            Log.i(TAG, "achievements: Steam has no stats for app $appId (${got.result})")
            return State(0, emptyMap(), emptyMap(), emptyMap(), "no stats for this game (${got.result})")
        }
        if (got.result != EResult.OK) throw IOException("Steam would not send the achievements (${got.result})")
        val schema = got.schemaKeyValues
        val titles = LinkedHashMap<String, String>()
        for (stat in Achievements.statsOf(schema).children) for (entry in stat["bits"].children) {
            val name = entry["name"].value ?: continue
            titles[name] = entry["display"]["name"]["english"].value ?: name
        }
        return State(
            got.crcStats, got.stats.associate { it.statId to it.statValue }, Achievements.bits(schema), titles,
            "schema ${got.schema.size()} bytes with ${Achievements.statsOf(schema).children.size} stats; " +
                "${got.stats.size} stat values, ${got.achievementBlocks.size} achievement blocks",
        )
    }

    /**
     * **Writes to Steam**: unlocks [names] that are not unlocked yet. Returns the names it unlocked; names
     * already unlocked, and names this game does not have, are left alone. Throws [IOException] when
     * Steam refuses the change.
     */
    fun unlock(names: Collection<String>): List<String> {
        val state = read()
        val fresh = names.filter { it in state.bits && !state.unlocked(it) }.distinct()
        val change = Achievements.toStore(fresh, state.bits, state.values)
        Log.i(TAG, "achievements: Steam has ${state.bits.keys.count { state.unlocked(it) }} of ${state.bits.size}; " +
            "${fresh.size} of ${names.size} asked for are new" + if (change.isEmpty()) "" else ", storing stats ${change.keys}")
        if (change.isEmpty()) return emptyList()
        val me = session.client.steamID ?: throw IOException("not logged on")
        val msg = ClientMsgProtobuf<CMsgClientStoreUserStats2.Builder>(
            CMsgClientStoreUserStats2::class.java, EMsg.ClientStoreUserStats2,
        )
        msg.sourceJobID = session.client.getNextJobID()
        msg.body.setGameId(appId.toLong())
            .setSettorSteamId(me.convertToUInt64())
            .setSetteeSteamId(me.convertToUInt64())
            .setCrcStats(state.crc)
            .setExplicitReset(false)
        for ((statId, value) in change) {
            msg.body.addStats(CMsgClientStoreUserStats2.Stats.newBuilder().setStatId(statId).setStatValue(value))
        }
        /* First as is. Claiming the game on this session as well ("in game") made Steam log it off as
         * LoggedInElsewhere when the playing-status session already held the game (Nova, 2026-10-21):
         * an account is in game on one session at a time. Only if Steam refuses the change is it sent
         * again with this session running the game, as Steam's own client would be. */
        var answer = send(msg, replies)
        if (answer == null || EResult.from(answer.eresult) != EResult.OK) {
            Log.i(TAG, "achievements: Steam did not take the change (" +
                (answer?.let { EResult.from(it.eresult).toString() } ?: closeReason()) + "); again, in game")
            if (session.disconnected) throw IOException("Steam closed the connection (${closeReason()})")
            session.runningGame(appId)
            Thread.sleep(500)
            msg.sourceJobID = session.client.getNextJobID()
            answer = try {
                send(msg, replies)
            } finally {
                runCatching { session.runningGame(null) }
            } ?: throw IOException("Steam did not answer the achievement change (${closeReason()})")
        }
        val result = EResult.from(answer.eresult)
        if (result != EResult.OK) throw IOException("Steam refused the achievement change ($result)")
        if (answer.statsFailedValidationCount > 0) {
            throw IOException("Steam refused ${answer.statsFailedValidationCount} of the stats sent")
        }
        val after = read()
        val missing = fresh.filter { !after.unlocked(it) }
        if (missing.isNotEmpty()) throw IOException("Steam does not show ${missing.joinToString()} as unlocked")
        return fresh
    }

    /** Sends the change and waits for Steam's answer; null when none came. */
    private fun send(
        msg: ClientMsgProtobuf<CMsgClientStoreUserStats2.Builder>,
        replies: StoreReplies,
    ): CMsgClientStoreUserStatsResponse? {
        val reply = replies.expect()
        session.client.send(msg)
        return try {
            reply.get(TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (e: Exception) {
            null
        }
    }

    private fun closeReason(): String = when {
        session.loggedOffResult != null -> "logged off: ${session.loggedOffResult}"
        session.disconnected -> "disconnected, no reason given"
        else -> "still connected"
    }

    /** Catches Steam's answer to a stats change, which JavaSteam's own handlers ignore. */
    private class StoreReplies : ClientMsgHandler() {
        @Volatile private var waiting: CompletableFuture<CMsgClientStoreUserStatsResponse>? = null

        fun expect(): CompletableFuture<CMsgClientStoreUserStatsResponse> =
            CompletableFuture<CMsgClientStoreUserStatsResponse>().also { waiting = it }

        override fun handleMsg(packetMsg: IPacketMsg) {
            if (packetMsg.msgType != EMsg.ClientStoreUserStatsResponse) return
            val body = ClientMsgProtobuf<CMsgClientStoreUserStatsResponse.Builder>(
                CMsgClientStoreUserStatsResponse::class.java, packetMsg,
            ).body.build()
            waiting?.complete(body)
        }
    }

    private companion object {
        const val TAG = "AlabasterDawnBox"
        const val TIMEOUT_SEC = 30L
    }
}
