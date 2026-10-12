#!/usr/bin/env python3
"""Download covers for the exported database source IDs from the public library API."""
import argparse
import concurrent.futures
import datetime
import hashlib
import importlib.util
import json
import os
import pathlib
import re
import time
import urllib.error
import urllib.parse
import urllib.request

spec = importlib.util.spec_from_file_location("catalogue", pathlib.Path(__file__).with_name("crawl-fanqie.py"))
catalogue = importlib.util.module_from_spec(spec)
spec.loader.exec_module(catalogue)


def image_extension(data):
    if data.startswith(b"\xff\xd8\xff") and data.endswith(b"\xff\xd9"):
        return "jpg"
    if data.startswith(b"\x89PNG\r\n\x1a\n") and b"IEND" in data[-16:]:
        return "png"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP" and int.from_bytes(data[4:8], "little") + 8 == len(data):
        return "webp"
    raise ValueError("响应不是完整的 JPEG/PNG/WebP 图片")


def allowed_image_url(url):
    parsed = urllib.parse.urlsplit(url)
    return parsed.scheme == "https" and not parsed.username and not parsed.password and parsed.port in (None, 443) and parsed.hostname and (
        parsed.hostname.endswith(".fqnovelpic.com") or parsed.hostname.endswith(".byteimg.com"))


def download(client, book, directory):
    book_id = str(book["book_id"])
    if not re.fullmatch(r"[0-9]{10,30}", book_id):
        raise ValueError("来源 ID 不合法")
    url = str(book.get("thumb_url", ""))
    if url.startswith("http://"):
        url = "https://" + url[7:]
    if not allowed_image_url(url):
        raise ValueError("封面 URL 不属于公开图片 CDN")
    for attempt in range(3):
        if client.stopped.is_set():
            raise RuntimeError("采集已停止")
        with client.lock:
            time.sleep(max(0, client.next_request - time.monotonic()))
            client.next_request = time.monotonic() + client.interval
        try:
            with urllib.request.urlopen(url, timeout=25) as response:
                if not allowed_image_url(response.url):
                    client.stopped.set()
                    raise RuntimeError("图片跳转到非预期站点，停止采集")
                data = response.read(5_000_001)
                if not data or len(data) > 5_000_000:
                    raise ValueError("封面为空或超过 5 MB")
                extension = image_extension(data)
            filename = book_id + "." + extension
            temporary = directory / (filename + ".tmp")
            temporary.write_bytes(data)
            temporary.replace(directory / filename)
            return {"externalId": book_id, "filename": filename,
                    "sha256": hashlib.sha256(data).hexdigest(), "size": len(data),
                    "coverUri": str(book.get("thumb_uri", "")),
                    "fetchedAt": datetime.datetime.now(datetime.timezone.utc).isoformat()}
        except urllib.error.HTTPError as error:
            if error.code in (401, 403, 429):
                client.stopped.set()
                raise RuntimeError("图片访问受限，停止采集") from error
            if error.code == 404 or attempt == 2:
                raise ValueError("图片请求失败 HTTP " + str(error.code)) from None
        except (TimeoutError, urllib.error.URLError):
            if attempt == 2:
                raise ValueError("图片下载超时或网络错误") from None
        time.sleep(3 * (attempt + 1))


def save(path, records, targets):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps({"source": "fanqie", "covers": list(records.values()),
                                     "missingIds": sorted(targets - records.keys())}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--targets", type=pathlib.Path, default=pathlib.Path("data/fanqie-cover-targets.json"))
    parser.add_argument("--output", type=pathlib.Path, default=pathlib.Path("data/fanqie-covers.json"))
    parser.add_argument("--directory", type=pathlib.Path, default=pathlib.Path(os.environ.get("LIBRARY_COVER_DIR", pathlib.Path.home() / "Library/Application Support/library-system/covers")))
    args = parser.parse_args()
    targets = set(json.loads(args.targets.read_text(encoding="utf-8")))
    if not targets or any(not re.fullmatch(r"[0-9]{10,30}", value) for value in targets):
        raise ValueError("必须提供数据库中的番茄来源 ID")
    args.directory.mkdir(parents=True, exist_ok=True)
    records = {}
    if args.output.exists():
        document = json.loads(args.output.read_text(encoding="utf-8"))
        for record in document.get("covers", []):
            filename = record.get("filename", "")
            if re.fullmatch(r"[0-9]{10,30}\.(jpg|png|webp)", filename) and record.get("externalId") in targets:
                path = args.directory / filename
                if path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == record.get("sha256"):
                    records[record["externalId"]] = record
    if targets <= records.keys():
        print("全部封面已有有效缓存：", len(records), flush=True)
        return
    client = catalogue.PublicClient(interval=2.0)
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            for page_index in range(200):
                params = dict(page_count=10, page_index=page_index, gender=-1, category_id=-1,
                              creation_status=-1, word_count=-1, book_type=-1, sort=0)
                data = json.loads(client.get(catalogue.BASE + "/api/author/library/book_list/v0/?" + urllib.parse.urlencode(params)))
                if data.get("code") != 0:
                    raise RuntimeError("公开书库返回错误")
                data = data.get("data") or {}
                candidates = {str(book.get("book_id")): book for book in data.get("book_list", [])
                              if str(book.get("book_id")) in targets - records.keys()}
                futures = {pool.submit(download, client, book, args.directory): book_id for book_id, book in candidates.items()}
                for future in concurrent.futures.as_completed(futures):
                    try:
                        record = future.result()
                        records[record["externalId"]] = record
                        save(args.output, records, targets)
                    except Exception as error:
                        print("封面失败", futures[future], type(error).__name__, flush=True)
                        if client.stopped.is_set():
                            raise RuntimeError("访问受限，已保留封面进度") from None
                print("封面已保存", len(records), "/", len(targets), "；目录页", page_index + 1, flush=True)
                if targets <= records.keys() or not data.get("has_more"):
                    break
    finally:
        save(args.output, records, targets)
    print("封面采集完成：", len(records), "；缺少", len(targets - records.keys()), flush=True)
    if targets - records.keys():
        raise SystemExit("部分来源不在当前公开目录中或封面获取失败，缺少列表已保存")


if __name__ == "__main__":
    main()
