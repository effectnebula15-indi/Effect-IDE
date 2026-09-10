/*
 * Проверка кадрового буфера.
 *
 * Половина смысла этого файла — последний тест: два потока, писатель и
 * читатель, и требование, чтобы читателю ни разу не достался кадр, собранный
 * из двух разных. Гоняется под ASan и TSan; без санитайзеров такие ошибки
 * проявляются раз в неделю на чужом телефоне.
 */

#include "eide_canvas.h"

#include <assert.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
/* nanosleep. На Linux он приезжает транзитивно через pthread.h, на macOS — нет. */
#include <time.h>

#define W 64
#define H 48
#define PIXELS ((size_t)W * H)

static void *make_area(size_t *size_out) {
    size_t size = ec_area_size(W, H);
    void *area = calloc(1, size);
    assert(area != NULL);
    assert(ec_init_area(area, size, W, H));
    *size_out = size;
    return area;
}

static void test_colour_byte_order(void) {
    /*
     * Красный — 0xFF0000FF. В памяти первым байтом обязан лежать R.
     * Если положить число как uint32_t на little-endian, первым окажется A,
     * и на экране красное станет синим.
     */
    uint32_t packed = ec_pack_rgba(0xFF0000FFu);
    const unsigned char *bytes = (const unsigned char *)&packed;
    assert(bytes[0] == 0xFF);
    assert(bytes[1] == 0x00);
    assert(bytes[2] == 0x00);
    assert(bytes[3] == 0xFF);

    /* Несимметричный цвет: тождественная упаковка здесь не спрячется. */
    uint32_t teal = ec_pack_rgba(0x11223344u);
    const unsigned char *t = (const unsigned char *)&teal;
    assert(t[0] == 0x11 && t[1] == 0x22 && t[2] == 0x33 && t[3] == 0x44);
}

static void test_colour_reaches_the_reader_in_the_right_order(void) {
    size_t size;
    void *area = make_area(&size);
    ec_ctx *ctx = ec_open_writer(area, size);

    ec_begin_frame(ctx);
    ec_clear(ctx, 0x11223344u);
    ec_end_frame(ctx);

    unsigned char dst[PIXELS * 4];
    assert(ec_read_frame(area, size, dst, sizeof(dst), 0) == 1);
    assert(dst[0] == 0x11 && dst[1] == 0x22 && dst[2] == 0x33 && dst[3] == 0x44);

    ec_close(ctx);
    free(area);
}

static void test_area_size(void) {
    assert(ec_area_size(0, 10) == 0);
    assert(ec_area_size(10, 0) == 0);

    /*
     * Заголовок, три слота пикселей и кольцо событий за ними, выровненное на
     * кэш-линию. Размер кольца из заголовка сюда не видён, поэтому проверяется
     * то, что проверить можно: пиксели на месте, кольцо непустое, выравнивание
     * соблюдено. Сумма целиком повторяла бы реализацию, а не проверяла её.
     */
    size_t pixels_end = 256 + 3 * (size_t)W * H * 4;
    size_t total = ec_area_size(W, H);
    assert(total > pixels_end);
    assert((total - ((pixels_end + 63) & ~(size_t)63)) > sizeof(ec_event) * EC_EVENT_CAPACITY);
}

static void test_too_small_area_is_rejected(void) {
    size_t size = ec_area_size(W, H);
    void *area = calloc(1, size);
    assert(area != NULL);
    /* На байт меньше нужного — область непригодна, и это должно быть сказано. */
    assert(!ec_init_area(area, size - 1, W, H));
    free(area);
}

static void test_garbage_is_not_mistaken_for_a_frame(void) {
    size_t size = ec_area_size(W, H);
    void *area = calloc(1, size);
    assert(area != NULL);
    memset(area, 0xAB, size);

    uint32_t dst[PIXELS];
    assert(ec_read_frame(area, size, dst, sizeof(dst), 0) == 0);
    assert(ec_open_writer(area, size) == NULL);
    free(area);
}

static void test_a_foreign_area_is_refused(void) {
    /*
     * Заголовок в порядке, магия чужая: так выглядит отображение не той
     * области. Молча рисовать в чужую память нельзя.
     */
    size_t size;
    void *area = make_area(&size);
    ((uint32_t *)area)[0] = 0xDEADBEEFu;

    uint32_t dst[PIXELS];
    assert(ec_open_writer(area, size) == NULL);
    assert(ec_read_frame(area, size, dst, sizeof(dst), 0) == 0);
    free(area);
}

