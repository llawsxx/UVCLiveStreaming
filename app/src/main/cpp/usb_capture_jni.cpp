#include <jni.h>
#include <android/log.h>
#include <libusb.h>
#include <libuvc/libuvc.h>
#include <libuac.h>
#include <atomic>
#include <chrono>
#include <cmath>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <memory>
#include <cstdio>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>
#include <unistd.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include "mjpeg_repair.h"
#include "usb_video_interval.h"

extern "C" uvc_error_t uvc_mjpeg2i420(uvc_frame_t *in, uvc_frame_t *out);
extern "C" uvc_error_t uvc_mjpeg2i420_diagnostic(uvc_frame_t *in, uvc_frame_t *out,
    long *warnings, char *message, size_t message_size);
extern "C" uvc_error_t uvc_mjpeg2yuv_diagnostic(uvc_frame_t *in, uvc_frame_t *out,
    unsigned int chroma_width, unsigned int chroma_height,
    long *warnings, char *message, size_t message_size);

namespace {
constexpr const char *TAG = "UVCLiveStreamingUsb";

int64_t monotonic_ns() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

// Process-wide counters cover both idle preview and recording decode workers.
// Log summaries, not per-frame messages, so diagnostics do not flood logcat.
void record_mjpeg_decode(bool no_soi, bool missing_eoi, bool leading_bytes,
                         bool repaired_soi, bool failed, long warnings, const char *detail,
                         size_t bytes, int width, int height) {
    static std::atomic<uint64_t> frames{0}, no_sois{0}, missing_eois{0}, prefixes{0},
        failures{0}, repaired_sois{0}, warned_frames{0}, warning_count{0};
    static std::atomic<int64_t> last_log_ns{0};
    ++frames;
    if (no_soi) ++no_sois;
    if (missing_eoi) ++missing_eois;
    if (leading_bytes) ++prefixes;
    if (repaired_soi && !failed) {
        const auto count = ++repaired_sois;
        if (count <= 3 || count % 100 == 0)
            __android_log_print(ANDROID_LOG_INFO, TAG,
                "MJPEG SOI repaired: count=%llu bytes=%zu mode=%dx%d",
                (unsigned long long)count, bytes, width, height);
    }
    if (warnings > 0) {
        const auto count = ++warned_frames;
        warning_count += warnings;
        if (count <= 3 || count % 1000 == 0)
            __android_log_print(ANDROID_LOG_WARN, TAG,
                "MJPEG tolerated warning: warnedFrames=%llu decoded=%d bytes=%zu mode=%dx%d detail=%s",
                (unsigned long long)count, !failed, bytes, width, height, detail);
    }
    if (failed) {
        const auto count = ++failures;
        if (count <= 3 || count % 100 == 0)
            __android_log_print(ANDROID_LOG_WARN, TAG,
                "MJPEG decode rejected: count=%llu bytes=%zu mode=%dx%d reason=%s",
                (unsigned long long)count, bytes, width, height, detail);
    }
    const auto now = monotonic_ns();
    auto previous = last_log_ns.load();
    if (now - previous >= 5000000000LL && last_log_ns.compare_exchange_strong(previous, now))
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "MJPEG process totals: frames=%llu failed=%llu noSOI=%llu missingEOI=%llu "
            "repairedSOI=%llu prefix=%llu warnedFrames=%llu warnings=%llu lastDetail=%s",
            (unsigned long long)frames.load(), (unsigned long long)failures.load(),
            (unsigned long long)no_sois.load(), (unsigned long long)missing_eois.load(),
            (unsigned long long)repaired_sois.load(), (unsigned long long)prefixes.load(), (unsigned long long)warned_frames.load(),
            (unsigned long long)warning_count.load(), detail);
}

void throw_java(JNIEnv *env, const std::string &message) {
    auto exception = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(exception, message.c_str());
    env->DeleteLocalRef(exception);
}

int supported_format(uvc_frame_format format) {
    switch (format) {
        case UVC_FRAME_FORMAT_MJPEG: return 1;
        case UVC_FRAME_FORMAT_YUYV: return 2;
        case UVC_FRAME_FORMAT_UYVY: return 3;
        case UVC_FRAME_FORMAT_RGB: return 4;
        case UVC_FRAME_FORMAT_NV12: return 5;
        case UVC_FRAME_FORMAT_I420: return 6;
        case UVC_FRAME_FORMAT_P010: return 7;
        case UVC_FRAME_FORMAT_H264: return 8;
        case UVC_FRAME_FORMAT_BGR: return 9;
        default: return -1;
    }
}

std::string format_label(const uvc_format_desc_t *format) {
    const auto known = uvc_frame_format_for_guid(const_cast<uint8_t *>(format->guidFormat));
    if (known == UVC_FRAME_FORMAT_MJPEG) return "MJPG";
    if (known == UVC_FRAME_FORMAT_YUYV) return "YUYV";
    if (known == UVC_FRAME_FORMAT_UYVY) return "UYVY";
    if (known == UVC_FRAME_FORMAT_RGB) return "RGB";
    if (known == UVC_FRAME_FORMAT_NV12) return "NV12";
    if (known == UVC_FRAME_FORMAT_I420) return "I420";
    if (known == UVC_FRAME_FORMAT_P010) return "P010";
    if (known == UVC_FRAME_FORMAT_H264) return "H264";
    if (known == UVC_FRAME_FORMAT_BGR) return "BGR";
    std::string fourcc;
    for (int i = 0; i < 4; ++i) {
        const auto c = format->fourccFormat[i];
        if (c < 32 || c > 126 || c == '|') { fourcc.clear(); break; }
        fourcc += static_cast<char>(c);
    }
    if (!fourcc.empty()) return fourcc;
    char guid[33];
    for (int i = 0; i < 16; ++i)
        std::snprintf(guid + i * 2, 3, "%02X", format->guidFormat[i]);
    return guid;
}

