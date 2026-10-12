#!/usr/bin/env python3
"""Collect public catalogue metadata only; no chapters, cookies or font decoding."""
import argparse
import concurrent.futures
import datetime
import json
import pathlib
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import urllib.robotparser

BASE = "https://fanqienovel.com"


class PublicClient:
    def __init__(self, interval=2.0):
        self.interval = interval
        self.lock = threading.Lock()
        self.next_request = 0.0
        self.stopped = threading.Event()
        self.robots = urllib.robotparser.RobotFileParser()
        self.robots.parse(self.get(BASE + "/robots.txt", check=False).splitlines())

    def get(self, url, check=True):
        if check and not self.robots.can_fetch("LibraryLearningMetadata/1.0", url):
            raise RuntimeError("robots.txt 不允许采集：" + url)
        for attempt in range(3):
            if self.stopped.is_set():
                raise RuntimeError("采集已停止")
            with self.lock:
                time.sleep(max(0, self.next_request - time.monotonic()))
                self.next_request = time.monotonic() + self.interval
            try:
                request = urllib.request.Request(url, headers={
                    "User-Agent": "LibraryLearningMetadata/1.0 (public catalogue only)",
                    "Accept": "application/json,text/html,text/plain",
                })
                with urllib.request.urlopen(request, timeout=25) as response:
                    if urllib.parse.urlsplit(response.url).hostname != "fanqienovel.com":
                        self.stopped.set()
                        raise RuntimeError("页面跳转到其他站点，停止采集")
                    content = response.read(4_000_000).decode("utf-8")
                    if not content.strip():
                        self.stopped.set()
                        raise RuntimeError("公开页面返回空响应，停止采集")
                    return content
            except urllib.error.HTTPError as error:
                if error.code in (401, 403, 429):
                    self.stopped.set()
                    raise RuntimeError("网站限制访问，停止采集：HTTP " + str(error.code)) from error
                if error.code == 404:
                    raise
                if attempt == 2:
                    raise
            except (TimeoutError, urllib.error.URLError):
                if attempt == 2:
                    raise
            time.sleep(3 * (attempt + 1))


def page_metadata(text, book_id):
    match = re.search(r"window\.__INITIAL_STATE__\s*=\s*", text)
    if not match:
        raise ValueError("页面没有公开书目信息；不尝试绕过验证")
    state, _ = json.JSONDecoder().raw_decode(text[match.end():])
    page = state["page"]
    title, author = page.get("bookName", "").strip(), page.get("author", "").strip()
    if str(page.get("bookId")) != book_id or not title or not author:
        raise ValueError("书名、作者或来源编号缺失")
    if any(0xE000 <= ord(char) <= 0xF8FF for char in title + author):
        raise ValueError("公开页面的文字仍包含字体编码，跳过")
    tags = json.loads(page.get("categoryV2") or "[]")
    names = list(dict.fromkeys(tag["Name"] for tag in tags if tag.get("Name")))
    category = next((tag["Name"] for tag in tags if tag.get("MainCategory") and tag.get("Name")), "网络小说")
    status = {0: "已完结", 1: "连载中"}.get(page.get("creationStatus"), "状态未标明")
    words = page.get("wordNumber", 0)
    # A factual metadata overview, rather than reproducing the publisher's blurb or novel text.
    description = "番茄小说公开书目。题材：" + ("、".join(names) or category) + "；状态：" + status + "。"
    if isinstance(words, int) and words > 0:
        description += "采集时约 " + str(words) + " 字。"
    description += "故事简介请查看来源页面：" + BASE + "/page/" + book_id
    return {
        "externalId": book_id, "title": title, "author": author,
        "category": category, "description": description,
        "sourceUrl": BASE + "/page/" + book_id,
        "fetchedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "tags": names, "serializationStatus": status,
    }


def save(path, records):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps({"source": "fanqie", "books": list(records.values())}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--limit", type=int, default=1000)
    parser.add_argument("--output", type=pathlib.Path, default=pathlib.Path("data/fanqie-catalog.json"))
    args = parser.parse_args()
    if not 1 <= args.limit <= 1000:
        parser.error("单批采集数量应为 1～1000")
    records = {}
    if args.output.exists():
        document = json.loads(args.output.read_text(encoding="utf-8"))
        if document.get("source") != "fanqie":
            raise ValueError("缓存来源不匹配")
        records = {book["externalId"]: book for book in document["books"]}
    if len(records) >= args.limit:
        print("已有采集缓存：", len(records), "本；不重复请求", flush=True)
        return
    client = PublicClient()
    seen = set(records)
    completed = len(records)
    failures = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        for page_index in range(200):
            params = dict(page_count=18, page_index=page_index, gender=-1, category_id=-1,
                          creation_status=-1, word_count=-1, book_type=-1, sort=0)
            document = json.loads(client.get(BASE + "/api/author/library/book_list/v0/?" + urllib.parse.urlencode(params)))
            if document.get("code") != 0:
                raise RuntimeError("公开目录返回错误，停止采集")
            data = document.get("data") or {}
            ids = []
            for book in data.get("book_list") or []:
                book_id = str(book.get("book_id", ""))
                if re.fullmatch(r"[0-9]{10,30}", book_id) and book_id not in seen:
                    seen.add(book_id)
                    ids.append(book_id)
            ids = ids[:args.limit - len(records)]

            def fetch(book_id):
                return page_metadata(client.get(BASE + "/page/" + book_id), book_id)

            futures = {pool.submit(fetch, book_id): book_id for book_id in ids}
            try:
                for future in concurrent.futures.as_completed(futures):
                    try:
                        book = future.result()
                        records[book["externalId"]] = book
                        completed += 1
                    except Exception as error:
                        failures += 1
                        print("跳过", futures[future], type(error).__name__, str(error)[:160], flush=True)
                        if client.stopped.is_set() or failures >= 10:
                            client.stopped.set()
                            raise RuntimeError("访问受限或累计异常过多，保留进度并停止") from error
            finally:
                save(args.output, records)
            print("已保存", completed, "/", args.limit, "本；目录页", page_index + 1, "；跳过", failures, flush=True)
            if len(records) >= args.limit or not data.get("has_more"):
                break
    print("采集完成：", len(records), "本，文件", args.output, flush=True)
    if len(records) < args.limit:
        raise SystemExit("公开目录不足或部分数据异常；未伪造缺少的书目")


if __name__ == "__main__":
    main()
