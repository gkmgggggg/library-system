import importlib.util
import json
import pathlib
import unittest
from unittest.mock import MagicMock, patch

spec = importlib.util.spec_from_file_location("crawler", pathlib.Path(__file__).with_name("crawl-fanqie.py"))
crawler = importlib.util.module_from_spec(spec)
spec.loader.exec_module(crawler)


class PublicMetadataTests(unittest.TestCase):
    def page(self, **overrides):
        page = dict(bookId="7000000000000000001", bookName="测试书", author="作者",
                    categoryV2=json.dumps([{"Name": "玄幻", "MainCategory": True}]),
                    creationStatus=1, wordNumber=12345, abstract="不会复制的故事正文")
        page.update(overrides)
        return "window.__INITIAL_STATE__=" + json.dumps({"page": page}) + "; }"

    def test_plain_metadata_and_factual_description(self):
        book = crawler.page_metadata(self.page(), "7000000000000000001")
        self.assertEqual(book["title"], "测试书")
        self.assertEqual(book["category"], "玄幻")
        self.assertIn("12345", book["description"])
        self.assertNotIn("不会复制的故事正文", book["description"])

    def test_challenge_missing_fields_or_font_codes_are_rejected(self):
        for page in ["captcha", self.page(author=""), self.page(bookName="\ue437乱码"), self.page(bookId="wrong")]:
            with self.assertRaises(ValueError):
                crawler.page_metadata(page, "7000000000000000001")

    def test_script_delimiters_in_user_text_do_not_truncate_json(self):
        book = crawler.page_metadata(self.page(bookName="标题; } 继续"), "7000000000000000001")
        self.assertEqual(book["title"], "标题; } 继续")

    def robots_response(self):
        response = MagicMock()
        response.__enter__.return_value = response
        response.url = crawler.BASE + "/robots.txt"
        response.read.return_value = b"User-agent: *\nAllow:/\n"
        return response

    def test_empty_response_stops_future_requests(self):
        empty = MagicMock()
        empty.__enter__.return_value = empty
        empty.url = crawler.BASE + "/page/7000000000000000001"
        empty.read.return_value = b""
        with patch.object(crawler.urllib.request, "urlopen", side_effect=[self.robots_response(), empty]) as request:
            client = crawler.PublicClient(interval=0)
            with self.assertRaises(RuntimeError):
                client.get(empty.url)
            with self.assertRaises(RuntimeError):
                client.get(empty.url)
            self.assertTrue(client.stopped.is_set())
            self.assertEqual(request.call_count, 2)

    def test_forbidden_response_stops_without_retry(self):
        url = crawler.BASE + "/page/7000000000000000001"
        forbidden = crawler.urllib.error.HTTPError(url, 403, "Forbidden", {}, None)
        with patch.object(crawler.urllib.request, "urlopen", side_effect=[self.robots_response(), forbidden]) as request:
            client = crawler.PublicClient(interval=0)
            with self.assertRaises(RuntimeError):
                client.get(url)
            self.assertTrue(client.stopped.is_set())
            self.assertEqual(request.call_count, 2)


if __name__ == "__main__":
    unittest.main()
