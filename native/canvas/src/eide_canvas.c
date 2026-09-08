#include "eide_canvas.h"

#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

/*
 * Раскладка области:
 *
 *   0    ec_area_header      — размеры и формат, пишутся один раз
 *   64   ec_slot_state[3]    — по кэш-линии на слот
 *   256  пиксели, EC_SLOTS x slot_bytes
 *
 * Каждый слот защищён собственным счётчиком (seqlock): нечётное значение
 * означает «в слот пишут», чётное — «слот целый». Читатель берёт счётчик до и
 * после копирования и повторяет попытку, если они разошлись.
 *
 * Почему именно так, а не мьютекс: писателя убивают штатно, кнопкой Stop.
 * Смерть посреди кадра оставляет нечётным ровно один счётчик — остальные слоты
 * остаются читаемыми, и IDE продолжает показывать последний целый кадр вместо
 * того, чтобы висеть.
 */

typedef struct {
    uint32_t magic;
    uint32_t version;
    int32_t width;
    int32_t height;
    uint32_t format;
    uint32_t slots;
    uint32_t slot_bytes;
    uint32_t pixels_offset;
} ec_area_header;

typedef struct {
    _Atomic uint64_t seq;
    /*
     * Номер кадра, лежащего в слоте.
     *
     * Без него «самый свежий слот» не определить: счётчик каждого слота растёт
     * независимо, и после трёх кадров подряд все три равны двум.
     *
     * Атомарный, хотя и защищён seqlock: восьмибайтное выровненное чтение
     * ничего не стоит, а гонка по букве стандарта перестаёт быть гонкой.
     * Пикселям такое не подходит — там полтора мегабайта, и по слову их не
     * скопируешь.
     */
    _Atomic uint64_t frame;
    /* До кэш-линии: счётчики соседних слотов не должны делить одну линию. */
    unsigned char padding[64 - 2 * sizeof(_Atomic uint64_t)];
} ec_slot_state;

struct ec_ctx {
    unsigned char *area;
    ec_area_header *header;
    ec_slot_state *slots;
    unsigned char *pixels;
    uint32_t writing;      /* слот, в который идёт текущий кадр */
    uint64_t frame;        /* номер последнего опубликованного кадра */
};

/*
 * Копия пикселей из слота, который писатель вправе переписывать прямо сейчас.
 *
 * Это настоящая гонка данных, и она намеренная: это классический seqlock —
 * читатель копирует, а потом проверяет счётчик и выбрасывает результат, если
 * писатель успел вмешаться. TSan об этом знать не может и ругается по делу,
 * поэтому ровно это место и только оно исключено из его проверки — атрибутом
 * ниже и подавлением в tsan.supp (memcpy TSan подменяет в своей библиотеке и
 * на атрибут не смотрит, поэтому нужно и то, и другое). Остальной файл
 * проверяется как обычно.
 *
 * Чем платим: по букве C11 это неопределённое поведение. Определённая
 * альтернатива — копировать по слову через relaxed-атомики, но тогда
 * полтора мегабайта кадра стоят миллисекунд вместо десятых долей, и
 * шестьдесят кадров в секунду становятся недостижимы.
 */
#if defined(__has_attribute)
#  if __has_attribute(no_sanitize)
#    define EC_NO_TSAN __attribute__((no_sanitize("thread")))
#  endif
#endif
#ifndef EC_NO_TSAN
#  define EC_NO_TSAN
#endif

EC_NO_TSAN static void copy_slot_pixels(void *dst, const void *src, size_t bytes) {
    memcpy(dst, src, bytes);
}

uint32_t ec_pack_rgba(uint32_t rgba) {
    uint32_t r = (rgba >> 24) & 0xFFu;
    uint32_t g = (rgba >> 16) & 0xFFu;
    uint32_t b = (rgba >> 8) & 0xFFu;
    uint32_t a = rgba & 0xFFu;

    /*
     * Собираем число так, чтобы при записи как uint32_t в памяти оказались
     * байты R, G, B, A. Порядок сборки зависит от порядка байтов машины —
     * поэтому он вычисляется, а не берётся из головы.
     */
#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_BIG_ENDIAN__
    return (r << 24) | (g << 16) | (b << 8) | a;
#else
    return (a << 24) | (b << 16) | (g << 8) | r;
#endif
}

