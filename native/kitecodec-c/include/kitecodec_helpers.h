/* GENERATED FILE. Do not edit.
 *
 * Extracted from kitecodec-core/src/nativeInterop/cinterop/ffmpeg.def by native/kitecodec-c/tools/extract_from_def.py.
 * scripts/verify-lift.sh re-runs the generator against a git revision of the def and
 * compares the result with this file, so a hand edit fails the gate.
 *
 * Declarations for the exported FFmpeg helper layer. */

#ifndef KITECODEC_HELPERS_H
#define KITECODEC_HELPERS_H

#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavfilter/avfilter.h>
#include <libavfilter/buffersrc.h>
#include <libavfilter/buffersink.h>
#include <libavutil/avutil.h>
#include <libavutil/channel_layout.h>
#include <libavutil/dict.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/imgutils.h>
#include <libavutil/opt.h>
#include <libavutil/display.h>
#include <libavutil/pixdesc.h>
#include <libavutil/samplefmt.h>
#include <libswscale/swscale.h>

/* KC_API marks the helpers the Kotlin side imports as deliberately exported.
 *
 * The archive is compiled with -fvisibility=hidden, which governs the DYNAMIC symbol table
 * and not static linking: an unmarked helper still resolves inside the link that embeds the
 * archive. So this macro is not what makes the cinterop work; it is what makes the exported
 * set a decision rather than an accident, and scripts/symbol-audit.sh checks the decision.
 * The four trailing-underscore helpers are `static` and never carry it.
 */
#if defined(_WIN32)
#define KC_API __declspec(dllexport)
#else
#define KC_API __attribute__((visibility("default")))
#endif

/* Errors & macros */

/* Thread affinity, register item B1-09. The returned pointer is into
 * `static __thread char buf[256]` at def line 37, which is the only static storage in
 * the whole helper layer. Two consequences, and both are contract rather than accident:
 * the storage is per thread, so a pointer must never be shared between threads; and the
 * next ffkmp_strerror call on the same thread overwrites it, so the string must be
 * copied or consumed before calling again. It must never be stored.
 * Proved by tests/test_strerror_thread.c.
 */
KC_API const char* ffkmp_strerror(int errnum);
KC_API int ffkmp_averror_eagain(void);
KC_API int ffkmp_averror_eof(void);
KC_API int64_t ffkmp_rescale_q(int64_t v, int sn, int sd, int dn, int dd);

/* AVFrame */

/* Ownership. Returns a new AVFrame the caller owns, or NULL when allocation fails.
 * Release it with ffkmp_frame_free and never with free.
 */
KC_API AVFrame* ffkmp_frame_alloc(void);

/* Ownership. Frees the frame and drops every reference it holds. The pointer arrives by
 * value, so the caller's own variable is not cleared and must be cleared by the caller.
 * A NULL frame is accepted and does nothing.
 */
KC_API void     ffkmp_frame_free(AVFrame *f);

/* Ownership. Drops the frame's data references and resets its fields. The AVFrame itself
 * stays allocated and stays the caller's. A NULL frame is accepted and does nothing.
 */
KC_API void     ffkmp_frame_unref(AVFrame *f);
KC_API int64_t  ffkmp_frame_pts(AVFrame *f);
KC_API int64_t  ffkmp_frame_duration(AVFrame *f);
KC_API int      ffkmp_frame_format(AVFrame *f);
KC_API int      ffkmp_frame_width(AVFrame *f);
KC_API int      ffkmp_frame_height(AVFrame *f);
KC_API int      ffkmp_frame_nb_samples(AVFrame *f);
KC_API int      ffkmp_frame_sample_rate(AVFrame *f);
KC_API int      ffkmp_frame_channels(AVFrame *f);
KC_API int      ffkmp_frame_linesize(AVFrame *f, int p);
KC_API void     ffkmp_frame_set_pts(AVFrame *f, int64_t pts);
KC_API void     ffkmp_frame_set_format(AVFrame *f, int v);
KC_API void     ffkmp_frame_set_width(AVFrame *f, int v);
KC_API void     ffkmp_frame_set_height(AVFrame *f, int v);
KC_API void     ffkmp_frame_set_sample_rate(AVFrame *f, int v);
KC_API void     ffkmp_frame_set_nb_samples(AVFrame *f, int v);

