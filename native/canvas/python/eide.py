"""Графика Effect IDE.

Кадр рисуется в разделяемую память, IDE его показывает. Соединение уже
установлено к моменту запуска программы — искать и открывать ничего не нужно::

    import eide

    canvas = eide.canvas()
    clock = eide.Clock()
    x = 0

    while True:
        canvas.clear(0x101010FF)
        canvas.fill_rect(x, 300, 80, 80, 0xFF3B30FF)
        canvas.present()
        x = (x + 4) % canvas.width
        clock.tick(60)

Цвет — 0xRRGGBBAA: красный это 0xFF0000FF. Помогает `eide.rgb(255, 0, 0)`.

Чего здесь нет: линий, спрайтов, текста, ввода. Пока только заливка —
этого хватает, чтобы увидеть движение и убедиться, что кадры доходят.
"""

import ctypes
import os
import time

__all__ = ["Canvas", "Clock", "canvas", "rgb", "available"]

_ENV_LIBRARY = "EIDE_CANVAS_LIB"
_ENV_ADDRESS = "EIDE_CANVAS_ADDR"
_ENV_PATH = "EIDE_CANVAS_PATH"
_ENV_SIZE = "EIDE_CANVAS_SIZE"
_ENV_WIDTH = "EIDE_CANVAS_W"
_ENV_HEIGHT = "EIDE_CANVAS_H"


def rgb(r, g, b, a=255):
    """Собирает цвет из каналов. Диапазон каждого — 0..255."""
    for value in (r, g, b, a):
        if not 0 <= value <= 255:
            raise ValueError("канал вне диапазона 0..255: %r" % (value,))
    return (r << 24) | (g << 16) | (b << 8) | a


def available():
    """Есть ли к чему подключаться. Ложь, если программу запустили без графики."""
    return _ENV_ADDRESS in os.environ or _ENV_PATH in os.environ


class _Library:
    """Обёртка над libeide_canvas.so.

    Через ctypes, а не как C-расширение: расширение пришлось бы пересобирать
    под каждую версию Python, а .so через ctypes переживает обновление
    интерпретатора.
    """

    def __init__(self):
        # Путь к библиотеке приходит из окружения, если он известен снаружи.
        # На Android она лежит в каталоге нативных библиотек приложения, и
        # достаточно имени; на десктопе имя ничего не значит.
        self._lib = ctypes.CDLL(os.environ.get(_ENV_LIBRARY, "libeide_canvas.so"))

        self._lib.ec_open_writer.argtypes = [ctypes.c_void_p, ctypes.c_size_t]
        self._lib.ec_open_writer.restype = ctypes.c_void_p

        self._lib.ec_close.argtypes = [ctypes.c_void_p]
        self._lib.ec_close.restype = None

        for name in ("ec_begin_frame", "ec_end_frame"):
            function = getattr(self._lib, name)
            function.argtypes = [ctypes.c_void_p]
            function.restype = None

        self._lib.ec_clear.argtypes = [ctypes.c_void_p, ctypes.c_uint32]
        self._lib.ec_clear.restype = None

        self._lib.ec_fill_rect.argtypes = [
            ctypes.c_void_p, ctypes.c_int32, ctypes.c_int32,
            ctypes.c_int32, ctypes.c_int32, ctypes.c_uint32,
        ]
        self._lib.ec_fill_rect.restype = None

    def __getattr__(self, name):
        return getattr(self._lib, name)


class Canvas:
    """Поверхность, в которую рисует программа.

    Кадр начинается сам при первом рисующем вызове и заканчивается на
    :meth:`present`. Ненарисованный или неопубликованный кадр IDE не увидит —
    она показывает последний целый.
    """

    def __init__(self, address, size, width, height, mapping=None):
        self._library = _Library()
        # Ссылка на отображение держится здесь: если его собрать сборщиком
        # мусора, область исчезнет из-под уже открытого писателя.
        self._mapping = mapping
        self._ctx = self._library.ec_open_writer(ctypes.c_void_p(address), size)
        if not self._ctx:
            raise RuntimeError("не удалось подключиться к области кадров")

        self.width = width
        self.height = height
        self._drawing = False

    def _begin(self):
        if not self._drawing:
            self._library.ec_begin_frame(self._ctx)
            self._drawing = True

    def clear(self, color):
        """Заливает кадр целиком."""
        self._begin()
        self._library.ec_clear(self._ctx, color)

    def fill_rect(self, x, y, w, h, color):
        """Заливает прямоугольник. Уехавший за край обрезается, а не падает."""
        self._begin()
        self._library.ec_fill_rect(self._ctx, int(x), int(y), int(w), int(h), color)

    def present(self):
        """Показывает нарисованное. До этого вызова кадра не видно."""
        if not self._drawing:
            return
        self._library.ec_end_frame(self._ctx)
        self._drawing = False

    def close(self):
        if self._ctx:
            self._library.ec_close(self._ctx)
            self._ctx = None
        if self._mapping is not None:
            self._mapping.close()
            self._mapping = None

    def __enter__(self):
        return self

    def __exit__(self, *exception):
        self.close()
        return False


class Clock:
    """Ограничитель частоты кадров.

    Без него цикл крутится на полной скорости: пользы никакой — IDE всё равно
    показывает не чаще, чем обновляется экран, — а телефон греется и
    расходует батарею.
    """

    def __init__(self):
        self._previous = time.monotonic()

    def tick(self, fps=60):
        """Досыпает остаток кадра. Возвращает, сколько секунд прошло с прошлого раза."""
        if fps <= 0:
            raise ValueError("частота кадров должна быть положительной")

        target = 1.0 / fps
        now = time.monotonic()
        remaining = target - (now - self._previous)
        if remaining > 0:
            time.sleep(remaining)
            now = time.monotonic()

        elapsed = now - self._previous
        self._previous = now
        return elapsed


_canvas = None


def canvas():
    """Канва этой программы. Один и тот же объект при повторных вызовах."""
    global _canvas
    if _canvas is not None:
        return _canvas

    if not available():
        raise RuntimeError(
            "графика недоступна: программа запущена без канвы"
        )

    size = int(os.environ[_ENV_SIZE])
    width = int(os.environ[_ENV_WIDTH])
    height = int(os.environ[_ENV_HEIGHT])

    if _ENV_PATH in os.environ:
        # Десктоп: область — файл, и отображать его должен тот, кто в него
        # пишет. Адрес чужого процесса здесь ничего не значит.
        mapping = _map_file(os.environ[_ENV_PATH], size)
        address = ctypes.addressof(ctypes.c_char.from_buffer(mapping))
        _canvas = Canvas(address, size, width, height, mapping)
    else:
        # Android: область уже отображена вызывающим, нам дали её адрес.
        _canvas = Canvas(int(os.environ[_ENV_ADDRESS]), size, width, height)

    return _canvas


def _map_file(path, size):
    import mmap

    handle = open(path, "r+b")
    try:
        return mmap.mmap(handle.fileno(), size)
    finally:
        # Отображение живёт независимо от дескриптора.
        handle.close()
