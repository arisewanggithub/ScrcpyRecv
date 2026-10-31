#include "srt_transport.h"
#include <jni.h>
#include <srt.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <sys/socket.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
constexpr int kPayload = 1316;
using Clock = std::chrono::steady_clock;

std::string socket_snapshot(SRTSOCKET socket) {
    // SRT 1.5.7 exposes these counters, but no last-peer-response timestamp.
    if (socket == SRT_INVALID_SOCK)
        return "snapshot=unavailable snapshot_unavailable=no_socket state=UNKNOWN last_peer_response_ms=unavailable";
    const auto status = srt_getsockstate(socket);
    const std::string state = "state=" + std::to_string(static_cast<int>(status));
    if (status == SRTS_CLOSED || status == SRTS_NONEXIST)
        return "snapshot=unavailable snapshot_unavailable=closed " + state + " last_peer_response_ms=unavailable";
    SRT_TRACEBSTATS stats{};
    if (srt_bstats(socket, &stats, 0) == SRT_ERROR)
        return "snapshot=unavailable snapshot_unavailable=stats_error " + state +
            " snapshot_error_code=" + std::to_string(srt_getlasterror(nullptr)) + " last_peer_response_ms=unavailable";
    return "snapshot=available " + state +
        " elapsed_ms=" + std::to_string(stats.msTimeStamp) +
        " packets_sent=" + std::to_string(stats.pktSentTotal) +
        " packets_received=" + std::to_string(stats.pktRecvTotal) +
        " send_loss=" + std::to_string(stats.pktSndLossTotal) +
        " receive_loss=" + std::to_string(stats.pktRcvLossTotal) +
        " retransmissions=" + std::to_string(stats.pktRetransTotal) +
        " acknowledgements_sent=" + std::to_string(stats.pktSentACKTotal) +
        " acknowledgements_received=" + std::to_string(stats.pktRecvACKTotal) +
        " negative_acknowledgements_sent=" + std::to_string(stats.pktSentNAKTotal) +
        " negative_acknowledgements_received=" + std::to_string(stats.pktRecvNAKTotal) +
        " bytes_sent=" + std::to_string(stats.byteSentTotal) +
        " bytes_received=" + std::to_string(stats.byteRecvTotal) +
        " send_buffer_packets=" + std::to_string(stats.pktSndBuf) +
        " receive_buffer_packets=" + std::to_string(stats.pktRcvBuf) +
        " send_buffer_bytes=" + std::to_string(stats.byteSndBuf) +
        " receive_buffer_bytes=" + std::to_string(stats.byteRcvBuf) +
        " send_buffer_available_bytes=" + std::to_string(stats.byteAvailSndBuf) +
        " receive_buffer_available_bytes=" + std::to_string(stats.byteAvailRcvBuf) +
        " rtt_ms=" + (std::isfinite(stats.msRTT) ? std::to_string(stats.msRTT) : "unavailable") +
        " last_peer_response_ms=unavailable";
}
std::string library_error(const char *operation) {
    const int code = srt_getlasterror(nullptr);
    return std::string("SRT ") + operation + ": native failure" +
        " (code " + std::to_string(code) + ")";
}
[[noreturn]] void fail(const char *operation, SRTSOCKET socket = SRT_INVALID_SOCK) {
    const auto error = library_error(operation);
    throw std::runtime_error(error + " | snapshot " + socket_snapshot(socket));
}
void initialize() {
    static std::once_flag once;
    std::call_once(once, [] { if (srt_startup() != 0) fail("startup"); });
}
template<class T> void option(SRTSOCKET socket, SRT_SOCKOPT name, const T &value) {
    if (srt_setsockflag(socket, name, &value, sizeof(value)) == SRT_ERROR) fail("socket option", socket);
}
SRTSOCKET make_socket(bool sender, int latency) {
    if (latency < 20 || latency > 2000) throw std::runtime_error("SRT latency must be 20..2000ms");
    initialize();
    const auto socket = srt_create_socket();
    if (socket == SRT_INVALID_SOCK) fail("create socket");
    try {
        const SRT_TRANSTYPE type = SRTT_LIVE;
        option(socket, SRTO_TRANSTYPE, type);
        option(socket, SRTO_SENDER, sender);
        option(socket, SRTO_TSBPDMODE, true);
        option(socket, SRTO_LATENCY, latency);
        // A byte container cannot recover if an application message is deliberately discarded.
        option(socket, SRTO_TLPKTDROP, false);
        option(socket, SRTO_MESSAGEAPI, true);
        option(socket, SRTO_PAYLOADSIZE, kPayload);
        option(socket, SRTO_SNDSYN, false);
        option(socket, SRTO_RCVSYN, false);
        option(socket, SRTO_SNDBUF, 4 * 1024 * 1024);
        option(socket, SRTO_RCVBUF, 4 * 1024 * 1024);
        option(socket, SRTO_CONNTIMEO, 3000);
        const linger immediate = {0, 0};
        option(socket, SRTO_LINGER, immediate);
        return socket;
    } catch (const std::exception &) {
        srt_close(socket);
        throw;
    }
}
struct Address {
    sockaddr_storage storage{};
    int length = 0;
};
Address address(const std::string &text, int port) {
    if (text.empty() || port < 0 || port > 65535) throw std::runtime_error("Invalid numeric SRT endpoint");
    std::string host = text;
    if (host.front() == '[' && host.back() == ']') host = host.substr(1, host.size() - 2);
    addrinfo hints{};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_DGRAM;
    hints.ai_flags = AI_NUMERICHOST | AI_NUMERICSERV;
    addrinfo *resolved = nullptr;
    const auto service = std::to_string(port);
    if (getaddrinfo(host.c_str(), service.c_str(), &hints, &resolved) != 0 || !resolved)
        throw std::runtime_error("SRT endpoint must be a numeric IP address");
    Address result;
    if (resolved->ai_addrlen == 0 || static_cast<size_t>(resolved->ai_addrlen) > sizeof(result.storage)) {
        freeaddrinfo(resolved);
        throw std::runtime_error("Unsupported SRT address family");
    }
    result.length = static_cast<int>(resolved->ai_addrlen);
    std::memcpy(&result.storage, resolved->ai_addr, resolved->ai_addrlen);
    freeaddrinfo(resolved);
    return result;
}
bool same_peer(const sockaddr_storage &a, const sockaddr_storage &b) {
    if (a.ss_family == AF_INET && b.ss_family == AF_INET)
        return reinterpret_cast<const sockaddr_in *>(&a)->sin_addr.s_addr ==
            reinterpret_cast<const sockaddr_in *>(&b)->sin_addr.s_addr;
    if (a.ss_family == AF_INET6 && b.ss_family == AF_INET6)
        return std::memcmp(&reinterpret_cast<const sockaddr_in6 *>(&a)->sin6_addr,
            &reinterpret_cast<const sockaddr_in6 *>(&b)->sin6_addr, sizeof(in6_addr)) == 0;
    const auto *v4 = a.ss_family == AF_INET ? reinterpret_cast<const sockaddr_in *>(&a) :
        (b.ss_family == AF_INET ? reinterpret_cast<const sockaddr_in *>(&b) : nullptr);
    const auto *v6 = a.ss_family == AF_INET6 ? reinterpret_cast<const sockaddr_in6 *>(&a) :
        (b.ss_family == AF_INET6 ? reinterpret_cast<const sockaddr_in6 *>(&b) : nullptr);
    return v4 && v6 && IN6_IS_ADDR_V4MAPPED(&v6->sin6_addr) &&
        std::memcmp(&v6->sin6_addr.s6_addr[12], &v4->sin_addr, sizeof(in_addr)) == 0;
}
int make_epoll(SRTSOCKET socket, int events) {
    const int id = srt_epoll_create();
    if (id < 0) fail("create epoll", socket);
    if (srt_epoll_add_usock(id, socket, &events) == SRT_ERROR) {
        const auto error = library_error("register epoll") + " | snapshot " + socket_snapshot(socket);
        srt_epoll_release(id);
        throw std::runtime_error(error);
    }
    return id;
}
struct State {
    std::mutex setup, read, write;
    std::atomic<bool> closing{false};
    SRTSOCKET listener = SRT_INVALID_SOCK, connection = SRT_INVALID_SOCK;
    int accept_poll = -1, read_poll = -1, write_poll = -1;
    Address expected{};
    std::string stream_id;
    std::array<char, kPayload> pending{};
    int pending_at = 0, pending_size = 0;