/* Ownership. Allocates data buffers from the frame's width, height, format and, for audio,
 * nb_samples and ch_layout, all of which must be set first. The frame owns the buffers and
 * ffkmp_frame_unref or ffkmp_frame_free releases them.
 */
KC_API int      ffkmp_frame_get_buffer(AVFrame *f, int align);

/* Ownership. Uninitialises the frame's existing channel layout before writing the default
 * for `ch`, so calling it repeatedly does not leak a layout allocation. The frame keeps
 * ownership of the result.
 */
KC_API void     ffkmp_frame_set_ch_layout_default(AVFrame *f, int ch);
KC_API void     ffkmp_frame_use_best_effort_ts(AVFrame *f);

/* Ownership. Returns a new caller-owned AVFrame, or NULL. The data is shared with the
 * source through a new reference and is not copied. Release it with ffkmp_frame_free.
 */
KC_API AVFrame* ffkmp_frame_clone(const AVFrame *f);

/* Ownership. Returns a new caller-owned AVFrame with its own buffers, or NULL. Release it
 * with ffkmp_frame_free. The SwsContext is allocated and freed inside this call on every
 * path, including every failure path, so nothing about it reaches the caller. That per call
 * cost is register item B1-23: it is the current behaviour on purpose, and
 * tests/test_convert.c records the measured numbers as the baseline B2's caching must beat.
 */
KC_API AVFrame* ffkmp_frame_convert_pixfmt(const AVFrame *src, int dst_fmt);
KC_API int ffkmp_image_get_buffer_size(int fmt, int w, int h, int align);
KC_API int ffkmp_frame_copy_to_buffer(AVFrame *f, uint8_t *dst, int dst_size);
KC_API int ffkmp_samples_get_buffer_size(AVFrame *f);
KC_API int ffkmp_samples_copy_to_buffer(AVFrame *f, uint8_t *dst, int dst_size);
KC_API int ffkmp_frame_fill_video(AVFrame *f, const uint8_t *src, int src_size);
KC_API int ffkmp_frame_fill_audio(AVFrame *f, const uint8_t *src, int src_size);

/* Pixel/sample format names */
KC_API const char* ffkmp_pix_fmt_name(int fmt);
KC_API int         ffkmp_pix_fmt_from_name(const char *n);
KC_API const char* ffkmp_sample_fmt_name(int fmt);
KC_API int         ffkmp_sample_fmt_from_name(const char *n);

/* AVDictionary iteration */
KC_API AVDictionaryEntry* ffkmp_dict_get(AVDictionary *d, AVDictionaryEntry *prev);
KC_API const char* ffkmp_dict_entry_key(AVDictionaryEntry *e);
KC_API const char* ffkmp_dict_entry_value(AVDictionaryEntry *e);

/* AVPacket */

/* Ownership. Returns a new AVPacket the caller owns, or NULL when allocation fails.
 * Release it with ffkmp_packet_free and never with free.
 */
KC_API AVPacket* ffkmp_packet_alloc(void);

/* Ownership. Frees the packet and drops every reference it holds. The pointer arrives by
 * value, so the caller's own variable is not cleared and must be cleared by the caller.
 * A NULL packet is accepted and does nothing.
 */
KC_API void      ffkmp_packet_free(AVPacket *p);

/* Ownership. Drops the packet's data reference and resets its fields. The AVPacket itself
 * stays allocated and stays the caller's. A NULL packet is accepted and does nothing.
 */
KC_API void      ffkmp_packet_unref(AVPacket *p);
KC_API int64_t   ffkmp_packet_pts(AVPacket *p);
KC_API int64_t   ffkmp_packet_dts(AVPacket *p);
KC_API int       ffkmp_packet_stream_index(AVPacket *p);
KC_API int       ffkmp_packet_size(AVPacket *p);
KC_API uint8_t*  ffkmp_packet_data(AVPacket *p);
KC_API int64_t   ffkmp_packet_duration(AVPacket *p);
KC_API int       ffkmp_packet_is_keyframe(AVPacket *p);
KC_API void      ffkmp_packet_set_stream_index(AVPacket *p, int i);
KC_API void      ffkmp_packet_set_pts(AVPacket *p, int64_t v);
KC_API void      ffkmp_packet_set_dts(AVPacket *p, int64_t v);
KC_API void      ffkmp_packet_rescale_ts(AVPacket *p, int sn, int sd, int dn, int dd);

