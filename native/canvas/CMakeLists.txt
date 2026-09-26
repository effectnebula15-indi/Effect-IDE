cmake_minimum_required(VERSION 3.22)
project(eide_canvas C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_library(eide_canvas SHARED src/eide_canvas.c)
target_include_directories(eide_canvas PUBLIC include)
target_compile_options(eide_canvas PRIVATE -Wall -Wextra -Werror)

# Хостовая сборка: тест под санитайзерами. На Android санитайзеры не собираем —
# там нужна библиотека, а не диагностика.
if(EIDE_CANVAS_TESTS)
    enable_testing()

    # Санитайзер выбирается снаружи: ASan и TSan несовместимы в одной сборке,
    # поэтому это два разных прогона, а не один.
    set(EIDE_SANITIZER "address,undefined" CACHE STRING "какой санитайзер включить")

    add_executable(test_canvas src/eide_canvas.c test/test_canvas.c)
    target_include_directories(test_canvas PRIVATE include)
    # Только на Linux: в заголовках macOS строгий _POSIX_C_SOURCE наоборот
    # прячет объявления, которые там лежат вне POSIX.
    if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
        target_compile_definitions(test_canvas PRIVATE _POSIX_C_SOURCE=200809L)
    endif()
    target_compile_options(test_canvas PRIVATE
        -Wall -Wextra -Werror -g -O1
        -fsanitize=${EIDE_SANITIZER} -fno-omit-frame-pointer)
    target_link_options(test_canvas PRIVATE -fsanitize=${EIDE_SANITIZER})
    target_link_libraries(test_canvas PRIVATE pthread)

    add_test(NAME canvas COMMAND test_canvas)
    set_tests_properties(canvas PROPERTIES
        ENVIRONMENT "TSAN_OPTIONS=suppressions=${CMAKE_CURRENT_SOURCE_DIR}/tsan.supp")
endif()
