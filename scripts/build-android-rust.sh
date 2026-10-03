#!/usr/bin/env bash
set -euo pipefail
project_root=$(cd "$(dirname "$0")/.." && pwd)
sdk_root=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"$HOME/Android/Sdk"}}
ndk_root="$sdk_root/ndk/26.1.10909125"
output_root=${1:?JNI output directory is required}
export PATH="$HOME/.cargo/bin:$PATH"
export CARGO_TARGET_DIR="$project_root/rust/target"
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384"
for spec in 'arm64-v8a aarch64-linux-android aarch64-linux-android' 'armeabi-v7a armv7-linux-androideabi armv7a-linux-androideabi' 'x86_64 x86_64-linux-android x86_64-linux-android' 'x86 i686-linux-android i686-linux-android'; do
    read -r abi target clang_target <<< "$spec"
    linker_key="CARGO_TARGET_$(echo "$target" | tr '[:lower:]-' '[:upper:]_')_LINKER"
    env "$linker_key=$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin/${clang_target}26-clang" cargo build --locked --release --manifest-path "$project_root/rust/ather-math/Cargo.toml" --target "$target"
    mkdir -p "$output_root/$abi"
    cp "$CARGO_TARGET_DIR/$target/release/libather_math.so" "$output_root/$abi/"
done
