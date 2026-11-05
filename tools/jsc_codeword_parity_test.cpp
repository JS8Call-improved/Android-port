// Exhaustive JSC on-air codeword parity check.
//
// The cleanroom replaced the precomputed JSC lookup with a derived index and
// an algorithmic codeword builder. This test proves the generated bit patterns
// are identical to the historical implementation for *every* symbol index, so
// on-air interoperability cannot regress.
//
// The reference below is a verbatim transcription of the original
// js8core::protocol::jsc::codeword() (core.__old_backup__/src/protocol/jsc.cpp).
// It uses only the public varicode::int_to_bits() helper, which is unchanged.
//
// Build (after a desktop build of the cleanroom: cmake -B core/build -S core):
//   c++ -std=c++20 -O2 \
//       -I core/include -I core/src/protocol \
//       tools/jsc_codeword_parity_test.cpp core/build/libjs8core.a \
//       -o /tmp/jsc-codeword-parity && /tmp/jsc-codeword-parity

#include <cstdint>
#include <cstdio>
#include <vector>

#include "js8core/protocol/jsc.hpp"
#include "js8core/protocol/varicode.hpp"
#include "jsc_data.hpp"  // JSC_SIZE / JSC_MAP

using namespace js8core::protocol;

namespace {

// Historical implementation (bit-for-bit reference).
jsc::Codeword legacy_codeword(std::uint32_t index, bool separate,
                              std::uint32_t bytesize, std::uint32_t s,
                              std::uint32_t c) {
  std::vector<jsc::Codeword> out;
  std::uint32_t v = ((index % s) << 1) + static_cast<std::uint32_t>(separate);
  out.insert(out.begin(), varicode::int_to_bits(v, static_cast<int>(bytesize + 1)));
  std::uint32_t x = index / s;
  while (x > 0) {
    x -= 1;
    out.insert(out.begin(),
               varicode::int_to_bits((x % c) + s, static_cast<int>(bytesize)));
    x /= c;
  }
  jsc::Codeword word;
  for (auto const& w : out) word.insert(word.end(), w.begin(), w.end());
  return word;
}

bool same(jsc::Codeword const& a, jsc::Codeword const& b) {
  if (a.size() != b.size()) return false;
  for (std::size_t i = 0; i < a.size(); ++i)
    if (a[i] != b[i]) return false;
  return true;
}

void dump(char const* label, jsc::Codeword const& w) {
  std::fprintf(stderr, "%s (%zu bits): ", label, w.size());
  for (bool b : w) std::fputc(b ? '1' : '0', stderr);
  std::fputc('\n', stderr);
}

}  // namespace

int main() {
  constexpr std::uint32_t kBytes = 4, kS = 7, kC = 9;
  std::printf("JSC_SIZE = %u\n", JSC_SIZE);
  if (JSC_SIZE != 262144u) {
    std::fprintf(stderr, "FAIL: unexpected JSC_SIZE\n");
    return 1;
  }

  std::uint64_t compared = 0;
  std::uint64_t mismatches = 0;
  std::uint64_t text_ok = 0;
  std::uint64_t text_bad = 0;

  for (std::uint32_t index = 0; index < JSC_SIZE; ++index) {
    for (int sep = 0; sep <= 1; ++sep) {
      const bool separate = sep != 0;
      auto const got = jsc::codeword(index, separate, kBytes, kS, kC);
      auto const want = legacy_codeword(index, separate, kBytes, kS, kC);
      ++compared;
      if (!same(got, want)) {
        if (mismatches == 0) {
          std::fprintf(stderr, "MISMATCH index=%u separate=%d\n", index, sep);
          dump("  got ", got);
          dump("  want", want);
        }
        ++mismatches;
      }
    }

    // Stronger invariant: the generated code must decode back to the canonical
    // dictionary text for this index (plus a space when the flag is set).
    auto const no_space = jsc::codeword(index, false, kBytes, kS, kC);
    std::string const text = jsc::decompress(no_space);
    std::string const expected(JSC_MAP[index].str,
                               static_cast<std::size_t>(JSC_MAP[index].size));
    if (text == expected) {
      ++text_ok;
    } else {
      if (text_bad == 0) {
        std::fprintf(stderr,
                     "TEXT MISMATCH index=%u expected='%s' decoded='%s'\n",
                     index, expected.c_str(), text.c_str());
      }
      ++text_bad;
    }
  }

  std::printf("codeword bit parity: %llu/%llu compared, %llu mismatches\n",
              static_cast<unsigned long long>(compared - mismatches),
              static_cast<unsigned long long>(compared),
              static_cast<unsigned long long>(mismatches));
  std::printf("index -> text decode: %llu/%llu ok\n",
              static_cast<unsigned long long>(text_ok),
              static_cast<unsigned long long>(text_ok + text_bad));

  if (mismatches != 0 || text_bad != 0) {
    std::fprintf(stderr, "FAIL\n");
    return 1;
  }
  std::printf("PASS\n");
  return 0;
}
