# Configure the Web Server Card first: component.webserver.start('factory01', 'password')
# pythonw /usr/share/examples/web_ae.py [AE component address]
# Open http://server:8080/ae/ and sign in with the card's ID/password.
# Craft an item -> Review order -> Confirm and submit. AE selects the CPU.
import sys
import aestorage

component.webserver.start()
address = sys.argv[1] if len(sys.argv) > 1 else None
inventory = aestorage.Inventory(address=address)
aestorage.serve(inventory, path='/ae')
