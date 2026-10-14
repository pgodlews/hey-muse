cmake_minimum_required(VERSION 3.22.1)
project(heymuse C)

# Keep absolute build-machine paths out of __FILE__ strings and debug info in shipped binaries.
add_compile_options(-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.)

# SVOX Pico (Apache-2.0, see pico/NOTICE): the built-in voice.
file(GLOB PICO_SOURCES CONFIGURE_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/pico/*.c)
add_library(pico-engine STATIC ${PICO_SOURCES})
target_include_directories(pico-engine PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/pico)
target_compile_options(pico-engine PRIVATE -w -O2)

add_library(pico SHARED pico_jni.c)
target_link_libraries(pico pico-engine log m)
