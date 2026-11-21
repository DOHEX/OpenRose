"""Check local page, asset and fragment links in the generated Starlight site."""

import sys
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urljoin, urlsplit


class Page(HTMLParser):
    def __init__(self, path):
        super().__init__()
        self.links = []
        self.ids = set()
        self.canonical = None
        self.feed(path.read_text(encoding="utf-8"))
        self.close()

    def handle_starttag(self, tag, attributes):
        attrs = dict(attributes)
        if "id" in attrs:
            self.ids.add(attrs["id"])
        if tag == "a" and attrs.get("href"):
            self.links.append(attrs["href"])
        if tag == "link" and attrs.get("rel") == "canonical":
            self.canonical = attrs.get("href")


def main():
    dist = Path(__file__).resolve().parents[1] / "dist"
    pages = {path: Page(path) for path in dist.rglob("*.html")}
    home = pages[dist / "index.html"].canonical
    if not home:
        sys.exit("Missing canonical URL on the home page")
    base = urlsplit(home.rstrip("/") + "/")
    errors = []
    for source, page in pages.items():
        relative = source.relative_to(dist).as_posix()
        source_url = urljoin(base.geturl(), relative.removesuffix("index.html"))
        for href in page.links:
            target = urlsplit(urljoin(source_url, href))
            if target.netloc != base.netloc or target.scheme not in ("https", "http"):
                continue
            if not target.path.startswith(base.path) and target.path != base.path.rstrip("/"):
                continue
            path = dist / unquote(target.path.removeprefix(base.path).lstrip("/"))
            if target.path == base.path.rstrip("/"):
                path = dist
            if path.is_dir():
                path = path / "index.html"
            fragment = unquote(target.fragment)
            if not path.is_file() or (fragment and path in pages and fragment not in pages[path].ids):
                errors.append(f"{relative}: {href}")
    if errors:
        sys.exit("\n".join(errors))
    print(f"Checked links in {len(pages)} pages")


if __name__ == "__main__":
    main()