static void test_no_frame_before_end(void) {
    size_t size;
    void *area = make_area(&size);
    ec_ctx *ctx = ec_open_writer(area, size);
    assert(ctx != NULL);

    uint32_t dst[PIXELS];
    assert(ec_read_frame(area, size, dst, sizeof(dst), 0) == 0);

    ec_begin_frame(ctx);
    ec_clear(ctx, 0xFF0000FFu);
    /* Кадр не опубликован: читателю его видеть нельзя. */
    assert(ec_read_frame(area, size, dst, sizeof(dst), 0) == 0);

    ec_end_frame(ctx);
    assert(ec_read_frame(area, size, dst, sizeof(dst), 0) == 1);
    assert(dst[0] == ec_pack_rgba(0xFF0000FFu));

    ec_close(ctx);
    free(area);
}

static void test_latest_frame_without_copying(void) {
    size_t size;
    void *area = make_area(&size);
    ec_ctx *ctx = ec_open_writer(area, size);

    /* Ни одного кадра — значит программа ещё ничего не нарисовала. */
    assert(ec_latest_frame(area, size) == 0);

    ec_begin_frame(ctx);
    ec_clear(ctx, 0x11223344u);
    /* Кадр не опубликован — считать его нарисованным нельзя. */
    assert(ec_latest_frame(area, size) == 0);

    ec_end_frame(ctx);
    assert(ec_latest_frame(area, size) == 1);

    for (int i = 0; i < 5; i++) {
        ec_begin_frame(ctx);
        ec_clear(ctx, 0x00000000u);
        ec_end_frame(ctx);
    }
    assert(ec_latest_frame(area, size) == 6);

    ec_close(ctx);
    free(area);
}

static void test_reader_skips_what_it_already_has(void) {
    size_t size;
    void *area = make_area(&size);
    ec_ctx *ctx = ec_open_writer(area, size);

    ec_begin_frame(ctx);
    ec_clear(ctx, 0x11223344u);
    ec_end_frame(ctx);

    uint32_t dst[PIXELS];
    uint64_t frame = ec_read_frame(area, size, dst, sizeof(dst), 0);
    assert(frame == 1);
    /* Нового кадра нет — копировать полтора мегабайта незачем. */
    assert(ec_read_frame(area, size, dst, sizeof(dst), frame) == 0);

    ec_begin_frame(ctx);
    ec_clear(ctx, 0x55667788u);
    ec_end_frame(ctx);
    assert(ec_read_frame(area, size, dst, sizeof(dst), frame) == 2);
    assert(dst[0] == ec_pack_rgba(0x55667788u));

    ec_close(ctx);
    free(area);
}

static void test_newest_frame_wins_after_a_full_round(void) {
    /*
     * Слотов три, счётчики у них растут независимо. После четырёх кадров
     * счётчик первого слота снова равен двум — по счётчикам «самый свежий»
     * не определить, только по номеру кадра.
     */
    size_t size;
    void *area = make_area(&size);
    ec_ctx *ctx = ec_open_writer(area, size);

    for (uint32_t i = 1; i <= 4; i++) {
        ec_begin_frame(ctx);
        ec_clear(ctx, 0x01010101u * i);
        ec_end_frame(ctx);
    }

    uint32_t dst[PIXELS];
    assert(ec_read_frame(area, size, dst, sizeof(dst), 0) == 4);
    assert(dst[0] == ec_pack_rgba(0x04040404u));

    ec_close(ctx);
    free(area);
}

static void test_writer_death_leaves_the_last_frame_readable(void) {
    size_t size;
    void *area = make_area(&size);
    ec_ctx *ctx = ec_open_writer(area, size);

    ec_begin_frame(ctx);
    ec_clear(ctx, 0xAAAAAAAAu);
    ec_end_frame(ctx);

    /* Раннер убит посреди следующего кадра: слот остаётся нечётным навсегда. */
    ec_begin_frame(ctx);
    ec_clear(ctx, 0xBBBBBBBBu);
    ec_close(ctx);

    uint32_t dst[PIXELS];
    assert(ec_read_frame(area, size, dst, sizeof(dst), 0) == 1);
    assert(dst[0] == ec_pack_rgba(0xAAAAAAAAu));

    free(area);
}

