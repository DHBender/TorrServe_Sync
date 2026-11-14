package ru.yourok.torrserve.ui.activities.play.players

import android.content.Intent
import android.net.Uri
import android.util.Log
import ru.yourok.torrserve.server.api.Viewed
import ru.yourok.torrserve.server.models.torrent.Torrent
import ru.yourok.torrserve.utils.Mime
import ru.yourok.torrserve.utils.TorrentHelper
import java.io.File
import java.util.UUID

object Vimu {
    fun isVimuIntent(intent: Intent): Boolean =
        intent.`package` == "net.gtvbox.videoplayer" || intent.`package` == "net.gtvbox.vimuhd"

    fun getIntent(pkg: String, torrent: Torrent, index: Int, viewed: List<Viewed> = emptyList()): Intent {
        // A fresh filename on every launch prevents Vimu's device-local resume cache
        // from competing with the shared TorrServer /viewed timecode.
        // MatriX.146 /stream selects the file by link+index, not the URL filename.
        val launchId = UUID.randomUUID().toString().replace("-", "")
        val link = freshPlaybackUrl(TorrentHelper.getTorrentPlayLink(torrent, index), launchId, index)

        val file = TorrentHelper.findFile(torrent, index) ?: throw Exception("file in torrent not found")
        val mime = Mime.getMimeType(file.path)

        val intent = Intent(Intent.ACTION_VIEW)
        intent.setPackage(pkg)
        intent.putExtra("forcename", torrent.title)
        intent.putExtra("forcedirect", true)
        // Server timecode is authoritative. Vimu's own resume database must not
        // show an outdated "Continue" prompt for a previously played file.
        intent.putExtra("forceresume", false)
        val seconds = viewed.firstOrNull {
            it.hash.equals(torrent.hash, ignoreCase = true) && it.file_index == index
        }?.timecode
        val startMs = if (seconds != null && seconds.isFinite() && seconds > 0.0) {
            (seconds * 1000.0).toLong().coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        } else {
            0
        }
        // Also pass 0 to explicitly start from the beginning when /viewed has no position.
        intent.putExtra("startfrom", startMs)
        Log.i("VimuSync", "Launching Vimu with server startMs=$startMs fileIndex=$index and isolated URL")
        intent.setDataAndType(Uri.parse(link), mime)

        val torrfiles = TorrentHelper.getPlayableFiles(torrent)
        if (torrfiles.size > 1) {
            val names = ArrayList<String>()
            val files = ArrayList<String>()
            var idx = 0
            for (i in torrfiles.indices) {
                names.add(File(torrfiles[i].path).name)
                files.add(freshPlaybackUrl(
                    TorrentHelper.getFileLink(torrent, torrfiles[i]),
                    launchId,
                    torrfiles[i].id
                ))
                if (torrfiles[i].id == index) idx = i
            }
            intent.setDataAndType(Uri.parse(link), "application/vnd.gtvbox.filelist")
            intent.putStringArrayListExtra("asusfilelist", files)
            intent.putStringArrayListExtra("asusnamelist", names)
            intent.putExtra("startindex", idx)
        } else {
            val extAudio = TorrentHelper.getAudioFiles(torrent).map { audio ->
                TorrentHelper.getFileLink(torrent, audio)
            }
            if (extAudio.isNotEmpty()) {
                intent.putStringArrayListExtra("extsound", ArrayList(extAudio))
            }
        }
        return intent
    }

    /**
     * Vimu 12.50 can remember playback by URL filename even when forceresume=false.
     * Each launch gets a new, short basename while preserving extension, host, and
     * the exact link/index/play query arguments that MatriX uses to resolve the file.
     *
     * The returned Vimu playlist URL still contains link and index, so the activity
     * result continues to be mapped to the real torrent file ID.
     */
    private fun freshPlaybackUrl(original: String, launchId: String, fileIndex: Int): String {
        val uri = Uri.parse(original)
        val encodedPath = uri.encodedPath ?: return original
        val fileName = encodedPath.substringAfterLast('/')
        val extension = fileName.substringAfterLast('.', "")
            .takeIf { it.matches(Regex("[a-zA-Z0-9]{1,8}")) }
            ?.let { ".$it" } ?: ""
        val filename = "tsync_${launchId}_${fileIndex}$extension"
        val prefix = encodedPath.substringBeforeLast('/', "")
        return uri.buildUpon()
            .encodedPath("$prefix/$filename")
            .build()
            .toString()
    }
}