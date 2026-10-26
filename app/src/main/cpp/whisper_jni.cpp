// JNI bridge to whisper.cpp for offline transcription (com.example.reader.WhisperNative).
#include <jni.h>
#include <algorithm>
#include <atomic>
#include <cstdio>
#include <string>
#include <thread>
#include "whisper.h"

static std::atomic<bool> g_abort(false);

struct Progress {
    JNIEnv *env;
    jobject callback;
    jmethodID method;
};

static void on_progress(struct whisper_context *, struct whisper_state *, int progress, void *user_data) {
    auto *p = static_cast<Progress *>(user_data);
    if (p && p->callback && p->method) p->env->CallVoidMethod(p->callback, p->method, (jint) progress);
}

static bool on_abort(void *) { return g_abort.load(); }

static void json_escape(std::string &out, const char *s) {
    for (const unsigned char *c = (const unsigned char *) s; *c; ++c) {
        switch (*c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (*c < 0x20) { char buf[8]; snprintf(buf, sizeof buf, "\\u%04x", *c); out += buf; }
                else out += (char) *c;
        }
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_reader_WhisperNative_initContext(JNIEnv *env, jclass, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    struct whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cp);
    env->ReleaseStringUTFChars(modelPath, path);
    return (jlong) ctx;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_reader_WhisperNative_freeContext(JNIEnv *, jclass, jlong ctx) {
    if (ctx) whisper_free((struct whisper_context *) ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_reader_WhisperNative_cancel(JNIEnv *, jclass) { g_abort.store(true); }

/** Transcribes 16 kHz mono samples. Returns UTF-8 JSON {"language":"no","segments":[{"start":ms,"end":ms,"text":"…"}]}, or null. */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_reader_WhisperNative_transcribe(JNIEnv *env, jclass, jlong ctxPtr, jfloatArray samples,
                                                 jstring language, jint threads, jstring prompt, jobject callback) {
    auto *ctx = (struct whisper_context *) ctxPtr;
    if (!ctx) return nullptr;
    g_abort.store(false);
    jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    const char *lang = env->GetStringUTFChars(language, nullptr);
    const char *init = prompt ? env->GetStringUTFChars(prompt, nullptr) : nullptr;

    struct whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    p.beam_search.beam_size = 3;
    p.print_realtime = false;
    p.print_progress = false;
    p.print_timestamps = false;
    p.print_special = false;
    p.translate = false;
    p.no_context = false;
    p.single_segment = false;
    p.suppress_blank = true;
    int hw = (int) std::thread::hardware_concurrency();
    p.n_threads = threads > 0 ? threads : std::max(1, hw);
    std::string l(lang);
    p.language = l == "auto" ? "auto" : lang;
    p.detect_language = false;
    if (init && *init) p.initial_prompt = init;

    Progress prog{env, callback, nullptr};
    if (callback) {
        jclass k = env->GetObjectClass(callback);
        prog.method = env->GetMethodID(k, "onProgress", "(I)V");
    }
    p.progress_callback = on_progress;
    p.progress_callback_user_data = &prog;
    p.abort_callback = on_abort;
    p.abort_callback_user_data = nullptr;

    int rc = whisper_full(ctx, p, data, n);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    if (init) env->ReleaseStringUTFChars(prompt, init);

    if (rc != 0 || g_abort.load()) { env->ReleaseStringUTFChars(language, lang); return nullptr; }

    std::string out = "{\"language\":\"";
    int id = whisper_full_lang_id(ctx);
    const char *detected = id >= 0 ? whisper_lang_str(id) : lang;
    json_escape(out, detected ? detected : lang);
    out += "\",\"segments\":[";
    int segs = whisper_full_n_segments(ctx);
    for (int i = 0; i < segs; ++i) {
        if (i) out += ",";
        long long t0 = (long long) whisper_full_get_segment_t0(ctx, i) * 10;
        long long t1 = (long long) whisper_full_get_segment_t1(ctx, i) * 10;
        out += "{\"start\":" + std::to_string(t0) + ",\"end\":" + std::to_string(t1) + ",\"text\":\"";
        json_escape(out, whisper_full_get_segment_text(ctx, i));
        out += "\"}";
    }
    out += "]}";
    env->ReleaseStringUTFChars(language, lang);
    // Bytes, not a jstring: segment text can hold characters JNI's modified UTF-8 cannot carry.
    jbyteArray result = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(result, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return result;
}
