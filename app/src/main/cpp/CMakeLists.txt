cmake_minimum_required(VERSION 3.22.1)

project(uvclivestreaming_mpegts C CXX)

add_library(
    uvclivestreaming_mpegts
    SHARED
    mpegts_muxer.c
    mpegts_jni.c
    h26x_vui_rewriter.c
    h26x_vui_jni.c
)

target_compile_features(uvclivestreaming_mpegts PRIVATE c_std_11)
target_compile_options(uvclivestreaming_mpegts PRIVATE -Wall -Wextra -Werror)
target_link_libraries(uvclivestreaming_mpegts m)

set(LIBUSB_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libusb)
set(LIBUAC_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libuac)
set(LIBUVC_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libuvc)
set(LIBJPEG_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libjpeg-turbo)

include(ExternalProject)
set(LIBJPEG_BUILD ${CMAKE_CURRENT_BINARY_DIR}/jpeg-turbo)
file(MAKE_DIRECTORY ${LIBJPEG_BUILD})
set(LIBJPEG_SIMD OFF)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(LIBJPEG_SIMD ON)
endif()
ExternalProject_Add(jpeg_turbo_build
    SOURCE_DIR ${LIBJPEG_ROOT}
    BINARY_DIR ${LIBJPEG_BUILD}
    CMAKE_ARGS
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DCMAKE_BUILD_TYPE=Release
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON
        -DENABLE_SHARED=OFF
        -DENABLE_STATIC=ON
        -DWITH_SIMD=${LIBJPEG_SIMD}
        -DWITH_TURBOJPEG=OFF
        -DWITH_TOOLS=OFF
        -DWITH_TESTS=OFF
    BUILD_BYPRODUCTS ${LIBJPEG_BUILD}/libjpeg.a
    INSTALL_COMMAND ""
)
add_library(jpeg_turbo STATIC IMPORTED)
set_target_properties(jpeg_turbo PROPERTIES IMPORTED_LOCATION ${LIBJPEG_BUILD}/libjpeg.a)
add_dependencies(jpeg_turbo jpeg_turbo_build)

add_library(libusb STATIC
    ${LIBUSB_ROOT}/libusb/core.c
    ${LIBUSB_ROOT}/libusb/descriptor.c
    ${LIBUSB_ROOT}/libusb/hotplug.c
    ${LIBUSB_ROOT}/libusb/io.c
    ${LIBUSB_ROOT}/libusb/sync.c
    ${LIBUSB_ROOT}/libusb/strerror.c
    ${LIBUSB_ROOT}/libusb/os/linux_usbfs.c
    ${LIBUSB_ROOT}/libusb/os/events_posix.c
    ${LIBUSB_ROOT}/libusb/os/threads_posix.c
    ${LIBUSB_ROOT}/libusb/os/linux_netlink.c
)
target_include_directories(libusb PUBLIC ${LIBUSB_ROOT}/libusb PRIVATE ${LIBUSB_ROOT})
target_compile_options(libusb PRIVATE -fvisibility=hidden -pthread -O2)
target_link_libraries(libusb log)

add_library(uac STATIC
    ${LIBUAC_ROOT}/src/uac_context.cpp
    ${LIBUAC_ROOT}/src/uac_device.cpp
    ${LIBUAC_ROOT}/src/uac_parser.cpp
    ${LIBUAC_ROOT}/src/uac_streaming.cpp
    ${LIBUAC_ROOT}/src/uac_exceptions.cpp
)
target_compile_features(uac PRIVATE cxx_std_17)
target_include_directories(uac PRIVATE ${LIBUAC_ROOT}/src ${LIBUAC_ROOT}/include)
target_link_libraries(uac PRIVATE libusb)
target_compile_options(uac PRIVATE -fno-rtti -pthread)
target_compile_definitions(uac PRIVATE $<$<CONFIG:Debug>:UAC_ENABLE_LOGGING>)

set(libuvc_VERSION_MAJOR 0)
set(libuvc_VERSION_MINOR 0)
set(libuvc_VERSION_PATCH 8)
set(libuvc_VERSION 0.0.8)
set(LIBUVC_HAS_JPEG 1)
configure_file(
    ${LIBUVC_ROOT}/include/libuvc/libuvc_config.h.in
    ${CMAKE_CURRENT_BINARY_DIR}/include/libuvc/libuvc_config.h
    @ONLY
)
add_library(uvc STATIC
    ${LIBUVC_ROOT}/src/ctrl.c
    ${LIBUVC_ROOT}/src/ctrl-gen.c
    ${LIBUVC_ROOT}/src/device.c
    ${LIBUVC_ROOT}/src/diag.c
    ${LIBUVC_ROOT}/src/frame.c
    ${LIBUVC_ROOT}/src/frame-mjpeg.c
    ${LIBUVC_ROOT}/src/init.c
    ${LIBUVC_ROOT}/src/stream.c
    ${LIBUVC_ROOT}/src/misc.c
)
target_include_directories(uvc PUBLIC ${LIBUVC_ROOT}/include ${CMAKE_CURRENT_BINARY_DIR}/include
    PRIVATE ${LIBJPEG_ROOT}/src ${LIBJPEG_BUILD})
target_compile_definitions(uvc PRIVATE LIBUVC_NUM_TRANSFER_BUFS=8 LIBUVC_PACKETS_PER_TRANSFER_MAX=16
    UVCLIVESTREAMING_DISABLE_UVC_STATUS_XFER=1)
target_compile_options(uvc PRIVATE -O2)
add_dependencies(uvc jpeg_turbo_build)
target_link_libraries(uvc PRIVATE libusb jpeg_turbo)

add_library(uvclivestreaming_usb SHARED usb_capture_jni.cpp mjpeg_repair.c audio_dsp.c audio_dsp_jni.cpp)
target_compile_features(uvclivestreaming_usb PRIVATE cxx_std_17 c_std_11)
target_compile_options(uvclivestreaming_usb PRIVATE -O2)
target_include_directories(uvclivestreaming_usb PRIVATE ${LIBUAC_ROOT}/include)
target_link_libraries(uvclivestreaming_usb uvc uac libusb jpeg_turbo log m)
