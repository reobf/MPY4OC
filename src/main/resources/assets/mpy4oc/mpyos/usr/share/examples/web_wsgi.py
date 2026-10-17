# Set credentials once before launch: component.webserver.start('factory01', 'password')
# Run: pythonw /usr/share/examples/web_wsgi.py
import webserver

component.webserver.start()

def application(environ, start_response):
    body = ('Hello, ' + environ['REMOTE_USER'] + '\n').encode('utf-8')
    start_response('200 OK', [('Content-Type', 'text/plain; charset=utf-8')])
    return [body]

webserver.serve_wsgi(application)
