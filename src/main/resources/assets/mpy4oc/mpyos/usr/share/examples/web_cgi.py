# Register this script with webserver.serve_cgi('/usr/share/examples/web_cgi.py', path='/cgi').
import os

print('Content-Type: text/plain; charset=utf-8')
print()
print('User:', os.getenv('REMOTE_USER'))
print('Method:', os.getenv('REQUEST_METHOD'))
