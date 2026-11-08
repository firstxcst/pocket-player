package com.silas.pocketplayer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import java.io.File
import java.util.Locale

/**
 * 两阶段扫描：
 *  1. mediaStore() 查系统媒体索引（含所有 volume：主存储 / SD 卡 / OTG），几十毫秒出结果；
 *  2. deep() 递归真实目录，把隐藏目录、外置卷、未被索引的文件补齐（需要「所有文件访问权限」）。
 * 两步结果由 merge() 按路径去重。
 */
object VideoScanner {

    private val EXTENSIONS = setOf(
        "mp4", "m4v", "mkv", "webm", "avi", "mov", "3gp", "ts", "mts", "m2ts",
        "flv", "f4v", "wmv", "asf", "mpg", "mpeg", "vob", "ogv", "divx",
        "rm", "rmvb"
    )

    private val PROJECTION = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DATA,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.MIME_TYPE
    )

    /** 系统噪声目录：只跳过这些，隐藏目录里的视频照扫 */
    private val SKIP_DIRS = setOf("Android", "LOST.DIR", ".thumbnails", ".Trash", "cache", "Cache")

    private const val PRIMARY_VOLUME = "external_primary"

    data class VolumeEntry(val name: String, val label: String, val videoCount: Int)

    /** MediaStore 的所有外部卷名；可移除卷一般形如 "1234-ABCD"（OTG U 盘 / SD 卡） */
    fun volumeNames(context: Context): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { MediaStore.getExternalVolumeNames(context).toList() }
                .getOrDefault(listOf(MediaStore.VOLUME_EXTERNAL))
                .ifEmpty { listOf(MediaStore.VOLUME_EXTERNAL) }
        } else {
            listOf(MediaStore.VOLUME_EXTERNAL)
        }

    private fun collectionOf(volume: String): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(volume)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

    /** OTG 入口用：列出所有可移除卷 + 每卷视频数（必须在 IO 线程调用） */
    fun volumeSummaries(context: Context): List<VolumeEntry> {
        val primary = setOf(MediaStore.VOLUME_EXTERNAL, PRIMARY_VOLUME)
        val labels: Map<String, String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                sm.storageVolumes.associate { (it.uuid ?: PRIMARY_VOLUME) to it.getDescription(context) }
            }.getOrDefault(emptyMap())
        } else emptyMap()

        return volumeNames(context).filter { it !in primary }.map { volume ->
            val count = runCatching {
                context.contentResolver.query(
                    collectionOf(volume),
                    arrayOf(MediaStore.Video.Media._ID), null, null, null
                )?.use { it.count } ?: 0
            }.getOrDefault(0)
            VolumeEntry(volume, labels[volume] ?: "外置存储 $volume", count)
        }
    }

    /** 查指定卷；不传就是全部卷。内部按路径去重（同一卷可能以多个别名出现）。 */
    fun mediaStore(context: Context, volumes: List<String> = volumeNames(context)): List<VideoItem> {
        val resolver = context.contentResolver
        val result = ArrayList<VideoItem>(256)
        val seen = HashSet<String>(512)
        for (volume in volumes.distinct()) {
            val collection = collectionOf(volume)
            val cursor = runCatching {
                resolver.query(collection, PROJECTION, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")
            }.getOrNull() ?: continue
            cursor.use { c ->
                val iId = c.getColumnIndex(MediaStore.Video.Media._ID)
                val iName = c.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val iData = c.getColumnIndex(MediaStore.Video.Media.DATA)
                val iDuration = c.getColumnIndex(MediaStore.Video.Media.DURATION)
                val iSize = c.getColumnIndex(MediaStore.Video.Media.SIZE)
                val iDate = c.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                val iMime = c.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
                while (c.moveToNext()) {
                    if (iId < 0) continue
                    val id = c.getLong(iId)
                    val path = if (iData >= 0) c.getString(iData).orEmpty() else ""
                    val name = if (iName >= 0) c.getString(iName).orEmpty() else ""
                    val item = VideoItem(
                        uri = ContentUris.withAppendedId(collection, id),
                        path = path,
                        title = name.ifBlank { path.substringAfterLast('/', "video") },
                        durationMs = if (iDuration >= 0) c.getLong(iDuration) else 0L,
                        sizeBytes = if (iSize >= 0) c.getLong(iSize) else 0L,
                        dateAddedSec = if (iDate >= 0) c.getLong(iDate) else 0L,
                        mimeType = if (iMime >= 0) c.getString(iMime) else null,
                        mediaStoreId = id,
                        volumeName = volume
                    )
                    val dedup = item.path.ifBlank { item.uri.toString() }.lowercase(Locale.ROOT)
                    if (seen.add(dedup)) result += item
                }
            }
        }
        return result
    }

    /** 真·深度扫描。没有 MANAGE_EXTERNAL_STORAGE 时直接返回空列表，主流程不受影响。 */
    fun deep(context: Context): List<VideoItem> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            return emptyList()
        }
        val roots = LinkedHashSet<File>()
        roots += Environment.getExternalStorageDirectory()
        context.getExternalFilesDirs(null).forEach { dir ->
            dir ?: return@forEach
            val marker = dir.absolutePath.indexOf("/Android/data")
            if (marker > 0) roots += File(dir.absolutePath.substring(0, marker))
        }
        // API 30+ 显式加入所有已挂载卷的目录，OTG U 盘才不会被漏掉
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                sm.storageVolumes.forEach { vol -> vol.directory?.let { roots += it } }
            }
        }

        val result = ArrayList<VideoItem>(256)
        fun walk(dir: File, depth: Int) {
            if (depth > 16 || result.size > 20_000) return
            if (File(dir, ".nomedia").exists()) return
            val children = dir.listFiles() ?: return
            for (f in children) {
                if (f.isDirectory) {
                    if (f.name in SKIP_DIRS) continue
                    walk(f, depth + 1)
                } else if (f.length() > 0 && f.extension.lowercase(Locale.ROOT) in EXTENSIONS) {
                    result += VideoItem(
                        uri = Uri.fromFile(f),
                        path = f.absolutePath,
                        title = f.name,
                        sizeBytes = f.length(),
                        dateAddedSec = f.lastModified() / 1000,
                        volumeName = volumeFromPath(f.absolutePath)
                    )
                }
            }
        }
        roots.forEach { walk(it, 0) }
        return result
    }

    /** /storage/1234-ABCD/Movies/x.mkv -> 1234-ABCD，与 MediaStore 卷名一致 */
    private fun volumeFromPath(path: String): String {
        val segment = path.removePrefix("/storage/").substringBefore('/')
        return if (segment.contains('-') && segment.length >= 5) segment else ""
    }

    /** MediaStore 结果优先（带时长/缩略图），深度扫描只补漏 */
    fun merge(primary: List<VideoItem>, extra: List<VideoItem>): List<VideoItem> {
        if (extra.isEmpty()) return primary
        val seen = HashSet<String>(primary.size + extra.size)
        val merged = ArrayList<VideoItem>(primary.size + extra.size)
        primary.forEach { if (seen.add(it.key)) merged += it }
        extra.forEach { if (seen.add(it.key)) merged += it }
        return merged
    }
}
