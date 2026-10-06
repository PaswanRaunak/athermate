#!/usr/bin/env bash
set -euo pipefail
project_root=$(cd "$(dirname "$0")/.." && pwd)
sdk_root=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"$HOME/Android/Sdk"}}
ndk_root="$sdk_root/ndk/26.1.10909125"
output_root=${1:?JNI output directory is required}
export PATH="$HOME/.cargo/bin:$PATH"

case "$(uname -s)" in
    Linux*)  prebuilt=linux-x86_64 ;;
    Darwin*) prebuilt=darwin-x86_64 ;;
    MINGW*|MSYS*|CYGWIN*) prebuilt=windows-x86_64 ;;
    *) echo "Unsupported host OS: $(uname -s)" >&2; exit 1 ;;
esac
toolchain_bin="$ndk_root/toolchains/llvm/prebuilt/$prebuilt/bin"
if command -v cygpath >/dev/null 2>&1; then
    manifest=$(cygpath -m "$project_root/rust/ather-math/Cargo.toml")
    export CARGO_TARGET_DIR=$(cygpath -m "$project_root/rust/target")
else
    manifest="$project_root/rust/ather-math/Cargo.toml"
    export CARGO_TARGET_DIR="$project_root/rust/target"
fi
base_flags="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384"
for spec in 'arm64-v8a aarch64-linux-android aarch64-linux-android' 'armeabi-v7a armv7-linux-androideabi armv7a-linux-androideabi' 'x86_64 x86_64-linux-android x86_64-linux-android' 'x86 i686-linux-android i686-linux-android'; do
    read -r abi target clang_target <<< "$spec"
    linker_key="CARGO_TARGET_$(echo "$target" | tr '[:lower:]-' '[:upper:]_')_LINKER"
    if [ "$prebuilt" = "windows-x86_64" ]; then
        # The .cmd wrappers re-tokenize args through cmd.exe and break on
        # paths with spaces; call clang.exe directly with an explicit target.
        linker="$toolchain_bin/clang.exe"
        export RUSTFLAGS="$base_flags -C link-arg=--target=${clang_target}26"
    else
        linker="$toolchain_bin/${clang_target}26-clang"
        export RUSTFLAGS="$base_flags"
    fi
    env "$linker_key=$linker" cargo build --locked --release --manifest-path "$manifest" --target "$target"
    mkdir -p "$output_root/$abi"
    cp "$CARGO_TARGET_DIR/$target/release/libather_math.so" "$output_root/$abi/"
done
