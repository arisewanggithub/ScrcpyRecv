#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace screencopy {
namespace srtio {
void write_bytes(int64_t handle, const uint8_t *bytes, size_t length);
bool is_closed(int64_t handle);
// Failure/close diagnostics only; closed or busy handles explicitly report unavailable.
std::string snapshot(int64_t handle);
}
}