uvc_error_t negotiate_custom_video_mode(uvc_device_handle_t *camera, uvc_stream_ctrl_t *ctrl,
                                        uvc_frame_format wanted, int width, int height, double fps) {
    // UVC addresses resolution/format by descriptor indices; never guess indices
    // or decode a differently sized frame as the user's requested dimensions.
    auto result = uvc_get_stream_ctrl_format_size(camera, ctrl, wanted, width, height, 0);
    if (result != UVC_SUCCESS) return result;
    const auto *frame = uvc_get_frame_desc_for_ctrl(camera, ctrl);
    if (!frame || frame->wWidth != width || frame->wHeight != height) return UVC_ERROR_INVALID_MODE;
    const uint32_t interval = usb_video_interval(fps, frame->intervals,
        frame->dwMinFrameInterval, frame->dwMaxFrameInterval, frame->dwFrameIntervalStep, 1);
    if (!interval) return UVC_ERROR_INVALID_MODE;
    ctrl->bmHint |= 1;
    ctrl->dwFrameInterval = interval;
    result = uvc_probe_stream_ctrl(camera, ctrl);
    if (result != UVC_SUCCESS) return result;
    if (std::abs(static_cast<int64_t>(ctrl->dwFrameInterval) - interval) > 1) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "Custom USB fps rejected: requested=%.6f interval=%u returned=%u",
            fps, interval, ctrl->dwFrameInterval);
        return UVC_ERROR_INVALID_MODE;
    }
    return UVC_SUCCESS;
}

class UsbCapture {
public:
    UsbCapture(JavaVM *vm, int fd, int width, int height, double fps,
               int preferred_video_format, bool audio, int audio_rate, bool custom_video_mode,
               int audio_bit_depth, int bulk_transfer_count, bool audio_only = false)
        : vm_(vm), bulk_transfer_count_(bulk_transfer_count) {
        try {
        libusb_init_option option{};
        option.option = LIBUSB_OPTION_NO_DEVICE_DISCOVERY;
        if (libusb_init_context(&usb_ctx_, &option, 1) != LIBUSB_SUCCESS)
            throw std::runtime_error("Cannot initialize libusb");
        libusb_set_option(usb_ctx_, LIBUSB_OPTION_LOG_LEVEL, LIBUSB_LOG_LEVEL_WARNING);
        if (!audio_only) {
        video_fd_ = dup(fd);
        if (video_fd_ < 0) throw std::runtime_error("Cannot duplicate USB video descriptor");
        if (uvc_init(&uvc_ctx_, usb_ctx_) != UVC_SUCCESS || !uvc_ctx_)
            throw std::runtime_error("Cannot initialize libuvc");
        const auto result = uvc_wrap(video_fd_, uvc_ctx_, &camera_);
        if (result != UVC_SUCCESS || !camera_)
            throw std::runtime_error("Cannot open USB camera: " + std::to_string(result));

        if (custom_video_mode && (!std::isfinite(fps) || fps < 1 || fps > 240 ||
            width < 1 || width > 3840 || height < 1 || height > 2160 || preferred_video_format == 0))
            throw std::runtime_error("自定义采集参数无效，请指定格式、分辨率和 1～240 fps 帧率");
        const double requested_fps = std::isfinite(fps) && fps > 0 ? fps : 30;
        const int requested_width = width > 0 ? width : 1280;
        const int requested_height = height > 0 ? height : 720;
        for (const auto format : {UVC_FRAME_FORMAT_MJPEG, UVC_FRAME_FORMAT_YUYV,
                                  UVC_FRAME_FORMAT_UYVY, UVC_FRAME_FORMAT_RGB,
                                  UVC_FRAME_FORMAT_BGR, UVC_FRAME_FORMAT_NV12,
                                  UVC_FRAME_FORMAT_I420, UVC_FRAME_FORMAT_P010,
                                  UVC_FRAME_FORMAT_H264}) {
            if (preferred_video_format != 0 && preferred_video_format != supported_format(format)) continue;
            const auto mode_result = custom_video_mode
                ? negotiate_custom_video_mode(camera_, &video_ctrl_, format, requested_width, requested_height, requested_fps)
                : uvc_get_stream_ctrl_format_size(camera_, &video_ctrl_, format,
                    requested_width, requested_height, static_cast<int>(std::llround(requested_fps)));
            if (mode_result == UVC_SUCCESS) {
                video_format_ = format;
                width_ = requested_width;
                height_ = requested_height;
                break;
            }
        }
        if (width_ == 0 && custom_video_mode)
            throw std::runtime_error("设备不支持或未接受此自定义格式、分辨率、帧率组合");
        if (width_ == 0 && preferred_video_format != 0)
            throw std::runtime_error("Selected USB video format, resolution or frame rate is unavailable");
        if (width_ == 0) {
            for (const auto *format = uvc_get_format_descs(camera_); format; format = format->next) {
                const auto candidate_format = uvc_frame_format_for_guid(const_cast<uint8_t *>(format->guidFormat));
                if (supported_format(candidate_format) < 0) continue;
                if (preferred_video_format != 0 && preferred_video_format != supported_format(candidate_format)) continue;
                for (const auto *frame = format->frame_descs; frame; frame = frame->next) {
                    const int candidate_fps = frame->dwDefaultFrameInterval > 0
                        ? static_cast<int>(std::llround(10000000.0 / frame->dwDefaultFrameInterval)) : 30;
                    if (frame->wWidth > 1920 || frame->wHeight > 1080) continue;
                    if (uvc_get_stream_ctrl_format_size(camera_, &video_ctrl_, candidate_format,
                            frame->wWidth, frame->wHeight, candidate_fps) == UVC_SUCCESS) {
                        video_format_ = candidate_format;
                        width_ = frame->wWidth;
                        height_ = frame->wHeight;
                        break;
                    }
                }
                if (width_) break;
            }
        }
        if (!width_) throw std::runtime_error("USB camera has no usable MJPG, YUYV, UYVY, RGB, NV12, I420, P010 or H264 mode");
        if (width_ > 3840 || height_ > 2160)
            throw std::runtime_error("USB video resolution exceeds the 3840x2160 processing limit");
        }
        if (audio) {
            audio_fd_ = dup(fd);
            if (audio_fd_ < 0) throw std::runtime_error("Cannot duplicate USB audio descriptor");
            // Share the libusb context used by UVC.  Creating a second context
            // can make Android's wrapped fd invisible to the UAC descriptor
            // parser on composite devices.
            audio_ctx_ = uac::uac_context::create(usb_ctx_);
            audio_device_ = audio_ctx_->wrap(audio_fd_);
            // The usual capture topology is an external input terminal
            // feeding a USB streaming output terminal.  Some capture cards
            // advertise the output as USB_UNDEFINED or a vendor-specific USB
            // terminal, however.  Match the whole USB terminal category
            // before falling back to the strict USB_STREAMING value; this is
            // also what Android's USB audio driver does when it creates the
            // U4 4K60 ALSA input device.
            auto routes = audio_device_->get_device()->query_audio_routes(
                uac::UAC_TERMINAL_EXTERNAL_UNDEFINED, uac::UAC_TERMINAL_USB_UNDEFINED);
            if (routes.empty()) {
                routes = audio_device_->get_device()->query_audio_routes(
                    uac::UAC_TERMINAL_ANY, uac::UAC_TERMINAL_USB_UNDEFINED);
            }
            if (routes.empty()) {
                routes = audio_device_->get_device()->query_audio_routes(
                    uac::UAC_TERMINAL_EXTERNAL_UNDEFINED, uac::UAC_TERMINAL_USB_STREAMING);
            }
            if (routes.empty()) {
                routes = audio_device_->get_device()->query_audio_routes(
                    uac::UAC_TERMINAL_ANY, uac::UAC_TERMINAL_USB_STREAMING);
            }
            if (routes.empty()) {
                __android_log_print(ANDROID_LOG_WARN, TAG,
                    "USB device exposes no UAC capture route; continuing without USB audio");
            }
            for (const auto &route : routes) {
                const auto &candidate = audio_device_->get_device()->get_stream_interface(route.get());
                std::vector<uint32_t> rates;
                if (audio_rate > 0) rates.push_back(static_cast<uint32_t>(audio_rate));
                else rates = {48000, 44100, 32000, 16000, 96000};
                if (audio_rate == 0) {
                    for (auto rate : candidate.get_sample_rates(uac::UAC_FORMAT_DATA_PCM)) {
                        if (rate >= 8000 && rate <= 96000) rates.push_back(rate);
                    }
                }
                for (int channels : {2, 1}) {
                    for (auto rate : rates) {
                        for (int bits : {32, 24, 16}) {
                            if (audio_bit_depth != 0 && bits != audio_bit_depth) continue;
                            audio_config_ = candidate.query_config_uncompressed(
                                uac::UAC_FORMAT_DATA_PCM, channels, rate, bits);
                            if (audio_config_) break;
                        }
                        if (audio_config_) break;
                    }
                    if (audio_config_) break;
                }
                if (audio_config_) {
                    audio_interface_ = &candidate;
                    break;
                }
            }
            if (!audio_config_ && !routes.empty()) {
                __android_log_print(ANDROID_LOG_WARN, TAG,
                    "USB UAC route found, but no compatible PCM rate; continuing without USB audio");
            }
            if (audio_config_) {
                audio_channels_ = audio_config_->bChannelCount;
                audio_rate_ = audio_config_->tSampleRate;
                audio_subframe_bytes_ = audio_config_->bSubframeSize;
                audio_bit_depth_ = audio_config_->bBitResolution;
                if (audio_channels_ < 1 || audio_channels_ > 2 || audio_subframe_bytes_ < 2 || audio_subframe_bytes_ > 4 ||
                    audio_bit_depth_ < 16 || audio_bit_depth_ > audio_subframe_bytes_ * 8) {
                    __android_log_print(ANDROID_LOG_WARN, TAG,
                        "Unsupported USB microphone PCM layout; continuing without USB audio");
                    audio_config_.reset();
                    audio_channels_ = audio_rate_ = audio_subframe_bytes_ = audio_bit_depth_ = 0;
                }
            }
        }
        if ((audio_only || (audio && audio_bit_depth != 0)) && !audio_config_)
            throw std::runtime_error("USB 音频设备不支持所选采样率／位深的单声道或双声道 PCM 输入");
        } catch (...) {
            cleanup();
            throw;
        }
    }

