package com.fug.openthumb.sandbox

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Where the rootfs comes from: the APK, or the network.
 *
 * Bundling a 14 MB Alpine image is right for a build you hand someone directly
 * — one install, no waiting, works offline. It is wrong for F-Droid, which
 * builds every app from source and will not ship a tarball of several hundred
 * precompiled executables it cannot rebuild. The projects that do get in
 * (Termux, UserLAnd) all fetch their rootfs at first run instead.
 *
 * So both paths exist and the APK decides which one is used: if the asset is
 * present it is used, and if it is absent the image is downloaded. Nothing in
 * gradle has to know — `scripts/prepare_android_sandbox.sh` simply is not run
 * for a build meant to download, and the asset is missing.
 *
 * The download is verified against a digest compiled into this file. That is
 * not belt-and-braces: without it, whoever can answer for that URL — a
 * compromised release, a proxy, a captive portal — chooses which native
 * binaries land in the sandbox. An unverified rootfs is arbitrary code
 * execution with extra steps, so a mismatch deletes the file and fails.
 */
object RootfsSource {

    /**
     * Published as its own release so the image and the app version can move
     * independently: a new app build does not need a new 14 MB upload, and
     * re-cutting the image does not need an app release.
     *
     * Generated and uploaded by `scripts/publish-rootfs.sh`, which also prints
     * the digest below. Change one, change the other.
     */
    const val ROOTFS_TAG = "rootfs-3.21.3-1"
    const val ROOTFS_FILE = "alpine-rootfs-3.21.3-aarch64-py312.tar.gz"
    const val ROOTFS_URL =
        "https://github.com/neulketing/openthumb/releases/download/$ROOTFS_TAG/$ROOTFS_FILE"

    /**
     * SHA-256 of the file at [ROOTFS_URL]. Empty disables the download path
     * entirely rather than accepting anything — a build that forgot to set this
     * must fail loudly, not fetch an unverified image.
     *
     * This pins the published artifact, not a reproducible build: the image is
     * repacked with `tar czf`, which records mtimes, so rebuilding it yields a
     * different digest. That is fine for what this guards — the app checking it
     * received the exact bytes we published — and it is why the image is
     * uploaded once and referenced by tag rather than rebuilt per app release.
     */
    const val ROOTFS_SHA256 =
        "bea4738287f0c7f38759d14f3a6a53852c9e93120629064ea2d873b7bdeedd24"

    private const val TAG = "RootfsSource"
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000

    /** How the image will be obtained, decided by what the APK contains. */
    sealed class Plan {
        /** Bundled: [assetName] is openable from `context.assets`. */
        data class FromAsset(val assetName: String, val compressedBytes: Long) : Plan()

        /** Not bundled: fetch [url] and check it against [sha256]. */
        data class FromNetwork(val url: String, val sha256: String) : Plan()

        /** Not bundled and not fetchable — say why rather than fail obscurely. */
        data class Unavailable(val reason: String) : Plan()
    }

    fun plan(context: Context): Plan {
        for (name in listOf("alpine-minirootfs.tar.gz", "alpine-minirootfs.tar")) {
            val size = runCatching {
                context.assets.open(name).close()
                // openFd throws for a 0-length asset on some devices; a 0 total
                // only costs the progress bar, so it is not worth failing over.
                runCatching { context.assets.openFd(name).use { it.length } }.getOrDefault(0L)
            }.getOrNull()
            if (size != null) return Plan.FromAsset(name, size)
        }
        if (ROOTFS_SHA256.isBlank()) {
            return Plan.Unavailable(
                "this build has no bundled rootfs and no checksum to verify a " +
                    "downloaded one against — it cannot install a sandbox safely",
            )
        }
        return Plan.FromNetwork(ROOTFS_URL, ROOTFS_SHA256)
    }

    /** Where a partial download lives. Outside the rootfs, which gets wiped on retry. */
    fun cacheFile(context: Context): File = File(context.cacheDir, ROOTFS_FILE)

    /**
     * Fetch the image if it is not already complete in the cache, verify it, and
     * return it.
     *
     * Resumable, because 14 MB over a phone's connection is long enough for
     * Doze or a walk out of Wi-Fi range to interrupt it, and restarting from
     * zero every time turns a slow install into one that never finishes.
     *
     * @param onProgress fraction in 0f..1f, or -1f when the server did not say
     *   how large the file is.
     */
    fun download(
        context: Context,
        url: String,
        expectedSha256: String,
        onProgress: (Float) -> Unit,
    ): File {
        val target = cacheFile(context)

        if (target.isFile && sha256Of(target).equals(expectedSha256, ignoreCase = true)) {
            Log.i(TAG, "rootfs already in cache and verified")
            onProgress(1f)
            return target
        }

        val part = File(target.parentFile, target.name + ".part")
        var have = if (part.isFile) part.length() else 0L

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }

        try {
            val code = conn.responseCode
            if (code == 200 && have > 0) {
                // The server ignored the range and is sending the whole file;
                // keeping the old bytes would splice two copies together.
                Log.i(TAG, "range ignored, restarting download")
                part.delete()
                have = 0
            } else if (code != 200 && code != 206) {
                throw java.io.IOException("rootfs download failed: HTTP $code")
            }

            val remaining = conn.contentLengthLong
            val total = if (remaining > 0) have + remaining else -1L

            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, have > 0).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var written = have
                    var lastReported = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        if (total > 0) {
                            // Whole percents only: a StateFlow updated per
                            // buffer would emit hundreds of times a second.
                            val pct = (written * 100 / total).toInt()
                            if (pct != lastReported) {
                                lastReported = pct
                                onProgress(pct / 100f)
                            }
                        } else if (lastReported < 0) {
                            lastReported = 0
                            onProgress(-1f)
                        }
                    }
                    out.fd.sync()  // the point of a resumable file is surviving a kill
                }
            }
        } finally {
            conn.disconnect()
        }

        val actual = sha256Of(part)
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            part.delete()
            // Deliberately does not report which bytes differed: the only
            // useful action is to fetch it again from a source you trust.
            throw java.io.IOException(
                "downloaded rootfs does not match its expected checksum and was discarded",
            )
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            throw java.io.IOException("could not move the verified rootfs into place")
        }
        onProgress(1f)
        Log.i(TAG, "rootfs downloaded and verified (${target.length()} bytes)")
        return target
    }

    fun openVerified(file: File): InputStream = file.inputStream()

    fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
