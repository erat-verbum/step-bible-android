cmake_minimum_required(VERSION 3.22)
project("step_jvm_stub")

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_library(step_jvm_stub SHARED step_jvm_stub.c)
target_link_libraries(step_jvm_stub log dl)
target_include_directories(step_jvm_stub PRIVATE ${ANDROID_NDK}/sysroot/usr/include)
target_compile_options(step_jvm_stub PRIVATE -fstack-protector-strong -D_FORTIFY_SOURCE=2)