static size_t slot_bytes_of(int32_t width, int32_t height) {
    return (size_t)width * (size_t)height * 4u;
}

size_t ec_area_size(int32_t width, int32_t height) {
    if (width <= 0 || height <= 0) return 0;
    return EC_PIXELS_OFFSET + EC_SLOTS * slot_bytes_of(width, height);
}

int ec_init_area(void *area, size_t size, int32_t width, int32_t height) {
    size_t needed = ec_area_size(width, height);
    if (area == NULL || needed == 0 || size < needed) return 0;

    memset(area, 0, EC_PIXELS_OFFSET);

    ec_area_header *header = (ec_area_header *)area;
    header->version = EC_VERSION;
    header->width = width;
    header->height = height;
    header->format = EC_FORMAT_RGBA8888;
    header->slots = EC_SLOTS;
    header->slot_bytes = (uint32_t)slot_bytes_of(width, height);
    header->pixels_offset = EC_PIXELS_OFFSET;

    /* Магия пишется последней: до неё область считается непригодной. */
    atomic_thread_fence(memory_order_release);
    header->magic = EC_MAGIC;
    return 1;
}

/** Общая проверка заголовка. Возвращает NULL, если области верить нельзя. */
static ec_area_header *validate(void *area, size_t size) {
    if (area == NULL || size < EC_PIXELS_OFFSET) return NULL;

    ec_area_header *header = (ec_area_header *)area;
    if (header->magic != EC_MAGIC || header->version != EC_VERSION) return NULL;
    if (header->slots != EC_SLOTS || header->format != EC_FORMAT_RGBA8888) return NULL;
    if (header->pixels_offset != EC_PIXELS_OFFSET) return NULL;
    if (header->width <= 0 || header->height <= 0) return NULL;

    size_t expected = slot_bytes_of(header->width, header->height);
    if (header->slot_bytes != expected) return NULL;
    if (size < ec_area_size(header->width, header->height)) return NULL;

    return header;
}

ec_ctx *ec_open_writer(void *area, size_t size) {
    ec_area_header *header = validate(area, size);
    if (header == NULL) return NULL;

    ec_ctx *ctx = (ec_ctx *)calloc(1, sizeof(ec_ctx));
    if (ctx == NULL) return NULL;

    ctx->area = (unsigned char *)area;
    ctx->header = header;
    ctx->slots = (ec_slot_state *)(ctx->area + 64);
    ctx->pixels = ctx->area + EC_PIXELS_OFFSET;
    ctx->writing = 0;
    ctx->frame = 0;
    return ctx;
}

void ec_close(ec_ctx *ctx) {
    free(ctx);
}

int32_t ec_width(const ec_ctx *ctx) { return ctx ? ctx->header->width : 0; }
int32_t ec_height(const ec_ctx *ctx) { return ctx ? ctx->header->height : 0; }
uint64_t ec_frame_number(const ec_ctx *ctx) { return ctx ? ctx->frame : 0; }

static unsigned char *slot_pixels(ec_ctx *ctx, uint32_t slot) {
    return ctx->pixels + (size_t)slot * ctx->header->slot_bytes;
}

void ec_begin_frame(ec_ctx *ctx) {
    if (ctx == NULL) return;
    /* Нечётный счётчик = «в слот пишут». Читатель такой слот пропустит. */
    atomic_fetch_add_explicit(&ctx->slots[ctx->writing].seq, 1, memory_order_relaxed);
    atomic_thread_fence(memory_order_release);
    atomic_store_explicit(&ctx->slots[ctx->writing].frame, ctx->frame + 1, memory_order_relaxed);
}

void ec_end_frame(ec_ctx *ctx) {
    if (ctx == NULL) return;

    /* Пиксели обязаны стать видимыми раньше, чем чётный счётчик. */
    atomic_thread_fence(memory_order_release);
    atomic_fetch_add_explicit(&ctx->slots[ctx->writing].seq, 1, memory_order_relaxed);

    ctx->frame++;
    /* По кругу: следующий кадр — в следующий слот, читателю остаются два целых. */
    ctx->writing = (ctx->writing + 1) % EC_SLOTS;
}

void ec_clear(ec_ctx *ctx, uint32_t rgba) {
    if (ctx == NULL) return;
    ec_fill_rect(ctx, 0, 0, ctx->header->width, ctx->header->height, rgba);
}