    void shutdown() {
        if (closing.exchange(true)) return;
        std::lock_guard<std::mutex> guard(setup);
        if (connection != SRT_INVALID_SOCK) { srt_close(connection); connection = SRT_INVALID_SOCK; }
        if (listener != SRT_INVALID_SOCK) { srt_close(listener); listener = SRT_INVALID_SOCK; }
    }
    ~State() {
        shutdown();
        // In-flight JNI calls retain State, so no waiter can still use these epolls here.
        for (int id : {accept_poll, read_poll, write_poll}) if (id >= 0) srt_epoll_release(id);
    }
};
std::string state_snapshot(const std::shared_ptr<State> &state, SRTSOCKET socket = SRT_INVALID_SOCK) {
    if (state->closing)
        return "snapshot=unavailable snapshot_unavailable=closed state=CLOSED last_peer_response_ms=unavailable";
    std::unique_lock<std::mutex> guard(state->setup, std::try_to_lock);
    if (!guard.owns_lock())
        return "snapshot=unavailable snapshot_unavailable=busy state=UNKNOWN last_peer_response_ms=unavailable";
    if (state->closing)
        return "snapshot=unavailable snapshot_unavailable=closed state=CLOSED last_peer_response_ms=unavailable";
    return socket_snapshot(socket != SRT_INVALID_SOCK ? socket :
        state->connection != SRT_INVALID_SOCK ? state->connection : state->listener);
}
[[noreturn]] void fail_io(const char *operation, const std::shared_ptr<State> &state, SRTSOCKET socket) {
    const auto error = library_error(operation);
    throw std::runtime_error(error + " | snapshot " + state_snapshot(state, socket));
}
std::mutex registry_lock;
std::unordered_map<int64_t, std::shared_ptr<State>> registry;
std::atomic<int64_t> next_handle{1};
std::shared_ptr<State> lookup(int64_t handle) {
    std::lock_guard<std::mutex> guard(registry_lock);
    const auto entry = registry.find(handle);
    if (entry == registry.end() || entry->second->closing)
        throw std::runtime_error("SRT connection closed | snapshot snapshot=unavailable snapshot_unavailable=closed state=CLOSED last_peer_response_ms=unavailable");
    return entry->second;
}
int64_t publish(std::shared_ptr<State> state) {
    const auto handle = next_handle.fetch_add(1);
    std::lock_guard<std::mutex> guard(registry_lock);
    registry.emplace(handle, std::move(state));
    return handle;
}
void check_open(const std::shared_ptr<State> &state) {
    if (state->closing)
        throw std::runtime_error("SRT connection closed | snapshot snapshot=unavailable snapshot_unavailable=closed state=CLOSED last_peer_response_ms=unavailable");
}
bool wait_event(const std::shared_ptr<State> &state, int id, bool writing, int timeout) {
    check_open(state);
    SRTSOCKET sockets[1];
    int count = 1;
    const int result = writing ?
        srt_epoll_wait(id, nullptr, nullptr, sockets, &count, timeout, nullptr, nullptr, nullptr, nullptr) :
        srt_epoll_wait(id, sockets, &count, nullptr, nullptr, timeout, nullptr, nullptr, nullptr, nullptr);
    check_open(state);
    if (result == SRT_ERROR) {
        if (srt_getlasterror(nullptr) == SRT_ETIMEOUT) return false;
        fail_io("wait", state, SRT_INVALID_SOCK);
    }
    return result > 0 && count > 0;
}
std::pair<SRTSOCKET, int> io_socket(const std::shared_ptr<State> &state, bool writing) {
    std::lock_guard<std::mutex> guard(state->setup);
    check_open(state);
    if (state->connection == SRT_INVALID_SOCK) throw std::runtime_error("SRT caller has not connected");
    return {state->connection, writing ? state->write_poll : state->read_poll};
}
int64_t listen(const std::string &host, const std::string &peer, const std::string &stream, int latency) {
    auto state = std::make_shared<State>();
    state->expected = address(peer, 0);
    state->stream_id = stream;
    state->listener = make_socket(true, latency);
    const auto bind = address(host, 0);
    if (srt_bind(state->listener, reinterpret_cast<const sockaddr *>(&bind.storage), bind.length) == SRT_ERROR)
        fail("bind", state->listener);
    if (srt_listen(state->listener, 1) == SRT_ERROR) fail("listen", state->listener);
    state->accept_poll = make_epoll(state->listener, SRT_EPOLL_IN | SRT_EPOLL_ERR);
    return publish(std::move(state));
}
int port(int64_t handle) {
    auto state = lookup(handle);
    std::lock_guard<std::mutex> guard(state->setup);
    Address local;
    local.length = sizeof(local.storage);
    const auto socket = state->listener != SRT_INVALID_SOCK ? state->listener : state->connection;
    if (srt_getsockname(socket, reinterpret_cast<sockaddr *>(&local.storage), &local.length) == SRT_ERROR)
        fail("local port", socket);
    return ntohs(local.storage.ss_family == AF_INET ?
        reinterpret_cast<sockaddr_in *>(&local.storage)->sin_port :
        reinterpret_cast<sockaddr_in6 *>(&local.storage)->sin6_port);
}
bool accept_caller(int64_t handle, int timeout) {
    if (timeout < 0 || timeout > 10000) throw std::runtime_error("Invalid SRT accept timeout");
    auto state = lookup(handle);
    const auto end = Clock::now() + std::chrono::milliseconds(timeout);
    do {
        {
            std::lock_guard<std::mutex> guard(state->setup);
            check_open(state);
            if (state->connection != SRT_INVALID_SOCK) return true;
        }
        if (!wait_event(state, state->accept_poll, false, std::min(timeout, 100))) continue;
        std::lock_guard<std::mutex> guard(state->setup);
        check_open(state);
        Address remote;
        remote.length = sizeof(remote.storage);
        const auto socket = srt_accept(state->listener, reinterpret_cast<sockaddr *>(&remote.storage), &remote.length);
        if (socket == SRT_INVALID_SOCK) {
            if (srt_getlasterror(nullptr) == SRT_EASYNCRCV) continue;
            fail("accept", state->listener);
        }
        try {
            char stream[513]{};
            int length = sizeof(stream) - 1;
            if (srt_getsockflag(socket, SRTO_STREAMID, stream, &length) == SRT_ERROR) fail("caller stream id", socket);
            while (length > 0 && stream[length - 1] == '\0') --length;
            if (!same_peer(state->expected.storage, remote.storage) ||
                std::string(stream, static_cast<size_t>(length)) != state->stream_id)
                throw std::runtime_error("SRT caller does not match the accepted control session | snapshot " + socket_snapshot(socket));
            state->read_poll = make_epoll(socket, SRT_EPOLL_IN | SRT_EPOLL_ERR);
            state->write_poll = make_epoll(socket, SRT_EPOLL_OUT | SRT_EPOLL_ERR);
            state->connection = socket;
            return true;
        } catch (const std::exception &) {
            srt_close(socket);
            throw;
        }
    } while (Clock::now() < end);
    return false;
}
int64_t create_caller(const std::string &stream, int latency) {
    auto state = std::make_shared<State>();
    state->connection = make_socket(false, latency);
    if (stream.empty() || stream.size() > 512) throw std::runtime_error("Invalid SRT stream id");
    if (srt_setsockflag(state->connection, SRTO_STREAMID, stream.data(), static_cast<int>(stream.size())) == SRT_ERROR)
        fail("stream id", state->connection);
    state->read_poll = make_epoll(state->connection, SRT_EPOLL_IN | SRT_EPOLL_ERR);
    state->write_poll = make_epoll(state->connection, SRT_EPOLL_OUT | SRT_EPOLL_ERR);
    return publish(std::move(state));
}
void connect(int64_t handle, const std::string &host, int remote_port, int timeout) {
    if (remote_port < 1 || remote_port > 65535 || timeout < 1 || timeout > 10000)
        throw std::runtime_error("Invalid SRT connection parameters");
    auto state = lookup(handle);
    const auto target = address(host, remote_port);
    const auto socket = io_socket(state, true);
    if (srt_connect(socket.first, reinterpret_cast<const sockaddr *>(&target.storage), target.length) == SRT_ERROR &&
        srt_getlasterror(nullptr) != SRT_EASYNCSND) fail_io("connect", state, socket.first);
    const auto end = Clock::now() + std::chrono::milliseconds(timeout);
    while (Clock::now() < end) {
        check_open(state);
        const auto status = srt_getsockstate(socket.first);
        if (status == SRTS_CONNECTED) return;
        if (status == SRTS_BROKEN || status == SRTS_CLOSED || status == SRTS_NONEXIST)
            fail_io("connection handshake", state, socket.first);
        wait_event(state, socket.second, true, 100);
    }
    throw std::runtime_error("SRT connection timed out | snapshot " + state_snapshot(state, socket.first));
}
int read(int64_t handle, uint8_t *output, int length) {
    if (length == 0) return 0;
    auto state = lookup(handle);
    std::lock_guard<std::mutex> guard(state->read);
    const auto socket = io_socket(state, false);
    int copied = 0;
    while (copied < length) {
        check_open(state);
        if (state->pending_at < state->pending_size) {
            const int count = std::min(length - copied, state->pending_size - state->pending_at);
            std::memcpy(output + copied, state->pending.data() + state->pending_at, count);
            state->pending_at += count;
            copied += count;
            continue;
        }
        const int received = srt_recvmsg(socket.first, state->pending.data(), state->pending.size());
        if (received == 0) return copied == 0 ? -1 : copied;
        if (received == SRT_ERROR) {
            check_open(state);
            if (srt_getlasterror(nullptr) != SRT_EASYNCRCV) fail_io("receive", state, socket.first);
            if (copied > 0) return copied;
            wait_event(state, socket.second, false, 100);
            continue;
        }
        state->pending_at = 0;
        state->pending_size = received;
    }
    return copied;
}
void close_handle(int64_t handle) {
    std::shared_ptr<State> state;
    {
        std::lock_guard<std::mutex> guard(registry_lock);
        const auto entry = registry.find(handle);
        if (entry == registry.end()) return;
        state = std::move(entry->second);
        registry.erase(entry);
    }
    state->shutdown();
}
std::string text(JNIEnv *env, jstring input) {
    if (!input) throw std::runtime_error("Missing SRT string");
    const char *chars = env->GetStringUTFChars(input, nullptr);
    if (!chars) throw std::runtime_error("Unable to read SRT string");
    std::string result(chars);
    env->ReleaseStringUTFChars(input, chars);
    return result;
}
void exception(JNIEnv *env, const std::exception &error) {
    if (env->ExceptionCheck()) return;
    const auto type = env->FindClass("java/io/IOException");
    if (type) env->ThrowNew(type, error.what());
}
template<class T, class Action> T jni(JNIEnv *env, T failure, Action action) {
    try { return action(); } catch (const std::exception &error) { exception(env, error); return failure; }
}
template<class Action> void jni_void(JNIEnv *env, Action action) {
    try { action(); } catch (const std::exception &error) { exception(env, error); }
}
void range(JNIEnv *env, jbyteArray bytes, jint offset, jint length) {
    if (!bytes || offset < 0 || length < 0 || length > 8 * 1024 * 1024 ||
        offset > env->GetArrayLength(bytes) - length) throw std::runtime_error("Invalid SRT buffer range");
}
}

