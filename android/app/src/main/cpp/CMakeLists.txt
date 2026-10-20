cmake_minimum_required(VERSION 3.22)
project(raopjni C CXX)

# libraop は scripts/build-libraop-mac.sh と同じ patches を当てた third_party/libraop を使う
set(LR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/libraop)

if(ANDROID_ABI STREQUAL "arm64-v8a")
  set(SSL_INC ${LR}/libopenssl/targets/linux/aarch64/include)
else()
  set(SSL_INC ${LR}/libopenssl/targets/linux/arm/include)
endif()

add_library(raopjni SHARED
  raop_jni.c
  ssl_stubs.c
  ${LR}/src/raop_client.c
  ${LR}/src/rtsp_client.c
  ${LR}/src/aes.c
  ${LR}/src/aes_ctr.c
  ${LR}/crosstools/src/cross_log.c
  ${LR}/crosstools/src/cross_net.c
  ${LR}/crosstools/src/cross_util.c
  ${LR}/crosstools/src/platform.c
  ${LR}/curve25519/source/curve25519_dh.c
  ${LR}/curve25519/source/curve25519_mehdi.c
  ${LR}/curve25519/source/curve25519_order.c
  ${LR}/curve25519/source/curve25519_utils.c
  ${LR}/curve25519/source/custom_blind.c
  ${LR}/curve25519/source/ed25519_sign.c
  ${LR}/curve25519/source/ed25519_verify.c
  ${LR}/curve25519/source/sha512.c
)

target_include_directories(raopjni PRIVATE
  ${LR}/src ${LR}/crosstools/src
  ${LR}/curve25519/include ${LR}/curve25519/source
  ${LR}/libcodecs/targets/include/addons
  ${SSL_INC})

# AirMac Express は 200ms 程度の遅延でも再生できる(Mac での検証済み)
target_compile_definitions(raopjni PRIVATE
  RAOP_LATENCY_MIN=4410 _GNU_SOURCE OPENSSL_SUPPRESS_DEPRECATED NDEBUG)
target_compile_options(raopjni PRIVATE -O2 -Wno-everything)
target_link_libraries(raopjni log)
