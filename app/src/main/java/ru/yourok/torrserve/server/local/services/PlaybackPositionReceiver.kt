package ru.yourok.torrserve.server.local.services

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.yourok.torrserve.server.api.Api
import ru.yourok.torrserve.ui.activities.play.players.JustPlus
import ru.yourok.torrserve.ui.activities.play.players.MagnetSeries

/**
 * Receives playback results reported by players through the [PendingIntent] from [pendingIntent]
 * and stores the last known position of every played file in TorrServer.
 *
 * The player id carried by the callback selects the parser for the extras the player filled in.
 */
class PlaybackPositionReceiver : BroadcastReceiver() {

    /** A state reported by a player; [index] is the index in the playlist sent to the player. */
    data class PlaybackState(
        val index: Int,
        val position: Long,
        val duration: Long,
        val timestamp: Long
    )

    data class Position(
        val fileId: Int,
        val position: Long,
        val duration: Long,
        val updatedAt: Long
    )

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) return
        val player = intent.getStringExtra(EXTRA_PLAYER)
        val hash = intent.getStringExtra(EXTRA_HASH)
        val fileIds = intent.getIntArrayExtra(EXTRA_FILE_IDS)
        if (player.isNullOrBlank() || hash.isNullOrBlank() || fileIds == null) {
            Log.w(TAG, "incomplete playback result, player=$player hash=$hash")
            return
        }
        val parser = parsers[player] ?: run {
            Log.w(TAG, "unsupported player $player")
            return
        }
        val positions = parser(intent)
            .groupBy { it.index }
            .mapNotNull { (index, states) ->
                val fileId = fileIds.getOrNull(index) ?: return@mapNotNull null
                val latest = states.maxBy { it.timestamp }
                Position(fileId, latest.position, latest.duration, latest.timestamp)
            }
        if (positions.isEmpty()) {
            Log.w(TAG, "no positions in $player playback result for $hash")
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                savePositions(hash, positions)
            } catch (e: Exception) {
                Log.e(TAG, "unable to save positions for $hash", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun savePositions(hash: String, positions: List<Position>) {
        positions.forEach {
            Api.setViewed(hash, it.fileId, it.position / 1000.0)
        }
    }

    companion object {
        private const val TAG = "PlaybackPosition"
        private const val ACTION = "ru.yourok.torrserve.PLAYBACK_POSITION"
        private const val EXTRA_PLAYER = "player"
        private const val EXTRA_HASH = "hash"
        private const val EXTRA_FILE_IDS = "file_ids"
        private const val VIMU_PLAYER = "net.gtvbox.videoplayer"
        private const val VIMU_RESULT_CODE = "vimu_result_code"

        /** No direct player IPC: the wrapper Activity forwards the Vimu result to this receiver. */
        fun reportVimuResult(
            context: Context,
            hash: String,
            fileId: Int,
            resultCode: Int,
            positionMs: Int,
            durationMs: Int
        ) {
            if (hash.isBlank() || fileId <= 0) return
            val report = Intent(context, PlaybackPositionReceiver::class.java).apply {
                action = ACTION
                putExtra(EXTRA_PLAYER, VIMU_PLAYER)
                putExtra(EXTRA_HASH, hash)
                putExtra(EXTRA_FILE_IDS, intArrayOf(fileId))
                putExtra(VIMU_RESULT_CODE, resultCode)
                putExtra("position", positionMs)
                putExtra("duration", durationMs)
            }
            context.sendBroadcast(report)
        }

        /** Vimu's position/duration are milliseconds; TorrServer's timecode is seconds. */
        private fun parseVimuResult(intent: Intent): List<PlaybackState> {
            val code = intent.getIntExtra(VIMU_RESULT_CODE, -1)
            val position = intent.getIntExtra("position", -1)
            val duration = intent.getIntExtra("duration", -1)
            val now = System.currentTimeMillis()
            return when {
                code == Activity.RESULT_FIRST_USER ->
                    listOf(PlaybackState(0, 0L, duration.coerceAtLeast(0).toLong(), now))
                code in setOf(Activity.RESULT_CANCELED, 2, 3) &&
                    position > 0 && duration > 0 -> {
                    val completed = position.toLong() * 100L >= duration.toLong() * 96L
                    listOf(PlaybackState(0, if (completed) 0L else position.toLong(), duration.toLong(), now))
                }
                else -> emptyList() // Error, missing position, or a zero-length session.
            }
        }

        /** Result parsers by player id. */
        private val parsers: Map<String, (Intent) -> List<PlaybackState>> = mapOf(
            MagnetSeries.PACKAGE to MagnetSeries::parseResult,
            JustPlus.PACKAGE to JustPlus::parseResult,
            VIMU_PLAYER to ::parseVimuResult
        )

        /**
         * Callback for [player]: [fileIds] maps playlist indexes sent to the player to TorrServer file ids.
         * Mutable, since the player fills in its result extras.
         */
        fun pendingIntent(context: Context, player: String, hash: String, fileIds: List<Int>): PendingIntent {
            val intent = Intent(context, PlaybackPositionReceiver::class.java).apply {
                action = ACTION
                putExtra(EXTRA_PLAYER, player)
                putExtra(EXTRA_HASH, hash)
                putExtra(EXTRA_FILE_IDS, fileIds.toIntArray())
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
            return PendingIntent.getBroadcast(context, (player + hash).hashCode(), intent, flags)
        }
    }
}
