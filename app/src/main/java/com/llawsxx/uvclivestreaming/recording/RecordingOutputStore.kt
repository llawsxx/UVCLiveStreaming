package com.llawsxx.uvclivestreaming.recording

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class RecordingOutputStore(
    private val context: Context,
    private val treeUri: String?,
) {
    private val resolver = context.contentResolver

    fun create(name: String, mimeType: String): OutputHandle {
        val customTree = treeUri?.let(Uri::parse)
        return when {
            customTree != null -> createInTree(customTree, name, mimeType)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> createInMediaStore(name, mimeType)
            else -> createLegacyDcim(name)
        }
    }

    private fun createInTree(tree: Uri, name: String, mimeType: String): OutputHandle {
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val uri = checkNotNull(DocumentsContract.createDocument(resolver, parent, mimeType, name)) {
            "无法在所选目录创建文件"
        }
        return descriptorHandle(uri, name, pending = false)
    }

    private fun createInMediaStore(name: String, mimeType: String): OutputHandle {
        val mediaDirectory = when {
            mimeType.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                Environment.DIRECTORY_MUSIC
            mimeType.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                Environment.DIRECTORY_DCIM
            else -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                Environment.DIRECTORY_DCIM
        }
        val (collection, directory) = mediaDirectory
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "$directory/UVCLiveStreaming",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(collection, values)) { "无法在 DCIM 创建文件" }
        return descriptorHandle(uri, "$directory/UVCLiveStreaming/$name", pending = true)
    }

    private fun createLegacyDcim(name: String): OutputHandle {
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "UVCLiveStreaming")
        check(directory.exists() || directory.mkdirs()) { "无法创建 ${directory.absolutePath}" }
        val file = File(directory, name)
        return OutputHandle(
            displayPath = file.absolutePath,
            file = file,
            openDescriptor = { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE) },
            openStream = { FileOutputStream(file) },
            publishAction = {},
            discardAction = { file.delete() },
        )
    }

    private fun descriptorHandle(uri: Uri, displayPath: String, pending: Boolean): OutputHandle = OutputHandle(
        displayPath = displayPath.ifBlank { uri.toString() },
        file = null,
        openDescriptor = { checkNotNull(resolver.openFileDescriptor(uri, "rw")) },
        openStream = { checkNotNull(resolver.openOutputStream(uri, "w")) },
        publishAction = {
            if (pending && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        },
        discardAction = { resolver.delete(uri, null, null) },
    )
}

class OutputHandle internal constructor(
    val displayPath: String,
    val file: File?,
    private val openDescriptor: () -> ParcelFileDescriptor,
    private val openStream: () -> OutputStream,
    private val publishAction: () -> Unit,
    private val discardAction: () -> Unit,
) {
    private var descriptor: ParcelFileDescriptor? = null

    fun descriptor(): ParcelFileDescriptor = descriptor ?: openDescriptor().also { descriptor = it }
    fun outputStream(): OutputStream = openStream()

    fun currentSize(): Long = runCatching {
        descriptor?.statSize?.takeIf { it >= 0L } ?: file?.length() ?: 0L
    }.getOrDefault(0L)

    fun closeAndPublish() {
        runCatching { descriptor?.close() }
        descriptor = null
        runCatching { publishAction() }
    }

    fun discard() {
        runCatching { descriptor?.close() }
        descriptor = null
        runCatching { discardAction() }
    }
}
