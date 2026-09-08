/*
 * Мост между кадровым буфером на C и Kotlin.
 *
 * Живёт в модуле раннера, хотя читателем кадров будет процесс IDE. Причина та
 * же, по которой протокол лежит в :core: это контракт между двумя процессами,
 * а не собственность одной из сторон. Собирать ради него второй проект CMake в
 * :platform:android — дороже, чем объяснить эту строчку.
 */

#include <android/bitmap.h>
#include <jni.h>
#include <string.h>

#include "eide_canvas.h"

#define UNUSED(x) (void)(x)

JNIEXPORT jlong JNICALL
Java_io_github_effectnebula_eide_runner_android_CanvasArea_nativeAreaSize(
        JNIEnv *env, jclass clazz, jint width, jint height) {
    UNUSED(env); UNUSED(clazz);
    return (jlong)ec_area_size(width, height);
}

JNIEXPORT jboolean JNICALL
Java_io_github_effectnebula_eide_runner_android_CanvasArea_nativeInitArea(
        JNIEnv *env, jclass clazz, jobject area, jint width, jint height) {
    UNUSED(clazz);
    void *address = (*env)->GetDirectBufferAddress(env, area);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, area);
    if (address == NULL || capacity <= 0) return JNI_FALSE;
    return ec_init_area(address, (size_t)capacity, width, height) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_io_github_effectnebula_eide_runner_android_CanvasArea_nativeAddressOf(
        JNIEnv *env, jclass clazz, jobject area) {
    UNUSED(clazz);
    return (jlong)(intptr_t)(*env)->GetDirectBufferAddress(env, area);
}

/*
 * Кадр читается прямо в пиксели Bitmap: лишняя промежуточная копия при 60 кадрах
 * в секунду стоит заметно, а `Bitmap.copyPixelsFromBuffer` — это именно она.
 */
JNIEXPORT jlong JNICALL
Java_io_github_effectnebula_eide_runner_android_CanvasArea_nativeReadFrame(
        JNIEnv *env, jclass clazz, jobject area, jobject bitmap, jlong since) {
    UNUSED(clazz);

    void *address = (*env)->GetDirectBufferAddress(env, area);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, area);
    if (address == NULL || capacity <= 0) return 0;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return 0;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return 0;
    /* Кадр копируется одним куском, поэтому строки обязаны идти вплотную. */
    if (info.stride != info.width * 4) return 0;

    void *pixels = NULL;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return 0;

    size_t bitmap_bytes = (size_t)info.stride * info.height;
    jlong frame = (jlong)ec_read_frame(address, (size_t)capacity, pixels, bitmap_bytes,
                                       (uint64_t)since);

    AndroidBitmap_unlockPixels(env, bitmap);
    return frame;
}