/* AVCodecParameters */
KC_API int     ffkmp_codecpar_codec_type(AVCodecParameters *p);
KC_API int     ffkmp_codecpar_codec_id(AVCodecParameters *p);
KC_API int64_t ffkmp_codecpar_bit_rate(AVCodecParameters *p);
KC_API int     ffkmp_codecpar_width(AVCodecParameters *p);
KC_API int     ffkmp_codecpar_height(AVCodecParameters *p);
KC_API int     ffkmp_codecpar_format(AVCodecParameters *p);
KC_API int     ffkmp_codecpar_sample_rate(AVCodecParameters *p);
KC_API int     ffkmp_codecpar_channels(AVCodecParameters *p);
KC_API void    ffkmp_codecpar_sample_aspect_ratio(AVCodecParameters *p, int *num, int *den);

/* Ownership. Fills the parameters from the context, freeing and replacing any extradata the
 * parameters already held. The parameters stay owned by whoever holds them.
 */
KC_API int ffkmp_codecpar_from_context(AVCodecParameters *par, AVCodecContext *ctx);

/* Ownership. Replaces the destination's contents with a copy of the source, freeing what the
 * destination held, then clears codec_tag so the muxer picks its own. The destination stays
 * owned by its stream.
 */
KC_API int ffkmp_codecpar_copy_for_mux(AVCodecParameters *dst, const AVCodecParameters *src);

/* AVCodec / AVCodecContext */

/* Ownership. Returns a new AVCodecContext the caller owns, or NULL. Release it with
 * ffkmp_codecctx_free whether or not it was ever opened.
 */
KC_API AVCodecContext* ffkmp_codecctx_alloc(const AVCodec *c);

/* Ownership. Frees the context together with everything it holds, including buffered
 * frames and, when it was opened, the codec's private state. The pointer arrives by value,
 * so the caller's own variable is not cleared.
 */
KC_API void  ffkmp_codecctx_free(AVCodecContext *c);

/* Ownership. Allocates the codec's internal state onto the context. Failure leaves nothing
 * extra to undo, because ffkmp_codecctx_free releases the context either way. Never call
 * it twice on one context.
 */
KC_API int   ffkmp_codecctx_open(AVCodecContext *c, const AVCodec *codec);

/* Ownership. Copies the parameters into the context, taking a private copy of extradata.
 * The parameters stay owned by whoever holds them, normally an AVStream.
 */
KC_API int   ffkmp_codecctx_from_par(AVCodecContext *c, AVCodecParameters *p);
KC_API void  ffkmp_codecctx_set_video(
    AVCodecContext *c, int width, int height, int pix_fmt,
    int fr_num, int fr_den, int tb_num, int tb_den, int64_t bit_rate, int gop_size
);

/* Ownership. Uninitialises the context's existing channel layout before writing the default
 * for `channels`, so calling it repeatedly does not leak a layout allocation.
 */
KC_API void  ffkmp_codecctx_set_audio(
    AVCodecContext *c, int sample_rate, int sample_fmt, int channels, int64_t bit_rate
);
KC_API int   ffkmp_codec_first_sample_fmt(const AVCodec *codec);
KC_API int ffkmp_codec_first_pix_fmt(const AVCodec *codec);
KC_API int ffkmp_codec_supports_pix_fmt(const AVCodec *codec, int fmt);
KC_API int   ffkmp_codecctx_frame_size(AVCodecContext *c);
KC_API int   ffkmp_codecctx_sample_rate(AVCodecContext *c);
KC_API int   ffkmp_codecctx_channels(AVCodecContext *c);
KC_API void  ffkmp_codecctx_time_base(AVCodecContext *c, int *n, int *d);
KC_API void  ffkmp_codecctx_set_global_header(AVCodecContext *c);

/* Ownership. The option system copies key and value, so neither string is retained and both
 * may be freed immediately. A NULL context or key is refused with AVERROR(EINVAL).
 */
