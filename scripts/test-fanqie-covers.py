import importlib.util
import pathlib
import unittest

spec=importlib.util.spec_from_file_location("covers",pathlib.Path(__file__).with_name("crawl-fanqie-covers.py"))
covers=importlib.util.module_from_spec(spec)
spec.loader.exec_module(covers)


class CoverTests(unittest.TestCase):
    def test_rejects_html_truncation_and_non_image_responses(self):
        for value in [b"",b"<html>captcha</html>",b"\xff\xd8\xffbroken",b"\x89PNG\r\n\x1a\ntruncated"]:
            with self.assertRaises(ValueError):covers.image_extension(value)

    def test_url_allowlist_rejects_other_hosts_credentials_and_http(self):
        self.assertTrue(covers.allowed_image_url("https://p3-reading-sign.fqnovelpic.com/novel-pic/test"))
        for url in ["http://p3-reading-sign.fqnovelpic.com/a","https://127.0.0.1/a","https://fqnovelpic.com.evil.test/a","https://user:password@p3-reading-sign.fqnovelpic.com/a"]:
            self.assertFalse(covers.allowed_image_url(url))


if __name__=="__main__":unittest.main()
