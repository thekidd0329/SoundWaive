// LiteRT's stable C interpreter API supplies the CPU path on 32-bit Intel.
#include <android/asset_manager_jni.h>
#include <jni.h>
#include <cpuid.h>
#include <stdint.h>
#include <stdlib.h>
#include "tflite/c/c_api.h"

typedef struct {
    AAsset *asset;  // Its model bytes must outlive the interpreter.
    TfLiteInterpreter *interpreter;
} TileModel;

static void release_model(TileModel *m) {
    if (!m) return;
    if (m->interpreter) TfLiteInterpreterDelete(m->interpreter);
    if (m->asset) AAsset_close(m->asset);
    free(m);
}

// The published x86 interpreter executes SSE4.1 instructions during model creation.
JNIEXPORT jboolean JNICALL
Java_com_parker_crownpulse_X86TileInterpreter_supportsModel(JNIEnv *env, jobject self) {
    (void)env;
    (void)self;
    unsigned int eax, ebx, ecx, edx;
    return __get_cpuid(1, &eax, &ebx, &ecx, &edx) && (ecx & bit_SSE4_1) != 0;
}

JNIEXPORT jlong JNICALL
Java_com_parker_crownpulse_X86TileInterpreter_open(JNIEnv *env, jobject self,
                                                  jobject assets, jstring path, jint threads) {
    (void)self;
    AAssetManager *manager = AAssetManager_fromJava(env, assets);
    if (!manager) return 0;
    TileModel *m = calloc(1, sizeof(*m));
    if (!m) return 0;
    const char *name = (*env)->GetStringUTFChars(env, path, NULL);
    if (name) {
        m->asset = AAssetManager_open(manager, name, AASSET_MODE_BUFFER);
        (*env)->ReleaseStringUTFChars(env, path, name);
    }
    if (m->asset) {
        const void *bytes = AAsset_getBuffer(m->asset);
        TfLiteModel *model = bytes ? TfLiteModelCreate(bytes, AAsset_getLength(m->asset)) : NULL;
        if (model) {
            TfLiteInterpreterOptions *options = TfLiteInterpreterOptionsCreate();
            if (options) TfLiteInterpreterOptionsSetNumThreads(options, threads);
            m->interpreter = TfLiteInterpreterCreate(model, options);
            if (options) TfLiteInterpreterOptionsDelete(options);
            TfLiteModelDelete(model);
        }
    }
    if (!m->interpreter || TfLiteInterpreterAllocateTensors(m->interpreter) != kTfLiteOk ||
        TfLiteInterpreterGetInputTensorCount(m->interpreter) != 1 ||
        TfLiteInterpreterGetOutputTensorCount(m->interpreter) != 1) {
        release_model(m);
        return 0;
    }
    return (jlong)(intptr_t)m;
}

static int matches(const TfLiteTensor *tensor, jsize count) {
    return tensor && TfLiteTensorType(tensor) == kTfLiteFloat32 &&
           TfLiteTensorByteSize(tensor) == (size_t)count * sizeof(jfloat) && TfLiteTensorData(tensor);
}

JNIEXPORT jboolean JNICALL
Java_com_parker_crownpulse_X86TileInterpreter_run(JNIEnv *env, jobject self, jlong handle,
                                                 jfloatArray input, jfloatArray output) {
    (void)self;
    TileModel *m = (TileModel *)(intptr_t)handle;
    if (!m) return JNI_FALSE;
    TfLiteTensor *in = TfLiteInterpreterGetInputTensor(m->interpreter, 0);
    jsize input_size = (*env)->GetArrayLength(env, input);
    if (!matches(in, input_size)) return JNI_FALSE;
    (*env)->GetFloatArrayRegion(env, input, 0, input_size, TfLiteTensorData(in));
    if ((*env)->ExceptionCheck(env) || TfLiteInterpreterInvoke(m->interpreter) != kTfLiteOk)
        return JNI_FALSE;
    const TfLiteTensor *out = TfLiteInterpreterGetOutputTensor(m->interpreter, 0);
    jsize output_size = (*env)->GetArrayLength(env, output);
    if (!matches(out, output_size)) return JNI_FALSE;
    (*env)->SetFloatArrayRegion(env, output, 0, output_size, TfLiteTensorData(out));
    return !(*env)->ExceptionCheck(env);
}

JNIEXPORT void JNICALL
Java_com_parker_crownpulse_X86TileInterpreter_close(JNIEnv *env, jobject self, jlong handle) {
    (void)env;
    (void)self;
    release_model((TileModel *)(intptr_t)handle);
}
