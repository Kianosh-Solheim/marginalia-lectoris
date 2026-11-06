package com.example.reader

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

/**
 * The offline meaning model: multilingual-e5-small (Norwegian, English and ~100 other languages),
 * run on the tablet with ONNX Runtime. Texts become 384 numbers; texts about the same thing get
 * similar numbers. The model (about 118 MB) is downloaded once, on request.
 */
object LocalEmbedder {
    const val DIMS = 384
    const val MODEL_URL = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/onnx/model_quantized.onnx"
    const val TOKENIZER_URL = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/tokenizer.json"

    fun dir(root: File) = File(root, "models/e5-small")
    fun installed(root: File) = File(dir(root), "model.onnx").isFile && File(dir(root), "vocab.bin").isFile

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tok: E5Tokenizer? = null

    @Synchronized fun close() { runCatching { session?.close() }; session = null; tok = null }

    @Synchronized private fun open(root: File) {
        if (session != null && tok != null) return
        val d = dir(root)
        check(installed(root)) { "The offline model is not downloaded yet." }
        tok = E5Tokenizer.load(File(d, "vocab.bin"))
        val e = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        env = e
        session = e.createSession(File(d, "model.onnx").absolutePath, opts)
    }

    /**
     * Meaning vectors (length 1) for [texts]. [query]: a search ("query: …"), else a passage ("passage: …"),
     * as the model was trained.
     */
    @Synchronized fun embed(root: File, texts: List<String>, query: Boolean, maxTokens: Int = 320): List<FloatArray> {
        open(root)
        val t = tok!!; val s = session!!; val e = env!!
        val enc = texts.map { t.encode((if (query) "query: " else "passage: ") + it, maxTokens) }
        val len = enc.maxOf { it.size }
        val ids = Array(enc.size) { i -> LongArray(len) { j -> if (j < enc[i].size) enc[i][j].toLong() else t.pad.toLong() } }
        val mask = Array(enc.size) { i -> LongArray(len) { j -> if (j < enc[i].size) 1L else 0L } }
        val types = Array(enc.size) { LongArray(len) }
        OnnxTensor.createTensor(e, ids).use { a ->
            OnnxTensor.createTensor(e, mask).use { b ->
                OnnxTensor.createTensor(e, types).use { c ->
                    s.run(mapOf("input_ids" to a, "attention_mask" to b, "token_type_ids" to c)).use { r ->
                        @Suppress("UNCHECKED_CAST")
                        val out = r[0].value as Array<Array<FloatArray>>
                        return out.mapIndexed { i, rows ->
                            // Mean of the word vectors (not the padding), then length 1.
                            val v = FloatArray(DIMS); val n = enc[i].size
                            for (j in 0 until n) { val row = rows[j]; for (k in 0 until DIMS) v[k] += row[k] }
                            var norm = 0.0
                            for (k in 0 until DIMS) { v[k] /= n; norm += v[k] * v[k] }
                            val inv = (1.0 / kotlin.math.sqrt(norm).coerceAtLeast(1e-9)).toFloat()
                            for (k in 0 until DIMS) v[k] *= inv
                            v
                        }
                    }
                }
            }
        }
    }
}
