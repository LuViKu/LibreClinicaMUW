"""Entry point: `python -m dicom_scp` starts the Storage SCP."""
import logging
import os

from .server import serve

if __name__ == "__main__":
    # DICOM_SCP_LOG_LEVEL=DEBUG makes pynetdicom dump every A-ASSOCIATE-RQ
    # (proposed abstract/transfer syntaxes) and DIMSE message — the way to see
    # what a modality actually negotiates when its UI only says "failed".
    level = getattr(logging, os.environ.get("DICOM_SCP_LOG_LEVEL", "INFO").upper(), logging.INFO)
    logging.basicConfig(
        level=level,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    if level > logging.DEBUG:
        # pynetdicom dumps every C-FIND identifier / response dataset at INFO
        # (PatientID, PatientName, …) — keep identifiers out of the routine log.
        logging.getLogger("pynetdicom.service_class").setLevel(logging.WARNING)
    serve()