void ec_fill_rect(ec_ctx *ctx, int32_t x, int32_t y, int32_t w, int32_t h, uint32_t rgba) {
    if (ctx == NULL || w <= 0 || h <= 0) return;

    int32_t width = ctx->header->width;
    int32_t height = ctx->header->height;

    /*
     * Обрезка, а не проверка аргументов: координаты приходят из
     * пользовательского кода на Python, и уехавший за край прямоугольник —
     * обычное дело, а не ошибка, за которую надо ронять процесс.
     */
    int32_t x0 = x < 0 ? 0 : x;
    int32_t y0 = y < 0 ? 0 : y;
    int64_t x1 = (int64_t)x + w;
    int64_t y1 = (int64_t)y + h;
    if (x1 > width) x1 = width;
    if (y1 > height) y1 = height;
    if (x0 >= x1 || y0 >= y1) return;

    uint32_t packed = ec_pack_rgba(rgba);
    uint32_t *pixels = (uint32_t *)slot_pixels(ctx, ctx->writing);
    for (int32_t row = y0; row < (int32_t)y1; row++) {
        uint32_t *line = pixels + (size_t)row * (size_t)width;
        for (int32_t col = x0; col < (int32_t)x1; col++) {
            line[col] = packed;
        }
    }
}

uint64_t ec_latest_frame(void *area, size_t size) {
    ec_area_header *header = validate(area, size);
    if (header == NULL) return 0;

    ec_slot_state *slots = (ec_slot_state *)((unsigned char *)area + 64);
    uint64_t newest = 0;

    for (uint32_t slot = 0; slot < EC_SLOTS; slot++) {
        uint64_t before = atomic_load_explicit(&slots[slot].seq, memory_order_acquire);
        if (before == 0 || (before & 1u) != 0) continue;

        uint64_t frame = atomic_load_explicit(&slots[slot].frame, memory_order_relaxed);
        if (atomic_load_explicit(&slots[slot].seq, memory_order_relaxed) != before) continue;
        if (frame > newest) newest = frame;
    }

    return newest;
}

uint64_t ec_read_frame(void *area, size_t size, void *dst, size_t dst_size, uint64_t since) {
    ec_area_header *header = validate(area, size);
    if (header == NULL || dst == NULL || dst_size < header->slot_bytes) return 0;

    unsigned char *bytes = (unsigned char *)area;
    ec_slot_state *slots = (ec_slot_state *)(bytes + 64);
    unsigned char *pixels = bytes + EC_PIXELS_OFFSET;

    /*
     * Попыток немного и они конечны намеренно: читатель работает в такт экрана
     * и обязан вернуться к нему вовремя. Пропущенный кадр — мелочь, зависший
     * поток отрисовки — нет. Практически повтор нужен, только если писатель
     * взялся ровно за тот слот, который мы копируем; следующий раз он придёт к
     * нему через три кадра.
     */
    for (int attempt = 0; attempt < 4; attempt++) {
        uint32_t chosen = EC_SLOTS;
        uint64_t chosen_frame = since;
        uint64_t chosen_seq = 0;

        for (uint32_t slot = 0; slot < EC_SLOTS; slot++) {
            uint64_t before = atomic_load_explicit(&slots[slot].seq, memory_order_acquire);
            if (before == 0 || (before & 1u) != 0) continue;

            uint64_t frame = atomic_load_explicit(&slots[slot].frame, memory_order_relaxed);
            /* Номер прочитан не под замком — проверяем, что счётчик не сдвинулся. */
            if (atomic_load_explicit(&slots[slot].seq, memory_order_relaxed) != before) continue;
            if (frame <= chosen_frame) continue;

            chosen = slot;
            chosen_frame = frame;
            chosen_seq = before;
        }

        /* Ничего нового: копировать полтора мегабайта незачем. */
        if (chosen == EC_SLOTS) return 0;

        copy_slot_pixels(dst, pixels + (size_t)chosen * header->slot_bytes, header->slot_bytes);

        atomic_thread_fence(memory_order_acquire);
        if (atomic_load_explicit(&slots[chosen].seq, memory_order_relaxed) == chosen_seq) {
            return chosen_frame;
        }
    }

    return 0;
}
