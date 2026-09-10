cmake_minimum_required(VERSION 3.22)
project(eide_python C)

# PYTHON_PREFIX_DIR и PYTHON_VERSION приходят из build.gradle.kts:
# это распакованная официальная сборка CPython под Android.
include_directories(${PYTHON_PREFIX_DIR}/include/python${PYTHON_VERSION})
link_directories(${PYTHON_PREFIX_DIR}/lib)

add_library(eide_python SHARED eide_python.c)
target_link_libraries(eide_python log python${PYTHON_VERSION})

# Кадровый буфер графики. Исходник общий с хостовой сборкой (native/canvas),
# где он гоняется под санитайзерами: здесь та же логика, но без диагностики.
# EIDE_CANVAS_DIR приходит из build.gradle.kts — путь считает Gradle, а не
# лесенка из «..», которую ломает любой переезд файла.
add_library(eide_canvas SHARED
        ${EIDE_CANVAS_DIR}/src/eide_canvas.c
        eide_canvas_jni.c)
target_include_directories(eide_canvas PRIVATE ${EIDE_CANVAS_DIR}/include)
target_compile_options(eide_canvas PRIVATE -Wall -Wextra -Werror)
# jnigraphics — ради записи кадра прямо в пиксели Bitmap, без лишней копии.
target_link_libraries(eide_canvas log jnigraphics)
