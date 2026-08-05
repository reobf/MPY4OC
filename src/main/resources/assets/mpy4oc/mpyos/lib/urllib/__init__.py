"""urllib -- URL handling for mpyos.

Submodules:
  urllib.parse    quote/unquote, urlencode, urlparse/urlunparse, urljoin
  urllib.request  urlopen() over the OC internet card (via requests)
  urllib.error    URLError / HTTPError

Pure-Python parsing works everywhere; urllib.request needs an internet card
at call time (OSError without one).
"""
