import json
from types import SimpleNamespace

import pytest

from app.config import Settings
from app.deps import get_extractor
from app.main import app
from app.routes import reports as reports_route
from app.services import reports as svc
from app.services.reports import AnthropicExtractor, compute_flag, parse_extraction
from tests.conftest import requires_db


def payload(values=None, summary="Your sugar is a little high. It is measured after fasting.", **kw):
    d = {"report_type": "Blood sugar", "report_date": "2026-03-01", "values": values if values is not None else [
        {"test_name": "Fasting glucose", "value": 110, "unit": "mg/dL", "reference_range": "70-100"}], "summary": summary}
    d.update(kw)
    return json.dumps(d)


class FakeExtractor:
    def __init__(self, raw=None):
        self.raw, self.calls = raw, []

    def extract(self, data, media_type, language):
        self.calls.append((data, media_type, language))
        if isinstance(self.raw, Exception):
            raise self.raw
        return self.raw


@pytest.fixture
def ext():
    e = FakeExtractor(payload())
    app.dependency_overrides[get_extractor] = lambda: e
    reports_route._recent.clear()
    return e


@pytest.fixture
def keyed(settings):
    settings.anthropic_api_key = "test-key"
    return settings


def make_report(db, family, path=None, status="pending", content=b"%PDF-fake"):
    path = path or f"{family.recipient_id}/{svc.__name__.split('.')[-1]}-{status}-{id(content)}.pdf"
    db.storage.from_("reports").upload(path, content, {"content-type": "application/pdf", "upsert": "true"})
    r = db.table("reports").insert({"recipient_id": family.recipient_id, "uploaded_by": family.user_id, "file_url": path, "ai_status": status}).execute().data[0]
    return r["id"]


def run(client, rid, lang="en"):
    return client.post(f"/reports/{rid}/analyze", json={"language": lang})


def get(db, rid):
    return db.table("reports").select("*").eq("id", rid).single().execute().data


def vals(db, rid):
    return {v["test_name"]: v for v in db.table("report_values").select("*").eq("report_id", rid).execute().data}


# ---- pure unit tests ----
@pytest.mark.parametrize("value,rng,expected", [
    (85, "70-100", "normal"), (65, "70 - 100 mg/dL", "low"), (110, "70–100", "high"),
    (200, "70-100", "critical"), (30, "70-100", "critical"), (5.0, "<5.7", "normal"), (6.5, "<5.7", "high"),
    (9.0, "<5.7", "critical"), (35, ">40", "low"), (10, ">40", "critical"), (45, ">40", "normal"),
])
def test_flag_rule(value, rng, expected):
    assert compute_flag(value, rng) == expected


def test_flag_fallback_when_unparseable():
    assert compute_flag(5, "see note", "high") == "high"
    assert compute_flag(5, "", "bogus") == "normal"


def test_nan_and_inf_rejected():
    for bad in ('{"report_type":"x","values":[{"test_name":"a","value":NaN}],"summary":"s"}',
                '{"report_type":"x","values":[{"test_name":"a","value":Infinity}],"summary":"s"}',
                "not json"):
        with pytest.raises(svc.ExtractionError):
            parse_extraction(bad)


def test_values_capped_and_names_trimmed():
    many = [{"test_name": "T" * 200, "value": i, "unit": "u", "reference_range": ""} for i in range(100)]
    ex = parse_extraction(payload(values=many))
    assert len(ex.values) == 60 and len(ex.values[0].test_name) == 80


def test_directive_sentence_replaced():
    out = svc.clean_summary("Sugar is high. You should stop taking metformin. Eat well.", "en", False)
    assert "stop taking" not in out and "ask the doctor" in out and "Eat well." in out


def test_real_anthropic_wiring_with_stub_client():
    calls = []

    class Msgs:
        def create(self, **kw):
            calls.append(kw)
            return SimpleNamespace(content=[SimpleNamespace(text="{"), SimpleNamespace(text="}")])

    e = AnthropicExtractor(Settings(anthropic_api_key="k", report_model="claude-sonnet-5-5"), client=SimpleNamespace(messages=Msgs()))
    assert e.extract(b"pdf", "application/pdf", "hi") == "{}"
    assert e.extract(b"img", "image/png", "en") == "{}"
    pdf, png = calls
    assert pdf["model"] == "claude-sonnet-5-5" and "untrusted" in pdf["system"]
    assert pdf["messages"][0]["content"][0]["type"] == "document"
    assert pdf["messages"][0]["content"][0]["source"]["media_type"] == "application/pdf"
    assert "Hindi" in pdf["messages"][0]["content"][1]["text"]
    assert png["messages"][0]["content"][0]["type"] == "image"


def test_get_extractor_singleton():
    from app import deps
    deps._extractor = None
    a = deps.get_extractor(Settings(anthropic_api_key="k"))
    assert isinstance(a, AnthropicExtractor) and deps.get_extractor(Settings()) is a
    deps._extractor = None


# ---- endpoint tests (need local Supabase) ----
@requires_db
def test_happy_path_en(client, db, family, as_caregiver, real_db_dep, ext, keyed):
    rid = make_report(db, family)
    r = run(client, rid)
    assert r.json() == {"status": "done", "values": 1}
    rep = get(db, rid)
    assert rep["ai_status"] == "done" and rep["type"] == "Blood sugar" and rep["report_date"] == "2026-03-01"
    assert "contact the doctor" in rep["ai_summary"] and "not medical advice" not in rep["ai_summary"].lower()
    v = vals(db, rid)["Fasting glucose"]
    assert v["flag"] == "high" and v["user_verified"] is False
    assert ext.calls[0][1:] == ("application/pdf", "en")


