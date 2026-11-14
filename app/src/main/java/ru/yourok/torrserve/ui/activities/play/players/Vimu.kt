package ru.yourok.torrserve.ui.activities.play.players

import android.content.Intent
import android.net.Uri
import ru.yourok.torrserve.server.api.Viewed
import ru.yourok.torrserve.server.models.torrent.Torrent
import ru.yourok.torrserve.utils.Mime
import ru.yourok.torrserve.utils.TorrentHelper
import java.io.File

object Vimu {
    fun isVimuIntent(intent: Intent): Boolean =
        intent.`package` == "net.gtvbox.videoplayer" || intent.`package` == "net.gtvbox.vimuhd"

    fun getIntent(pkg: String, torrent: Torrent, index: Int, viewed: List<Viewed> = emptyList()): Intent {
        val link = TorrentHelper.getTorrentPlayLink(torrent, index)

        val file = TorrentHelper.findFile(torrent, index) ?: throw Exception("file in torrent not found")
        val mime = Mime.getMimeType(file.path)

        val intent = Intent(Intent.ACTION_VIEW)
        intent.setPackage(pkg)
        intent.putExtra("forcename", torrent.title)
        intent.putExtra("forcedirect", true)
        // TorrServer is the only resume authority. Don't use Vimu's local resume.
        intent.putExtra("forceresume", false)
        val seconds = viewed.firstOrNull {
            it.hash.equals(torrent.hash, ignoreCase = true) && it.file_index == index
        }?.timecode
        if (seconds != null && seconds.isFinite() && seconds > 0.0) {
            val ms = (seconds * 1000.0).toLong()
                .coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            intent.putExtra("startfrom", ms)
        }
        intent.setDataAndType(Uri.parse(link), mime)

        val torrfiles = TorrentHelper.getPlayableFiles(torrent)
        if (torrfiles.size > 1) {
            val names = ArrayList<String>()
            val files = ArrayList<String>()
            var idx = 0
            for (i in torrfiles.indices) {
                names.add(File(torrfiles[i].path).name)
                files.add(TorrentHelper.getFileLink(torrent, torrfiles[i]))
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
}