namespace screencopy {
namespace srtio {
void write_bytes(int64_t handle, const uint8_t *bytes, size_t length) {
    auto state = lookup(handle);
    std::lock_guard<std::mutex> guard(state->write);
    const auto socket = io_socket(state, true);
    auto no_progress_until = Clock::now() + std::chrono::seconds(1);
    size_t written = 0;
    while (written < length) {
        check_open(state);
        const int count = static_cast<int>(std::min(length - written, static_cast<size_t>(kPayload)));
        const int result = srt_sendmsg(socket.first, reinterpret_cast<const char *>(bytes + written), count, -1, 1);
        if (result == SRT_ERROR) {
            check_open(state);
            if (srt_getlasterror(nullptr) != SRT_EASYNCSND) fail_io("send", state, socket.first);
            if (Clock::now() >= no_progress_until)
                throw std::runtime_error("SRT send made no progress within one second | snapshot " + state_snapshot(state, socket.first));
            wait_event(state, socket.second, true, 100);
            continue;
        }
        if (result != count)
            throw std::runtime_error("SRT message was not accepted in full | snapshot " + state_snapshot(state, socket.first));
        written += static_cast<size_t>(result);
        no_progress_until = Clock::now() + std::chrono::seconds(1);
    }
}
bool is_closed(int64_t handle) {
    std::lock_guard<std::mutex> guard(registry_lock);
    const auto entry = registry.find(handle);
    return entry == registry.end() || entry->second->closing;
}
std::string snapshot(int64_t handle) {
    std::shared_ptr<State> state;
    {
        std::lock_guard<std::mutex> guard(registry_lock);
        const auto entry = registry.find(handle);
        if (entry == registry.end())
            return "snapshot=unavailable snapshot_unavailable=closed state=CLOSED last_peer_response_ms=unavailable";
        state = entry->second;
    }
    return state_snapshot(state);
}
}
}

