cmake_minimum_required(VERSION 3.22)
project(eide_python C)

# PYTHON_PREFIX_DIR и PYTHON_VERSION приходят из build.gradle.kts:
# это распакованная официальная сборка CPython под Android.
include_directories(${PYTHON_PREFIX_DIR}/include/python${PYTHON_VERSION})
link_directories(${PYTHON_PREFIX_DIR}/lib)

add_library(eide_python SHARED eide_python.c)
target_link_libraries(eide_python log python${PYTHON_VERSION})
