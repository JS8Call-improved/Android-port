#include <iostream>
#include <string>
#include <vector>

#include "js8core/protocol/varicode.hpp"

int main() {
  int failures = 0;
  auto check = [&](bool condition, std::string const& description) {
    if (!condition) {
      std::cerr << "FAIL: " << description << "\n";
      ++failures;
      return;
    }
    std::cout << "OK: " << description << "\n";
  };

  std::vector<std::string> samples = {
      "CQ CQ CQ",
      "HB EM73",
      "KN4CRD: HELLO WORLD",
      "`@ALLCALL HB",
      "J1Y ACK 73",
      "TEST FASTDATA PAYLOAD"
  };

  for (auto const& s : samples) {
    std::uint8_t t = 0;
    std::uint16_t num = 0;
    std::uint8_t bits3 = 0;
    int n = 0;
    auto hb = js8core::protocol::varicode::pack_heartbeat_message(s, "KN4CRD", &n);
    if (!hb.empty()) {
      auto unpacked = js8core::protocol::varicode::unpack_heartbeat_message(hb, &t, nullptr, &bits3);
      check(!unpacked.empty(), "heartbeat roundtrip for '" + s + "'");
    }
    auto cmp = js8core::protocol::varicode::pack_compound_message("`" + s, &n);
    if (!cmp.empty()) {
      auto unpacked = js8core::protocol::varicode::unpack_compound_message(cmp, &t, &num, &bits3);
      check(!unpacked.empty(), "compound roundtrip for '" + s + "'");
    }
    auto fast = js8core::protocol::varicode::pack_fast_data_message(s, &n);
    auto fast_dec = js8core::protocol::varicode::unpack_fast_data_message(fast);
    check(fast_dec == s, "fast-data roundtrip for '" + s + "'");
  }

  {
    // Regression: forced data must not be modified by automatic identification.
    auto frames = js8core::protocol::varicode::build_message_frames(
        "KN4CRD", "EM73", "", "PAYLOAD", true, true, 2, nullptr);
    check(frames.size() == 1, "forced Turbo data produces one frame");
    if (!frames.empty()) {
      check(js8core::protocol::varicode::unpack_fast_data_message(frames.front().first) == "PAYLOAD",
            "forced Turbo data preserves payload without sender callsign");
    }
  }

  {
    check(!js8core::protocol::varicode::is_valid_callsign("HELLO", nullptr),
          "ordinary text is not accepted as a callsign");
    check(js8core::protocol::varicode::is_valid_callsign("2E0ABC", nullptr),
          "callsign beginning with a digit is accepted");
    check(js8core::protocol::varicode::is_valid_callsign("EA8/K1ABC", nullptr),
          "compound callsign is accepted");

    auto frames = js8core::protocol::varicode::build_message_frames(
        "W1ABC", "EM73", "", "JUST TESTING", true, false, 0, nullptr);
    check(!frames.empty(), "forced identification produces frames for ordinary text");
    bool identified = false;
    for (auto const& frame : frames) {
      auto text = js8core::protocol::varicode::unpack_data_message(frame.first);
      identified = identified || text.find("W1ABC:") != std::string::npos;
    }
    check(identified, "forced identification prefixes sender to ordinary text");
  }

  {
    std::string to, cmd, num;
    bool compound = false;
    int consumed = 0;
    auto directed = js8core::protocol::varicode::pack_directed_message(
        "2E0ABC HELLO", "VE7NHW", &to, &compound, &cmd, &num, &consumed);
    check(!directed.empty() && to == "2E0ABC", "relay keeps leading digit in destination callsign");

    auto frames = js8core::protocol::varicode::build_message_frames(
        "VE7NHW", "CN89", "", "N0XYZ RR 73", false, false, 0, nullptr);
    bool saw_rr = false;
    bool saw_remainder = false;
    for (auto const& frame : frames) {
      auto decoded = js8core::protocol::varicode::unpack_directed_message(frame.first, nullptr);
      if (decoded.size() >= 3 && decoded[2] == " RR") {
        saw_rr = true;
      }
      saw_remainder = saw_remainder || js8core::protocol::varicode::unpack_data_message(frame.first) == "73";
    }
    check(saw_rr, "RR 73 is not parsed as an SNR number");
    check(saw_remainder, "non-buffered command remainder is left-stripped");

    auto snr_frame = js8core::protocol::varicode::pack_directed_message(
        "K1ABC SNR -8", "VE7NHW", &to, &compound, &cmd, &num, &consumed);
    auto snr_decoded = js8core::protocol::varicode::unpack_directed_message(snr_frame, nullptr);
    check(snr_decoded.size() >= 4 && snr_decoded[3] == "-08", "SNR is zero-padded like desktop");
  }

  {
    int chars = 0;
    auto frame = js8core::protocol::varicode::pack_data_message("N0XYZ QUERY CALL W1ABC?", &chars);
    check(!frame.empty() && chars > 0, "normal-speed data selects a usable compressed frame");
    auto const decoded_data = js8core::protocol::varicode::unpack_data_message(frame);
    check(decoded_data == std::string("N0XYZ QUERY CALL W1ABC?").substr(0, decoded_data.size()) &&
              decoded_data.size() == static_cast<std::size_t>(chars),
          "normal-speed compressed frame decodes its packed text prefix");
  }

  {
    auto frames = js8core::protocol::varicode::build_message_frames(
        "VE7NHW", "CN89", "", "@APRSIS MSG HELLO APRS", false, false, 0, nullptr);
    bool has_message_data = false;
    for (auto const& frame : frames) {
      auto payload = js8core::protocol::varicode::unpack_data_message(frame.first);
      has_message_data = has_message_data || payload == "HELLO APRS";
    }
    check(has_message_data, "@APRSIS MSG data is sent without an appended checksum");
  }

  {
    // Regression: an explicit destination must not be replaced by selectedCall.
    auto frames = js8core::protocol::varicode::build_message_frames(
        "KN4CRD", "EM73", "W1AW", "K1ABC SNR?", false, false, 2, nullptr);
    bool addressedToExplicitCall = false;
    for (auto const& frame : frames) {
      std::uint8_t type = 0;
      auto decoded = js8core::protocol::varicode::unpack_directed_message(frame.first, &type);
      if (decoded.size() > 1 && decoded[1] == "K1ABC") addressedToExplicitCall = true;
    }
    check(addressedToExplicitCall, "explicit Turbo destination is not auto-prefixed");
  }

  {
    // Regression: command-only compound helper frame must not decode as a fake grid (e.g. RA90).
    std::uint8_t type = 0;
    std::uint16_t extra = 0;
    std::uint8_t bits3 = 0;
    int n = 0;

    auto helper = js8core::protocol::varicode::pack_compound_message("`MO1QF ", &n);
    check(!helper.empty(), "pack compound helper '`MO1QF '");

    auto unpacked = js8core::protocol::varicode::unpack_compound_message(helper, &type, &extra, &bits3);
    check(!unpacked.empty(), "unpack compound helper '`MO1QF '");
    check(extra > 180 * 180, "compound helper extra uses command range");

    std::string rendered;
    for (auto part : unpacked) {
      while (!part.empty() && part.front() == ' ') part.erase(part.begin());
      if (part.empty()) continue;
      if (!rendered.empty()) rendered += " ";
      rendered += part;
    }
    check(rendered.find("RA90") == std::string::npos, "compound helper does not render RA90");
  }

  {
    check(!js8core::protocol::varicode::is_compound_callsign("K1ABC/P"),
          "portable /P callsign is not compound");
    check(js8core::protocol::varicode::is_compound_callsign("EA8/K1ABC"),
          "prefix compound callsign remains compound");

    auto frames = js8core::protocol::varicode::build_message_frames(
        "K1ABC/P", "EM73", "W1AW", "W1AW MSG TEST", false, false, 0, nullptr);
    bool saw_directed = false;
    bool saw_compound_directed = false;
    for (auto const& frame : frames) {
      std::uint8_t directed_type = 0;
      saw_directed = saw_directed || !js8core::protocol::varicode::unpack_directed_message(frame.first, &directed_type).empty();
      std::uint8_t compound_type = 0;
      saw_compound_directed = saw_compound_directed ||
          (!js8core::protocol::varicode::unpack_compound_message(frame.first, &compound_type, nullptr, nullptr).empty() && compound_type == 2);
    }
    check(saw_directed, "portable sender uses a standard directed frame");
    check(!saw_compound_directed, "portable sender does not use a compound-directed frame");
  }

  {
    // Regression: compound sender should use a compound-directed frame (no <....> over the air).
    auto frames = js8core::protocol::varicode::build_message_frames(
        "2W0OXE/5", "IO81", "MO1QF", "MO1QF MSG TEST", false, false, 0, nullptr);
    check(frames.size() >= 2, "compound sender directed free-text produces multiple frames");

    bool saw_ra90 = false;
    bool saw_directed = false;
    bool saw_compound_directed = false;
    for (auto const& frame : frames) {
      std::uint8_t directed_type = 0;
      auto directed = js8core::protocol::varicode::unpack_directed_message(frame.first, &directed_type);
      if (!directed.empty()) {
        saw_directed = true;
      }

      std::uint8_t type = 0;
      std::uint16_t extra = 0;
      std::uint8_t bits3 = 0;
      auto unpacked = js8core::protocol::varicode::unpack_compound_message(frame.first, &type, &extra, &bits3);
      if (unpacked.empty()) continue;
      if (type == 2) {
        saw_compound_directed = true;
      }
      std::string rendered;
      for (auto part : unpacked) {
        while (!part.empty() && part.front() == ' ') part.erase(part.begin());
        if (part.empty()) continue;
        if (!rendered.empty()) rendered += " ";
        rendered += part;
      }
      if (rendered.find("RA90") != std::string::npos) {
        saw_ra90 = true;
      }
    }
    check(saw_compound_directed, "compound sender to normal recipient uses compound-directed frame");
    check(!saw_directed, "compound sender to normal recipient does not emit standard directed frame");
    check(!saw_ra90, "compound sender directed decode has no RA90 artifact");
  }

  {
    // Regression: compound recipient should use a compound-directed frame.
    auto frames = js8core::protocol::varicode::build_message_frames(
        "2W0OXE", "IO81", "MO1QF/5", "MO1QF/5 MSG TEST", false, false, 0, nullptr);
    check(frames.size() >= 2, "compound recipient directed free-text produces multiple frames");

    bool saw_directed = false;
    bool saw_compound_directed = false;
    for (auto const& frame : frames) {
      std::uint8_t directed_type = 0;
      auto directed = js8core::protocol::varicode::unpack_directed_message(frame.first, &directed_type);
      if (!directed.empty()) {
        saw_directed = true;
      }

      std::uint8_t type = 0;
      std::uint16_t extra = 0;
      std::uint8_t bits3 = 0;
      auto unpacked = js8core::protocol::varicode::unpack_compound_message(frame.first, &type, &extra, &bits3);
      if (!unpacked.empty() && type == 2) {
        saw_compound_directed = true;
      }
    }
    check(saw_compound_directed, "compound recipient uses compound-directed frame");
    check(!saw_directed, "compound recipient does not emit standard directed frame");
  }

  {
    // Regression: compound sender and recipient should use a compound-directed frame.
    auto frames = js8core::protocol::varicode::build_message_frames(
        "2W0OXE/5", "IO81", "MO1QF/5", "MO1QF/5 MSG TEST", false, false, 0, nullptr);
    check(frames.size() >= 2, "compound sender and recipient produce multiple frames");

    bool saw_directed = false;
    bool saw_compound_directed = false;
    for (auto const& frame : frames) {
      std::uint8_t directed_type = 0;
      auto directed = js8core::protocol::varicode::unpack_directed_message(frame.first, &directed_type);
      if (!directed.empty()) {
        saw_directed = true;
      }

      std::uint8_t type = 0;
      std::uint16_t extra = 0;
      std::uint8_t bits3 = 0;
      auto unpacked = js8core::protocol::varicode::unpack_compound_message(frame.first, &type, &extra, &bits3);
      if (!unpacked.empty() && type == 2) {
        saw_compound_directed = true;
      }
    }
    check(saw_compound_directed, "compound sender and recipient use compound-directed frame");
    check(!saw_directed, "compound sender and recipient do not emit standard directed frame");
  }

  if (failures != 0) {
    std::cerr << "varicode_roundtrip failures: " << failures << "\n";
    return 1;
  }
  return 0;
}