#define SRT_BINDINGS(PREFIX) \
extern "C" JNIEXPORT jlong JNICALL PREFIX##_listen(JNIEnv *e, jobject, jstring host, jstring peer, jstring stream, jint latency) { \
    return jni<jlong>(e, 0, [&] { return listen(text(e, host), text(e, peer), text(e, stream), latency); }); } \
extern "C" JNIEXPORT jint JNICALL PREFIX##_localPort(JNIEnv *e, jobject, jlong h) { return jni<jint>(e, 0, [&] { return port(h); }); } \
extern "C" JNIEXPORT jboolean JNICALL PREFIX##_awaitCaller(JNIEnv *e, jobject, jlong h, jint timeout) { \
    return jni<jboolean>(e, JNI_FALSE, [&] { return accept_caller(h, timeout) ? JNI_TRUE : JNI_FALSE; }); } \
extern "C" JNIEXPORT jlong JNICALL PREFIX##_createCaller(JNIEnv *e, jobject, jstring stream, jint latency) { \
    return jni<jlong>(e, 0, [&] { return create_caller(text(e, stream), latency); }); } \
extern "C" JNIEXPORT void JNICALL PREFIX##_connect(JNIEnv *e, jobject, jlong h, jstring host, jint p, jint timeout) { \
    jni_void(e, [&] { connect(h, text(e, host), p, timeout); }); } \