KC_API int   ffkmp_codecctx_set_opt(AVCodecContext *c, const char *key, const char *value);
KC_API void  ffkmp_codecctx_set_full_range(AVCodecContext *c);
KC_API const AVCodec* ffkmp_find_decoder_by_id(int id);
KC_API const char* ffkmp_codec_id_name(int id);
KC_API int ffkmp_codecctx_pix_fmt(AVCodecContext *c);
KC_API int ffkmp_codecctx_width(AVCodecContext *c);
KC_API int ffkmp_codecctx_height(AVCodecContext *c);

/* AVFormatContext (input + output) */

/* Ownership. On success *out is a new AVFormatContext the caller owns and must release with
 * ffkmp_fmt_close_input, never with ffkmp_fmt_free_output. On failure *out is set to NULL
 * and nothing is left allocated.
 */
KC_API int  ffkmp_fmt_open_input(AVFormatContext **out, const char *path);

/* Ownership. Closes the demuxer, frees the context with every stream in it, and writes NULL
 * through ctx. Safe on a pointer that is already NULL. It must not be used on a context
 * from ffkmp_fmt_alloc_output2.
 */
KC_API void ffkmp_fmt_close_input(AVFormatContext **ctx);

/* Ownership. Allocates per stream parsing state, and may probe and buffer packets. All of
 * it belongs to the context and is released when the context is closed. Nothing becomes
 * the caller's.
 */
KC_API int  ffkmp_fmt_find_stream_info(AVFormatContext *c);
KC_API int  ffkmp_fmt_seek_micros(AVFormatContext *ctx, int stream_index, int64_t micros);

/* Ownership. On success the packet holds a new reference the caller owns. The packet must be
 * blank on entry, and must be unreferenced before it is filled again, or the reference
 * leaks. On failure the packet is left blank.
 */
KC_API int  ffkmp_fmt_read_frame(AVFormatContext *c, AVPacket *p);
KC_API int64_t       ffkmp_fmt_duration(AVFormatContext *c);
KC_API int64_t       ffkmp_fmt_start_time(AVFormatContext *c);
KC_API unsigned      ffkmp_fmt_nb_streams(AVFormatContext *c);
KC_API AVStream*     ffkmp_fmt_stream(AVFormatContext *c, unsigned i);
KC_API const char*   ffkmp_fmt_iformat_name(AVFormatContext *c);
KC_API AVDictionary* ffkmp_fmt_metadata(AVFormatContext *c);

/* Ownership. On success *out is a new muxer context the caller owns and must release with
 * ffkmp_fmt_free_output, never with ffkmp_fmt_close_input. On failure *out is set to NULL.
 */
KC_API int  ffkmp_fmt_alloc_output2(AVFormatContext **out, const char *path, const char *format);

/* Ownership. The option system copies key and value, so neither string is retained and both
 * may be freed immediately. A NULL context is refused with AVERROR(EINVAL).
 */
KC_API int  ffkmp_fmt_set_opt(AVFormatContext *c, const char *k, const char *v);

/* Ownership. Closes ctx->pb when the format uses a file and pb is open, then frees the
 * context with every stream in it, then writes NULL through ctx. Safe on a pointer that is
 * already NULL. It must not be used on a context from ffkmp_fmt_open_input.
 */
KC_API void ffkmp_fmt_free_output(AVFormatContext **ctx);

/* Ownership. The returned AVStream belongs to the format context and not to the caller.
 * There is no per stream free, so the pairing rule is different from every other allocating
 * helper here: ffkmp_fmt_free_output releases every stream the context holds. Never free the
 * result, and never use it after the context is gone.
 */
KC_API AVStream* ffkmp_fmt_new_stream(AVFormatContext *ctx, const AVCodec *codec);

/* Ownership. Opens ctx->pb, which the context then holds. It is a no op, returning 0, for a
 * format carrying AVFMT_NOFILE. There is no separate close: ffkmp_fmt_free_output closes pb
 * exactly when this call opened it, so the pairing is with that free.
 */
KC_API int ffkmp_fmt_io_open(AVFormatContext *ctx, const char *path);
KC_API void ffkmp_fmt_avoid_negative_ts(AVFormatContext *ctx);

/* Ownership. Allocates muxer private state onto the context, released when the context is
 * freed. Once it has succeeded, write the trailer before freeing the context.
 */
KC_API int ffkmp_fmt_write_header(AVFormatContext *ctx);

