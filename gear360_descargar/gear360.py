#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Gear 360 Downloader
===================

Herramienta para conectarse a una camara Samsung Gear 360 (New) 2017 (SM-R210)
por WiFi y descargar/listar las fotos y videos usando la API estandar
OSC (Open Spherical Camera) sobre HTTP.

Esta herramienta NO depende de la app Gear 360 Manager de Samsung (descontinuada
en 2020). Habla directamente con el servidor HTTP de la camara.

Como usar (resumen):
  1. Enciende la Gear 360 y ponla en modo WiFi Direct / conexion (no en modo
     "conectar a telefono via app", sino el modo WiFi que crea su propia red).
  2. En el PC, conectate a la red WiFi de la camara (algo como "Gear 360(XXXX)").
     La contrasena suele mostrarse en la pantalla de la camara.
  3. Ejecuta:
         python gear360.py info        -> prueba la conexion
         python gear360.py list         -> lista fotos/videos en la camara
         python gear360.py download     -> descarga TODO a la carpeta ./descargas
         python gear360.py download-latest  -> descarga solo la ultima foto/video

NOTA SOBRE BLUETOOTH: la transferencia de archivos de la Gear 360 va por WiFi.
El Bluetooth solo servia para emparejar/controlar, no para pasar fotos.
"""

import sys
import os
import json
import time
import argparse

try:
    import requests
except ImportError:
    print("ERROR: falta la libreria 'requests'. Instalala con:")
    print("    pip install requests")
    sys.exit(1)

import stitch


# IP por defecto de la Gear 360 2017 cuando crea su propia red WiFi.
# Si tu camara da otra IP (mira el gateway de la conexion WiFi), cambiala
# con el parametro --ip o editando este valor.
DEFAULT_IP = "192.168.43.1"
DEFAULT_PORT = 80
DEFAULT_OUTPUT = "descargas"


class Gear360:
    """Cliente minimo de la API OSC para la Samsung Gear 360 2017."""

    def __init__(self, ip=DEFAULT_IP, port=DEFAULT_PORT, timeout=15):
        self.ip = ip
        self.port = port
        self.timeout = timeout
        self.base_url = "http://%s:%s" % (ip, port)
        self.session_id = None

    # ---- utilidades internas -------------------------------------------------

    def _url(self, path):
        return "%s/osc/%s" % (self.base_url, path)

    def _post(self, path, body=None, stream=False):
        url = self._url(path)
        data = json.dumps(body) if body is not None else None
        return requests.post(
            url,
            data=data,
            headers={"Content-Type": "application/json"},
            timeout=self.timeout,
            stream=stream,
        )

    def _execute(self, name, parameters=None):
        """Ejecuta un comando camera.* via /osc/commands/execute."""
        body = {"name": name, "parameters": parameters or {}}
        return self._post("commands/execute", body)

    # ---- comandos de alto nivel ----------------------------------------------

    def info(self):
        """GET /osc/info - datos basicos de la camara. No requiere sesion."""
        resp = requests.get(self._url("info"), timeout=self.timeout)
        resp.raise_for_status()
        return resp.json()

    def start_session(self):
        resp = self._execute("camera.startSession", {})
        resp.raise_for_status()
        data = resp.json()
        self.session_id = data["results"]["sessionId"]
        return self.session_id

    def close_session(self):
        if not self.session_id:
            return
        try:
            self._execute("camera.closeSession", {"sessionId": self.session_id})
        except Exception:
            pass
        finally:
            self.session_id = None

    def list_images(self, entry_count=100, include_thumb=False):
        """
        Lista fotos y videos. Maneja paginacion via continuationToken
        para traer TODO el contenido, no solo la primera pagina.
        """
        entries = []
        token = None
        while True:
            params = {
                "entryCount": entry_count,
                "includeThumb": include_thumb,
            }
            if token:
                params["continuationToken"] = token

            resp = self._execute("camera.listImages", params)
            resp.raise_for_status()
            results = resp.json().get("results", {})

            page = results.get("entries", [])
            entries.extend(page)

            token = results.get("continuationToken")
            if not token or not page:
                break

        return entries

    def download_file(self, file_uri, output_dir):
        """Descarga un archivo (por su uri) al directorio indicado."""
        os.makedirs(output_dir, exist_ok=True)
        # El nombre suele venir como "100ssCAM/ssc_20170101.jpg"; tomamos la parte final
        file_name = file_uri.split("/")[-1]
        out_path = os.path.join(output_dir, file_name)

        body = {
            "name": "camera.getImage",
            "parameters": {"fileUri": file_uri, "_type": "image"},
        }
        resp = self._post("commands/execute", body, stream=True)
        resp.raise_for_status()

        total = int(resp.headers.get("Content-Length", 0))
        written = 0
        with open(out_path, "wb") as fh:
            for chunk in resp.iter_content(chunk_size=8192):
                if chunk:
                    fh.write(chunk)
                    written += len(chunk)
                    if total:
                        pct = written * 100 // total
                        print("\r    %s  %3d%%" % (file_name, pct), end="", flush=True)
        if total:
            print()  # salto de linea tras la barra
        return out_path


# ---------------------------------------------------------------------------
# Comandos de linea de comandos
# ---------------------------------------------------------------------------

def _human_size(num):
    for unit in ("B", "KB", "MB", "GB"):
        if num < 1024:
            return "%.1f %s" % (num, unit)
        num /= 1024
    return "%.1f TB" % num


def cmd_info(cam, args):
    print("Conectando a %s ..." % cam.base_url)
    info = cam.info()
    print("\nConexion OK. Datos de la camara:")
    print("  Fabricante : %s" % info.get("manufacturer"))
    print("  Modelo     : %s" % info.get("model"))
    print("  Firmware   : %s" % info.get("firmwareVersion"))
    print("  Serie      : %s" % info.get("serialNumber"))
    print("  API        : %s" % ", ".join(info.get("api", [])))
    return 0


def cmd_list(cam, args):
    cam.start_session()
    try:
        entries = cam.list_images()
    finally:
        cam.close_session()

    if not entries:
        print("No se encontraron fotos ni videos en la camara.")
        return 0

    print("Se encontraron %d archivos:\n" % len(entries))
    total_size = 0
    for i, e in enumerate(entries, 1):
        name = e.get("name", e.get("uri", "?"))
        size = int(e.get("size", 0))
        total_size += size
        kind = "video" if name.lower().endswith((".mp4", ".mov")) else "foto"
        print("  %3d. [%-5s] %-40s %s" % (i, kind, name, _human_size(size)))
    print("\nTotal: %s" % _human_size(total_size))
    return 0


def _maybe_stitch(file_path, args):
    """
    Si el stitching esta activado, cose el archivo descargado a 360
    (equirectangular) con ffmpeg. Devuelve True si se genero el 360.
    Los errores de stitching no detienen la descarga: solo se avisan.
    """
    if getattr(args, "no_stitch", False):
        return False

    if not stitch.ffmpeg_available():
        print("      [360] ffmpeg no esta instalado: se deja el archivo original "
              "(sin coser). Instala ffmpeg o usa --no-stitch para ocultar este aviso.")
        return False

    try:
        out = stitch.stitch_file(file_path)
        if out:
            print("      [360] generado: %s" % os.path.basename(out))
            return True
        else:
            print("      [360] tipo de archivo no reconocido para coser: %s"
                  % os.path.basename(file_path))
            return False
    except Exception as ex:
        print("      [360] no se pudo coser %s: %s"
              % (os.path.basename(file_path), ex))
        return False


def cmd_download(cam, args):
    cam.start_session()
    try:
        entries = cam.list_images()
        if not entries:
            print("No hay archivos para descargar.")
            return 0

        stitch_note = "" if getattr(args, "no_stitch", False) else " (+ costura 360 automatica)"
        print("Descargando %d archivos a '%s/'%s ...\n"
              % (len(entries), args.output, stitch_note))
        ok = 0
        stitched = 0
        for i, e in enumerate(entries, 1):
            uri = e.get("uri")
            name = e.get("name", uri)
            if not uri:
                print("  %3d. (sin uri, se omite) %s" % (i, name))
                continue
            print("  %3d/%d %s" % (i, len(entries), name))
            try:
                path = cam.download_file(uri, args.output)
                ok += 1
                if _maybe_stitch(path, args):
                    stitched += 1
            except Exception as ex:
                print("      ERROR al descargar %s: %s" % (name, ex))
        msg = "\nListo. %d de %d archivos descargados en '%s/'." % (ok, len(entries), args.output)
        if not getattr(args, "no_stitch", False):
            msg += " %d cosidos a 360." % stitched
        print(msg)
    finally:
        cam.close_session()
    return 0


def cmd_download_latest(cam, args):
    cam.start_session()
    try:
        entries = cam.list_images(entry_count=1)
        if not entries:
            print("No hay archivos en la camara.")
            return 0
        latest = entries[0]
        uri = latest.get("uri")
        name = latest.get("name", uri)
        print("Descargando la ultima captura: %s" % name)
        path = cam.download_file(uri, args.output)
        _maybe_stitch(path, args)
        print("Guardado en '%s/'." % args.output)
    finally:
        cam.close_session()
    return 0


def build_parser():
    p = argparse.ArgumentParser(
        description="Descarga fotos/videos de una Samsung Gear 360 2017 por WiFi (API OSC).",
    )
    p.add_argument("--ip", default=DEFAULT_IP,
                   help="IP de la camara (por defecto %s)" % DEFAULT_IP)
    p.add_argument("--port", type=int, default=DEFAULT_PORT,
                   help="Puerto HTTP (por defecto %d)" % DEFAULT_PORT)
    p.add_argument("--output", "-o", default=DEFAULT_OUTPUT,
                   help="Carpeta de destino (por defecto '%s')" % DEFAULT_OUTPUT)

    sub = p.add_subparsers(dest="command")
    sub.add_parser("info", help="Prueba la conexion y muestra datos de la camara")
    sub.add_parser("list", help="Lista las fotos/videos de la camara")

    p_dl = sub.add_parser("download", help="Descarga TODOS los archivos (y los cose a 360)")
    p_dl.add_argument("--no-stitch", action="store_true",
                      help="No coser a 360: deja los archivos originales dual-fisheye")

    p_dll = sub.add_parser("download-latest",
                           help="Descarga solo la ultima captura (y la cose a 360)")
    p_dll.add_argument("--no-stitch", action="store_true",
                       help="No coser a 360: deja el archivo original dual-fisheye")

    p_st = sub.add_parser("stitch",
                          help="Cose a 360 archivos dual-fisheye ya descargados")
    p_st.add_argument("files", nargs="+",
                      help="Rutas de archivos (fotos/videos) a coser a 360")

    return p


def cmd_stitch(args):
    """Cose a 360 archivos ya descargados. No requiere la camara."""
    if not stitch.ffmpeg_available():
        print("ERROR: ffmpeg no esta instalado o no esta en el PATH.")
        print("Instalalo (en Windows):  winget install Gyan.FFmpeg")
        return 2

    ok = 0
    for f in args.files:
        if not os.path.isfile(f):
            print("  (no existe, se omite) %s" % f)
            continue
        print("Cosiendo a 360: %s" % f)
        try:
            out = stitch.stitch_file(f)
            if out:
                print("  -> generado: %s" % out)
                ok += 1
            else:
                print("  -> tipo no reconocido (ni foto ni video): %s" % f)
        except Exception as ex:
            print("  -> ERROR: %s" % ex)
    print("\nListo. %d archivo(s) cosido(s) a 360." % ok)
    return 0


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)

    if not args.command:
        parser.print_help()
        return 1

    # 'stitch' trabaja con archivos locales: no necesita conectarse a la camara.
    if args.command == "stitch":
        return cmd_stitch(args)

    cam = Gear360(ip=args.ip, port=args.port)

    handlers = {
        "info": cmd_info,
        "list": cmd_list,
        "download": cmd_download,
        "download-latest": cmd_download_latest,
    }

    try:
        return handlers[args.command](cam, args)
    except requests.exceptions.ConnectTimeout:
        print("\nERROR: no se pudo conectar a %s (timeout)." % cam.base_url)
        print("Revisa que: 1) la camara este encendida en modo WiFi,")
        print("            2) el PC este conectado a la red WiFi de la camara,")
        print("            3) la IP sea correcta (prueba --ip con el gateway de la WiFi).")
        return 2
    except requests.exceptions.ConnectionError:
        print("\nERROR: conexion rechazada/caida contra %s." % cam.base_url)
        print("Confirma que estas conectado a la WiFi de la camara y la IP es correcta.")
        return 2
    except KeyboardInterrupt:
        print("\nCancelado por el usuario.")
        return 130


if __name__ == "__main__":
    sys.exit(main())
