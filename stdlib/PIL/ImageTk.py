"""Pillow-style PhotoImage bridge to a Tk display."""
import tkinter
from PIL import Image


class PhotoImage(tkinter.PhotoImage):
    def __init__(self, image=None, size=None, **kw):
        if isinstance(image, str):
            image = Image.new(image, size)
        if image is None:
            if 'file' in kw:
                image = Image.open(kw.pop('file'))
            elif 'data' in kw:
                from io import BytesIO
                image = Image.open(BytesIO(kw.pop('data')))
            else:
                raise ValueError('give a PIL image, file or data')
        kw['data'] = image._encoded()
        tkinter.PhotoImage.__init__(self, **kw)

    def paste(self, im):
        if im.size != (self.width(), self.height()):
            raise ValueError('images do not match')
        self._cmd('replace', {'data': im._encoded()})