/* Ownership. Takes over the packet's reference. On success and on failure alike the packet
 * is blank afterwards and must not be unreferenced again. A NULL packet flushes the
 * interleaving queue.
 */
KC_API int ffkmp_fmt_write_frame(AVFormatContext *ctx, AVPacket *p);

/* Ownership. Flushes and releases the packets the muxer had buffered. It does not free the
 * context, so ffkmp_fmt_free_output is still required.
 */
KC_API int ffkmp_fmt_write_trailer(AVFormatContext *ctx);
KC_API int ffkmp_oformat_global_header(AVFormatContext *c);

/* Ownership. Copies key and value into the context's metadata dictionary, which the context
 * owns and its free releases. Neither string is retained.
 */
KC_API int ffkmp_fmt_set_metadata(AVFormatContext *c, const char *key, const char *value);

/* AVStream */
KC_API int                  ffkmp_stream_index(AVStream *s);
KC_API AVCodecParameters*   ffkmp_stream_codecpar(AVStream *s);
KC_API int64_t              ffkmp_stream_duration_micros(AVStream *s);
KC_API int64_t              ffkmp_stream_start_time(AVStream *s);
KC_API AVDictionary*        ffkmp_stream_metadata(AVStream *s);
KC_API void ffkmp_stream_time_base(AVStream *s, int *n, int *d);
KC_API void ffkmp_stream_avg_frame_rate(AVStream *s, int *n, int *d);
KC_API void ffkmp_stream_set_time_base(AVStream *s, int n, int d);

/* Filter graphs (single-input video / audio) */

/* Ownership. On success the caller owns the graph through *out_graph and releases it with
 * ffkmp_graph_free; the two filter contexts belong to the graph and must never be freed
 * separately. On every failure path the graph is freed inside the call and all three out
 * parameters are left NULL.
 */
KC_API int ffkmp_graph_build_video(
    AVFilterGraph **out_graph, AVFilterContext **out_src, AVFilterContext **out_sink,
    const char *description,
    int width, int height, int pix_fmt,
    int tb_num, int tb_den, int fr_num, int fr_den, int sar_num, int sar_den
);

/* Ownership. On success the caller owns the graph through *out_graph and releases it with
 * ffkmp_graph_free; the two filter contexts belong to the graph and must never be freed
 * separately. On every failure path the graph is freed inside the call and all three out
 * parameters are left NULL.
 */
KC_API int ffkmp_graph_build_audio(
    AVFilterGraph **out_graph, AVFilterContext **out_src, AVFilterContext **out_sink,
    const char *description,
    int sample_rate, int sample_fmt, int channels,
    int tb_num, int tb_den,
    /* Pin the graph's output so frames arrive encoder-ready. Pass -1/-1/0 to leave free.
       Implemented by appending an `aformat` filter rather than buffersink options, because the
       option names were renamed across FFmpeg 7→8, the filter-string syntax never changes. */
    int out_sample_fmt, int out_sample_rate, int out_channels
);

/* Ownership. On success the caller owns the graph through *out_graph and releases it with
 * ffkmp_graph_free; the sink and the n source contexts belong to the graph and must never be
 * freed separately. On failure the graph is freed inside the call and *out_graph and
 * *out_sink are NULL, but out_srcs is NOT cleared and its filled entries point into the
 * freed graph. Read out_srcs only when the call returned 0.
 */
KC_API int ffkmp_graph_build_video_multi(
    AVFilterGraph **out_graph, AVFilterContext **out_srcs, AVFilterContext **out_sink,
    const char *description, int n,
    const int *widths, const int *heights, const int *pix_fmts,
    const int *tb_nums, const int *tb_dens,
    const int *fr_nums, const int *fr_dens,
    const int *sar_nums, const int *sar_dens
);

/* Ownership. On success the caller owns the graph through *out_graph and releases it with
 * ffkmp_graph_free; the sink and the n source contexts belong to the graph and must never be
 * freed separately. On failure the graph is freed inside the call and *out_graph and
 * *out_sink are NULL, but out_srcs is NOT cleared and its filled entries point into the
 * freed graph. Read out_srcs only when the call returned 0.
 */
