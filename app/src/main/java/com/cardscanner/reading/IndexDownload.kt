package com.cardscanner.reading

import com.cardscanner.server.ScannerServer
import com.cardscanner.server.ServerRefused
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The picture index downloaded from a scanner server (its `GET /api/image_index/download`: stations_api.image_index_download, and one route per file) into
 * [folder], laid out as ImageIndex.load reads it. The server's rules are kept:
 *  - the listing names the current generation and each file's size and SHA-256; a file is asked for with that generation (and the printings table with
 *    its own version): a 409 means the index was rebuilt or the card data changed meanwhile, and the download starts again from the listing (once);
 *  - a file is fetched into `<name>.part`, resumed with a Range when the part is there (an interrupted download goes on where it stopped), and kept only
 *    when its SHA-256 is the listed one;
 *  - a new index is PUBLISHED by one rename of manifest.json, written last, naming the new files - a reader sees the old index or the new one, never a
 *    mix; the files the new manifest does not name are deleted after. It is published only once it LOADS (ImageIndex.open of the candidate manifest:
 *    the checksums say the bytes arrived as sent, not that they hold together - a count that does not match, another hash size, a table of other
 *    columns): a download that does not load leaves the index that worked in place, and its own files are removed (a Codex review of 2026-10-21).
 * [ImageIndex.DESCRIPTOR] must be the server's descriptor, or nothing is downloaded (another fingerprint: every distance would be wrong).
 */
class IndexDownload(private val http: OkHttpClient, serverUrl: String, private val token: String, private val folder: File) {
    private companion object { const val CANDIDATE = "manifest.json.new" }
    private val base = ScannerServer.normalize(serverUrl)

    /** Progress: what is being fetched, and the bytes done of all the files' bytes */
    fun interface Progress { fun update(what: String, done: Long, total: Long) }

