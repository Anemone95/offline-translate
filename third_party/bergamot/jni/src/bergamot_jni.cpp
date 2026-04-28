// JNI wrapper around bergamot-translator's BlockingService.
//
// Java/Kotlin side:  com.viwoods.stt.bergamot.BergamotTranslator
// Loadable from:     libbergamot-translator-jni.so
//
// Lifecycle:
//   nativeNew(modelDir)  -> handle (long); allocates Service + Model
//   nativeTranslate(handle, text) -> std::string; one shot blocking translation
//   nativeClose(handle)  -> tears everything down
//
// `modelDir` is expected to contain the standard Mozilla Firefox-translations
// model files:
//   model.<...>.intgemm.alphas.bin       (or model.<...>.bin)
//   lex.50.50.<src><tgt>.s2t.bin         (binary shortlist)
//   vocab.<src><tgt>.spm                 (SentencePiece, used as src+tgt)
//   config.bergamot.yml                  (optional; auto-generated if absent)
//
// We synthesize a marian YAML config string at runtime so the caller doesn't
// need to ship one. The config matches what Mozilla Firefox-translations and
// MTranServer use.

#include <jni.h>
#include <android/log.h>

#include <cstring>
#include <future>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include <dirent.h>
#include <sys/stat.h>

#include "translator/parser.h"
#include "translator/response.h"
#include "translator/response_options.h"
#include "translator/service.h"
#include "translator/translation_model.h"

#define LOG_TAG "BergamotJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// ---- file/dir helpers ------------------------------------------------------