extern "C" JNIEXPORT jint JNICALL PREFIX##_read(JNIEnv *e, jobject, jlong h, jbyteArray output, jint offset, jint length) { \
    return jni<jint>(e, -1, [&] { range(e, output, offset, length); std::vector<uint8_t> data(static_cast<size_t>(length)); \
        const int count = read(h, data.data(), length); if (count > 0) e->SetByteArrayRegion(output, offset, count, reinterpret_cast<const jbyte *>(data.data())); return count; }); } \
extern "C" JNIEXPORT void JNICALL PREFIX##_write(JNIEnv *e, jobject, jlong h, jbyteArray input, jint offset, jint length) { \
    jni_void(e, [&] { range(e, input, offset, length); std::vector<uint8_t> data(static_cast<size_t>(length)); \
        e->GetByteArrayRegion(input, offset, length, reinterpret_cast<jbyte *>(data.data())); if (!e->ExceptionCheck()) screencopy::srtio::write_bytes(h, data.data(), data.size()); }); } \
extern "C" JNIEXPORT void JNICALL PREFIX##_close(JNIEnv *e, jobject, jlong h) { jni_void(e, [&] { close_handle(h); }); } \
extern "C" JNIEXPORT jstring JNICALL PREFIX##_snapshot(JNIEnv *e, jobject, jlong h) { \
    return jni<jstring>(e, nullptr, [&] { const auto value = screencopy::srtio::snapshot(h); return e->NewStringUTF(value.c_str()); }); } \
extern "C" JNIEXPORT jstring JNICALL PREFIX##_version(JNIEnv *e, jobject) { \
    return jni<jstring>(e, nullptr, [&] { initialize(); const auto v = srt_getversion(); const auto value = std::to_string(v >> 16) + "." + std::to_string((v >> 8) & 255) + "." + std::to_string(v & 255); return e->NewStringUTF(value.c_str()); }); }

SRT_BINDINGS(Java_dev_screencopy_sender_SrtNative)
SRT_BINDINGS(Java_org_screencopy_receiver_media_SrtNative)
