"""Entry point: `python -m dicom_scp` starts the Storage SCP."""
import logging

from .server import serve

if __name__ == "__main__":
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    serve()
