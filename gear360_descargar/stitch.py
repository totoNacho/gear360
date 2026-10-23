#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Stitching (costura) de archivos dual-fisheye de la Gear 360 a formato
equirectangular 360, usando ffmpeg y su filtro v360.

La Gear 360 2017 graba cada captura como DOS circulos fisheye lado a lado
(una lente delante, otra detras). Para verlo como esfera 360 hay que
"coser" esos dos circulos en una imagen/video equirectangular.

Samsung usaba calibracion propietaria de sus lentes. Aqui usamos ffmpeg
(libre y automatizable). El resultado es un 360 equirectangular valido;
la costura puede no ser tan perfecta como la de Samsung, pero es visible
en cualquier visor 360 y el proceso es 100% automatico.

Requiere ffmpeg instalado y accesible en el PATH.
"""

import os
import shutil
import subprocess


# Extensiones que tratamos como foto / video
PHOTO_EXT = (".jpg", ".jpeg", ".png")
VIDEO_EXT = (".mp4", ".mov")


def ffmpeg_available():
    """Devuelve la ruta a ffmpeg si esta disponible, o None."""
    return shutil.which("ffmpeg")


def _v360_filter():
    """
    Filtro v360 para convertir dual-fisheye (dos circulos lado a lado) a
    equirectangular.

    - input=dfisheye : dos fisheye uno al lado del otro (formato Gear 360).
    - output=e        : equirectangular.
    - ih_fov/iv_fov   : campo de vision de cada lente (~195 grados en la Gear 360).

    Estos valores dan un buen punto de partida. Si la costura se ve
    desalineada, se pueden ajustar ih_fov/iv_fov o anadir yaw/pitch/roll.
    """
    return "v360=dfisheye:e:ih_fov=195:iv_fov=195"


def stitch_photo(input_path, output_path=None):
    """
    Cose una foto dual-fisheye a equirectangular 360.
    Devuelve la ruta de salida.
    """
    if output_path is None:
        base, ext = os.path.splitext(input_path)
        output_path = base + "_360" + ext

    cmd = [
        "ffmpeg", "-y",
        "-i", input_path,
        "-vf", _v360_filter(),
        output_path,
    ]
    subprocess.run(cmd, check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return output_path


def stitch_video(input_path, output_path=None):
    """
    Cose un video dual-fisheye a equirectangular 360.
    Re-codifica el video con el filtro v360 y copia el audio tal cual.
    Devuelve la ruta de salida.
    """
    if output_path is None:
        base, ext = os.path.splitext(input_path)
        output_path = base + "_360" + ext

    cmd = [
        "ffmpeg", "-y",
        "-i", input_path,
        "-vf", _v360_filter(),
        "-c:a", "copy",
        output_path,
    ]
    subprocess.run(cmd, check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return output_path


def stitch_file(input_path, output_path=None):
    """
    Detecta si es foto o video por su extension y aplica el stitching
    correspondiente. Devuelve la ruta de salida, o None si el tipo no
    se reconoce.
    """
    ext = os.path.splitext(input_path)[1].lower()
    if ext in PHOTO_EXT:
        return stitch_photo(input_path, output_path)
    if ext in VIDEO_EXT:
        return stitch_video(input_path, output_path)
    return None
