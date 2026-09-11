from datetime import date

from pydicom.dataset import Dataset

from dicom_scp import worklist


def _entry(**over):
    base = {
        "studyEventId": 4242, "subjectLabel": "HAE-001", "gender": "f",
        "dateOfBirth": "1970-05-17", "date": "2026-09-11", "time": "093000",
        "eventLabel": "Baseline fundus", "studyName": "HealthAEye", "modality": "OP",
    }
    base.update(over)
    return base


def test_accession_and_uid_are_deterministic_per_visit():
    assert worklist.accession_for(4242) == "LC4242"
    assert worklist.study_event_id_from_accession("LC4242") == 4242
    assert worklist.study_event_id_from_accession(" LC7 ") == 7
    assert worklist.study_event_id_from_accession("ACC-9") is None
    assert worklist.study_event_id_from_accession(None) is None
    assert worklist.study_instance_uid_for(4242) == worklist.study_instance_uid_for(4242)
    assert worklist.study_instance_uid_for(4242) != worklist.study_instance_uid_for(4243)
    assert len(worklist.study_instance_uid_for(4242)) <= 64


def test_build_item_shape():
    ds = worklist.build_item(_entry(), "OPTOMED")
    assert str(ds.PatientID) == "HAE-001"
    assert str(ds.PatientName) == "HAE-001"
    assert ds.PatientBirthDate == "19700517"
    assert ds.PatientSex == "F"
    assert ds.AccessionNumber == "LC4242"
    assert ds.StudyInstanceUID == worklist.study_instance_uid_for(4242)
    sps = ds.ScheduledProcedureStepSequence[0]
    assert sps.Modality == "OP"
    assert sps.ScheduledStationAETitle == "OPTOMED"
    assert sps.ScheduledProcedureStepStartDate == "20260911"
    assert sps.ScheduledProcedureStepStartTime == "093000"
    assert sps.ScheduledProcedureStepID == "LC4242"


def test_build_item_tolerates_missing_demographics():
    ds = worklist.build_item(_entry(gender=None, dateOfBirth=None, time=None), "")
    assert ds.PatientSex == ""
    assert ds.PatientBirthDate == ""
    assert ds.ScheduledProcedureStepSequence[0].ScheduledProcedureStepStartTime == ""


def _query(**sps_keys):
    q = Dataset()
    sps = Dataset()
    for k, v in sps_keys.items():
        setattr(sps, k, v)
    q.ScheduledProcedureStepSequence = [sps]
    return q


def test_requested_date_range_single_range_and_default():
    assert worklist.requested_date_range(_query(ScheduledProcedureStepStartDate="20260911")) == ("2026-09-11", "2026-09-11")
    assert worklist.requested_date_range(_query(ScheduledProcedureStepStartDate="20260901-20260930")) == ("2026-09-01", "2026-09-30")
    today = date.today().isoformat()
    assert worklist.requested_date_range(Dataset()) == (today, today)
    assert worklist.requested_date_range(_query(ScheduledProcedureStepStartDate="")) == (today, today)


def test_filter_entries_honours_modality_and_patient_id():
    entries = [_entry(), _entry(studyEventId=1, subjectLabel="HAE-002", modality="XC")]
    assert len(worklist.filter_entries(entries, _query())) == 2
    assert len(worklist.filter_entries(entries, _query(Modality="OP"))) == 1
    assert len(worklist.filter_entries(entries, _query(Modality="*"))) == 2
    q = _query()
    q.PatientID = "HAE-002"
    assert [e["subjectLabel"] for e in worklist.filter_entries(entries, q)] == ["HAE-002"]