static void test_rect_clipping(void) {
    size_t size;
    void *area = make_area(&size);
    ec_ctx *ctx = ec_open_writer(area, size);

    ec_begin_frame(ctx);
    ec_clear(ctx, 0x00000000u);
    ec_fill_rect(ctx, -10, -10, 12, 12, 0xFFFFFFFFu);   /* заезжает за левый верх */
    ec_fill_rect(ctx, W - 2, H - 2, 100, 100, 0x11111111u); /* за правый низ */
    ec_fill_rect(ctx, 1000, 1000, 5, 5, 0x22222222u);   /* целиком снаружи */
    ec_fill_rect(ctx, 5, 5, -3, 4, 0x33333333u);        /* отрицательный размер */
    ec_end_frame(ctx);

    uint32_t dst[PIXELS];
    assert(ec_read_frame(area, size, dst, sizeof(dst), 0) == 1);

    assert(dst[0] == ec_pack_rgba(0xFFFFFFFFu));
    assert(dst[1 * W + 1] == ec_pack_rgba(0xFFFFFFFFu));
    assert(dst[2 * W + 2] == ec_pack_rgba(0x00000000u));
    assert(dst[(H - 1) * W + (W - 1)] == ec_pack_rgba(0x11111111u));
    assert(dst[5 * W + 5] == ec_pack_rgba(0x00000000u));

    /*
     * Главная проверка обрезки: без неё строка не кончается на краю, а
     * перетекает в начало следующей. Прямоугольник шириной сто пикселей от
     * (W-2) залил бы начало последней строки — а он туда не заезжал.
     */
    assert(dst[(H - 1) * W + 0] == ec_pack_rgba(0x00000000u));
    assert(dst[(H - 2) * W + 0] == ec_pack_rgba(0x00000000u));

    /* Зеркальная проверка для левого края: строка не начинается раньше нуля. */
    ec_begin_frame(ctx);
    ec_clear(ctx, 0x00000000u);
    ec_fill_rect(ctx, -3, 4, 5, 1, 0x44444444u);
    ec_end_frame(ctx);
    assert(ec_read_frame(area, size, dst, sizeof(dst), 1) == 2);
    assert(dst[4 * W + 0] == ec_pack_rgba(0x44444444u));
    assert(dst[4 * W + 1] == ec_pack_rgba(0x44444444u));
    assert(dst[4 * W + 2] == ec_pack_rgba(0x00000000u));
    assert(dst[3 * W + (W - 1)] == ec_pack_rgba(0x00000000u));

    ec_close(ctx);
    free(area);
}

static void test_clipping_protects_the_end_of_the_area(void) {
    /*
     * То же самое, но в последнем слоте: там за краем строки уже не соседний
     * слот, а конец выделенной памяти. Ловится ASan — если обрезки нет.
     */
    size_t size;
    void *area = make_area(&size);
    ec_ctx *ctx = ec_open_writer(area, size);

    for (int i = 0; i < 3; i++) {
        ec_begin_frame(ctx);
        ec_clear(ctx, 0x00000000u);
        /* Правый нижний угол плюс сто пикселей во все стороны. */
        ec_fill_rect(ctx, W - 1, H - 1, 100, 100, 0xFFFFFFFFu);
        ec_end_frame(ctx);
    }

    uint32_t dst[PIXELS];
    assert(ec_read_frame(area, size, dst, sizeof(dst), 0) == 3);
    assert(dst[(H - 1) * W + (W - 1)] == ec_pack_rgba(0xFFFFFFFFu));

    ec_close(ctx);
    free(area);
}

/* --- гонка писателя и читателя ------------------------------------------ */

typedef struct {
    void *area;
    size_t size;
    _Atomic int stop;
    _Atomic uint64_t frames_read;
    _Atomic uint64_t torn;
} race_state;

static void *producer(void *arg) {
    race_state *state = (race_state *)arg;
    ec_ctx *ctx = ec_open_writer(state->area, state->size);
    assert(ctx != NULL);

    for (uint32_t i = 1; !atomic_load(&state->stop); i++) {
        ec_begin_frame(ctx);
        /* Кадр одноцветный: любой разнобой в пикселях означает склейку. */
        ec_clear(ctx, 0x01010101u * (i & 0xFFu));
        ec_end_frame(ctx);
    }

    ec_close(ctx);
    return NULL;
}

static void *consumer(void *arg) {
    race_state *state = (race_state *)arg;
    uint32_t *dst = (uint32_t *)malloc(PIXELS * 4);
    assert(dst != NULL);

    uint64_t seen = 0;
    while (!atomic_load(&state->stop)) {
        uint64_t frame = ec_read_frame(state->area, state->size, dst, PIXELS * 4, seen);
        if (frame == 0) continue;
        assert(frame > seen);
        seen = frame;
        atomic_fetch_add(&state->frames_read, 1);

        for (size_t i = 1; i < PIXELS; i++) {
            if (dst[i] != dst[0]) {
                atomic_fetch_add(&state->torn, 1);
                break;
            }
        }
    }

    free(dst);
    return NULL;
}

