# ShareMe

Local file and folder sharing between Windows, macOS, Linux, Android, and iPhone/iPad through a browser. The host computer needs Python 3.9 or newer. No third-party packages or internet connection are required after Python is installed.

## Start

On macOS or Linux, run `python3 server.py` in this folder. On Windows, run `py server.py`.

1. Connect the computer and phone to the same Wi-Fi.
2. Open the Phone URL printed in the terminal on your phone, and the Computer URL on the computer.
3. Enter the printed pairing code on each device.
4. Choose files or a folder to send. Other devices can refresh the shared list and save them.

Uploads are saved to `Shared` beside the directory from which you launch the server. Put files into that folder directly to share them too. Set a different folder with `python3 server.py --folder /path/to/folder`, or a port with `--port 9000`.

If devices cannot connect, allow Python through the computer’s firewall for private networks. Guest Wi-Fi networks may block communication between devices. Keep the server running during transfers.

## Supported behavior and limits

- Files upload in sequence with progress, and folder uploads preserve relative paths. Existing files receive a numbered name rather than being overwritten.
- Folders download as ZIP archives. Empty directories are not preserved. Mobile browser folder picking varies; if unavailable, create a ZIP using your device’s Files app and send it as a file.
- This is a browser app hosted on a computer, not native Android/iOS apps. Phones do not run the server. No QR pairing, background transfer, automatic discovery, or transfer resume yet.
- Pairing grants access to the whole selected shared folder. Restarting the server invalidates paired sessions and generates a new code.
- Use on trusted private Wi-Fi: HTTP traffic is not encrypted. Do not expose the server to the internet or forward its port. The pairing code is displayed only in the terminal.

## Verify

Run `python3 -m unittest discover -s tests -v`.
