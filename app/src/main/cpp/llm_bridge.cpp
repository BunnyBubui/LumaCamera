// Small JNI bridge over llama.cpp: load a GGUF model, wrap a message in the model's own chat template, and generate text.
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstring>
#include <algorithm>
#include "llama.h"

#define TAG "LumaLlm"

namespace {
struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    int nCtx = 2048;
};

bool g_backend = false;
volatile bool g_stop = false;

void quietLog(ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

std::string piece(const llama_vocab *vocab, llama_token t) {
    char buf[256];
    int n = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, true);
    if (n < 0) {
        std::string big(static_cast<size_t>(-n), '\0');
        n = llama_token_to_piece(vocab, t, &big[0], static_cast<int32_t>(big.size()), 0, true);
        return n > 0 ? big.substr(0, static_cast<size_t>(n)) : std::string();
    }
    return std::string(buf, static_cast<size_t>(n));
}
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_th_ogay_luma_LlamaEngine_nativeLoad(JNIEnv *env, jobject, jstring path, jint threads, jint nCtx) {
    if (!g_backend) { llama_log_set(quietLog, nullptr); llama_backend_init(); g_backend = true; }
    llama_model_params mp = llama_model_default_params();
    llama_model *model = llama_model_load_from_file(jstr(env, path).c_str(), mp);
    if (!model) return 0;
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(nCtx);
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); return 0; }
    auto *e = new Engine();
    e->model = model; e->ctx = ctx; e->nCtx = nCtx;
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL Java_th_ogay_luma_LlamaEngine_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) return;
    llama_free(e->ctx);
    llama_model_free(e->model);
    delete e;
}

JNIEXPORT void JNICALL Java_th_ogay_luma_LlamaEngine_nativeStop(JNIEnv *, jobject) { g_stop = true; }

/** Wraps [text] as one user message with the model's own chat template; returns "" when the model has none. */
JNIEXPORT jstring JNICALL Java_th_ogay_luma_LlamaEngine_nativeTemplate(JNIEnv *env, jobject, jlong handle, jstring text) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) return env->NewStringUTF("");
    const char *tmpl = llama_model_chat_template(e->model, nullptr);
    if (!tmpl) return env->NewStringUTF("");
    std::string content = jstr(env, text);
    llama_chat_message msg{"user", content.c_str()};
    std::vector<char> buf(content.size() * 2 + 4096);
    int n = llama_chat_apply_template(tmpl, &msg, 1, true, buf.data(), static_cast<int32_t>(buf.size()));
    if (n > static_cast<int>(buf.size())) {
        buf.resize(static_cast<size_t>(n));
        n = llama_chat_apply_template(tmpl, &msg, 1, true, buf.data(), static_cast<int32_t>(buf.size()));
    }
    if (n <= 0) return env->NewStringUTF("");
    return env->NewStringUTF(std::string(buf.data(), static_cast<size_t>(n)).c_str());
}

/** Reads [prompt] (special tokens allowed) and writes at most [maxNew] tokens, stopping at the end-of-turn token. */
JNIEXPORT jstring JNICALL Java_th_ogay_luma_LlamaEngine_nativeGenerate(JNIEnv *env, jobject, jlong handle, jstring prompt, jint maxNew, jfloat repeatPenalty) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) return env->NewStringUTF("");
    g_stop = false;
    const llama_vocab *vocab = llama_model_get_vocab(e->model);
    std::string text = jstr(env, prompt);

    // Templates such as Gemma's already write the start token; do not add a second one.
    bool addSpecial = true;
    if (llama_vocab_get_add_bos(vocab)) {
        llama_token bos = llama_vocab_bos(vocab);
        if (bos >= 0) {
            const char *b = llama_vocab_get_text(vocab, bos);
            if (b && *b && text.rfind(b, 0) == 0) addSpecial = false;
        }
    }
    std::vector<llama_token> toks(text.size() + 16);
    int n = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), toks.data(), static_cast<int32_t>(toks.size()), addSpecial, true);
    if (n < 0) {
        toks.resize(static_cast<size_t>(-n));
        n = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), toks.data(), static_cast<int32_t>(toks.size()), addSpecial, true);
    }
    if (n <= 0) return env->NewStringUTF("");
    toks.resize(static_cast<size_t>(n));
    if (n + 8 >= e->nCtx) return env->NewStringUTF("");
    int budget = std::min<int>(maxNew, e->nCtx - n - 4);

    llama_memory_clear(llama_get_memory(e->ctx), true);
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 256, repeatPenalty, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    std::string out;
    // Feed the prompt in batches of at most n_batch tokens.
    int done = 0;
    bool ok = true;
    while (done < n) {
        int chunk = std::min(512, n - done);
        llama_batch b = llama_batch_get_one(toks.data() + done, chunk);
        if (llama_decode(e->ctx, b) != 0) { ok = false; break; }
        done += chunk;
    }
    if (ok) {
        for (int i = 0; i < budget && !g_stop; i++) {
            llama_token t = llama_sampler_sample(smpl, e->ctx, -1);
            if (llama_vocab_is_eog(vocab, t)) break;
            out += piece(vocab, t);
            llama_batch b = llama_batch_get_one(&t, 1);
            if (llama_decode(e->ctx, b) != 0) break;
        }
    }
    llama_sampler_free(smpl);
    return env->NewStringUTF(out.c_str());
}

}  // extern "C"