@requires_db
def test_happy_path_hi_flags_and_bp(client, db, family, as_caregiver, real_db_dep, ext, keyed):
    ext.raw = payload(values=[
        {"test_name": "Systolic BP", "value": 125, "unit": "mmHg", "reference_range": "90-120"},
        {"test_name": "Diastolic BP", "value": 80, "unit": "mmHg", "reference_range": "60-80"},
        {"test_name": "HDL", "value": 15, "unit": "mg/dL", "reference_range": ">40"},
        {"test_name": "LDL", "value": 60, "unit": "mg/dL", "reference_range": "<100", "flag": "high"},
        {"test_name": "Weird", "value": 1, "unit": "", "reference_range": "n/a", "flag": "low"},
    ], summary="रिपोर्ट सरल है।")
    rid = make_report(db, family)
    assert run(client, rid, "hi").json()["values"] == 5
    v = vals(db, rid)
    assert (v["Systolic BP"]["flag"], v["Diastolic BP"]["flag"]) == ("high", "normal")
    assert (v["HDL"]["flag"], v["LDL"]["flag"], v["Weird"]["flag"]) == ("critical", "normal", "low")
    assert "डॉक्टर" in get(db, rid)["ai_summary"]


@requires_db
def test_normal_has_no_doctor_sentence_and_directive_stripped(client, db, family, as_caregiver, real_db_dep, ext, keyed):
    ext.raw = payload(values=[{"test_name": "Glucose", "value": 90, "unit": "mg/dL", "reference_range": "70-100"}],
                      summary="All fine. Please stop taking your tablets.")
    rid = make_report(db, family)
    run(client, rid)
    s = get(db, rid)["ai_summary"]
    assert "stop taking" not in s and "contact the doctor" not in s and "All fine." in s


@requires_db
def test_invalid_json_fails_then_retry_works(client, db, family, as_caregiver, real_db_dep, ext, keyed):
    ext.raw = "sorry, no json"
    rid = make_report(db, family)
    assert run(client, rid).json() == {"status": "failed"}
    assert get(db, rid)["ai_status"] == "failed"
    ext.raw = payload()
    assert run(client, rid).json()["status"] == "done"
    ext.raw = RuntimeError("boom")
    rid2 = make_report(db, family, status="pending", content=b"other")
    assert run(client, rid2).json() == {"status": "failed"}


@requires_db
def test_not_a_member_404(client, db, family, real_db_dep, ext, keyed):
    app.dependency_overrides[__import__("app.deps", fromlist=["x"]).current_user_id] = lambda: "00000000-0000-0000-0000-000000000001"
    try:
        rid = make_report(db, family)
        assert run(client, rid).status_code == 404
        assert run(client, "00000000-0000-0000-0000-000000000002").status_code == 404
    finally:
        app.dependency_overrides.pop(__import__("app.deps", fromlist=["x"]).current_user_id, None)
    assert get(db, rid)["ai_status"] == "pending" and not ext.calls


@requires_db
def test_processing_guard(client, db, family, as_caregiver, real_db_dep, ext, keyed):
    rid = make_report(db, family, status="processing")
    assert run(client, rid).json() == {"status": "processing"} and not ext.calls


@requires_db
def test_no_key_503(client, db, family, as_caregiver, real_db_dep, ext, settings):
    settings.anthropic_api_key = ""
    rid = make_report(db, family)
    r = run(client, rid)
    assert r.status_code == 503 and r.json()["detail"] == "AI reading is not set up yet"
    assert get(db, rid)["ai_status"] == "failed"


@requires_db
def test_rate_limit(client, db, family, as_caregiver, real_db_dep, ext, keyed):
    keyed.report_analyses_per_hour = 1
    rid = make_report(db, family)
    assert run(client, rid).json()["status"] == "done"
    rid2 = make_report(db, family, content=b"second")
    assert run(client, rid2).status_code == 429


@requires_db
def test_storage_path_mismatch_refused(client, db, family, as_caregiver, real_db_dep, ext, keyed):
    rid = make_report(db, family, path=f"{family.circle_id}/x.pdf")  # not under the recipient's folder
    assert run(client, rid).status_code == 400
    assert get(db, rid)["ai_status"] == "failed" and not ext.calls


@requires_db
def test_oversize_file_fails(client, db, family, as_caregiver, real_db_dep, ext, keyed):
    keyed.report_max_bytes = 4
    rid = make_report(db, family)
    assert run(client, rid).json() == {"status": "failed"} and not ext.calls


@requires_db
def test_values_capped_endpoint(client, db, family, as_caregiver, real_db_dep, ext, keyed):
    ext.raw = payload(values=[{"test_name": f"T{i}", "value": i, "unit": "u", "reference_range": ""} for i in range(90)])
    rid = make_report(db, family)
    assert run(client, rid).json()["values"] == 60


@requires_db
def test_nan_endpoint_failed(client, db, family, as_caregiver, real_db_dep, ext, keyed):
    ext.raw = '{"report_type":"x","values":[{"test_name":"a","value":NaN}],"summary":"s"}'
    rid = make_report(db, family)
    assert run(client, rid).json() == {"status": "failed"} and not vals(db, rid)
