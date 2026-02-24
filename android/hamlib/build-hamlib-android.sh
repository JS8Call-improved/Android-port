#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd "${SCRIPT_DIR}/../.." && pwd)
PATCH_DIR="${SCRIPT_DIR}/patches"

HAMLIB_SRC="${HAMLIB_SRC:-${SCRIPT_DIR}/hamlib-src}"
HAMLIB_BUILD="${HAMLIB_BUILD:-${SCRIPT_DIR}/hamlib-build}"
HAMLIB_VERSION="${HAMLIB_VERSION:-master}"
ANDROID_API="${ANDROID_API:-26}"
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86_64}"

NDK_ROOT="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "${NDK_ROOT}" ]]; then
  echo "ANDROID_NDK_HOME or ANDROID_NDK_ROOT must be set" >&2
  exit 1
fi

HOST_OS=$(uname -s)
case "${HOST_OS}" in
  Darwin) HOST_TAG="darwin-x86_64";;
  Linux) HOST_TAG="linux-x86_64";;
  *)
    echo "Unsupported host OS: ${HOST_OS}" >&2
    exit 1
    ;;
esac

TOOLCHAIN="${NDK_ROOT}/toolchains/llvm/prebuilt/${HOST_TAG}"
if [[ ! -d "${TOOLCHAIN}" ]]; then
  echo "NDK toolchain not found at ${TOOLCHAIN}" >&2
  exit 1
fi

if [[ ! -d "${HAMLIB_SRC}/.git" ]]; then
  git clone https://github.com/Hamlib/Hamlib.git "${HAMLIB_SRC}"
fi

pushd "${HAMLIB_SRC}" >/dev/null

git fetch --tags origin

git checkout "${HAMLIB_VERSION}"

for patch in "${PATCH_DIR}"/*.patch; do
  if [[ ! -f "${patch}" ]]; then
    continue
  fi

  if git apply --check --ignore-space-change --unidiff-zero --recount "${patch}" >/dev/null 2>&1; then
    git apply --ignore-space-change --unidiff-zero --recount "${patch}"
  elif git apply --reverse --check --ignore-space-change --unidiff-zero --recount "${patch}" >/dev/null 2>&1; then
    echo "Hamlib patch already applied: $(basename "${patch}")"
  else
    echo "Hamlib source tree is dirty or patch failed: ${patch}" >&2
    exit 1
  fi
done

if [[ ! -x "${HAMLIB_SRC}/configure" ]]; then
  ./bootstrap
fi

cpu_count() {
  if command -v nproc >/dev/null 2>&1; then
    nproc
  elif command -v getconf >/dev/null 2>&1; then
    getconf _NPROCESSORS_ONLN
  else
    echo 4
  fi
}

build_abi() {
  local abi="$1"
  local target=""

  case "${abi}" in
    arm64-v8a) target="aarch64-linux-android";;
    armeabi-v7a) target="armv7a-linux-androideabi";;
    x86_64) target="x86_64-linux-android";;
    *)
      echo "Unknown ABI: ${abi}" >&2
      return 1
      ;;
  esac

  local build_dir="${HAMLIB_BUILD}/${abi}"
  local install_dir="${ROOT_DIR}/android/libs/hamlib/${abi}"

  mkdir -p "${build_dir}" "${install_dir}"

  export CC="${TOOLCHAIN}/bin/${target}${ANDROID_API}-clang"
  export CXX="${TOOLCHAIN}/bin/${target}${ANDROID_API}-clang++"
  export AR="${TOOLCHAIN}/bin/llvm-ar"
  export RANLIB="${TOOLCHAIN}/bin/llvm-ranlib"
  export STRIP="${TOOLCHAIN}/bin/llvm-strip"
  export CFLAGS="-fPIC"
  export CXXFLAGS="-fPIC"

  pushd "${build_dir}" >/dev/null
  "${HAMLIB_SRC}/configure" \
    --host="${target}" \
    --prefix="${install_dir}" \
    --disable-shared \
    --enable-static \
    --without-cxx-binding \
    --disable-winradio \
    --without-libusb

  make -C lib -j"$(cpu_count)"
  make -C security -j"$(cpu_count)"
  make -C src -j"$(cpu_count)"

  make -C include install
  make -C lib install
  make -C security install
  make -C src install
  popd >/dev/null
}

for abi in ${ABIS}; do
  build_abi "${abi}"
done

popd >/dev/null

printf '\nHamlib Android build complete. Artifacts in android/libs/hamlib/<abi>\n'