    ~UsbCapture() { cleanup(); }

    void cleanup() {
        stop();
        // UAC handles reference the same libusb context. They must be
        // destroyed before UVC/libusb tears that context down; otherwise a
        // second preview open hits bionic's "destroyed mutex" abort.
        audio_stream_.reset();
        audio_device_.reset();
        audio_ctx_.reset();
        if (camera_) { uvc_close(camera_); camera_ = nullptr; }
        if (uvc_ctx_) { uvc_exit(uvc_ctx_); uvc_ctx_ = nullptr; }
        if (usb_ctx_) { libusb_exit(usb_ctx_); usb_ctx_ = nullptr; }
        if (audio_fd_ >= 0) { close(audio_fd_); audio_fd_ = -1; }
        if (video_fd_ >= 0) { close(video_fd_); video_fd_ = -1; }
    }

    void start(JNIEnv *env, jobject callback) {
        if (started_) throw std::runtime_error("USB capture already started");
        callback_ = env->NewGlobalRef(callback);
        auto klass = env->GetObjectClass(callback);
        video_method_ = env->GetMethodID(klass, "onUsbVideoFrame", "([BIIIJ)V");
        audio_method_ = env->GetMethodID(klass, "onUsbAudioPcmRaw", "([BJI)V");
        env->DeleteLocalRef(klass);
        if (!video_method_ || (audio_config_ && !audio_method_))
            throw std::runtime_error("USB callback methods unavailable");
        running_ = true;
        if (camera_) {
        // sysfs speed may be inaccessible on Android. Query the USB Host fd
        // directly; this ioctl neither claims an interface nor changes it.
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "USB video link: kernelSpeedEnum=%d (3=HighSpeed480Mbps 5=SuperSpeed5Gbps 6=SuperSpeedPlus)",
            ioctl(video_fd_, USBDEVFS_GET_SPEED, nullptr));
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "USB video negotiated: mode=%dx%d format=%d interval100ns=%u fps=%.6f maxFrame=%u maxPayload=%u clockHz=%u",
            width_, height_, supported_format(video_format_), video_ctrl_.dwFrameInterval,
            video_ctrl_.dwFrameInterval ? 10000000.0 / video_ctrl_.dwFrameInterval : 0.0,
            video_ctrl_.dwMaxVideoFrameSize, video_ctrl_.dwMaxPayloadTransferSize, video_ctrl_.dwClockFrequency);
        }
        event_thread_ = std::thread([this] {
            timeval timeout{0, 200000};
            while (running_) libusb_handle_events_timeout(usb_ctx_, &timeout);
        });
        if (camera_) {
            uvc_stream_handle_t *stream = nullptr;
            auto result = uvc_stream_open_ctrl(camera_, &stream, &video_ctrl_);
            if (result == UVC_SUCCESS)
                result = uvc_stream_set_bulk_transfer_count(stream, bulk_transfer_count_);
            if (result == UVC_SUCCESS)
                result = uvc_stream_start(stream, &UsbCapture::video_callback, this, 0);
            if (result != UVC_SUCCESS) {
                if (stream) uvc_stream_close(stream);
                stop();
                throw std::runtime_error("Cannot start USB video: " + std::to_string(result));
            }
            video_started_ = true;
        }
        if (audio_config_) {
            __android_log_print(ANDROID_LOG_INFO, TAG, "USB PCM input: rate=%d channels=%d bits=%d subslot=%d",
                audio_rate_, audio_channels_, audio_bit_depth_, audio_subframe_bytes_);
            audio_stream_ = audio_device_->start_streaming(*audio_interface_, *audio_config_,
                [this](uint8_t *data, uint count) { audio_frame(data, count); }, 8);
            if (!audio_stream_) {
                stop();
                throw std::runtime_error("Cannot start USB microphone");
            }
        }
        started_ = true;
    }

    void stop() {
        if (audio_stream_) audio_stream_.reset();
        if (started_ && audio_config_) {
            __android_log_print(ANDROID_LOG_INFO, TAG,
                "USB PCM stopped: packets=%llu bytes=%llu peak16=%d Java batches=%llu",
                (unsigned long long)audio_packets_, (unsigned long long)audio_bytes_,
                audio_peak_, (unsigned long long)audio_batches_);
        }
        if (video_started_) {
            uvc_stop_streaming(camera_);
            video_started_ = false;
        }
        running_ = false;
        if (event_thread_.joinable()) event_thread_.join();
        if (callback_) {
            JNIEnv *env = nullptr;
            bool attached = vm_->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK;
            if (attached) vm_->AttachCurrentThread(&env, nullptr);
            env->DeleteGlobalRef(callback_);
            callback_ = nullptr;
            if (attached) vm_->DetachCurrentThread();
        }
        started_ = false;
    }

    int width() const { return width_; }
    int height() const { return height_; }
    int audio_rate() const { return audio_rate_; }
    int audio_channels() const { return audio_channels_; }
    int audio_bit_depth() const { return audio_bit_depth_; }
    int audio_sample_bytes() const { return audio_subframe_bytes_; }
    uint64_t received_video_bytes() const { return camera_ ? uvc_get_received_video_bytes(camera_) : 0; }

