"""Проверка питоновского шима графики на десктопе.

`eide.py` уезжает в APK, но ctypes не знает, что он на Android: та же
библиотека собирается на хосте, и весь шим проверяется обычным запуском.
Это единственный способ поймать здесь опечатку в сигнатуре или в порядке
аргументов — на телефоне она выглядит как «просто не рисует».

Путь к библиотеке берётся из EIDE_CANVAS_LIB — тем же способом, что и в самом
шиме. Имя файла на разных системах разное (.so, .dylib), и полагаться на поиск
по имени значит проверять на macOS не то же, что на Linux.
"""

import ctypes
import os
import sys
import time

CANVAS_W = 64
CANVAS_H = 48


def load_library():
    lib = ctypes.CDLL(os.environ.get("EIDE_CANVAS_LIB", "libeide_canvas.so"))
    lib.ec_area_size.argtypes = [ctypes.c_int32, ctypes.c_int32]
    lib.ec_area_size.restype = ctypes.c_size_t
    lib.ec_init_area.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_int32, ctypes.c_int32]
    lib.ec_init_area.restype = ctypes.c_int
    lib.ec_read_frame.argtypes = [
        ctypes.c_void_p, ctypes.c_size_t, ctypes.c_void_p, ctypes.c_size_t, ctypes.c_uint64
    ]
    lib.ec_read_frame.restype = ctypes.c_uint64
    return lib


def make_area(lib):
    size = lib.ec_area_size(CANVAS_W, CANVAS_H)
    assert size > 0
    area = ctypes.create_string_buffer(size)
    assert lib.ec_init_area(ctypes.cast(area, ctypes.c_void_p), size, CANVAS_W, CANVAS_H)
    return area, size


def read_frame(lib, area, size, since=0):
    pixels = ctypes.create_string_buffer(CANVAS_W * CANVAS_H * 4)
    frame = lib.ec_read_frame(
        ctypes.cast(area, ctypes.c_void_p), size,
        ctypes.cast(pixels, ctypes.c_void_p), len(pixels), since,
    )
    return frame, pixels.raw


def pixel(raw, x, y):
    offset = (y * CANVAS_W + x) * 4
    return tuple(raw[offset:offset + 4])


def main():
    sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "python"))

    lib = load_library()
    area, size = make_area(lib)

    # --- цвет ---------------------------------------------------------------
    import eide

    assert eide.rgb(255, 0, 0) == 0xFF0000FF
    assert eide.rgb(0x11, 0x22, 0x33, 0x44) == 0x11223344
    try:
        eide.rgb(256, 0, 0)
        raise AssertionError("канал вне диапазона должен ругаться")
    except ValueError:
        pass

    # --- без окружения графики нет -----------------------------------------
    assert not eide.available()
    try:
        eide.canvas()
        raise AssertionError("канва без окружения должна ругаться")
    except RuntimeError:
        pass

    # --- подключение --------------------------------------------------------
    os.environ["EIDE_CANVAS_ADDR"] = str(ctypes.addressof(area))
    os.environ["EIDE_CANVAS_SIZE"] = str(size)
    os.environ["EIDE_CANVAS_W"] = str(CANVAS_W)
    os.environ["EIDE_CANVAS_H"] = str(CANVAS_H)

    assert eide.available()
    canvas = eide.canvas()
    assert canvas.width == CANVAS_W and canvas.height == CANVAS_H
    assert eide.canvas() is canvas, "канва должна быть одна на программу"

    # --- кадр не виден до present ------------------------------------------
    canvas.clear(0x101010FF)
    # Прямоугольник намеренно не квадратный и не по центру: перепутанные
    # местами координаты и размеры на квадрате в центре неотличимы.
    canvas.fill_rect(4, 20, 30, 6, 0xFF0000FF)
    frame, _ = read_frame(lib, area, size)
    assert frame == 0, "неопубликованный кадр не должен быть виден"

    canvas.present()
    frame, raw = read_frame(lib, area, size)
    assert frame == 1, "кадр не дошёл до читателя"

    # --- цвет доехал в правильном порядке -----------------------------------
    assert pixel(raw, 0, 0) == (0x10, 0x10, 0x10, 0xFF), pixel(raw, 0, 0)
    assert pixel(raw, 10, 22) == (0xFF, 0x00, 0x00, 0xFF), pixel(raw, 10, 22)
    assert pixel(raw, 33, 25) == (0xFF, 0x00, 0x00, 0xFF), pixel(raw, 33, 25)
    # Внутри прямоугольника с переставленными координатами, снаружи правильного.
    assert pixel(raw, 31, 10) == (0x10, 0x10, 0x10, 0xFF), pixel(raw, 31, 10)
    assert pixel(raw, 40, 40) == (0x10, 0x10, 0x10, 0xFF)

    # --- повторный present без рисования ничего не публикует ----------------
    canvas.present()
    assert read_frame(lib, area, size, since=1)[0] == 0

    # --- обрезка вместо падения ---------------------------------------------
    canvas.fill_rect(-1000, -1000, 10, 10, 0x00FF00FF)
    canvas.fill_rect(CANVAS_W + 5, CANVAS_H + 5, 10, 10, 0x00FF00FF)
    canvas.present()
    frame, raw = read_frame(lib, area, size, since=1)
    assert frame == 2

    # --- ограничитель частоты ------------------------------------------------
    clock = eide.Clock()
    clock.tick(60)
    started = time.monotonic()
    for _ in range(5):
        clock.tick(60)
    elapsed = time.monotonic() - started
    assert elapsed >= 5 / 60 * 0.9, "ограничитель не удерживает частоту: %.4f с" % elapsed
    assert elapsed < 5 / 60 * 3, "ограничитель спит слишком долго: %.4f с" % elapsed

    canvas.close()
    print("eide.py: все проверки прошли")


if __name__ == "__main__":
    main()
