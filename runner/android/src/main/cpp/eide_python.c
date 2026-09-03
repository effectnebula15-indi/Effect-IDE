// Запуск CPython внутри процесса-раннера.
//
// Опирается на официальный способ встраивания Python в Android-приложение
// (https://docs.python.org/3/using/android.html) и на testbed из дерева CPython.
//
// Живёт в отдельном процессе (ADR-002): Py_RunMain финализирует интерпретатор,
// повторный запуск в том же процессе ненадёжен, а нам всё равно нужен процесс,
// который можно убить целиком в любой момент.

#include <android/log.h>
#include <errno.h>
#include <jni.h>
#include <Python.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#define LOG_TAG "eide.runner"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// CPython на Android перенаправляет sys.stdout и sys.stderr в logcat.
// Нам они нужны в своём канале, поэтому после инициализации возвращаем их
// на файловые дескрипторы 1 и 2, которые мы подменили на свои пайпы.
static const char *REBIND_STDIO =
    "import sys, io\n"
    "sys.stdout = io.TextIOWrapper(io.FileIO(1, 'w', closefd=False),"
    " encoding='utf-8', errors='backslashreplace', line_buffering=True)\n"
    "sys.stderr = io.TextIOWrapper(io.FileIO(2, 'w', closefd=False),"
    " encoding='utf-8', errors='backslashreplace', line_buffering=True)\n";

static int fail(JNIEnv *env, const char *message) {
    LOGE("%s", message);
    jclass cls = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (cls != NULL) {
        (*env)->ThrowNew(env, cls, message);
    }
    return -1;
}

static int fail_status(JNIEnv *env, PyStatus status) {
    return fail(env, status.err_msg ? status.err_msg : "не удалось инициализировать Python");
}

JNIEXPORT jint JNICALL
Java_io_github_effectnebula_eide_runner_android_PythonRuntime_nativeRun(
    JNIEnv *env, jclass clazz,
    jstring home, jstring script, jstring workDir,
    jint out_fd, jint err_fd
) {
    (void)clazz;

    // Свои дескрипторы на место 1 и 2: всё, что печатает нативный код и сам
    // интерпретатор, уходит в наш канал, а не в logcat.
    if (dup2(out_fd, STDOUT_FILENO) == -1 || dup2(err_fd, STDERR_FILENO) == -1) {
        return fail(env, strerror(errno));
    }
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    const char *home_utf8 = (*env)->GetStringUTFChars(env, home, NULL);
    const char *script_utf8 = (*env)->GetStringUTFChars(env, script, NULL);
    const char *work_utf8 = (*env)->GetStringUTFChars(env, workDir, NULL);

    // Рабочий каталог — папка проекта. Это не песочница (ADR-002 честно
    // говорит, что изоляции ФС здесь нет), а лишь разумный умолчательный cwd.
    if (chdir(work_utf8) != 0) {
        LOGE("chdir(%s): %s", work_utf8, strerror(errno));
    }

    PyConfig config;
    PyConfig_InitPythonConfig(&config);

    const char *argv[] = { "", script_utf8, NULL };
    PyStatus status = PyConfig_SetBytesArgv(&config, 2, (char **)argv);
    if (PyStatus_Exception(status)) {
        PyConfig_Clear(&config);
        return fail_status(env, status);
    }

    status = PyConfig_SetBytesString(&config, &config.home, home_utf8);
    if (PyStatus_Exception(status)) {
        PyConfig_Clear(&config);
        return fail_status(env, status);
    }

    status = Py_InitializeFromConfig(&config);
    PyConfig_Clear(&config);
    if (PyStatus_Exception(status)) {
        return fail_status(env, status);
    }

    if (PyRun_SimpleString(REBIND_STDIO) != 0) {
        LOGE("не удалось вернуть sys.stdout из logcat в наш канал");
    }

    int exit_code = Py_RunMain();

    // Пока дескрипторы 1 и 2 открыты, читающая сторона пайпа не увидит конца
    // файла и будет ждать вечно. Закрываем их здесь, а не в Kotlin: там до
    // подменённых dup2 дескрипторов уже не дотянуться.
    close(STDOUT_FILENO);
    close(STDERR_FILENO);

    (*env)->ReleaseStringUTFChars(env, home, home_utf8);
    (*env)->ReleaseStringUTFChars(env, script, script_utf8);
    (*env)->ReleaseStringUTFChars(env, workDir, work_utf8);
    return exit_code;
}