private:
    static void video_callback(uvc_frame_t *frame, void *user) {
        static_cast<UsbCapture *>(user)->video_frame(frame);
    }

    void video_frame(uvc_frame_t *frame) {
        if (!running_ || !callback_ || !frame || !frame->data || frame->data_bytes == 0) return;
        const int format = supported_format(frame->frame_format);
        if (format < 0 || frame->width != static_cast<uint32_t>(width_) ||
            frame->height != static_cast<uint32_t>(height_)) return;
        const size_t length = frame->data_bytes;
        if (length > 3840ULL * 2160 * 4) return;
        const auto callback_start = monotonic_ns();
        // libuvc stamps frame assembly with CLOCK_MONOTONIC. Do not timestamp
        // after attaching to ART and allocating/copying the Java array: those
        // operations can add GC/scheduling jitter unrelated to capture time.
        const auto &finished = frame->capture_time_finished;
        const int64_t captured_ns = finished.tv_sec >= 0 && finished.tv_nsec >= 0 &&
            finished.tv_nsec < 1000000000L
            ? static_cast<int64_t>(finished.tv_sec) * 1000000000LL + finished.tv_nsec : 0;
        const auto timestamp_ns = captured_ns > 0 ? captured_ns : callback_start;
        ++video_frames_;
        video_bytes_ += length;
        if (video_frames_ > 1) {
            const uint32_t delta = frame->sequence - video_last_sequence_;
            if (delta > 1 && delta < 0x80000000u) video_sequence_gaps_ += delta - 1;
        }
        video_last_sequence_ = frame->sequence;
        JNIEnv *env = nullptr;
        bool attached = vm_->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK;
        if (attached && vm_->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        auto bytes = env->NewByteArray(static_cast<jsize>(length));
        if (bytes) {
            env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(length),
                reinterpret_cast<const jbyte *>(frame->data));
            env->CallVoidMethod(callback_, video_method_, bytes, format,
                static_cast<jint>(frame->width), static_cast<jint>(frame->height), static_cast<jlong>(timestamp_ns));
            env->DeleteLocalRef(bytes);
        }
        if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
        if (attached) vm_->DetachCurrentThread();
        const auto now = monotonic_ns();
        video_callback_max_ns_ = std::max(video_callback_max_ns_, now - callback_start);
        if (!video_last_log_ns_) {
            video_last_log_ns_ = now;
            video_last_log_frames_ = video_frames_;
        } else if (now - video_last_log_ns_ >= 5000000000LL) {
            __android_log_print(ANDROID_LOG_INFO, TAG,
                "USB video callbacks: frames=%llu fps=%.3f sequenceGaps=%llu bytes=%llu callbackMaxMs=%.3f",
                (unsigned long long)video_frames_,
                (video_frames_ - video_last_log_frames_) * 1000000000.0 / (now - video_last_log_ns_),
                (unsigned long long)video_sequence_gaps_, (unsigned long long)video_bytes_,
                video_callback_max_ns_ / 1000000.0);
            video_last_log_ns_ = now;
            video_last_log_frames_ = video_frames_;
            video_callback_max_ns_ = 0;
        }
    }

    void audio_frame(uint8_t *data, uint count) {
        if (!running_ || !callback_ || !data || !count) return;
        const int sample_bytes = audio_subframe_bytes_;
        const int total_samples = static_cast<int>(count) / (sample_bytes * audio_channels_) * audio_channels_;
        if (total_samples <= 0) return;
        ++audio_packets_;
        audio_bytes_ += count;
        const auto old_size = audio_aggregate_.size();
        audio_aggregate_.resize(old_size + static_cast<size_t>(total_samples) * sample_bytes);
        // Keep every input bit until the DSP worker converts it directly to float.
        std::memcpy(audio_aggregate_.data() + old_size, data, static_cast<size_t>(total_samples) * sample_bytes);
        for (int i = 0; i < total_samples; ++i) {
            const uint8_t *sample = data + i * sample_bytes;
            uint32_t raw = 0;
            for (int b = 0; b < sample_bytes; ++b) raw |= static_cast<uint32_t>(sample[b]) << (b * 8);
            if (sample_bytes == 3 && (raw & 0x800000u)) raw |= 0xff000000u;
            int32_t value = sample_bytes == 2 ? static_cast<int16_t>(raw) : static_cast<int32_t>(raw);
            value >>= (sample_bytes - 2) * 8;
            audio_peak_ = std::max(audio_peak_, value < 0 ? -value : value);
        }
        const size_t batch_bytes = static_cast<size_t>(audio_rate_) * audio_channels_ * sample_bytes / 100;
        if (audio_aggregate_.size() < batch_bytes) return;
        JNIEnv *env = nullptr;
        bool attached = vm_->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK;
        if (attached && vm_->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        auto bytes = env->NewByteArray(static_cast<jsize>(audio_aggregate_.size()));
        if (bytes) {
            env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(audio_aggregate_.size()), audio_aggregate_.data());
            const auto frames = audio_aggregate_.size() / (audio_channels_ * sample_bytes);
            const auto first_sample_ns = monotonic_ns() -
                static_cast<int64_t>(frames) * 1000000000LL / audio_rate_;
            env->CallVoidMethod(callback_, audio_method_, bytes, static_cast<jlong>(first_sample_ns), sample_bytes);
            ++audio_batches_;
            env->DeleteLocalRef(bytes);
        }
        const int64_t now = monotonic_ns();
        if (now - audio_last_log_ns_ >= 5000000000LL) {
            __android_log_print(ANDROID_LOG_INFO, TAG,
                "USB PCM received: rate=%d channels=%d packets=%llu bytes=%llu peak16=%d Java batches=%llu",
                audio_rate_, audio_channels_, (unsigned long long)audio_packets_,
                (unsigned long long)audio_bytes_, audio_peak_, (unsigned long long)audio_batches_);
            audio_last_log_ns_ = now;
        }
        audio_aggregate_.clear();
        if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
        if (attached) vm_->DetachCurrentThread();
    }

    JavaVM *vm_;
    int bulk_transfer_count_;
    int video_fd_ = -1;
    int audio_fd_ = -1;
    libusb_context *usb_ctx_ = nullptr;
    uvc_context_t *uvc_ctx_ = nullptr;
    uvc_device_handle_t *camera_ = nullptr;
    uvc_stream_ctrl_t video_ctrl_{};
    uvc_frame_format video_format_ = UVC_FRAME_FORMAT_UNKNOWN;
    int width_ = 0;
    int height_ = 0;
    std::shared_ptr<uac::uac_context> audio_ctx_;
    std::shared_ptr<uac::uac_device_handle> audio_device_;
    const uac::uac_stream_if *audio_interface_ = nullptr;
    std::unique_ptr<const uac::uac_audio_config_uncompressed> audio_config_;
    std::shared_ptr<uac::uac_stream_handle> audio_stream_;
    int audio_rate_ = 0;
    int audio_channels_ = 0;
    int audio_subframe_bytes_ = 0;
    int audio_bit_depth_ = 0;
    std::vector<jbyte> audio_aggregate_;
    uint64_t audio_packets_ = 0;
    uint64_t video_frames_ = 0, video_bytes_ = 0, video_sequence_gaps_ = 0;
    uint64_t video_last_log_frames_ = 0;
    uint32_t video_last_sequence_ = 0;
    int64_t video_last_log_ns_ = 0, video_callback_max_ns_ = 0;
    uint64_t audio_bytes_ = 0;
    uint64_t audio_batches_ = 0;
    int audio_peak_ = 0;
    int64_t audio_last_log_ns_ = 0;
    std::atomic<bool> running_{false};
    bool video_started_ = false;
    bool started_ = false;
    std::thread event_thread_;
    jobject callback_ = nullptr;
    jmethodID video_method_ = nullptr;
    jmethodID audio_method_ = nullptr;
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_llawsxx_uvclivestreaming_recording_NativeUsbCapture_nativeOpen(
    JNIEnv *env, jobject, jint fd, jint width, jint height, jdouble fps, jint video_format,
    jboolean audio, jint audio_rate, jboolean custom_video_mode, jint audio_bit_depth, jint bulk_transfer_count) {
    try {
        JavaVM *vm = nullptr;
        env->GetJavaVM(&vm);
        return reinterpret_cast<jlong>(new UsbCapture(vm, fd, width, height, fps,
            video_format, audio, audio_rate, custom_video_mode, audio_bit_depth, bulk_transfer_count));
    } catch (const std::exception &error) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "open: %s", error.what());
        throw_java(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_llawsxx_uvclivestreaming_recording_NativeUsbCapture_nativeOpenAudio(
    JNIEnv *env, jobject, jint fd, jint audio_rate, jint audio_bit_depth) {
    try {
        JavaVM *vm = nullptr;
        env->GetJavaVM(&vm);
        return reinterpret_cast<jlong>(new UsbCapture(vm, fd, 0, 0, 0, 0, true,
            audio_rate, false, audio_bit_depth, 64, true));
    } catch (const std::exception &error) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "open audio: %s", error.what());
        throw_java(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_llawsxx_uvclivestreaming_recording_NativeUsbCapture_nativeListVideoModes(
    JNIEnv *env, jobject, jint fd) {
    int owned_fd = dup(fd);
    libusb_context *usb = nullptr;
    uvc_context_t *uvc = nullptr;
    uvc_device_handle_t *camera = nullptr;
    std::vector<std::string> modes;
    try {
        if (owned_fd < 0) throw std::runtime_error("Cannot duplicate USB descriptor");
        libusb_init_option option{};
        option.option = LIBUSB_OPTION_NO_DEVICE_DISCOVERY;
        if (libusb_init_context(&usb, &option, 1) != LIBUSB_SUCCESS)
            throw std::runtime_error("Cannot initialize libusb");
        libusb_set_option(usb, LIBUSB_OPTION_LOG_LEVEL, LIBUSB_LOG_LEVEL_WARNING);
        if (uvc_init(&uvc, usb) != UVC_SUCCESS || !uvc)
            throw std::runtime_error("Cannot initialize libuvc");
        if (uvc_wrap(owned_fd, uvc, &camera) != UVC_SUCCESS || !camera)
            throw std::runtime_error("Cannot inspect USB camera formats");
        for (auto *format = uvc_get_format_descs(camera); format;
             format = format->next) {
            const auto label = format_label(format);
            const int input_format = supported_format(
                uvc_frame_format_for_guid(const_cast<uint8_t *>(format->guidFormat)));
            for (auto *frame = format->frame_descs; frame;
                 frame = frame->next) {
                auto add_mode = [&](uint32_t interval, const std::string &detail) {
                    if (!interval) return;
                    const int fps = static_cast<int>((10000000ULL + interval / 2) / interval);
                    if (fps < 1) return;
                    modes.push_back(label + "|" + std::to_string(frame->wWidth) + "|" +
                        std::to_string(frame->wHeight) + "|" + std::to_string(fps) + "|" +
                        std::to_string(input_format) + "|" + detail);
                };
                if (frame->intervals) {
                    for (auto *interval = frame->intervals; *interval;
                         ++interval) add_mode(*interval, "");
                } else {
                    std::string detail;
                    if (frame->dwMinFrameInterval && frame->dwMaxFrameInterval) {
                        const auto min_fps = 10000000ULL / frame->dwMaxFrameInterval;
                        const auto max_fps = 10000000ULL / frame->dwMinFrameInterval;
                        detail = std::to_string(min_fps) + "-" + std::to_string(max_fps) + " fps variable";
                    }
                    add_mode(frame->dwDefaultFrameInterval ? frame->dwDefaultFrameInterval
                        : frame->dwMinFrameInterval, detail);
                }
            }
        }
    } catch (const std::exception &error) {
        throw_java(env, error.what());
    }
    if (camera) uvc_close(camera);
    if (uvc) uvc_exit(uvc);
    if (usb) libusb_exit(usb);
    if (owned_fd >= 0) close(owned_fd);
    if (env->ExceptionCheck()) return nullptr;
    auto string_class = env->FindClass("java/lang/String");
    auto result = env->NewObjectArray(static_cast<jsize>(modes.size()), string_class, nullptr);
    for (jsize i = 0; i < static_cast<jsize>(modes.size()); ++i) {
        auto value = env->NewStringUTF(modes[i].c_str());
        env->SetObjectArrayElement(result, i, value);
        env->DeleteLocalRef(value);
    }
    env->DeleteLocalRef(string_class);
    return result;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_llawsxx_uvclivestreaming_recording_NativeUsbCapture_nativeFormat(JNIEnv *env, jobject, jlong handle) {
    auto capture = reinterpret_cast<UsbCapture *>(handle);
    if (!capture) return nullptr;
    jint values[] = {capture->width(), capture->height(), capture->audio_rate(), capture->audio_channels(),
        capture->audio_bit_depth(), capture->audio_sample_bytes()};
    auto array = env->NewIntArray(6);
    if (array) env->SetIntArrayRegion(array, 0, 6, values);
    return array;
}

static bool decode_mjpeg_i420(const uint8_t *src, size_t length, int width, int height,
                              uvc_frame_t *destination, int chroma_width = 0, int chroma_height = 0) {
    thread_local std::vector<uint8_t> normalized;
    size_t soi = length;
    for (size_t i = 0; i + 1 < length; ++i) {
        if (src[i] == 0xff && src[i + 1] == 0xd8) { soi = i; break; }
    }
    const bool missing_soi = soi == length;
    if (missing_soi) {
        soi = mjpeg_missing_soi_header(src, length, width, height);
        if (soi == SIZE_MAX) {
            char detail[192] = "SOI absent; no complete baseline header; first=";
            size_t used = std::strlen(detail);
            for (size_t i = 0; i < std::min(length, size_t{16}); ++i)
                used += std::snprintf(detail + used, sizeof(detail) - used, "%02X", src[i]);
            record_mjpeg_decode(true, false, false, false, true, 0, detail, length, width, height);
            return false;
        }
    }
    size_t end = length;
    bool eoi = false;
    for (size_t i = length; i >= soi + 2; --i) {
        if (src[i - 2] == 0xff && src[i - 1] == 0xd9) { end = i; eoi = true; break; }
    }
    const uint8_t *payload = src + soi;
    size_t payload_size = end - soi;
    if (missing_soi || !eoi) {
        normalized.clear();
        if (missing_soi) {
            normalized.push_back(0xff);
            normalized.push_back(0xd8);
        }
        normalized.insert(normalized.end(), payload, payload + payload_size);
        if (!eoi) {
            normalized.push_back(0xff);
            normalized.push_back(0xd9);
        }
        payload = normalized.data();
        payload_size = normalized.size();
    }
    uvc_frame_t in{};
    in.data = const_cast<uint8_t *>(payload);
    in.data_bytes = payload_size;
    in.width = width; in.height = height; in.frame_format = UVC_FRAME_FORMAT_MJPEG;
    long warnings = 0;
    char detail[256]{};
    const auto decoded = chroma_width > 0
        ? uvc_mjpeg2yuv_diagnostic(&in, destination, chroma_width, chroma_height, &warnings, detail, sizeof(detail))
        : uvc_mjpeg2i420_diagnostic(&in, destination, &warnings, detail, sizeof(detail));
    record_mjpeg_decode(missing_soi, !eoi, soi != 0, missing_soi, decoded != UVC_SUCCESS,
        warnings, detail, length, width, height);
    return decoded == UVC_SUCCESS;
}

static void *writable_direct_output(JNIEnv *env, jobject destination, size_t size) {
    if (!destination) return nullptr;
    auto *data = env->GetDirectBufferAddress(destination);
    const auto capacity = env->GetDirectBufferCapacity(destination);
    if (!data || capacity < static_cast<jlong>(size)) return nullptr;
    // Kotlin supplies writable buffers. Reject read-only aliases as well as undersized/non-direct ones.
    auto buffer_class = env->GetObjectClass(destination);
    if (!buffer_class) return nullptr;
    auto is_read_only = env->GetMethodID(buffer_class, "isReadOnly", "()Z");
    const bool read_only = is_read_only && env->CallBooleanMethod(destination, is_read_only);
    env->DeleteLocalRef(buffer_class);
    return env->ExceptionCheck() || read_only ? nullptr : data;
}

static jboolean decode_mjpeg_direct(JNIEnv *env, jbyteArray encoded, jint width, jint height,
                                   int chroma_width, int chroma_height, jobject destination) {
    if (!encoded || width <= 0 || height <= 0 || width > 3840 || height > 2160)
        return JNI_FALSE;
    const int cw = chroma_width > 0 ? chroma_width : (width + 1) / 2;
    const int ch = chroma_height > 0 ? chroma_height : (height + 1) / 2;
    if (cw <= 0 || ch <= 0 || cw > width || ch > height) return JNI_FALSE;
    const size_t size = static_cast<size_t>(width) * height + 2 * static_cast<size_t>(cw) * ch;
    auto *data = writable_direct_output(env, destination, size);
    if (!data) return JNI_FALSE;
    const size_t length = env->GetArrayLength(encoded);
    auto elements = env->GetByteArrayElements(encoded, nullptr);
    if (!elements) return JNI_FALSE;
    // libuvc treats this as caller-owned memory: it must neither realloc nor free it.
    uvc_frame_t output{};
    output.data = data;
    output.data_bytes = size;
    bool success = false;
    try {
        success = decode_mjpeg_i420(reinterpret_cast<const uint8_t *>(elements), length, width, height,
                                    &output, chroma_width, chroma_height);
    } catch (const std::exception &error) {
        env->ReleaseByteArrayElements(encoded, elements, JNI_ABORT);
        throw_java(env, error.what());
        return JNI_FALSE;
    }
    env->ReleaseByteArrayElements(encoded, elements, JNI_ABORT);
    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_llawsxx_uvclivestreaming_recording_NativeUsbCapture_nativeDecodeMjpegToI420(
    JNIEnv *env, jobject, jbyteArray encoded, jint width, jint height, jobject destination) {
    return decode_mjpeg_direct(env, encoded, width, height, 0, 0, destination);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_llawsxx_uvclivestreaming_recording_NativeUsbCapture_nativeDecodeMjpegToYuv(
    JNIEnv *env, jobject, jbyteArray encoded, jint width, jint height,
    jint chroma_width, jint chroma_height, jobject destination) {
    if (chroma_width <= 0 || chroma_height <= 0) return JNI_FALSE;
    return decode_mjpeg_direct(env, encoded, width, height, chroma_width, chroma_height, destination);
}

// Source-preserving packing for GL. The legacy I420 helper below is only for explicit I420 callers.
static void repack_raw_yuv(const uint8_t *src, int format, int width, int height, uint8_t *dst) {
    const size_t pixels = static_cast<size_t>(width) * height;
    if (format == 5 || format == 7) {
        const size_t sample_bytes = format == 7 ? 2 : 1;
        const size_t chroma = pixels / 4;
        memcpy(dst, src, pixels * sample_bytes);
        for (size_t i = 0; i < chroma; ++i) {
            memcpy(dst + (pixels + i) * sample_bytes, src + (pixels + 2 * i) * sample_bytes, sample_bytes);
            memcpy(dst + (pixels + chroma + i) * sample_bytes, src + (pixels + 2 * i + 1) * sample_bytes, sample_bytes);
        }
    } else {
        const int yo = format == 2 ? 0 : 1, uo = format == 2 ? 1 : 0, vo = format == 2 ? 3 : 2;
        const size_t chroma = pixels / 2;
        for (size_t pair = 0; pair < chroma; ++pair) {
            dst[2 * pair] = src[4 * pair + yo];
            dst[2 * pair + 1] = src[4 * pair + yo + 2];
            dst[pixels + pair] = src[4 * pair + uo];
            dst[pixels + chroma + pair] = src[4 * pair + vo];
        }
    }
}

static void repack_raw_i420(const uint8_t *src, int format, int width, int height, uint8_t *dst) {
    const size_t pixels = static_cast<size_t>(width) * height;
    const size_t cw = (width + 1) / 2, ch = (height + 1) / 2;
    auto read8 = [&](size_t off) -> uint8_t {
        // P010 stores 10 significant bits in the MSBs; the current SDR path retains the high eight bits.
        return format == 7 ? src[off * 2 + 1] : src[off];
    };
    if (format == 5 || format == 7) {
        for (size_t i = 0; i < pixels; ++i) dst[i] = read8(i);
        for (size_t i = 0; i < cw * ch; ++i) {
            dst[pixels + i] = read8(pixels + 2 * i);
            dst[pixels + cw * ch + i] = read8(pixels + 2 * i + 1);
        }
    } else {
        const int yo = format == 2 ? 0 : 1, uo = format == 2 ? 1 : 0, vo = format == 2 ? 3 : 2;
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; x += 2) {
                size_t off = (static_cast<size_t>(y) * width + x) * 2;
                dst[static_cast<size_t>(y) * width + x] = src[off + yo];
                dst[static_cast<size_t>(y) * width + x + 1] = src[off + yo + 2];
                if (!(y & 1)) {
                    size_t ci = static_cast<size_t>(y / 2) * cw + x / 2;
                    dst[pixels + ci] = (src[off + uo] + src[off + width * 2 + uo] + 1) / 2;
                    dst[pixels + cw * ch + ci] = (src[off + vo] + src[off + width * 2 + vo] + 1) / 2;
                }
            }
        }
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_llawsxx_uvclivestreaming_recording_NativeUsbCapture_nativeConvertRawToGpuBuffer(
    JNIEnv *env, jobject, jbyteArray encoded, jint format, jint width, jint height, jobject destination) {
    if (!encoded || width <= 0 || height <= 0 || width > 3840 || height > 2160) return JNI_FALSE;
    const bool rgb = format == 4 || format == 9;
    if (!rgb && format != 2 && format != 3 && format != 5 && format != 6 && format != 7) return JNI_FALSE;
    if (!rgb && format != 6 && ((width & 1) || (height & 1))) return JNI_FALSE;
    const size_t pixels = static_cast<size_t>(width) * height;
    const size_t size = rgb || format == 7 ? pixels * 3 :
        format == 2 || format == 3 ? pixels * 2 : pixels + 2 * static_cast<size_t>((width + 1) / 2) * ((height + 1) / 2);
    const size_t required = format == 2 || format == 3 ? pixels * 2 : format == 7 ? pixels * 3 : size;
    if (static_cast<size_t>(env->GetArrayLength(encoded)) < required) return JNI_FALSE;
    auto *dst = static_cast<uint8_t *>(writable_direct_output(env, destination, size));
    if (!dst) return JNI_FALSE;
    if (rgb || format == 6) {
        // No intermediate native copy or input array pin is needed for layouts already usable by GL.
        env->GetByteArrayRegion(encoded, 0, static_cast<jsize>(size), reinterpret_cast<jbyte *>(dst));
        return env->ExceptionCheck() ? JNI_FALSE : JNI_TRUE;
    }
    auto elements = env->GetByteArrayElements(encoded, nullptr);
    if (!elements) return JNI_FALSE;
    repack_raw_yuv(reinterpret_cast<const uint8_t *>(elements), format, width, height, dst);
    env->ReleaseByteArrayElements(encoded, elements, JNI_ABORT);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_llawsxx_uvclivestreaming_recording_NativeUsbCapture_nativeDecodeToI420(
    JNIEnv *env, jobject, jbyteArray encoded, jint format, jint width, jint height) {
    if (!encoded || width <= 0 || height <= 0 || width > 3840 || height > 2160) return nullptr;
    const size_t pixels = static_cast<size_t>(width) * height;
    const size_t cw = (width + 1) / 2, ch = (height + 1) / 2;
    const size_t size = pixels + 2 * cw * ch;
    const size_t length = env->GetArrayLength(encoded);
    if (format == 6) return length >= size ? encoded : nullptr;
    if (format != 1 && format != 2 && format != 3 && format != 5 && format != 7) return nullptr;
    // Subsampled raw USB formats use even dimensions and tightly packed rows.
    if (format != 1 && ((width & 1) || (height & 1))) return nullptr;
    const size_t required = format == 2 || format == 3 ? pixels * 2 :
        format == 7 ? pixels * 3 : size;
    if (format != 1 && length < required) return nullptr;
    struct YuvScratch {
        uvc_frame_t *frame = uvc_allocate_frame(0);
        std::vector<jbyte> raw;
        ~YuvScratch() { if (frame) uvc_free_frame(frame); }
    };
    thread_local YuvScratch scratch;
    if (!scratch.frame) return nullptr;
    auto elements = env->GetByteArrayElements(encoded, nullptr);
    if (!elements) return nullptr;
    const auto *src = reinterpret_cast<const uint8_t *>(elements);
    const jbyte *output = nullptr;
    if (format == 1) {
        if (decode_mjpeg_i420(src, length, width, height, scratch.frame))
            output = static_cast<const jbyte *>(scratch.frame->data);
    } else {
        scratch.raw.resize(size);
        repack_raw_i420(src, format, width, height, reinterpret_cast<uint8_t *>(scratch.raw.data()));
        output = scratch.raw.data();
    }
    jbyteArray result = output ? env->NewByteArray(static_cast<jsize>(size)) : nullptr;
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(size), output);
    env->ReleaseByteArrayElements(encoded, elements, JNI_ABORT);
    return result;
}


extern "C" JNIEXPORT void JNICALL
Java_com_llawsxx_uvclivestreaming_recording_NativeUsbCapture_nativeStart(
    JNIEnv *env, jobject, jlong handle, jobject callback) {
    try {
        auto capture = reinterpret_cast<UsbCapture *>(handle);
        if (!capture) throw std::runtime_error("USB capture is closed");
        capture->start(env, callback);
    } catch (const std::exception &error) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "start: %s", error.what());
        throw_java(env, error.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_llawsxx_uvclivestreaming_recording_NativeUsbCapture_nativeClose(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<UsbCapture *>(handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_llawsxx_uvclivestreaming_recording_NativeUsbCapture_nativeReceivedVideoBytes(JNIEnv *, jobject, jlong handle) {
    auto capture = reinterpret_cast<UsbCapture *>(handle);
    return capture ? static_cast<jlong>(capture->received_video_bytes()) : 0;
}
