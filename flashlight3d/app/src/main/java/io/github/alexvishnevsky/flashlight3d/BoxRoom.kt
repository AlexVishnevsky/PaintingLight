package io.github.alexvishnevsky.flashlight3d

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds, as an in-memory .glb, the open box that sits behind the screen: its
 * opening is exactly the screen rectangle and its gridded walls are what makes
 * head-coupled perspective read as depth. Generating a glTF keeps everything on
 * gltfio's stock materials, so no custom Filament material has to be compiled.
 *
 * Units are metres; the screen is the z = 0 plane, the box extends to -depth.
 */
object BoxRoom {

    fun buildGlb(halfWidth: Float, halfHeight: Float, depth: Float, tile: Float): ByteBuffer {
        val w = halfWidth
        val h = halfHeight
        val d = depth
        // 5 quads: position (3), normal (3), uv (2) per vertex.
        val quads = arrayOf(
            // back
            floatArrayOf(-w, -h, -d, w, -h, -d, w, h, -d, -w, h, -d) to floatArrayOf(0f, 0f, 1f),
            // left
            floatArrayOf(-w, -h, 0f, -w, -h, -d, -w, h, -d, -w, h, 0f) to floatArrayOf(1f, 0f, 0f),
            // right
            floatArrayOf(w, -h, -d, w, -h, 0f, w, h, 0f, w, h, -d) to floatArrayOf(-1f, 0f, 0f),
            // bottom
            floatArrayOf(-w, -h, 0f, w, -h, 0f, w, -h, -d, -w, -h, -d) to floatArrayOf(0f, 1f, 0f),
            // top
            floatArrayOf(-w, h, -d, w, h, -d, w, h, 0f, -w, h, 0f) to floatArrayOf(0f, -1f, 0f),
        )
        val vertexCount = quads.size * 4
        val indexCount = quads.size * 6
        val png = gridPng()

        val posBytes = vertexCount * 12
        val uvBytes = vertexCount * 8
        val idxBytes = indexCount * 2
        val idxOffset = posBytes * 2 + uvBytes
        val pngOffset = align4(idxOffset + idxBytes)
        val binLength = align4(pngOffset + png.size)
        val bin = ByteBuffer.allocate(binLength).order(ByteOrder.LITTLE_ENDIAN)

        for ((p, _) in quads) p.forEach { bin.putFloat(it) }
        for ((_, n) in quads) repeat(4) { n.forEach { bin.putFloat(it) } }
        for ((p, n) in quads) {
            for (i in 0 until 4) {
                val x = p[i * 3]
                val y = p[i * 3 + 1]
                val z = p[i * 3 + 2]
                // Project along the wall's normal so tiles stay square on every wall.
                val (s, t) = when {
                    n[2] != 0f -> x to y
                    n[0] != 0f -> z to y
                    else -> x to z
                }
                bin.putFloat(s / tile)
                bin.putFloat(t / tile)
            }
        }
        for (q in quads.indices) {
            val b = q * 4
            intArrayOf(b, b + 1, b + 2, b, b + 2, b + 3).forEach { bin.putShort(it.toShort()) }
        }
        bin.position(pngOffset)
        bin.put(png)

        val json = """
            {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
            "nodes":[{"mesh":0,"name":"Room"}],
            "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"TEXCOORD_0":2},"indices":3,"material":0}]}],
            "materials":[{"name":"Walls","doubleSided":true,"pbrMetallicRoughness":{"baseColorTexture":{"index":0},"metallicFactor":0.0,"roughnessFactor":0.62}}],
            "textures":[{"source":0,"sampler":0}],
            "samplers":[{"magFilter":9729,"minFilter":9987,"wrapS":10497,"wrapT":10497}],
            "images":[{"bufferView":4,"mimeType":"image/png"}],
            "accessors":[
            {"bufferView":0,"componentType":5126,"count":$vertexCount,"type":"VEC3","min":[${-w},${-h},${-d}],"max":[$w,$h,0.0]},
            {"bufferView":1,"componentType":5126,"count":$vertexCount,"type":"VEC3"},
            {"bufferView":2,"componentType":5126,"count":$vertexCount,"type":"VEC2"},
            {"bufferView":3,"componentType":5123,"count":$indexCount,"type":"SCALAR"}],
            "bufferViews":[
            {"buffer":0,"byteOffset":0,"byteLength":$posBytes,"target":34962},
            {"buffer":0,"byteOffset":$posBytes,"byteLength":$posBytes,"target":34962},
            {"buffer":0,"byteOffset":${posBytes * 2},"byteLength":$uvBytes,"target":34962},
            {"buffer":0,"byteOffset":$idxOffset,"byteLength":$idxBytes,"target":34963},
            {"buffer":0,"byteOffset":$pngOffset,"byteLength":${png.size}}],
            "buffers":[{"byteLength":$binLength}]}
        """.trimIndent().replace("\n", "")
        var jsonBytes = json.toByteArray(Charsets.US_ASCII)
        if (jsonBytes.size % 4 != 0) {
            jsonBytes = jsonBytes + ByteArray(4 - jsonBytes.size % 4) { ' '.code.toByte() }
        }

        val total = 12 + 8 + jsonBytes.size + 8 + binLength
        val glb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        glb.putInt(0x46546C67).putInt(2).putInt(total)
        glb.putInt(jsonBytes.size).putInt(0x4E4F534A).put(jsonBytes)
        glb.putInt(binLength).putInt(0x004E4942).put(bin.array())
        glb.flip()
        return glb
    }

    private fun align4(n: Int) = (n + 3) and 3.inv()

    /** One tile: light panel with a darker outline. */
    private fun gridPng(): ByteArray {
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF50555F.toInt())
        val paint = Paint().apply { color = 0xFFB4B8C0.toInt() }
        val line = 2f
        canvas.drawRect(line, line, size - line, size - line, paint)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}
