package com.yourgame.videoexport

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.provider.MediaStore
import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import java.io.File

class VideoExportPlugin(godot: Godot) : GodotPlugin(godot) {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var outputFile: File? = null
    private var width = 0
    private var height = 0
    private var fps = 30
    private var frameDurationUs = 0L
    private var presentationTimeUs = 0L

    override fun getPluginName() = "VideoExport"

    fun startEncoder(fileName: String, w: Int, h: Int, frameRate: Int) {
        width = w; height = h; fps = frameRate
        frameDurationUs = 1_000_000L / fps
        presentationTimeUs = 0L

        outputFile = File(activity!!.cacheDir, fileName)
        val mime = "video/avc"
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        codec = MediaCodec.createEncoderByType(mime)
        codec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec!!.start()

        muxer = MediaMuxer(outputFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxerStarted = false
        trackIndex = -1
    }

    fun addFrame(jpegBytes: ByteArray) {
        val codec = this.codec ?: return
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        if (bitmap == null) return

        val yuvBytes = bitmapToYuv420(bitmap, width, height)
        bitmap.recycle()

        val inputBufferId = codec.dequeueInputBuffer(10000)
        if (inputBufferId >= 0) {
            val inputBuffer = codec.getInputBuffer(inputBufferId)!!
            inputBuffer.clear()
            inputBuffer.put(yuvBytes)
            codec.queueInputBuffer(inputBufferId, 0, yuvBytes.size, presentationTimeUs, 0)
            presentationTimeUs += frameDurationUs
        }
        drainEncoder()
    }

    fun finishEncoderAndSaveToGallery(displayName: String) {
        codec?.signalEndOfInputStream()
        drainEncoder()
        codec?.stop()
        codec?.release()
        muxer?.stop()
        muxer?.release()

        outputFile?.let { file ->
            saveToGallery(file, displayName)
            file.delete()
        }
    }

    private fun drainEncoder() {
        val codec = this.codec ?: return
        val muxer = this.muxer ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outId = codec.dequeueOutputBuffer(bufferInfo, 10000)
            if (outId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted) {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
            } else if (outId >= 0) {
                val buf = codec.getOutputBuffer(outId)
                if (bufferInfo.size > 0 && muxerStarted && buf != null) {
                    buf.position(bufferInfo.offset)
                    buf.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(trackIndex, buf, bufferInfo)
                }
                codec.releaseOutputBuffer(outId, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            } else break
        }
    }

    private fun saveToGallery(file: File, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
        }
        val uri = activity?.contentResolver?.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
        )
        uri?.let {
            activity?.contentResolver?.openOutputStream(it)?.use { os ->
                file.inputStream().use { inp -> inp.copyTo(os) }
            }
        }
    }

    private fun bitmapToYuv420(bitmap: Bitmap, width: Int, height: Int): ByteArray {
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        val ySize = width * height
        val uvSize = (width / 2) * (height / 2)
        val yuv = ByteArray(ySize + uvSize * 2)
        var yIndex = 0
        var uIndex = ySize
        var vIndex = ySize + uvSize

        for (j in 0 until height) {
            for (i in 0 until width) {
                val pixel = argb[j * width + i]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIndex++] = y.coerceIn(0, 255).toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    yuv[uIndex++] = u.coerceIn(0, 255).toByte()
                    yuv[vIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
        return yuv
    }
}