static void test_no_torn_frames_under_contention(void) {
    race_state state;
    state.area = make_area(&state.size);
    atomic_init(&state.stop, 0);
    atomic_init(&state.frames_read, 0);
    atomic_init(&state.torn, 0);

    pthread_t writer_thread, reader_thread;
    assert(pthread_create(&writer_thread, NULL, producer, &state) == 0);
    assert(pthread_create(&reader_thread, NULL, consumer, &state) == 0);

    /*
     * Останавливаемся по числу прочитанных кадров, а не по времени: под TSan
     * всё идёт на порядок медленнее, и «поработать треть секунды» на загруженной
     * машине означает «не поработать вовсе». Крайний срок оставлен, чтобы
     * зависший тест падал, а не висел в CI до таймаута.
     */
    const uint64_t target = 500;
    const int deadline_ms = 30000;
    struct timespec tick = {0, 5 * 1000 * 1000};
    int waited_ms = 0;
    while (atomic_load(&state.frames_read) < target && waited_ms < deadline_ms) {
        nanosleep(&tick, NULL);
        waited_ms += 5;
    }
    atomic_store(&state.stop, 1);

    pthread_join(writer_thread, NULL);
    pthread_join(reader_thread, NULL);

    uint64_t read = atomic_load(&state.frames_read);
    uint64_t torn = atomic_load(&state.torn);
    printf("  прочитано кадров: %llu, склеенных: %llu, за %d мс\n",
           (unsigned long long)read, (unsigned long long)torn, waited_ms);

    /* Меньше цели — значит истёк крайний срок и проверять было нечего. */
    assert(read >= target);
    assert(torn == 0);

    free(state.area);
}

/* --- события ввода ------------------------------------------------------ */

static ec_event pointer_event(uint32_t type, int32_t x, int32_t y) {
    ec_event event;
    memset(&event, 0, sizeof(event));
    event.type = type;
    event.x = x;
    event.y = y;
    return event;
}

static void test_events_arrive_in_order(void) {
    size_t size;
    void *area = make_area(&size);
    ec_ctx *ctx = ec_open_writer(area, size);
    assert(ctx != NULL);

    for (int32_t i = 0; i < 5; i++) {
        ec_event event = pointer_event(EC_EVENT_POINTER_MOVE, i, i * 2);
        assert(ec_post_event(area, size, &event) == 1);
    }

    for (int32_t i = 0; i < 5; i++) {
        ec_event got;
        assert(ec_poll_event(ctx, &got) == 1);
        assert(got.type == EC_EVENT_POINTER_MOVE);
        assert(got.x == i);
        assert(got.y == i * 2);
    }

    ec_event empty;
    assert(ec_poll_event(ctx, &empty) == 0);

    ec_close(ctx);
    free(area);
}

static void test_a_full_ring_drops_the_new_event(void) {
    /*
     * Терять надо новое, а не старое: выброшенное «нажали» оставит программу
     * с «отпустили» без начала, и палец залипнет навсегда.
     */
    size_t size;
    void *area = make_area(&size);
    ec_ctx *ctx = ec_open_writer(area, size);
    assert(ctx != NULL);

    for (uint32_t i = 0; i < EC_EVENT_CAPACITY; i++) {
        ec_event event = pointer_event(EC_EVENT_POINTER_MOVE, (int32_t)i, 0);
        assert(ec_post_event(area, size, &event) == 1);
    }

    ec_event extra = pointer_event(EC_EVENT_POINTER_UP, 999, 0);
    assert(ec_post_event(area, size, &extra) == 0);
    assert(ec_dropped_events(area, size) == 1);

    /* Первое событие всё ещё на месте — выбросили именно новое. */
    ec_event got;
    assert(ec_poll_event(ctx, &got) == 1);
    assert(got.x == 0);

    ec_close(ctx);
    free(area);
}

