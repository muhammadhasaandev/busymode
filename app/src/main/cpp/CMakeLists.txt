cmake_minimum_required(VERSION 3.18.1)
project(busymode-native)

add_library(busymode-native SHARED native-lib.cpp)

# Prebuilt Node.js runtime from nodejs-mobile (placed by scripts/prepare.sh)
add_library(libnode SHARED IMPORTED)
set_target_properties(libnode PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libnode.so)

find_library(log-lib log)
target_link_libraries(busymode-native libnode ${log-lib})
