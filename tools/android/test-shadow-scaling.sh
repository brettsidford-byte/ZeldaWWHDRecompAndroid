#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
test_output=$(mktemp -d)
trap 'rm -rf "$test_output"' EXIT
fetch_headers() {
    git clone --quiet --depth 1 --branch "$2" "$1" "$3"
}
if [ -z "${WWHD_TEST_VOLK:-}" ]; then
    fetch_headers https://github.com/zeux/volk.git vulkan-sdk-1.3.275.0 "$test_output/volk"
    WWHD_TEST_VOLK="$test_output/volk"
fi
if [ -z "${WWHD_TEST_VMA:-}" ]; then
    fetch_headers https://github.com/GPUOpen-LibrariesAndSDKs/VulkanMemoryAllocator.git v3.3.0 "$test_output/vma"
    WWHD_TEST_VMA="$test_output/vma"
fi
if [ -z "${WWHD_TEST_VULKAN_HEADERS:-}" ]; then
    fetch_headers https://github.com/KhronosGroup/Vulkan-Headers.git vulkan-sdk-1.3.275.0 "$test_output/headers"
    WWHD_TEST_VULKAN_HEADERS="$test_output/headers"
fi
"${CXX:-g++}" -std=c++20 -O1 -ffunction-sections -fdata-sections -Wl,--gc-sections \
    -include runtime/third_party/cemu/cemu_shim.h \
    -Iruntime/src -Iruntime/include -Iruntime/third_party/cemu -Iruntime/third_party/fmt/include \
    -I"$WWHD_TEST_VOLK" -I"$WWHD_TEST_VMA/include" -I"$WWHD_TEST_VULKAN_HEADERS/include" \
    tools/android/tests/ShadowScalingTest.cpp -o "$test_output/shadows"
"$test_output/shadows"