static void test_the_ring_wraps_around(void) {
    /* Индекс берётся по маске и растёт без границ: обход кольца обязан работать. */
    size_t size;
    void *area = make_area(&size);
    ec_ctx *ctx = ec_open_writer(area, size);
    assert(ctx != NULL);

    for (int32_t round = 0; round < 10; round++) {
        for (uint32_t i = 0; i < EC_EVENT_CAPACITY; i++) {
            ec_event event = pointer_event(EC_EVENT_POINTER_MOVE, round * 1000 + (int32_t)i, 0);
            assert(ec_post_event(area, size, &event) == 1);
        }
        for (uint32_t i = 0; i < EC_EVENT_CAPACITY; i++) {
            ec_event got;
            assert(ec_poll_event(ctx, &got) == 1);
            assert(got.x == round * 1000 + (int32_t)i);
        }
    }

    ec_close(ctx);
    free(area);
}

static void test_a_fresh_area_has_no_events(void) {
    /*
     * Область переиспользуется между запусками: на Android её создают один раз
     * на приложение. События прошлой программы новой доставаться не должны.
     */
    size_t size;
    void *area = make_area(&size);
    ec_ctx *first = ec_open_writer(area, size);
    ec_event event = pointer_event(EC_EVENT_POINTER_DOWN, 7, 7);
    assert(ec_post_event(area, size, &event) == 1);
    ec_close(first);

    assert(ec_init_area(area, size, W, H));

    ec_ctx *second = ec_open_writer(area, size);
    ec_event got;
    assert(ec_poll_event(second, &got) == 0);
    assert(ec_dropped_events(area, size) == 0);

    ec_close(second);
    free(area);
}

typedef struct {
    void *area;
    size_t size;
    ec_ctx *reader;
    uint32_t to_send;
    _Atomic uint64_t received;
    _Atomic uint64_t out_of_order;
} input_race_state;

static void *input_producer(void *argument) {
    input_race_state *state = (input_race_state *)argument;
    uint32_t sent = 0;
    while (sent < state->to_send) {
        ec_event event = pointer_event(EC_EVENT_POINTER_MOVE, (int32_t)sent, 0);
        if (ec_post_event(state->area, state->size, &event)) sent++;
    }
    return NULL;
}

static void *input_consumer(void *argument) {
    input_race_state *state = (input_race_state *)argument;
    int32_t expected = 0;
    ec_event got;
    while ((uint32_t)expected < state->to_send) {
        if (!ec_poll_event(state->reader, &got)) continue;
        if (got.x != expected) atomic_fetch_add(&state->out_of_order, 1);
        expected++;
        atomic_fetch_add(&state->received, 1);
    }
    return NULL;
}

static void test_events_survive_two_threads(void) {
    /*
     * Кольцо, в отличие от кадров, обходится без исключений для TSan: писатель
     * один, читатель один, и всё общение идёт через два атомарных индекса.
     * Если этот тест начнёт ругаться под TSan — значит порядок памяти выбран
     * неверно, а не «санитайзер не понимает приём».
     */
    input_race_state state;
    state.area = make_area(&state.size);
    state.reader = ec_open_writer(state.area, state.size);
    assert(state.reader != NULL);
    state.to_send = 5000;
    atomic_init(&state.received, 0);
    atomic_init(&state.out_of_order, 0);

    pthread_t writer_thread, reader_thread;
    assert(pthread_create(&writer_thread, NULL, input_producer, &state) == 0);
    assert(pthread_create(&reader_thread, NULL, input_consumer, &state) == 0);
    pthread_join(writer_thread, NULL);
    pthread_join(reader_thread, NULL);

    printf("  событий доставлено: %llu, не по порядку: %llu\n",
           (unsigned long long)atomic_load(&state.received),
           (unsigned long long)atomic_load(&state.out_of_order));

    assert(atomic_load(&state.received) == state.to_send);
    assert(atomic_load(&state.out_of_order) == 0);

    ec_close(state.reader);
    free(state.area);
}

int main(void) {
    test_colour_byte_order();
    test_colour_reaches_the_reader_in_the_right_order();
    test_area_size();
    test_too_small_area_is_rejected();
    test_garbage_is_not_mistaken_for_a_frame();
    test_a_foreign_area_is_refused();
    test_no_frame_before_end();
    test_latest_frame_without_copying();
    test_reader_skips_what_it_already_has();
    test_newest_frame_wins_after_a_full_round();
    test_writer_death_leaves_the_last_frame_readable();
    test_rect_clipping();
    test_clipping_protects_the_end_of_the_area();
    test_no_torn_frames_under_contention();
    test_events_arrive_in_order();
    test_a_full_ring_drops_the_new_event();
    test_the_ring_wraps_around();
    test_a_fresh_area_has_no_events();
    test_events_survive_two_threads();
    printf("eide_canvas: все проверки прошли\n");
    return 0;
}
