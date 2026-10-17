# Set card credentials first, then run with pythonw.
import webserver

component.webserver.start()

def echo(connection):
    try:
        while True:
            connection.send(connection.recv())
    except OSError:
        pass

webserver.serve_websocket(echo, path='/events')