KC_API int ffkmp_graph_build_audio_multi(
    AVFilterGraph **out_graph, AVFilterContext **out_srcs, AVFilterContext **out_sink,
    const char *description, int n,
    const int *sample_rates, const int *sample_fmts, const int *channels,
    const int *tb_nums, const int *tb_dens,
    int out_sample_fmt, int out_sample_rate, int out_channels
);

/* Ownership. Frees the graph together with every filter context in it, and writes NULL
 * through g. Every AVFilterContext a builder handed out dangles afterwards. Safe on a
 * pointer that is already NULL.
 */
KC_API void ffkmp_graph_free(AVFilterGraph **g);

/* Ownership. Sends the frame with AV_BUFFERSRC_FLAG_KEEP_REF, so the graph takes its own
 * reference and the caller keeps and must still release the frame it passed in. Without
 * that flag the frame would be consumed, which is why the flag is part of the contract.
 */
KC_API int  ffkmp_graph_send(AVFilterContext *src, AVFrame *frame);

/* Ownership. On success the frame holds a new reference the caller owns. The frame must be
 * blank on entry and must be unreferenced before it is filled again. AVERROR(EAGAIN) and
 * AVERROR_EOF leave it blank and are not failures.
 */
KC_API int  ffkmp_graph_receive(AVFilterContext *sink, AVFrame *frame);
KC_API void ffkmp_buffersink_set_frame_size(AVFilterContext *sink, unsigned n);
KC_API void ffkmp_buffersink_time_base(AVFilterContext *sink, int *n, int *d);

/* Playback additions */
KC_API void ffkmp_codecctx_flush(AVCodecContext *c);
KC_API void ffkmp_codecctx_set_threads(AVCodecContext *c, int count, int frame_level);
KC_API void ffkmp_codecctx_set_low_delay(AVCodecContext *c, int on);
KC_API int ffkmp_avseek_flag_backward(void);
KC_API int ffkmp_avseek_flag_any(void);
KC_API int ffkmp_fmt_seek_file(AVFormatContext *ctx, int stream_index,
                                     int64_t min_ts, int64_t ts, int64_t max_ts, int flags);
KC_API int ffkmp_fmt_is_seekable(AVFormatContext *c);
KC_API void ffkmp_stream_discard_all(AVStream *s);
KC_API void ffkmp_stream_discard_none(AVStream *s);
KC_API int ffkmp_stream_disposition(AVStream *s);
KC_API int ffkmp_disposition_default(void);
KC_API int ffkmp_disposition_forced(void);
KC_API int ffkmp_disposition_hearing_impaired(void);
KC_API int ffkmp_disposition_visual_impaired(void);
KC_API int ffkmp_disposition_attached_pic(void);
KC_API int ffkmp_stream_rotation_degrees(AVStream *s);

/* Ownership. Moves every reference from src to dst and leaves src blank, so exactly one of
 * the two owns the data afterwards. dst must be blank on entry. Neither packet is freed,
 * and a NULL on either side makes the call do nothing.
 */
KC_API void ffkmp_packet_move_ref(AVPacket *dst, AVPacket *src);
KC_API int64_t ffkmp_packet_pos(AVPacket *p);
KC_API int ffkmp_frame_color_range(AVFrame *f);
KC_API int ffkmp_frame_colorspace(AVFrame *f);
KC_API int ffkmp_frame_color_primaries(AVFrame *f);
KC_API int ffkmp_frame_color_trc(AVFrame *f);
KC_API int ffkmp_frame_chroma_location(AVFrame *f);
KC_API int ffkmp_frame_is_keyframe(AVFrame *f);
KC_API void ffkmp_frame_sample_aspect_ratio(AVFrame *f, int *n, int *d);
KC_API int64_t ffkmp_frame_ch_layout_mask(AVFrame *f);
KC_API int64_t ffkmp_codecpar_ch_layout_mask(AVCodecParameters *p);
KC_API uint8_t* ffkmp_frame_plane(AVFrame *f, int p);
KC_API int ffkmp_frame_plane_count(AVFrame *f);
KC_API int ffkmp_frame_plane_height(AVFrame *f, int p);
KC_API void* ffkmp_frame_hw_surface(AVFrame *f);
KC_API int ffkmp_frame_is_hardware(AVFrame *f);

#endif /* KITECODEC_HELPERS_H */
