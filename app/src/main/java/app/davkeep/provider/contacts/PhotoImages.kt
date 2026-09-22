package app.davkeep.provider.contacts

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Reads a photo whose URL a vCard named.
 *
 * The mapper only ever calls this for a URL it has already proven is on the Collection's own Origin:
 * a photo URL is data, and data must not be able to decide where an Account's Credentials are sent.
 */
fun interface PhotoFetcher {
    /** The bytes [url] names, or null when they cannot be read. Blocking; called from the sync thread. */
    fun fetch(url: String): ByteArray?
}

/**
 * Turns bytes from the network or from a vCard into the image the contacts provider wants.
 *
 * Nothing here scales an image <em>up</em>: a photo smaller than the bound is written as it is.
 */
internal object PhotoImages {

    /**
     * JPEG quality for the image handed to the provider. It re-encodes the thumbnail itself, so this
     * only has to be good enough for the full-size photo it stores alongside.
     */
    private const val DISPLAY_QUALITY = 85

    /**
     * Decodes [bytes] and returns a JPEG no larger than [maxDimension] on its longest side, or null
     * when nothing decodes.
     *
     * The image handed over is the display-size one on purpose: the provider derives both the
     * thumbnail and the full-size display photo from it, and only creates the display-photo file at
     * all when what it is given is larger than a thumbnail — hand it a thumbnail and the full-size
     * photo silently never exists.
     */
    fun toDisplayPhoto(bytes: ByteArray, maxDimension: Int): ByteArray? {
        if (bytes.isEmpty()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val scaled = scaleToFit(decoded, maxDimension)
        try {
            return ByteArrayOutputStream().use { out ->
                if (scaled.compress(Bitmap.CompressFormat.JPEG, DISPLAY_QUALITY, out)) out.toByteArray() else null
            }
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    /**
     * The largest power of two that still leaves the longer side at or above [maxDimension].
     *
     * Decoding at a fraction of full size is the only thing that keeps a camera-sized photo from
     * becoming a full-size ARGB bitmap on the way in, and [BitmapFactory] only ever samples by powers
     * of two — so the exact resize happens afterwards, on the much smaller result.
     */
    private fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= maxDimension) sample *= 2
        return sample
    }

    private fun scaleToFit(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longestSide = max(bitmap.width, bitmap.height)
        if (longestSide <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / longestSide
        return Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * ratio).toInt()),
            max(1, (bitmap.height * ratio).toInt()),
            true,
        )
    }

    /**
     * The absolute URL [reference] names, resolved against the Collection when it is relative.
     *
     * Returns null when it is neither absolute nor resolvable, which keeps an unreadable reference
     * from being treated as anything at all.
     */
    fun resolve(collectionUrl: String, reference: String): HttpUrl? =
        collectionUrl.toHttpUrlOrNull()?.resolve(reference) ?: reference.toHttpUrlOrNull()

    /**
     * True when [target] names the same scheme, host and port as [collectionUrl].
     *
     * Nothing else about the URL matters: this answers exactly one question, whether sending the
     * Account's Credentials there would send them off the Origin the user configured.
     */
    fun isSameOrigin(collectionUrl: String, target: HttpUrl): Boolean {
        val origin = collectionUrl.toHttpUrlOrNull() ?: return false
        return origin.scheme == target.scheme && origin.host == target.host && origin.port == target.port
    }
}
