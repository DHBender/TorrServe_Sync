package ru.yourok.torrserve.ui.activities.play

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import ru.yourok.torrserve.server.local.services.PlaybackPositionReceiver

/**
 * A separate result owner is required because the existing PlayActivity immediately finishes
 * after launching a player (and NUM expects its original result). No media data passes here.
 */
class VimuResultActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return

        @Suppress("DEPRECATION")
        val playerIntent = intent.getParcelableExtra<Intent>(EXTRA_PLAYER_INTENT)
        if (playerIntent == null) {
            Log.e(TAG, "Missing player Intent")
            finish()
            return
        }
        try {
            startActivityForResult(playerIntent, REQUEST_VIMU)
        } catch (e: Exception) {
            Log.e(TAG, "Vimu failed to launch", e)
            finish()
        }
    }

    @Deprecated("Uses the legacy result contract implemented by Vimu")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VIMU) return

        val hash = intent.getStringExtra(EXTRA_HASH).orEmpty()
        val selectedIndex = intent.getIntExtra(EXTRA_SELECTED_INDEX, -1)
        val allowedIds = intent.getIntArrayExtra(EXTRA_FILE_IDS) ?: intArrayOf()
        val url = data?.getStringExtra("url")?.takeIf { it.isNotBlank() }
            ?: data?.dataString?.takeIf { it.isNotBlank() }

        val fileId = findResultFileId(url, hash, selectedIndex, allowedIds)
        val pos = data?.getIntExtra("position", -1) ?: -1
        val dur = data?.getIntExtra("duration", -1) ?: -1

        Log.i(TAG, "Vimu exit code=" + resultCode + " file=" + fileId +
            " positionMs=" + pos + " durationMs=" + dur)
        if (fileId != null && resultCode in setOf(0, 1, 2, 3)) {
            PlaybackPositionReceiver.reportVimuResult(
                applicationContext, hash, fileId, resultCode, pos, dur
            )
        } else {
            Log.w(TAG, "Ignoring result: unknown episode, failure or missing URL")
        }
        finish()
    }

    /**
     * Vimu returns the URL of the interrupted playlist item. Never overwrite the originally
     * selected episode if the user switched to another one. For a single file, URL is optional.
     */
    private fun findResultFileId(
        url: String?, hash: String, selectedIndex: Int, allowedIds: IntArray
    ): Int? {
        if (hash.isBlank()) return null
        if (url.isNullOrBlank()) {
            return selectedIndex.takeIf { allowedIds.size == 1 && allowedIds[0] == it }
        }
        return runCatching {
            val uri = Uri.parse(url)
            val returnedHash = uri.getQueryParameter("link")
            val returnedIndex = uri.getQueryParameter("index")?.toIntOrNull()
            returnedIndex?.takeIf {
                returnedHash.equals(hash, ignoreCase = true) && allowedIds.contains(it)
            }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "VimuSync"
        private const val REQUEST_VIMU = 1655
        private const val EXTRA_PLAYER_INTENT = "vimu_sync_player_intent"
        private const val EXTRA_HASH = "vimu_sync_hash"
        private const val EXTRA_SELECTED_INDEX = "vimu_sync_selected_index"
        private const val EXTRA_FILE_IDS = "vimu_sync_allowed_ids"

        fun wrap(
            context: Context,
            playerIntent: Intent,
            hash: String,
            selectedIndex: Int,
            allowedIds: IntArray
        ): Intent = Intent(context, VimuResultActivity::class.java).apply {
            putExtra(EXTRA_PLAYER_INTENT, playerIntent)
            putExtra(EXTRA_HASH, hash)
            putExtra(EXTRA_SELECTED_INDEX, selectedIndex)
            putExtra(EXTRA_FILE_IDS, allowedIds)
        }
    }
}
