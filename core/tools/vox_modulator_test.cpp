#include "js8core/tx/modulator.hpp"

#include <array>
#include <cassert>
#include <chrono>
#include <cmath>
#include <cstdint>

using js8core::protocol::kJs8NumSymbols;
using js8core::protocol::kJs8RxSampleRate;
using js8core::tx::Modulator;

static void set_period_offset(Modulator& modulator, int offset_ms, int period_ms) {
  auto now = std::chrono::duration_cast<std::chrono::milliseconds>(
      std::chrono::system_clock::now().time_since_epoch()).count();
  modulator.set_clock_offset_ms(offset_ms - now % period_ms);
}

int main() {
  std::array<int, kJs8NumSymbols> tones{};
  constexpr int period = 6000;
  constexpr int rate = kJs8RxSampleRate;

  // A late request needs the next full slot, not a shortened lead-in.
  Modulator late;
  set_period_offset(late, 5900, period);
  late.start(tones, 1920, 100, period, 1500, 0, false, 1000);
  assert(late.milliseconds_until_active() >= 6000);

  // The synchronizing interval contains silence then exactly one second of
  // tone. It ends at the same frame boundary as without VOX.
  Modulator vox;
  set_period_offset(vox, 1000, period);
  vox.start(tones, 1920, 100, period, 1500, 0, false, 1000);
  auto wait = vox.milliseconds_until_active();
  assert(wait >= 5095 && wait <= 5105);
  int index = 0;
  int first_nonzero = -1;
  double steady_energy = 0.0;
  while (!vox.is_active()) {
    auto sample = vox.next_sample();
    if (first_nonzero < 0 && std::abs(sample) > 0.00001f) first_nonzero = index;
    if (vox.milliseconds_until_active() <= 800 && vox.milliseconds_until_active() > 200) {
      steady_energy += sample * sample;
    }
    ++index;
  }
  assert(first_nonzero >= index - rate && first_nonzero < index - rate + 10);
  assert(steady_energy > 0.25 * rate);
  assert(std::abs(vox.next_sample()) > 0.1f); // first JS8 sample, not delayed

  Modulator disabled;
  set_period_offset(disabled, 1000, period);
  disabled.start(tones, 1920, 100, period, 1500, 0, false);
  while (!disabled.is_active()) assert(disabled.next_sample() == 0.0f);
  assert(std::abs(disabled.next_sample()) > 0.1f);

  // Tune starts immediately and never gets a lead-in.
  Modulator tune;
  tune.start(tones, 1920, 100, period, 1500, 0, true, 5000);
  assert(tune.is_active());
  assert(std::abs(tune.next_sample()) > 0.1f);
}