    private fun request(pathAndQuery: String): Request.Builder {
        val url = ScannerServer.validatedUrl(base)
        val path = pathAndQuery.substringBefore('?')
        val query = pathAndQuery.substringAfter('?', "").ifEmpty { null }
        return Request.Builder().url(url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + path).encodedQuery(query).build()).apply {
            if (token.isNotBlank()) header("X-Station-Token", token)
        }
    }

    private class Listing(val json: JSONObject) {
        val generation: String = json.getString("generation")
        val files: Map<String, JSONObject> = json.getJSONArray("files").let { a -> (0 until a.length()).associate { a.getJSONObject(it).let { f -> f.getString("kind") to f } } }
    }

    private fun listing(): Listing {
        http.newCall(request("/api/image_index/download").header("Accept", "application/json").build()).execute().use { response ->
            val text = response.body.string()
            val json = try { JSONObject(text) } catch (e: Exception) { throw IOException("The server did not answer with its index (HTTP ${response.code})") }
            when (response.code) {
                200 -> {}
                401 -> throw ServerRefused(json.optString("message", "Wrong or missing station token"), 401)
                404 -> throw ServerRefused(json.optString("message", "This server has no picture index"), 404)
                else -> throw IOException(json.optString("message", "HTTP ${response.code}"))
            }
            if (json.optString("descriptor") != ImageIndex.DESCRIPTOR)
                throw ServerRefused("The server's index is of another fingerprint (${json.optString("descriptor")}) than this app's - update the app", 0)
            return Listing(json)
        }
    }

    /** The files the PUBLISHED manifest names (none without one) */
    private fun publishedNames(): Set<String> = try {
        val m = JSONObject(File(folder, ImageIndex.MANIFEST).readText())
        setOfNotNull(m.optString("hashes").ifEmpty { null }, m.optString("ids").ifEmpty { null }, m.optString("printings").ifEmpty { null })
    } catch (e: Exception) { emptySet() }

    /** Thrown when the server answers 409: the listing is out of date */
    private class Stale : IOException("the index changed on the server")

    /**
     * Fetch one file to [target] (resuming its .part), checked against [sha256]. [onBytes] gets the bytes of this file so far.
     */
    private fun fetch(url: String, target: File, size: Long, sha256: String, onBytes: (Long) -> Unit) {
        if (target.isFile && target.length() == size && ImageIndex.sha256(target.readBytes()) == sha256) { onBytes(size); return }   // already here
        val part = File(target.path + ".part")
        if (part.length() > size) part.delete()
        val have = if (part.isFile) part.length() else 0L
        val call = request(url).apply { if (have > 0) header("Range", "bytes=$have-").header("If-Range", "\"$sha256\"") }.build()
        http.newCall(call).execute().use { response ->
            when (response.code) {
                409 -> throw Stale()
                200, 206 -> {}
                401 -> throw ServerRefused("Wrong or missing station token", 401)
                else -> throw IOException("${target.name}: HTTP ${response.code}")
            }
            val append = response.code == 206                              // 200: the whole file again (the part was of another version)
            var done = if (append) have else 0L
            java.io.FileOutputStream(part, append).use { out ->
                val buffer = ByteArray(1 shl 16)
                response.body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        onBytes(done)
                    }
                }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        part.inputStream().use { input -> val buffer = ByteArray(1 shl 16); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        val got = digest.digest().joinToString("") { "%02x".format(it) }
        if (part.length() != size || got != sha256) { part.delete(); throw IOException("${target.name} arrived damaged - try again") }
        if (!part.renameTo(target)) throw IOException("Could not keep ${target.name}")
    }

    /** Download the server's current index (see the class); returns its generation. Blocking: call it off the main thread */
    fun download(progress: Progress): String {
        folder.mkdirs()
        repeat(2) { attempt ->
            val listing = listing()
            try {
                return publish(listing, progress)
            } catch (e: Stale) {
                if (attempt == 1) throw IOException("The server's index kept changing during the download - try again later")
            }
        }
        error("unreachable")
    }

    private fun publish(listing: Listing, progress: Progress): String {
        val g = listing.generation
        val hashes = listing.files["hashes"] ?: throw IOException("The server listed no fingerprints")
        val ids = listing.files["ids"] ?: throw IOException("The server listed no ids")
        val printings = listing.files["printings"] ?: throw IOException("The server listed no printings table")
        // names of this generation (and, for the table, of this version): never a file a published manifest names
        val names = mapOf("hashes" to "hashes-$g.bin", "ids" to "ids-$g.txt", "printings" to "printings-${printings.getString("sha256").take(16)}.tsv.gz")
        val total = listOf(hashes, ids, printings).sumOf { it.getLong("bytes") }
        var before = 0L
        for ((kind, entry) in listOf("hashes" to hashes, "ids" to ids, "printings" to printings)) {
            val start = before
            fetch(entry.getString("url"), File(folder, names.getValue(kind)), entry.getLong("bytes"), entry.getString("sha256")) { bytes ->
                progress.update(kind, start + bytes, total)
            }
            before += entry.getLong("bytes")
        }
        // The manifest ImageIndex.load reads (the server's own, plus the table's name), written beside and published by one rename
        val manifest = JSONObject()
            .put("descriptor", listing.json.getString("descriptor")).put("count", listing.json.getInt("count"))
            .put("hash_bytes", listing.json.getInt("hash_bytes")).put("built", listing.json.optString("built"))
            .put("generation", g).put("hashes", names["hashes"]).put("ids", names["ids"]).put("printings", names["printings"])
            .put("hashes_sha256", hashes.getString("sha256")).put("ids_sha256", ids.getString("sha256"))
        val candidate = File(folder, CANDIDATE).also { it.writeText(manifest.toString()) }
        // Checked BEFORE it is published: what the app will load must load. Not loading, the published manifest (the index that works, or none) stays, and
        // the files only the candidate names go - never one the published manifest names
        ImageIndex.open(folder, CANDIDATE).why?.let { why ->
            candidate.delete()
            val kept = publishedNames()
            names.values.filter { it !in kept }.forEach { File(folder, it).delete() }
            throw IOException("The downloaded index does not load ($why) - the index you had is kept")
        }
        if (!candidate.renameTo(File(folder, ImageIndex.MANIFEST))) throw IOException("Could not publish the index")
        // what the new manifest does not name goes (an older generation, a part left over)
        folder.listFiles()?.filter { it.name != ImageIndex.MANIFEST && it.name !in names.values }?.forEach { it.delete() }
        return g
    }
}