bool fileExists(const std::string &path) {
  struct stat st;
  return ::stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

// Pick the first matching file in `dir` whose name contains all of the given
// substrings. Returns "" if none.
std::string findFileContaining(const std::string &dir,
                               const std::vector<std::string> &substrings) {
  DIR *d = ::opendir(dir.c_str());
  if (!d) return "";
  std::string match;
  struct dirent *entry;
  while ((entry = ::readdir(d)) != nullptr) {
    if (entry->d_name[0] == '.') continue;
    std::string name(entry->d_name);
    bool ok = true;
    for (const auto &s : substrings) {
      if (name.find(s) == std::string::npos) {
        ok = false;
        break;
      }
    }
    if (ok) {
      match = name;
      break;
    }
  }
  ::closedir(d);
  return match;
}

// Build a marian-compatible YAML config string for a Mozilla Firefox model
// directory. The exact set of options matches MTranServer's `config.bergamot.yml`.
//
// References:
//   https://github.com/browsermt/students/tree/master/esen
//   https://github.com/xxnuo/MTranServer/blob/main/server/configs.go
std::string makeBergamotConfigYaml(const std::string &modelDir) {
  // Locate the three required files.
  std::string modelFile = findFileContaining(modelDir, {"model.", ".intgemm.alphas.bin"});
  if (modelFile.empty()) modelFile = findFileContaining(modelDir, {"model.", ".intgemm.bin"});
  if (modelFile.empty()) modelFile = findFileContaining(modelDir, {"model.", ".bin"});

  std::string lexFile  = findFileContaining(modelDir, {"lex.", ".bin"});

  // Mozilla en-zh ships separate src/tgt vocabs (`srcvocab.*.spm`,
  // `trgvocab.*.spm`); the more common shared-vocab models use `vocab.*.spm`.
  std::string srcVocabFile = findFileContaining(modelDir, {"srcvocab.", ".spm"});
  std::string trgVocabFile = findFileContaining(modelDir, {"trgvocab.", ".spm"});
  if (srcVocabFile.empty() && trgVocabFile.empty()) {
    std::string sharedVocab = findFileContaining(modelDir, {"vocab.", ".spm"});
    srcVocabFile = sharedVocab;
    trgVocabFile = sharedVocab;
  }

  if (modelFile.empty() || lexFile.empty() ||
      srcVocabFile.empty() || trgVocabFile.empty()) {
    LOGE("Missing model files. Found: model='%s' lex='%s' srcvocab='%s' trgvocab='%s' in dir='%s'",
         modelFile.c_str(), lexFile.c_str(),
         srcVocabFile.c_str(), trgVocabFile.c_str(), modelDir.c_str());
    return "";
  }
  LOGI("Using model='%s' lex='%s' srcvocab='%s' trgvocab='%s'",
       modelFile.c_str(), lexFile.c_str(),
       srcVocabFile.c_str(), trgVocabFile.c_str());

  // gemm-precision: int8shiftAll for non-alphas, int8shiftAlphaAll for alphas
  std::string gemmPrecision = "int8shiftAll";
  if (modelFile.find("alphas") != std::string::npos) {
    gemmPrecision = "int8shiftAlphaAll";
  }

  std::ostringstream y;
  y << "models:\n";
  y << "  - " << modelDir << "/" << modelFile << "\n";
  y << "vocabs:\n";
  y << "  - " << modelDir << "/" << srcVocabFile << "\n";
  y << "  - " << modelDir << "/" << trgVocabFile << "\n";
  y << "shortlist:\n";
  y << "  - " << modelDir << "/" << lexFile << "\n";
  y << "  - false\n";  // skipShortlist == false
  y << "beam-size: 1\n";
  y << "normalize: 1.0\n";
  y << "word-penalty: 0\n";
  y << "max-length-break: 128\n";
  y << "mini-batch-words: 1024\n";
  y << "workspace: 128\n";
  y << "max-length-factor: 2.0\n";
  y << "skip-cost: true\n";
  y << "cpu-threads: 0\n";  // BlockingService -> single-threaded
  y << "quiet: true\n";
  y << "quiet-translation: true\n";
  y << "gemm-precision: " << gemmPrecision << "\n";
  y << "alignment: soft\n";
  return y.str();
}

// ---- JNI handle wrapping ---------------------------------------------------

struct BergamotInstance {
  std::unique_ptr<marian::bergamot::BlockingService> service;
  std::shared_ptr<marian::bergamot::TranslationModel> model;
  std::mutex translateMu;  // BlockingService::translateMultiple is not documented as thread-safe.
};

inline jlong toHandle(BergamotInstance *p) {
  return reinterpret_cast<jlong>(p);
}
inline BergamotInstance *fromHandle(jlong h) {
  return reinterpret_cast<BergamotInstance *>(h);
}

std::string jstring2str(JNIEnv *env, jstring s) {
  if (s == nullptr) return "";
  const char *chars = env->GetStringUTFChars(s, nullptr);
  std::string out(chars);
  env->ReleaseStringUTFChars(s, chars);
  return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_viwoods_stt_bergamot_BergamotTranslator_nativeNew(JNIEnv *env, jclass /*clazz*/, jstring jModelDir) {
  std::string modelDir = jstring2str(env, jModelDir);
  if (modelDir.empty()) {
    LOGE("nativeNew: modelDir is empty");
    return 0;
  }

  std::string yaml = makeBergamotConfigYaml(modelDir);
  if (yaml.empty()) {
    LOGE("nativeNew: failed to build config from %s", modelDir.c_str());
    return 0;
  }

  try {
    // Make marian throw rather than abort (so we can catch & log).
    marian::setThrowExceptionOnAbort(true);

    marian::bergamot::BlockingService::Config svcConfig;
    svcConfig.cacheSize = 0;  // no cache for now
    auto service = std::make_unique<marian::bergamot::BlockingService>(svcConfig);

    auto options = marian::bergamot::parseOptionsFromString(yaml, /*validate=*/false);
    auto memory  = marian::bergamot::getMemoryBundleFromConfig(options);
    auto model = std::make_shared<marian::bergamot::TranslationModel>(
        options, std::move(memory), /*replicas=*/1);

    auto *inst = new BergamotInstance{};
    inst->service = std::move(service);
    inst->model   = std::move(model);
    LOGI("nativeNew: model loaded from %s", modelDir.c_str());
    return toHandle(inst);
  } catch (const std::exception &e) {
    LOGE("nativeNew: exception: %s", e.what());
  } catch (...) {
    LOGE("nativeNew: unknown exception");
  }
  return 0;
}

JNIEXPORT jstring JNICALL
Java_com_viwoods_stt_bergamot_BergamotTranslator_nativeTranslate(JNIEnv *env, jclass /*clazz*/,
                                                                 jlong handle, jstring jText) {
  auto *inst = fromHandle(handle);
  if (!inst) {
    LOGE("nativeTranslate: null handle");
    return env->NewStringUTF("");
  }
  std::string input = jstring2str(env, jText);
  std::string output;
  try {
    std::lock_guard<std::mutex> lk(inst->translateMu);
    marian::bergamot::ResponseOptions opts;
    opts.qualityScores    = false;
    opts.alignment        = false;
    opts.HTML             = false;
    opts.sentenceMappings = false;

    std::vector<std::string> sources{std::move(input)};
    std::vector<marian::bergamot::ResponseOptions> respOpts{opts};
    auto responses = inst->service->translateMultiple(inst->model, std::move(sources), respOpts);
    if (!responses.empty()) {
      output = responses[0].target.text;
    }
  } catch (const std::exception &e) {
    LOGE("nativeTranslate: exception: %s", e.what());
  } catch (...) {
    LOGE("nativeTranslate: unknown exception");
  }
  return env->NewStringUTF(output.c_str());
}

JNIEXPORT void JNICALL
Java_com_viwoods_stt_bergamot_BergamotTranslator_nativeClose(JNIEnv * /*env*/, jclass /*clazz*/,
                                                             jlong handle) {
  auto *inst = fromHandle(handle);
  if (inst) {
    delete inst;
  }
}

}  // extern "C"
