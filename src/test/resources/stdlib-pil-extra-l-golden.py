# Generated with Pillow 12.0.0 by tools/pil-extra-golden.py
from PIL import Image, ImageChops, ImageEnhance, ImageFilter, ImageStat
im = Image.frombytes('L', (7, 6), bytes((i * 37 + 11) % 256 for i in range(42)))
other = Image.frombytes('L', (7, 6), bytes((i * 63 + 97) % 256 for i in range(42)))
result = ImageChops.difference(im, other)
assert result.size == (7, 6) and result.mode == 'L', 'ImageChops.difference(im, other)'
assert result.tobytes() == b'Vp\x8a\\B(\x0e\x0c&@Z\x8crX\xc2$\n\x10*\xbc\xa2x\x92T: \x06\xec.Hb\x84jP6\xe4\x02\x182L\x9a\x80', 'ImageChops.difference(im, other)'
result = ImageChops.lighter(im, other)
assert result.size == (7, 6) and result.mode == 'L', 'ImageChops.lighter(im, other)'
assert result.tobytes() == b'a\xa0\xdfz\x9f\xc4\xe9\x1aY\x98\xd7\xa2\xc7\xec\xd36[\x90\xcf\xca\xef\x8c\xcb^\x83\xa8\xcd\xf2E\x84\xc3\x86\xab\xd0\xf5\xfe?|\xbb\xfa\xd3\xf8', 'ImageChops.lighter(im, other)'
result = ImageChops.darker(im, other)
assert result.size == (7, 6) and result.mode == 'L', 'ImageChops.darker(im, other)'
assert result.tobytes() == b'\x0b0U\x1e]\x9c\xdb\x0e3X}\x16U\x94\x11\x12Q\x80\xa5\x0eM\x149\nI\x88\xc7\x06\x17<a\x02A\x80\xbf\x1a=d\x89\xae9x', 'ImageChops.darker(im, other)'
result = ImageChops.multiply(im, other)
assert result.size == (7, 6) and result.mode == 'L', 'ImageChops.multiply(im, other)'
assert result.tobytes() == b'\x04\x1eJ\x0e9w\xc8\x01\x114i\rB\x88\x0e\x03\x1cH\x85\x0bH\n-\x03%Y\x9f\x05\x06\x1fJ\x01+h\xb7\x19\x0f0d\xaa/t', 'ImageChops.multiply(im, other)'
result = ImageChops.screen(im, other)
assert result.size == (7, 6) and result.mode == 'L', 'ImageChops.screen(im, other)'
assert result.tobytes() == b"h\xb2\xea\x8a\xc3\xe9\xfc'{\xbc\xeb\xab\xda\xf8\xd6E\x90\xc8\xef\xcd\xf4\x96\xd7e\xa7\xd7\xf5\xf3V\xa1\xda\x87\xc1\xe8\xfd\xffm\xb0\xe0\xfe\xdd\xfc", 'ImageChops.screen(im, other)'
result = ImageChops.add_modulo(im, other)
assert result.size == (7, 6) and result.mode == 'L', 'ImageChops.add_modulo(im, other)'
assert result.tobytes() == b'l\xd04\x98\xfc`\xc4(\x8c\xf0T\xb8\x1c\x80\xe4H\xac\x10t\xd8<\xa0\x04h\xcc0\x94\xf8\\\xc0$\x88\xecP\xb4\x18|\xe0D\xa8\x0cp', 'ImageChops.add_modulo(im, other)'
result = ImageChops.subtract_modulo(im, other)
assert result.size == (7, 6) and result.mode == 'L', 'ImageChops.subtract_modulo(im, other)'
assert result.tobytes() == b'\xaa\x90v\\B(\x0e\xf4\xda\xc0\xa6\x8crX>$\n\xf0\xd6\xbc\xa2\x88nT: \x06\xec\xd2\xb8\x9e\x84jP6\x1c\x02\xe8\xce\xb4\x9a\x80', 'ImageChops.subtract_modulo(im, other)'
result = ImageChops.overlay(im, other)
assert result.size == (7, 6) and result.mode == 'L', 'ImageChops.overlay(im, other)'
assert result.tobytes() == b'\x08<\x95\x1c\x85\xd2\xf9\x02#i\xd3U\xb5\xef\x1c\x07:\x90\xdd\x9b\xe9\x16[\x07N\xae\xe9\xe6\x0c>\x94\x0e\x82\xd0\xfa4\x1ea\xc0\xfc\xbb\xf8', 'ImageChops.overlay(im, other)'
result = ImageChops.hard_light(im, other)
assert result.size == (7, 6) and result.mode == 'L', 'ImageChops.hard_light(im, other)'
assert result.tobytes() == b'\x08e\xd5\x1ct\xd2\xf9\x02#x\xd7\x1c\x85\xef\xad\x07:\x90\xdd\x16\x90+\xae\x07K\xae\xe9\x0b\x0cC\xb5\x02W\xd0\xfa\xfe\x1ea\xc0\xfc^\xea', 'ImageChops.hard_light(im, other)'
result = ImageChops.soft_light(im, other)
assert result.size == (7, 6) and result.mode == 'L', 'ImageChops.soft_light(im, other)'
assert result.tobytes() == b"\x079\x7fI\x8e\xce\xf7\x03&b\xa8q\xb8\xef\x1b\x10E\x87\xc8\xa4\xe8\x15R'g\xab\xe3\xe6\x0c<\x7fF\x8f\xd0\xfa1%b\xa6\xe2\xbe\xf8", 'ImageChops.soft_light(im, other)'
result = ImageChops.add(im, other, 1, 0)
assert result.size == (7, 6) and result.mode == 'L', 'ImageChops.add(im, other, 1, 0)'
assert result.tobytes() == b'l\xd0\xff\x98\xfc\xff\xff(\x8c\xf0\xff\xb8\xff\xff\xe4H\xac\xff\xff\xd8\xff\xa0\xffh\xcc\xff\xff\xf8\\\xc0\xff\x88\xec\xff\xff\xff|\xe0\xff\xff\xff\xff', 'ImageChops.add(im, other, 1, 0)'
result = ImageChops.add(im, other, 2, 12)
assert result.size == (7, 6) and result.mode == 'L', 'ImageChops.add(im, other, 2, 12)'
assert result.tobytes() == b'Bt\xa6X\x8a\xbc\xee R\x84\xb6h\x9a\xcc~0b\x94\xc6x\xaa\\\x8e@r\xa4\xd6\x88:l\x9eP\x82\xb4\xe6\x98J|\xae\xe0\x92\xc4', 'ImageChops.add(im, other, 2, 12)'
result = ImageChops.add(im, other, 0.75, -30)
assert result.size == (7, 6) and result.mode == 'L', 'ImageChops.add(im, other, 0.75, -30)'
assert result.tobytes() == b'r\xf7\xff\xac\xff\xff\xff\x17\x9c\xff\xff\xd7\xff\xff\xffB\xc7\xff\xff\xff\xff\xb7\xffl\xf2\xff\xff\xff\\\xe2\xff\x97\xff\xff\xff\xff\x87\xff\xff\xff\xff\xff', 'ImageChops.add(im, other, 0.75, -30)'
result = ImageChops.add(im, other, -2, 200)
assert result.size == (7, 6) and result.mode == 'L', 'ImageChops.add(im, other, -2, 200)'
assert result.tobytes() == b'\x92`.|J\x18\x00\xb4\x82P\x1el:\x08V\xa4r@\x0e\\*xF\x94b0\x00L\x9ah6\x84R \x00<\x8aX&\x00B\x10', 'ImageChops.add(im, other, -2, 200)'
result = ImageChops.subtract(im, other, 1, 0)
assert result.size == (7, 6) and result.mode == 'L', 'ImageChops.subtract(im, other, 1, 0)'
assert result.tobytes() == b'\x00\x00\x00\\B(\x0e\x00\x00\x00\x00\x8crX\x00$\n\x00\x00\xbc\xa2\x00\x00T: \x06\xec\x00\x00\x00\x84jP6\x00\x02\x00\x00\x00\x9a\x80', 'ImageChops.subtract(im, other, 1, 0)'
result = ImageChops.subtract(im, other, 2, 12)
assert result.size == (7, 6) and result.mode == 'L', 'ImageChops.subtract(im, other, 2, 12)'
assert result.tobytes() == b"\x00\x00\x00:- \x13\x06\x00\x00\x00RE8\x00\x1e\x11\x04\x00j]\x00\x006)\x1c\x0f\x82\x00\x00\x00NA4'\x00\r\x00\x00\x00YL", 'ImageChops.subtract(im, other, 2, 12)'
result = ImageChops.subtract(im, other, 0.75, -30)
assert result.size == (7, 6) and result.mode == 'L', 'ImageChops.subtract(im, other, 0.75, -30)'
assert result.tobytes() == b'\x00\x00\x00\\:\x17\x00\x00\x00\x00\x00\x9czW\x00\x12\x00\x00\x00\xdc\xba\x00\x00R/\x0c\x00\xff\x00\x00\x00\x92oL*\x00\x00\x00\x00\x00\xaf\x8c', 'ImageChops.subtract(im, other, 0.75, -30)'
result = ImageChops.subtract(im, other, -2, 200)
assert result.size == (7, 6) and result.mode == 'L', 'ImageChops.subtract(im, other, -2, 200)'
assert result.tobytes() == b'\xf3\xff\xff\x9a\xa7\xb4\xc1\xce\xdb\xe8\xf5\x82\x8f\x9c\xff\xb6\xc3\xd0\xddjw\xff\xff\x9e\xab\xb8\xc5R\xdf\xec\xf9\x86\x93\xa0\xad\xff\xc7\xd4\xe1\xee{\x88', 'ImageChops.subtract(im, other, -2, 200)'
result = ImageChops.difference(im, other.crop((0, 0, 3, 2)))
assert result.size == (3, 2) and result.mode == 'L', 'ImageChops.difference(im, other.crop((0, 0, 3, 2)))'
assert result.tobytes() == b'Vp\x8a\x0c&@', 'ImageChops.difference(im, other.crop((0, 0, 3, 2)))'
result = ImageChops.invert(im)
assert result.size == (7, 6) and result.mode == 'L', 'ImageChops.invert(im)'
assert result.tobytes() == b'\xf4\xcf\xaa\x85`;\x16\xf1\xcc\xa7\x82]8\x13\xee\xc9\xa4\x7fZ5\x10\xeb\xc6\xa1|W2\r\xe8\xc3\x9eyT/\n\xe5\xc0\x9bvQ,\x07', 'ImageChops.invert(im)'
result = ImageChops.constant(im, 73)
assert result.size == (7, 6) and result.mode == 'L', 'ImageChops.constant(im, 73)'
assert result.tobytes() == b'IIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIII', 'ImageChops.constant(im, 73)'
result = ImageChops.duplicate(im)
assert result.size == (7, 6) and result.mode == 'L', 'ImageChops.duplicate(im)'
assert result.tobytes() == b'\x0b0Uz\x9f\xc4\xe9\x0e3X}\xa2\xc7\xec\x116[\x80\xa5\xca\xef\x149^\x83\xa8\xcd\xf2\x17<a\x86\xab\xd0\xf5\x1a?d\x89\xae\xd3\xf8', 'ImageChops.duplicate(im)'
result = ImageChops.offset(im, 3)
assert result.size == (7, 6) and result.mode == 'L', 'ImageChops.offset(im, 3)'
assert result.tobytes() == b'\xa8\xcd\xf2\x149^\x83\xab\xd0\xf5\x17<a\x86\xae\xd3\xf8\x1a?d\x89\x9f\xc4\xe9\x0b0Uz\xa2\xc7\xec\x0e3X}\xa5\xca\xef\x116[\x80', 'ImageChops.offset(im, 3)'
result = ImageChops.offset(im, -2, 7)
assert result.size == (7, 6) and result.mode == 'L', 'ImageChops.offset(im, -2, 7)'
assert result.tobytes() == b'd\x89\xae\xd3\xf8\x1a?Uz\x9f\xc4\xe9\x0b0X}\xa2\xc7\xec\x0e3[\x80\xa5\xca\xef\x116^\x83\xa8\xcd\xf2\x149a\x86\xab\xd0\xf5\x17<', 'ImageChops.offset(im, -2, 7)'
result = ImageEnhance.Color(im).enhance(-0.5)
assert result.size == (7, 6) and result.mode == 'L', 'ImageEnhance.Color(im).enhance(-0.5)'
assert result.tobytes() == b'\x0b0Uz\x9f\xc4\xe9\x0e3X}\xa2\xc7\xec\x116[\x80\xa5\xca\xef\x149^\x83\xa8\xcd\xf2\x17<a\x86\xab\xd0\xf5\x1a?d\x89\xae\xd3\xf8', 'ImageEnhance.Color(im).enhance(-0.5)'
result = ImageEnhance.Color(im).enhance(0)
assert result.size == (7, 6) and result.mode == 'L', 'ImageEnhance.Color(im).enhance(0)'
assert result.tobytes() == b'\x0b0Uz\x9f\xc4\xe9\x0e3X}\xa2\xc7\xec\x116[\x80\xa5\xca\xef\x149^\x83\xa8\xcd\xf2\x17<a\x86\xab\xd0\xf5\x1a?d\x89\xae\xd3\xf8', 'ImageEnhance.Color(im).enhance(0)'
result = ImageEnhance.Color(im).enhance(0.5)
assert result.size == (7, 6) and result.mode == 'L', 'ImageEnhance.Color(im).enhance(0.5)'
assert result.tobytes() == b'\x0b0Uz\x9f\xc4\xe9\x0e3X}\xa2\xc7\xec\x116[\x80\xa5\xca\xef\x149^\x83\xa8\xcd\xf2\x17<a\x86\xab\xd0\xf5\x1a?d\x89\xae\xd3\xf8', 'ImageEnhance.Color(im).enhance(0.5)'
result = ImageEnhance.Color(im).enhance(1)
assert result.size == (7, 6) and result.mode == 'L', 'ImageEnhance.Color(im).enhance(1)'
assert result.tobytes() == b'\x0b0Uz\x9f\xc4\xe9\x0e3X}\xa2\xc7\xec\x116[\x80\xa5\xca\xef\x149^\x83\xa8\xcd\xf2\x17<a\x86\xab\xd0\xf5\x1a?d\x89\xae\xd3\xf8', 'ImageEnhance.Color(im).enhance(1)'
result = ImageEnhance.Color(im).enhance(1.75)
assert result.size == (7, 6) and result.mode == 'L', 'ImageEnhance.Color(im).enhance(1.75)'
assert result.tobytes() == b'\x0b0Uz\x9f\xc4\xe9\x0e3X}\xa2\xc7\xec\x116[\x80\xa5\xca\xef\x149^\x83\xa8\xcd\xf2\x17<a\x86\xab\xd0\xf5\x1a?d\x89\xae\xd3\xf8', 'ImageEnhance.Color(im).enhance(1.75)'
result = ImageEnhance.Contrast(im).enhance(-0.5)
assert result.size == (7, 6) and result.mode == 'L', 'ImageEnhance.Contrast(im).enhance(-0.5)'
assert result.tobytes() == b'\xbd\xab\x98\x86saN\xbc\xa9\x97\x84r_M\xba\xa8\x95\x83p^K\xb9\xa6\x94\x81o\\J\xb7\xa5\x92\x80m[H\xb6\xa3\x91~lYG', 'ImageEnhance.Contrast(im).enhance(-0.5)'
result = ImageEnhance.Contrast(im).enhance(0)
assert result.size == (7, 6) and result.mode == 'L', 'ImageEnhance.Contrast(im).enhance(0)'
assert result.tobytes() == b'\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82\x82', 'ImageEnhance.Contrast(im).enhance(0)'
result = ImageEnhance.Contrast(im).enhance(0.5)
assert result.size == (7, 6) and result.mode == 'L', 'ImageEnhance.Contrast(im).enhance(0.5)'
assert result.tobytes() == b'FYk~\x90\xa3\xb5HZm\x7f\x92\xa4\xb7I\\n\x81\x93\xa6\xb8K]p\x82\x95\xa7\xbaL_q\x84\x96\xa9\xbbN`s\x85\x98\xaa\xbd', 'ImageEnhance.Contrast(im).enhance(0.5)'
result = ImageEnhance.Contrast(im).enhance(1)
assert result.size == (7, 6) and result.mode == 'L', 'ImageEnhance.Contrast(im).enhance(1)'
assert result.tobytes() == b'\x0b0Uz\x9f\xc4\xe9\x0e3X}\xa2\xc7\xec\x116[\x80\xa5\xca\xef\x149^\x83\xa8\xcd\xf2\x17<a\x86\xab\xd0\xf5\x1a?d\x89\xae\xd3\xf8', 'ImageEnhance.Contrast(im).enhance(1)'
result = ImageEnhance.Contrast(im).enhance(1.75)
assert result.size == (7, 6) and result.mode == 'L', 'ImageEnhance.Contrast(im).enhance(1.75)'
assert result.tobytes() == b'\x00\x003t\xb4\xf5\xff\x00\x008y\xba\xfa\xff\x00\x00=~\xbf\xff\xff\x00\x02C\x83\xc4\xff\xff\x00\x07H\x89\xc9\xff\xff\x00\x0cM\x8e\xcf\xff\xff', 'ImageEnhance.Contrast(im).enhance(1.75)'
result = ImageEnhance.Brightness(im).enhance(-0.5)
assert result.size == (7, 6) and result.mode == 'L', 'ImageEnhance.Brightness(im).enhance(-0.5)'
assert result.tobytes() == b'\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00', 'ImageEnhance.Brightness(im).enhance(-0.5)'
result = ImageEnhance.Brightness(im).enhance(0)
assert result.size == (7, 6) and result.mode == 'L', 'ImageEnhance.Brightness(im).enhance(0)'
assert result.tobytes() == b'\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00', 'ImageEnhance.Brightness(im).enhance(0)'
result = ImageEnhance.Brightness(im).enhance(0.5)
assert result.size == (7, 6) and result.mode == 'L', 'ImageEnhance.Brightness(im).enhance(0.5)'
assert result.tobytes() == b'\x05\x18*=Obt\x07\x19,>Qcv\x08\x1b-@Rew\n\x1c/ATfy\x0b\x1e0CUhz\r\x1f2DWi|', 'ImageEnhance.Brightness(im).enhance(0.5)'
result = ImageEnhance.Brightness(im).enhance(1)
assert result.size == (7, 6) and result.mode == 'L', 'ImageEnhance.Brightness(im).enhance(1)'
assert result.tobytes() == b'\x0b0Uz\x9f\xc4\xe9\x0e3X}\xa2\xc7\xec\x116[\x80\xa5\xca\xef\x149^\x83\xa8\xcd\xf2\x17<a\x86\xab\xd0\xf5\x1a?d\x89\xae\xd3\xf8', 'ImageEnhance.Brightness(im).enhance(1)'
result = ImageEnhance.Brightness(im).enhance(1.75)
assert result.size == (7, 6) and result.mode == 'L', 'ImageEnhance.Brightness(im).enhance(1.75)'
assert result.tobytes() == b'\x13T\x94\xd5\xff\xff\xff\x18Y\x9a\xda\xff\xff\xff\x1d^\x9f\xe0\xff\xff\xff#c\xa4\xe5\xff\xff\xff(i\xa9\xea\xff\xff\xff-n\xaf\xef\xff\xff\xff', 'ImageEnhance.Brightness(im).enhance(1.75)'
result = ImageEnhance.Sharpness(im).enhance(-0.5)
assert result.size == (7, 6) and result.mode == 'L', 'ImageEnhance.Sharpness(im).enhance(-0.5)'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b'\x0b0Uz\x9f\xc4\xe9\x0e3X}\xa2\xc7\xec\x116[\x80\xa5\xca\xef\x149^\x83\xa8\xcd\xf2\x17<a\x86\xab\xd0\xf5\x1a?d\x89\xae\xd3\xf8')]) <= 1, 'ImageEnhance.Sharpness(im).enhance(-0.5)'
result = ImageEnhance.Sharpness(im).enhance(0)
assert result.size == (7, 6) and result.mode == 'L', 'ImageEnhance.Sharpness(im).enhance(0)'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b'\x0b0Uz\x9f\xc4\xe9\x0e3X}\xa2\xc7\xec\x116[\x80\xa5\xca\xef\x149^\x83\xa8\xcd\xf2\x17<a\x86\xab\xd0\xf5\x1a?d\x89\xae\xd3\xf8')]) <= 1, 'ImageEnhance.Sharpness(im).enhance(0)'
result = ImageEnhance.Sharpness(im).enhance(0.5)
assert result.size == (7, 6) and result.mode == 'L', 'ImageEnhance.Sharpness(im).enhance(0.5)'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b'\x0b0Uz\x9f\xc4\xe9\x0e3X}\xa2\xc7\xec\x116[\x80\xa5\xca\xef\x149^\x83\xa8\xcd\xf2\x17<a\x86\xab\xd0\xf5\x1a?d\x89\xae\xd3\xf8')]) <= 1, 'ImageEnhance.Sharpness(im).enhance(0.5)'
result = ImageEnhance.Sharpness(im).enhance(1)
assert result.size == (7, 6) and result.mode == 'L', 'ImageEnhance.Sharpness(im).enhance(1)'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b'\x0b0Uz\x9f\xc4\xe9\x0e3X}\xa2\xc7\xec\x116[\x80\xa5\xca\xef\x149^\x83\xa8\xcd\xf2\x17<a\x86\xab\xd0\xf5\x1a?d\x89\xae\xd3\xf8')]) <= 1, 'ImageEnhance.Sharpness(im).enhance(1)'
result = ImageEnhance.Sharpness(im).enhance(1.75)
assert result.size == (7, 6) and result.mode == 'L', 'ImageEnhance.Sharpness(im).enhance(1.75)'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b'\x0b0Uz\x9f\xc4\xe9\x0e3X}\xa2\xc7\xec\x116[\x80\xa5\xca\xef\x149^\x83\xa8\xcd\xf2\x17<a\x86\xab\xd0\xf5\x1a?d\x89\xae\xd3\xf8')]) <= 1, 'ImageEnhance.Sharpness(im).enhance(1.75)'
result = im.filter(ImageFilter.BLUR)
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.BLUR)'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b'\x0b0Uz\x9f\xc4\xe9\x0e3X}\xa2\xc7\xec\x116[\x80\xa5\xca\xef\x149^\x83\xa8\xcd\xf2\x17<a\x86\xab\xd0\xf5\x1a?d\x89\xae\xd3\xf8')]) <= 1, 'im.filter(ImageFilter.BLUR)'
result = im.filter(ImageFilter.CONTOUR)
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.CONTOUR)'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b'\x0b0Uz\x9f\xc4\xe9\x0e\xff\xff\xff\xff\xff\xec\x11\xff\xff\xff\xff\xff\xef\x14\xff\xff\xff\xff\xff\xf2\x17\xff\xff\xff\xff\xff\xf5\x1a?d\x89\xae\xd3\xf8')]) <= 1, 'im.filter(ImageFilter.CONTOUR)'
result = im.filter(ImageFilter.DETAIL)
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.DETAIL)'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b'\x0b0Uz\x9f\xc4\xe9\x0e3X}\xa2\xc7\xec\x116[\x80\xa5\xca\xef\x149^\x83\xa8\xcd\xf2\x17<a\x86\xab\xd0\xf5\x1a?d\x89\xae\xd3\xf8')]) <= 1, 'im.filter(ImageFilter.DETAIL)'
result = im.filter(ImageFilter.EDGE_ENHANCE)
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.EDGE_ENHANCE)'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b'\x0b0Uz\x9f\xc4\xe9\x0e3X}\xa2\xc7\xec\x116[\x80\xa5\xca\xef\x149^\x83\xa8\xcd\xf2\x17<a\x86\xab\xd0\xf5\x1a?d\x89\xae\xd3\xf8')]) <= 1, 'im.filter(ImageFilter.EDGE_ENHANCE)'
result = im.filter(ImageFilter.EDGE_ENHANCE_MORE)
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.EDGE_ENHANCE_MORE)'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b'\x0b0Uz\x9f\xc4\xe9\x0e3X}\xa2\xc7\xec\x116[\x80\xa5\xca\xef\x149^\x83\xa8\xcd\xf2\x17<a\x86\xab\xd0\xf5\x1a?d\x89\xae\xd3\xf8')]) <= 1, 'im.filter(ImageFilter.EDGE_ENHANCE_MORE)'
result = im.filter(ImageFilter.EMBOSS)
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.EMBOSS)'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b'\x0b0Uz\x9f\xc4\xe9\x0e\xa2\xa2\xa2\xa2\xa2\xec\x11\xa2\xa2\xa2\xa2\xa2\xef\x14\xa2\xa2\xa2\xa2\xa2\xf2\x17\xa2\xa2\xa2\xa2\xa2\xf5\x1a?d\x89\xae\xd3\xf8')]) <= 1, 'im.filter(ImageFilter.EMBOSS)'
result = im.filter(ImageFilter.FIND_EDGES)
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.FIND_EDGES)'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b'\x0b0Uz\x9f\xc4\xe9\x0e\x00\x00\x00\x00\x00\xec\x11\x00\x00\x00\x00\x00\xef\x14\x00\x00\x00\x00\x00\xf2\x17\x00\x00\x00\x00\x00\xf5\x1a?d\x89\xae\xd3\xf8')]) <= 1, 'im.filter(ImageFilter.FIND_EDGES)'
result = im.filter(ImageFilter.SHARPEN)
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.SHARPEN)'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b'\x0b0Uz\x9f\xc4\xe9\x0e3X}\xa2\xc7\xec\x116[\x80\xa5\xca\xef\x149^\x83\xa8\xcd\xf2\x17<a\x86\xab\xd0\xf5\x1a?d\x89\xae\xd3\xf8')]) <= 1, 'im.filter(ImageFilter.SHARPEN)'
result = im.filter(ImageFilter.SMOOTH)
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.SMOOTH)'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b'\x0b0Uz\x9f\xc4\xe9\x0e3X}\xa2\xc7\xec\x116[\x80\xa5\xca\xef\x149^\x83\xa8\xcd\xf2\x17<a\x86\xab\xd0\xf5\x1a?d\x89\xae\xd3\xf8')]) <= 1, 'im.filter(ImageFilter.SMOOTH)'
result = im.filter(ImageFilter.SMOOTH_MORE)
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.SMOOTH_MORE)'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b'\x0b0Uz\x9f\xc4\xe9\x0e3X}\xa2\xc7\xec\x116[\x80\xa5\xca\xef\x149^\x83\xa8\xcd\xf2\x17<a\x86\xab\xd0\xf5\x1a?d\x89\xae\xd3\xf8')]) <= 1, 'im.filter(ImageFilter.SMOOTH_MORE)'
result = im.filter(ImageFilter.Kernel((3, 3), [1, 2, 3, 4, 5, 6, 7, 8, 9], 45, -3))
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.Kernel((3, 3), [1, 2, 3, 4, 5, 6, 7, 8, 9], 45, -3))'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b'\x0b0Uz\x9f\xc4\xe9\x0e4Y~\xa3\xc8\xec\x117\\\x81\xa6\xcb\xef\x14:_\x84\xa9\xce\xf2\x17=b\x87\xac\xd1\xf5\x1a?d\x89\xae\xd3\xf8')]) <= 1, 'im.filter(ImageFilter.Kernel((3, 3), [1, 2, 3, 4, 5, 6, 7, 8, 9], 45, -3))'
result = im.filter(ImageFilter.MinFilter(1))
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.MinFilter(1))'
assert result.tobytes() == b'\x0b0Uz\x9f\xc4\xe9\x0e3X}\xa2\xc7\xec\x116[\x80\xa5\xca\xef\x149^\x83\xa8\xcd\xf2\x17<a\x86\xab\xd0\xf5\x1a?d\x89\xae\xd3\xf8', 'im.filter(ImageFilter.MinFilter(1))'
result = im.filter(ImageFilter.MinFilter(3))
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.MinFilter(3))'
assert result.tobytes() == b'\x0b\x0b0Uz\x9f\xc4\x0b\x0b0Uz\x9f\xc4\x0e\x0e3X}\xa2\xc7\x11\x116[\x80\xa5\xca\x14\x149^\x83\xa8\xcd\x17\x17<a\x86\xab\xd0', 'im.filter(ImageFilter.MinFilter(3))'
result = im.filter(ImageFilter.MinFilter(5))
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.MinFilter(5))'
assert result.tobytes() == b'\x0b\x0b\x0b0Uz\x9f\x0b\x0b\x0b0Uz\x9f\x0b\x0b\x0b0Uz\x9f\x0e\x0e\x0e3X}\xa2\x11\x11\x116[\x80\xa5\x14\x14\x149^\x83\xa8', 'im.filter(ImageFilter.MinFilter(5))'
result = im.filter(ImageFilter.MaxFilter(1))
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.MaxFilter(1))'
assert result.tobytes() == b'\x0b0Uz\x9f\xc4\xe9\x0e3X}\xa2\xc7\xec\x116[\x80\xa5\xca\xef\x149^\x83\xa8\xcd\xf2\x17<a\x86\xab\xd0\xf5\x1a?d\x89\xae\xd3\xf8', 'im.filter(ImageFilter.MaxFilter(1))'
result = im.filter(ImageFilter.MaxFilter(3))
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.MaxFilter(3))'
assert result.tobytes() == b'3X}\xa2\xc7\xec\xec6[\x80\xa5\xca\xef\xef9^\x83\xa8\xcd\xf2\xf2<a\x86\xab\xd0\xf5\xf5?d\x89\xae\xd3\xf8\xf8?d\x89\xae\xd3\xf8\xf8', 'im.filter(ImageFilter.MaxFilter(3))'
result = im.filter(ImageFilter.MaxFilter(5))
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.MaxFilter(5))'
assert result.tobytes() == b'[\x80\xa5\xca\xef\xef\xef^\x83\xa8\xcd\xf2\xf2\xf2a\x86\xab\xd0\xf5\xf5\xf5d\x89\xae\xd3\xf8\xf8\xf8d\x89\xae\xd3\xf8\xf8\xf8d\x89\xae\xd3\xf8\xf8\xf8', 'im.filter(ImageFilter.MaxFilter(5))'
result = im.filter(ImageFilter.MedianFilter(1))
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.MedianFilter(1))'
assert result.tobytes() == b'\x0b0Uz\x9f\xc4\xe9\x0e3X}\xa2\xc7\xec\x116[\x80\xa5\xca\xef\x149^\x83\xa8\xcd\xf2\x17<a\x86\xab\xd0\xf5\x1a?d\x89\xae\xd3\xf8', 'im.filter(ImageFilter.MedianFilter(1))'
result = im.filter(ImageFilter.MedianFilter(3))
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.MedianFilter(3))'
assert result.tobytes() == b'\x0e0Uz\x9f\xc4\xe9\x113X}\xa2\xc7\xe9\x146[\x80\xa5\xca\xec\x179^\x83\xa8\xcd\xef\x1a<a\x86\xab\xd0\xf2\x1a?d\x89\xae\xd3\xf5', 'im.filter(ImageFilter.MedianFilter(3))'
result = im.filter(ImageFilter.MedianFilter(5))
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.MedianFilter(5))'
assert result.tobytes() == b'\x110Uz\x9f\xc4\xe9\x143X}\xa2\xc7\xe9\x176[\x80\xa5\xca\xe9\x1a9^\x83\xa8\xcd\xec\x1a<a\x86\xab\xd0\xef\x1a?d\x89\xae\xd3\xf2', 'im.filter(ImageFilter.MedianFilter(5))'
result = im.filter(ImageFilter.RankFilter(3, 2))
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.RankFilter(3, 2))'
assert result.tobytes() == b'\x0b\x0e3X}\xa2\xc7\x0e\x116[\x80\xa5\xca\x11\x149^\x83\xa8\xcd\x14\x17<a\x86\xab\xd0\x17\x1a?d\x89\xae\xd3\x1a\x1a?d\x89\xae\xd3', 'im.filter(ImageFilter.RankFilter(3, 2))'
result = im.filter(ImageFilter.BoxBlur(0))
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.BoxBlur(0))'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b'\x0b0Uz\x9f\xc4\xe9\x0e3X}\xa2\xc7\xec\x116[\x80\xa5\xca\xef\x149^\x83\xa8\xcd\xf2\x17<a\x86\xab\xd0\xf5\x1a?d\x89\xae\xd3\xf8')]) <= 1, 'im.filter(ImageFilter.BoxBlur(0))'
result = im.filter(ImageFilter.BoxBlur(0.5))
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.BoxBlur(0.5))'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b'\x151V{\xa0\xc5\xe1\x173X}\xa2\xc7\xe3\x1a6[\x80\xa5\xca\xe6\x1d9^\x83\xa8\xcd\xe9 <a\x86\xab\xd0\xec">c\x88\xad\xd2\xee')]) <= 1, 'im.filter(ImageFilter.BoxBlur(0.5))'
result = im.filter(ImageFilter.BoxBlur(1))
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.BoxBlur(1))'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b'\x181V{\xa0\xc5\xde\x1a3X}\xa2\xc7\xe0\x1d6[\x80\xa5\xca\xe3 9^\x83\xa8\xcd\xe6#<a\x86\xab\xd0\xe9%>c\x88\xad\xd2\xeb')]) <= 1, 'im.filter(ImageFilter.BoxBlur(1))'
result = im.filter(ImageFilter.BoxBlur(2.3))
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.BoxBlur(2.3))'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b"'=Y|\x9f\xbb\xd1)?[~\xa1\xbd\xd3+A]\x80\xa3\xbf\xd5.D`\x83\xa6\xc2\xd80Fb\x85\xa8\xc4\xda2Hd\x87\xaa\xc6\xdc")]) <= 1, 'im.filter(ImageFilter.BoxBlur(2.3))'
result = im.filter(ImageFilter.BoxBlur((0, 3)))
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.BoxBlur((0, 3)))'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b'\x0e3X}\xa2\xc7\xec\x0f4Y~\xa3\xc8\xed\x116[\x80\xa5\xca\xef\x149^\x83\xa8\xcd\xf2\x16;`\x85\xaa\xcf\xf4\x17<a\x86\xab\xd0\xf5')]) <= 1, 'im.filter(ImageFilter.BoxBlur((0, 3)))'
result = im.filter(ImageFilter.BoxBlur((2, 0)))
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.BoxBlur((2, 0)))'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b"!7Uz\x9f\xbd\xd3$:X}\xa2\xc0\xd6'=[\x80\xa5\xc3\xd9*@^\x83\xa8\xc6\xdc-Ca\x86\xab\xc9\xdf0Fd\x89\xae\xcc\xe2")]) <= 1, 'im.filter(ImageFilter.BoxBlur((2, 0)))'
result = im.filter(ImageFilter.BoxBlur(12))
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.BoxBlur(12))'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b'enw\x80\x89\x92\x9bfox\x81\x8a\x93\x9cfox\x81\x8a\x93\x9cgpy\x82\x8b\x94\x9dgpy\x82\x8b\x94\x9dhqz\x83\x8c\x95\x9e')]) <= 1, 'im.filter(ImageFilter.BoxBlur(12))'
result = im.filter(ImageFilter.GaussianBlur(0))
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.GaussianBlur(0))'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b'\x0b0Uz\x9f\xc4\xe9\x0e3X}\xa2\xc7\xec\x116[\x80\xa5\xca\xef\x149^\x83\xa8\xcd\xf2\x17<a\x86\xab\xd0\xf5\x1a?d\x89\xae\xd3\xf8')]) <= 1, 'im.filter(ImageFilter.GaussianBlur(0))'
result = im.filter(ImageFilter.GaussianBlur(0.5))
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.GaussianBlur(0.5))'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b'\x0f0Uz\x9f\xc4\xe5\x123X}\xa2\xc7\xe8\x156[\x80\xa5\xca\xeb\x189^\x83\xa8\xcd\xee\x1b<a\x86\xab\xd0\xf1\x1e?d\x89\xae\xd3\xf4')]) <= 1, 'im.filter(ImageFilter.GaussianBlur(0.5))'
result = im.filter(ImageFilter.GaussianBlur(1))
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.GaussianBlur(1))'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b'\x1a2Uz\x9f\xc1\xd9\x1d5X}\xa2\xc4\xdc 8[\x80\xa5\xc7\xdf#;^\x83\xa8\xca\xe2&>a\x86\xab\xcd\xe5(@c\x88\xad\xcf\xe7')]) <= 1, 'im.filter(ImageFilter.GaussianBlur(1))'
result = im.filter(ImageFilter.GaussianBlur(2.3))
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.GaussianBlur(2.3))'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b'<Lb~\x9a\xb0\xc0=Mc\x7f\x9b\xb1\xc1?Oe\x81\x9d\xb3\xc3@Pf\x82\x9e\xb4\xc4BRh\x84\xa0\xb6\xc6CSi\x85\xa1\xb7\xc7')]) <= 1, 'im.filter(ImageFilter.GaussianBlur(2.3))'
result = im.filter(ImageFilter.GaussianBlur((0, 3)))
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.GaussianBlur((0, 3)))'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b'\x105Z\x7f\xa4\xc9\xee\x116[\x80\xa5\xca\xef\x127\\\x81\xa6\xcb\xf0\x138]\x82\xa7\xcc\xf1\x149^\x83\xa8\xcd\xf2\x15:_\x84\xa9\xce\xf3')]) <= 1, 'im.filter(ImageFilter.GaussianBlur((0, 3)))'
result = im.filter(ImageFilter.GaussianBlur((2, 0)))
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.GaussianBlur((2, 0)))'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b'2B[z\x99\xb2\xc25E^}\x9c\xb5\xc58Ha\x80\x9f\xb8\xc8;Kd\x83\xa2\xbb\xcb>Ng\x86\xa5\xbe\xceAQj\x89\xa8\xc1\xd1')]) <= 1, 'im.filter(ImageFilter.GaussianBlur((2, 0)))'
result = im.filter(ImageFilter.GaussianBlur(12))
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.GaussianBlur(12))'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b'\x7f\x80\x80\x81\x82\x82\x83\x7f\x80\x80\x81\x82\x82\x83\x7f\x80\x80\x81\x82\x82\x83\x80\x81\x81\x82\x83\x83\x84\x80\x81\x81\x82\x83\x83\x84\x80\x81\x81\x82\x83\x83\x84')]) <= 1, 'im.filter(ImageFilter.GaussianBlur(12))'
result = im.filter(ImageFilter.UnsharpMask(2, 150, 3))
assert result.size == (7, 6) and result.mode == 'L', 'im.filter(ImageFilter.UnsharpMask(2, 150, 3))'
assert max([abs(a-b) for a,b in zip(result.tobytes(), b'\x00\x11Hz\x9f\xda\xff\x00\x17N}\xa9\xe0\xff\x00\x1bR\x80\xae\xe5\xff\x00\x1eU\x83\xb1\xe8\xff\x00#Z\x86\xb5\xec\xff\x00)d\x89\xbb\xf2\xff')]) <= 2, 'im.filter(ImageFilter.UnsharpMask(2, 150, 3))'
mask = Image.frombytes("L", (7, 6), bytes((255 if i % 3 else 0) for i in range(42)))
stat = ImageStat.Stat(im)
assert stat.count == [42], 'count'
assert stat.sum == [5439.0], 'sum'
assert stat.sum2 == [935445.0], 'sum2'
assert max([abs(a-b) for a,b in zip(stat.mean, [129.5])]) < 0.000001, 'mean'
assert stat.median == [131], 'median'
assert max([abs(a-b) for a,b in zip(stat.rms, [149.23974001585503])]) < 0.000001, 'rms'
assert max([abs(a-b) for a,b in zip(stat.var, [5502.25])]) < 0.000001, 'var'
assert max([abs(a-b) for a,b in zip(stat.stddev, [74.17715281675349])]) < 0.000001, 'stddev'
assert stat.extrema == [(11, 248)], 'extrema'
stat = ImageStat.Stat(im, mask)
assert stat.count == [28], 'count'
assert stat.sum == [3632.0], 'sum'
assert stat.sum2 == [625622.0], 'sum2'
assert max([abs(a-b) for a,b in zip(stat.mean, [129.71428571428572])]) < 0.000001, 'mean'
assert stat.median == [134], 'median'
assert max([abs(a-b) for a,b in zip(stat.rms, [149.47790089890498])]) < 0.000001, 'rms'
assert max([abs(a-b) for a,b in zip(stat.var, [5517.846938775509])]) < 0.000001, 'var'
assert max([abs(a-b) for a,b in zip(stat.stddev, [74.28221145587622])]) < 0.000001, 'stddev'
assert stat.extrema == [(14, 248)], 'extrema'
stat = ImageStat.Stat(im.histogram())
assert stat.count == [42], 'count'
assert stat.sum == [5439.0], 'sum'
assert stat.sum2 == [935445.0], 'sum2'
assert max([abs(a-b) for a,b in zip(stat.mean, [129.5])]) < 0.000001, 'mean'
assert stat.median == [131], 'median'
assert max([abs(a-b) for a,b in zip(stat.rms, [149.23974001585503])]) < 0.000001, 'rms'
assert max([abs(a-b) for a,b in zip(stat.var, [5502.25])]) < 0.000001, 'var'
assert max([abs(a-b) for a,b in zip(stat.stddev, [74.17715281675349])]) < 0.000001, 'stddev'
assert stat.extrema == [(11, 248)], 'extrema'
passed = True
